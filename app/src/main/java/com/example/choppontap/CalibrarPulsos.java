package com.example.choppontap;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.snackbar.Snackbar;

/**
 * CalibrarPulsos — Tela de calibracao do sensor de fluxo e teste de liberacao.
 *
 * Protocolo ESP32 NUS v1.0.0 (2026-05-05):
 *
 * Consulta de parametros:
 *   $PL:0    -> PL:<valor>     Pulsos/litro atual
 *   $TO:0    -> TO:<valor_ms>  Timeout de inatividade atual (ms)
 *   $VR:     -> VR:<v>/<data>  Versao do firmware
 *   $DB:     -> diagnostico completo
 *
 * Configuracao direta:
 *   $PL:<n>  -> OK             Grava novo pulsos/litro na EEPROM
 *
 * Teste de fluxo:
 *   $ML:100  -> OK -> IN: -> VP:... -> QP: -> ML: -> FN:
 *   $LB:     -> OK -> IN: -> VP:... (fecha por timeout de inatividade)
 *
 * Calibracao automatica (protocolo v1.0.0):
 *   Passo 1: $CA:<ml>      -> OK -> IN: -> VP:... -> QP:<n> -> CA:
 *            (ESP32 aguarda $CF: indefinidamente)
 *   Passo 2: $CF:<ml_real> -> PL:<novo> -> FN:
 *            (ESP32 calcula e grava pulsosLitro = (QP / ml_real) x 1000)
 *
 * Maquina de estados de calibracao:
 *   IDLE            -> botao "Iniciar Calibracao" disponivel
 *   CAL_DISPENSANDO -> aguardando IN: / VP: / CA:
 *   CAL_AGUARDANDO  -> CA: recebido, campo de volume real visivel, aguardando $CF:
 *   CAL_CONCLUIDA   -> PL: + FN: recebidos, exibe novo fator e volta para IDLE
 */
public class CalibrarPulsos extends AppCompatActivity {

    private static final String TAG = "CALIBRAR_PULSOS";

    // Maquina de estados de calibracao
    private enum EstadoCalibracao {
        IDLE,            // Nenhuma calibracao em andamento
        CAL_DISPENSANDO, // $CA: enviado, aguardando IN: / VP: / CA:
        CAL_AGUARDANDO,  // CA: recebido, aguardando operador digitar volume real e enviar $CF:
        CAL_CONCLUIDA    // PL: + FN: recebidos
    }

    private EstadoCalibracao mEstadoCal = EstadoCalibracao.IDLE;

    // Pulsos contados no ciclo de calibracao (recebidos via QP:)
    private int mQpCalibracao = 0;

    // Servico BLE
    private BluetoothServiceIndustrial mBluetoothService;
    private boolean mIsServiceBound = false;

    // Estado de liberacao continua
    private boolean mLiberacaoContinuaAtiva = false;

    // Views
    private ConstraintLayout main;
    private TextView txtPulsosAtual;       // exibe PL:<valor>
    private TextView txtVolumeLiberado;    // volume parcial VP: durante teste/calibracao
    private TextView txtStatusCal;         // status da calibracao (mensagens ao operador)

    private Button btnPulsos;              // Gravar $PL:<n> manualmente
    private Button btnLiberar;             // Teste rapido $ML:100
    private Button btnTimeout;             // Navegar para ModificarTimeout
    private Button btnLiberacaoContinua;   // Alternar $LB: / parar / Iniciar Calibracao
    private Button btnConfirmarCal;        // Enviar $CF:<ml_real> (reutiliza btnChangePulsos)

    private EditText edtNovoPulsos;        // Campo para $PL:<n> manual / volume medido
    private EditText edtVolumeMedido;      // Alias para edtNovoPulsos durante calibracao

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // -------------------------------------------------------------------------
    // BroadcastReceiver: recebe eventos do BluetoothServiceIndustrial
    // -------------------------------------------------------------------------
    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;
            if (BluetoothServiceIndustrial.BLE_STATUS_ACTION.equals(action)) {
                processarStatus(intent.getStringExtra("status"));
            } else if (BluetoothServiceIndustrial.BLE_DATA_ACTION.equals(action)) {
                String data = intent.getStringExtra("data");
                if (data != null) processarDado(data.trim());
            }
        }
    };

    // -------------------------------------------------------------------------
    // Processamento de status BLE
    // -------------------------------------------------------------------------
    private void processarStatus(String status) {
        if (status == null) return;
        Log.d(TAG, "[STATUS] " + status);
        if (status.startsWith("disconnected")) {
            changeButtons(false);
            mLiberacaoContinuaAtiva = false;
            if (mEstadoCal == EstadoCalibracao.CAL_DISPENSANDO
                    || mEstadoCal == EstadoCalibracao.CAL_AGUARDANDO) {
                mEstadoCal = EstadoCalibracao.IDLE;
                runOnUiThread(() -> {
                    setStatusCal("Desconectado durante calibracao. Reinicie o processo.", Color.RED);
                    mostrarPainelCalibracao(false);
                    mostrarPainelConfirmacao(false);
                });
            }
            View root = findViewById(R.id.mainCalibrar);
            if (root != null) {
                Snackbar.make(root, "TAP Desconectada", Snackbar.LENGTH_SHORT)
                        .setAction("Conectar", v -> reconectar())
                        .show();
            }
        } else if ("connected".equals(status) || "ready".equals(status)) {
            changeButtons(true);
            if (mBluetoothService != null) {
                mBluetoothService.sendCommand("$PL:0");
                mBluetoothService.sendCommand("$TO:0");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Processamento de dados recebidos do ESP32
    // -------------------------------------------------------------------------
    private void processarDado(String data) {
        Log.d(TAG, "[RX] " + data);

        // PL:<valor> — pulsos/litro atual ou novo fator apos calibracao
        if (data.startsWith("PL:")) {
            String valor = data.substring(3);
            runOnUiThread(() -> txtPulsosAtual.setText("PL: " + valor));
            if (mEstadoCal == EstadoCalibracao.CAL_CONCLUIDA) {
                Log.i(TAG, "[CAL] Novo PL gravado: " + valor);
                runOnUiThread(() ->
                    setStatusCal("Calibracao concluida! Novo fator: " + valor + " pulsos/L",
                            Color.parseColor("#2E7D32"))
                );
            }
            return;
        }

        // TO:<valor_ms> — timeout atual
        if (data.startsWith("TO:")) {
            Log.d(TAG, "[TO] Timeout atual: " + data.substring(3) + " ms");
            return;
        }

        // VR:<versao>/<data> — versao do firmware
        if (data.startsWith("VR:")) {
            Log.i(TAG, "[VR] Firmware: " + data.substring(3));
            runOnUiThread(() ->
                Toast.makeText(CalibrarPulsos.this, "Firmware: " + data.substring(3), Toast.LENGTH_SHORT).show()
            );
            return;
        }

        // IN: — valvula abriu
        if ("IN:".equals(data)) {
            runOnUiThread(() -> {
                txtVolumeLiberado.setText("0.000 ML");
                if (mEstadoCal == EstadoCalibracao.CAL_DISPENSANDO) {
                    setStatusCal("Dispensando para calibracao... aguarde.", Color.DKGRAY);
                }
            });
            return;
        }

        // VP:<ml> — volume parcial
        if (data.startsWith("VP:")) {
            try {
                double ml = Double.parseDouble(data.substring(3));
                runOnUiThread(() -> txtVolumeLiberado.setText(String.format("%.3f ML", ml)));
            } catch (NumberFormatException e) {
                Log.w(TAG, "[VP] Parse error: " + data);
            }
            return;
        }

        // QP:<pulsos> — pulsos totais do ciclo
        if (data.startsWith("QP:")) {
            try {
                mQpCalibracao = Integer.parseInt(data.substring(3));
                Log.i(TAG, "[QP] Pulsos contados: " + mQpCalibracao);
            } catch (NumberFormatException e) {
                Log.w(TAG, "[QP] Parse error: " + data);
            }
            return;
        }

        // ML:<ml> — volume final do ciclo (teste normal ou $LB:)
        if (data.startsWith("ML:")) {
            mLiberacaoContinuaAtiva = false;
            runOnUiThread(() -> {
                btnLiberacaoContinua.setText("Iniciar Calibracao");
                String ml = data.substring(3);
                Log.d(TAG, "[ML] Ciclo encerrado: " + ml + " mL");
            });
            return;
        }

        // CA: — ciclo de calibracao encerrado, aguardando $CF:
        if ("CA:".equals(data)) {
            Log.i(TAG, "[CAL] Dispensacao concluida — aguardando medicao na proveta");
            mEstadoCal = EstadoCalibracao.CAL_AGUARDANDO;
            runOnUiThread(() -> {
                setStatusCal(
                    "Dispensa concluida (" + mQpCalibracao + " pulsos).\n"
                    + "Meca o volume na proveta e informe abaixo:",
                    Color.parseColor("#1565C0")
                );
                mostrarPainelCalibracao(false);
                mostrarPainelConfirmacao(true);
            });
            return;
        }

        // FN: — ciclo encerrado (normal ou apos $CF:)
        if ("FN:".equals(data)) {
            Log.i(TAG, "[FN] Ciclo encerrado");
            if (mEstadoCal == EstadoCalibracao.CAL_CONCLUIDA) {
                runOnUiThread(() -> {
                    mostrarPainelConfirmacao(false);
                    mEstadoCal = EstadoCalibracao.IDLE;
                    // Consulta novo PL para confirmar gravacao
                    if (mBluetoothService != null) mBluetoothService.sendCommand("$PL:0");
                });
            }
            return;
        }

        // OK — comando aceito
        if ("OK".equalsIgnoreCase(data)) {
            Log.d(TAG, "[OK] Comando aceito");
            return;
        }

        // ERRO — comando rejeitado
        if ("ERRO".equalsIgnoreCase(data)) {
            Log.e(TAG, "[ERRO] ESP32 rejeitou o comando");
            if (mEstadoCal == EstadoCalibracao.CAL_AGUARDANDO) {
                mEstadoCal = EstadoCalibracao.IDLE;
                runOnUiThread(() -> {
                    setStatusCal("Erro: calibracao perdida (ESP32 reiniciou?). Reinicie o processo.", Color.RED);
                    mostrarPainelConfirmacao(false);
                });
            } else {
                runOnUiThread(() ->
                    Toast.makeText(CalibrarPulsos.this, "Erro no comando BLE", Toast.LENGTH_SHORT).show()
                );
            }
            return;
        }

        // PONG
        if ("PONG".equalsIgnoreCase(data)) {
            Log.d(TAG, "[PONG] Firmware ativo");
        }
    }

    // -------------------------------------------------------------------------
    // ServiceConnection
    // -------------------------------------------------------------------------
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothServiceIndustrial.LocalBinder binder =
                    (BluetoothServiceIndustrial.LocalBinder) service;
            mBluetoothService = binder.getService();
            mIsServiceBound = true;
            String status = mBluetoothService.getCurrentStatus();
            Log.d(TAG, "BluetoothService vinculado. Status: " + status);
            if ("ready".equals(status) || "connected".equals(status)) {
                changeButtons(true);
                mBluetoothService.sendCommand("$PL:0");
                mBluetoothService.sendCommand("$TO:0");
            } else {
                changeButtons(false);
                reconectar();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "BluetoothService desvinculado");
            mIsServiceBound = false;
        }
    };

    // -------------------------------------------------------------------------
    // Ciclo de vida
    // -------------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        EdgeToEdge.enable(this);
        setContentView(R.layout.calibrar_pulsos);
        setupFullscreen();
        setupBackBlock();

        // Bind de views
        main                 = findViewById(R.id.mainCalibrar);
        txtPulsosAtual       = findViewById(R.id.txtTimeoutAtual);
        txtVolumeLiberado    = findViewById(R.id.txtVolumeLiberado);
        edtNovoPulsos        = findViewById(R.id.edtNovoTimeout);
        btnPulsos            = findViewById(R.id.btnChangePulsos);
        btnLiberar           = findViewById(R.id.btnSalvarTimeout);
        btnTimeout           = findViewById(R.id.btnTimeout);
        btnLiberacaoContinua = findViewById(R.id.btnLiberacaoContinua);
        Button btnConfig     = findViewById(R.id.btnConfig);
        Button btnVoltar     = findViewById(R.id.btnVoltar);

        // Aliases para o fluxo de calibracao (reutilizam views existentes)
        txtStatusCal    = txtVolumeLiberado;
        edtVolumeMedido = edtNovoPulsos;
        btnConfirmarCal = btnPulsos;

        // Estado inicial
        if (main != null) main.setVisibility(View.VISIBLE);
        mostrarPainelCalibracao(false);
        mostrarPainelConfirmacao(false);
        changeButtons(false);

        // Bind ao servico BLE
        Intent serviceIntent = new Intent(this, BluetoothServiceIndustrial.class);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        // ── Listeners ─────────────────────────────────────────────────────────

        // Gravar pulsos/litro manualmente ($PL:<n>)
        btnPulsos.setOnClickListener(v -> {
            // Durante calibracao aguardando: este botao confirma o volume medido ($CF:)
            if (mEstadoCal == EstadoCalibracao.CAL_AGUARDANDO) {
                confirmarCalibracao();
                return;
            }
            // Fora da calibracao: grava $PL:<n> diretamente
            String val = edtNovoPulsos.getText().toString().trim();
            if (val.isEmpty()) {
                Toast.makeText(this, "Informe o valor de pulsos/litro", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                int qtd = Integer.parseInt(val);
                if (qtd <= 0) throw new NumberFormatException("zero");
                Log.d(TAG, "[PL] Enviando $PL:" + qtd);
                if (mBluetoothService != null) mBluetoothService.sendCommand("$PL:" + qtd);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Valor invalido (deve ser inteiro > 0)", Toast.LENGTH_SHORT).show();
            }
        });

        // Teste rapido de fluxo ($ML:100)
        btnLiberar.setOnClickListener(v -> {
            Log.d(TAG, "[TESTE] Enviando $ML:100");
            if (mBluetoothService != null) mBluetoothService.sendCommand("$ML:100");
        });

        // Botao multiplo: Liberacao continua / Iniciar Calibracao
        // Comportamento depende do estado atual
        btnLiberacaoContinua.setOnClickListener(v -> {
            if (mBluetoothService == null) return;
            if (mEstadoCal == EstadoCalibracao.IDLE) {
                // Modo normal: alterna liberacao continua
                if (!mLiberacaoContinuaAtiva) {
                    Log.d(TAG, "[LB] Iniciando liberacao continua");
                    mBluetoothService.sendCommand("$LB:");
                    mLiberacaoContinuaAtiva = true;
                    btnLiberacaoContinua.setText("Parar liberacao");
                } else {
                    Log.d(TAG, "[LB] Parando liberacao continua");
                    mBluetoothService.sendCommand("$ML:0");
                    mLiberacaoContinuaAtiva = false;
                    btnLiberacaoContinua.setText("Iniciar Calibracao");
                }
            }
        });

        // Navegar para ModificarTimeout
        btnTimeout.setOnClickListener(v ->
                startActivity(new Intent(CalibrarPulsos.this, ModificarTimeout.class)));

        // Configuracoes do sistema
        if (btnConfig != null) {
            btnConfig.setOnClickListener(v ->
                    startActivity(new Intent(Settings.ACTION_SETTINGS)));
        }

        // Voltar para Home
        btnVoltar.setOnClickListener(v -> voltarHome());

        Log.d(TAG, "onCreate concluido");
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothServiceIndustrial.BLE_STATUS_ACTION);
        filter.addAction(BluetoothServiceIndustrial.BLE_DATA_ACTION);
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceUpdateReceiver, filter);
        Log.d(TAG, "onResume — receiver registrado");
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mServiceUpdateReceiver);
        Log.d(TAG, "onPause — receiver desregistrado");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
        Log.d(TAG, "onDestroy");
    }

    // -------------------------------------------------------------------------
    // Fluxo de calibracao
    // -------------------------------------------------------------------------

    /**
     * Passo 1 — Inicia o ciclo de calibracao enviando $CA:300.
     * Recomendado usar >= 300 mL para maior precisao.
     * O ESP32 responde: OK -> IN: -> VP:... -> QP:<n> -> CA:
     */
    private void iniciarCalibracao() {
        if (mBluetoothService == null) {
            Toast.makeText(this, "Sem conexao BLE", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mEstadoCal != EstadoCalibracao.IDLE) {
            Toast.makeText(this, "Calibracao ja em andamento", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.i(TAG, "[CAL] Iniciando calibracao com $CA:300");
        mEstadoCal = EstadoCalibracao.CAL_DISPENSANDO;
        mQpCalibracao = 0;
        txtVolumeLiberado.setText("0.000 ML");
        setStatusCal("Iniciando calibracao (300 mL)...", Color.DKGRAY);
        mostrarPainelCalibracao(true);
        mostrarPainelConfirmacao(false);
        mBluetoothService.sendCommand("$CA:300");
    }

    /**
     * Passo 2 — Envia $CF:<ml_real> com o volume medido pelo operador na proveta.
     * O ESP32 calcula: pulsosLitro = (QP / ml_real) x 1000, grava na EEPROM.
     * Responde: PL:<novo_valor> -> FN:
     */
    private void confirmarCalibracao() {
        if (mEstadoCal != EstadoCalibracao.CAL_AGUARDANDO) {
            Toast.makeText(this, "Nenhuma calibracao aguardando confirmacao", Toast.LENGTH_SHORT).show();
            return;
        }
        String val = edtVolumeMedido.getText().toString().trim();
        if (val.isEmpty()) {
            Toast.makeText(this, "Informe o volume medido na proveta", Toast.LENGTH_SHORT).show();
            return;
        }
        int mlReal;
        try {
            mlReal = Integer.parseInt(val);
            if (mlReal <= 0) throw new NumberFormatException("zero");
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Volume invalido (deve ser inteiro > 0)", Toast.LENGTH_SHORT).show();
            return;
        }
        double plEstimado = (mQpCalibracao > 0) ? ((double) mQpCalibracao / mlReal) * 1000.0 : 0;
        Log.i(TAG, String.format("[CAL] Enviando $CF:%d | QP=%d | PL estimado=%.0f",
                mlReal, mQpCalibracao, plEstimado));
        mEstadoCal = EstadoCalibracao.CAL_CONCLUIDA;
        setStatusCal("Calculando novo fator...", Color.DKGRAY);
        if (mBluetoothService != null) mBluetoothService.sendCommand("$CF:" + mlReal);
    }

    // -------------------------------------------------------------------------
    // Helpers de UI
    // -------------------------------------------------------------------------

    /**
     * Controla o estado do botao de calibracao durante a dispensacao.
     */
    private void mostrarPainelCalibracao(boolean ativo) {
        runOnUiThread(() -> {
            if (ativo) {
                btnLiberacaoContinua.setText("Calibrando...");
                btnLiberacaoContinua.setEnabled(false);
                btnLiberar.setEnabled(false);
            } else {
                btnLiberacaoContinua.setText("Iniciar Calibracao");
                btnLiberacaoContinua.setEnabled(true);
                btnLiberar.setEnabled(true);
            }
        });
    }

    /**
     * Exibe ou oculta o painel de confirmacao (campo de volume medido + botao confirmar).
     * Reutiliza edtNovoTimeout e btnChangePulsos.
     */
    private void mostrarPainelConfirmacao(boolean visivel) {
        runOnUiThread(() -> {
            if (visivel) {
                edtVolumeMedido.setHint("Volume medido na proveta (mL)");
                edtVolumeMedido.setInputType(InputType.TYPE_CLASS_NUMBER);
                edtVolumeMedido.setText("");
                edtVolumeMedido.setEnabled(true);
                edtVolumeMedido.requestFocus();
                btnConfirmarCal.setText("Confirmar Medicao");
                btnConfirmarCal.setEnabled(true);
                btnLiberar.setEnabled(false);
                btnLiberacaoContinua.setEnabled(false);
            } else {
                edtVolumeMedido.setHint("Pulsos/litro");
                edtVolumeMedido.setInputType(InputType.TYPE_CLASS_NUMBER);
                edtVolumeMedido.setText("");
                btnConfirmarCal.setText("Salvar Pulsos");
                btnLiberar.setEnabled(true);
                btnLiberacaoContinua.setEnabled(true);
            }
        });
    }

    /**
     * Atualiza o TextView de status com a mensagem e cor fornecidas.
     */
    private void setStatusCal(String msg, int color) {
        runOnUiThread(() -> {
            txtStatusCal.setText(msg);
            txtStatusCal.setTextColor(color);
        });
    }

    /**
     * Habilita ou desabilita os botoes de acao conforme o estado da conexao BLE.
     */
    public void changeButtons(boolean enabled) {
        runOnUiThread(() -> {
            int color = enabled ? Color.WHITE : Color.GRAY;
            btnLiberacaoContinua.setTextColor(color);
            btnPulsos.setTextColor(color);
            btnLiberar.setTextColor(color);
            btnTimeout.setTextColor(color);
            btnLiberacaoContinua.setEnabled(enabled);
            btnPulsos.setEnabled(enabled);
            btnLiberar.setEnabled(enabled);
            btnTimeout.setEnabled(enabled);
        });
    }

    private void reconectar() {
        if (mBluetoothService != null) {
            String mac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                    .getString("esp32_mac", "");
            if (mac != null && !mac.isEmpty()) mBluetoothService.connectWithMac(mac);
        }
    }

    private void voltarHome() {
        Log.d(TAG, "Voltando para Home");
        Intent intent = new Intent(CalibrarPulsos.this, Home.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void setupFullscreen() {
        WindowInsetsControllerCompat wic =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        wic.hide(WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    // v5.2: Impede que o botao Voltar caia na AcessoMaster via back stack.
    private void setupBackBlock() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Log.i(TAG, "[KIOSK] Botao Voltar bloqueado -> Home");
                voltarHome();
            }
        });
    }
}
