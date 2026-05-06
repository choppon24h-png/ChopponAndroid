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
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.google.android.material.snackbar.Snackbar;

/**
 * CalibrarPulsos — Tela de calibracao e testes do sensor de fluxo.
 *
 * Protocolo ESP32 NUS v1.0.0 (2026-05-05)
 *
 * Funcoes disponíveis:
 *   1. Liberar 100 mL ($ML:100) — teste rapido
 *   2. Liberacao continua ($LB:) — fecha por $ML:0
 *   3. Calibracao automatica ($CA:300 -> $CF:<ml_real>) — 2 passos
 *   4. Salvar pulsos/litro manual ($PL:<n>)
 *   5. Modificar Timeout -> navega para ModificarTimeout
 *
 * Volume Aferido: exibe VP: em tempo real para qualquer funcao ativa.
 * Painel de Calibracao: aparece somente apos CA: recebido do ESP32.
 *
 * Maquina de estados de calibracao:
 *   IDLE -> CAL_DISPENSANDO -> CAL_AGUARDANDO -> CAL_CONCLUIDA -> IDLE
 */
public class CalibrarPulsos extends AppCompatActivity {

    // ── Estados da maquina de calibracao ──────────────────────────────────
    private enum EstadoCal { IDLE, CAL_DISPENSANDO, CAL_AGUARDANDO, CAL_CONCLUIDA }
    private EstadoCal estadoCal = EstadoCal.IDLE;

    // ── Flags de operacao ativa ───────────────────────────────────────────
    private boolean mLiberandoContinuo = false;   // $LB: ativo
    private boolean mLiberando100ml    = false;   // $ML:100 ativo

    // ── Servico BLE ───────────────────────────────────────────────────────
    private BluetoothServiceIndustrial mBluetoothService;
    private boolean mIsServiceBound = false;
    private final Handler handler = new Handler();

    // ── Views ─────────────────────────────────────────────────────────────
    private TextView  txtTimeoutAtual;
    private TextView  txtVolumeLiberado;
    private TextView  txtStatusOperacao;
    private EditText  edtNovoTimeout;
    private EditText  edtVolumeMedido;
    private Button    btnChangePulsos;
    private Button    btnSalvarTimeout;
    private Button    btnLiberacaoContinua;
    private Button    btnIniciarCal;
    private Button    btnConfirmarCal;
    private Button    btnTimeout;
    private Button    btnVoltar;
    private LinearLayout painelConfirmacao;
    private View      mainView;

    // ── BroadcastReceiver — mensagens do servico BLE ──────────────────────
    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            // ── Status de conexao ──────────────────────────────────────────
            if (BluetoothServiceIndustrial.BLE_STATUS_ACTION.equals(action)) {
                String status = intent.getStringExtra("status");
                if (status == null) return;
                if (status.equals("Not found") || status.startsWith("disconnected")) {
                    setAllButtonsEnabled(false);
                    mostrarSnackbarReconectar();
                    // Aborta calibracao se estava em andamento
                    if (estadoCal != EstadoCal.IDLE) {
                        estadoCal = EstadoCal.IDLE;
                        atualizarUiEstado();
                        Toast.makeText(CalibrarPulsos.this,
                                "Conexao perdida — calibracao abortada", Toast.LENGTH_LONG).show();
                    }
                } else if (status.equals("connected") || status.startsWith("ready")) {
                    setAllButtonsEnabled(true);
                    // Consulta PL e TO ao conectar
                    handler.postDelayed(() -> {
                        if (mIsServiceBound && mBluetoothService != null) {
                            mBluetoothService.sendCommand("$PL:0\n");
                            handler.postDelayed(() -> mBluetoothService.sendCommand("$TO:0\n"), 400);
                        }
                    }, 600);
                }
            }

            // ── Dados recebidos do ESP32 ───────────────────────────────────
            if (BluetoothServiceIndustrial.BLE_DATA_ACTION.equals(action)) {
                String msg = intent.getStringExtra("data");
                if (msg == null) return;
                processarMensagem(msg.trim());
            }
        }
    };

    // ── ServiceConnection ─────────────────────────────────────────────────
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothServiceIndustrial.LocalBinder binder =
                    (BluetoothServiceIndustrial.LocalBinder) service;
            mBluetoothService = binder.getService();
            mIsServiceBound = true;
            // Consulta estado atual
            handler.postDelayed(() -> {
                mBluetoothService.sendCommand("$PL:0\n");
                handler.postDelayed(() -> mBluetoothService.sendCommand("$TO:0\n"), 400);
            }, 800);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mIsServiceBound = false;
            mBluetoothService = null;
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // onCreate
    // ─────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.calibrar_pulsos);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);

        // Tela cheia (oculta barras do sistema)
        WindowInsetsControllerCompat insetsController =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        insetsController.hide(WindowInsetsCompat.Type.systemBars());
        insetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        // Bloquear botao Voltar do sistema (kiosk mode)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                navegarHome();
            }
        });

        // Bind das views
        mainView           = findViewById(R.id.mainCalibrar);
        txtTimeoutAtual    = findViewById(R.id.txtTimeoutAtual);
        txtVolumeLiberado  = findViewById(R.id.txtVolumeLiberado);
        txtStatusOperacao  = findViewById(R.id.txtStatusOperacao);
        edtNovoTimeout     = findViewById(R.id.edtNovoTimeout);
        edtVolumeMedido    = findViewById(R.id.edtVolumeMedido);
        btnChangePulsos    = findViewById(R.id.btnChangePulsos);
        btnSalvarTimeout   = findViewById(R.id.btnSalvarTimeout);
        btnLiberacaoContinua = findViewById(R.id.btnLiberacaoContinua);
        btnIniciarCal      = findViewById(R.id.btnIniciarCal);
        btnConfirmarCal    = findViewById(R.id.btnConfirmarCal);
        btnTimeout         = findViewById(R.id.btnTimeout);
        btnVoltar          = findViewById(R.id.btnVoltar);
        painelConfirmacao  = findViewById(R.id.painelConfirmacao);

        configurarBotoes();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Configuracao dos botoes
    // ─────────────────────────────────────────────────────────────────────
    private void configurarBotoes() {

        // ── Botao: Salvar Pulsos/Litro manual ($PL:<n>) ───────────────────
        btnChangePulsos.setOnClickListener(v -> {
            String val = edtNovoTimeout.getText().toString().trim();
            if (TextUtils.isEmpty(val)) {
                Toast.makeText(this, "Informe o valor de pulsos/litro", Toast.LENGTH_SHORT).show();
                return;
            }
            int pl;
            try { pl = Integer.parseInt(val); } catch (NumberFormatException e) {
                Toast.makeText(this, "Valor invalido", Toast.LENGTH_SHORT).show();
                return;
            }
            if (pl <= 0) {
                Toast.makeText(this, "Valor deve ser maior que zero", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!verificarServico()) return;
            mBluetoothService.sendCommand("$PL:" + pl + "\n");
            Toast.makeText(this, "Enviando $PL:" + pl, Toast.LENGTH_SHORT).show();
        });

        // ── Botao: Liberar 100 mL ($ML:100) ──────────────────────────────
        btnSalvarTimeout.setOnClickListener(v -> {
            if (!verificarServico()) return;
            if (mLiberandoContinuo) {
                // Para liberacao continua antes de iniciar novo ciclo
                mBluetoothService.sendCommand("$ML:0\n");
                mLiberandoContinuo = false;
            }
            mLiberando100ml = true;
            resetarVolumeDisplay();
            setStatusOperacao("Liberando 100 mL...", true);
            mBluetoothService.sendCommand("$ML:100\n");
        });

        // ── Botao: Liberacao continua ($LB: / para com $ML:0) ────────────
        btnLiberacaoContinua.setOnClickListener(v -> {
            if (!verificarServico()) return;
            if (mLiberandoContinuo) {
                // Segundo clique: para a liberacao
                mBluetoothService.sendCommand("$ML:0\n");
                mLiberandoContinuo = false;
                btnLiberacaoContinua.setText("Liberacao Continua");
                btnLiberacaoContinua.setBackgroundTintList(
                        getColorStateList(com.google.android.material.R.color.design_default_color_primary));
                setStatusOperacao("", false);
            } else {
                // Primeiro clique: inicia liberacao continua
                mLiberandoContinuo = true;
                resetarVolumeDisplay();
                btnLiberacaoContinua.setText("PARAR Liberacao");
                btnLiberacaoContinua.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252")));
                setStatusOperacao("Liberacao continua ativa — clique novamente para parar", true);
                mBluetoothService.sendCommand("$LB:\n");
            }
        });

        // ── Botao: Iniciar Calibracao Automatica ($CA:300) ────────────────
        btnIniciarCal.setOnClickListener(v -> {
            if (!verificarServico()) return;
            if (estadoCal != EstadoCal.IDLE) {
                Toast.makeText(this, "Calibracao ja em andamento", Toast.LENGTH_SHORT).show();
                return;
            }
            estadoCal = EstadoCal.CAL_DISPENSANDO;
            resetarVolumeDisplay();
            atualizarUiEstado();
            mBluetoothService.sendCommand("$CA:300\n");
        });

        // ── Botao: Confirmar Medicao ($CF:<ml_real>) ──────────────────────
        btnConfirmarCal.setOnClickListener(v -> {
            if (estadoCal != EstadoCal.CAL_AGUARDANDO) {
                Toast.makeText(this, "Aguarde a dispensacao terminar", Toast.LENGTH_SHORT).show();
                return;
            }
            String val = edtVolumeMedido.getText().toString().trim();
            if (TextUtils.isEmpty(val)) {
                Toast.makeText(this, "Informe o volume medido na proveta", Toast.LENGTH_SHORT).show();
                return;
            }
            int mlReal;
            try { mlReal = Integer.parseInt(val); } catch (NumberFormatException e) {
                Toast.makeText(this, "Valor invalido", Toast.LENGTH_SHORT).show();
                return;
            }
            if (mlReal <= 0) {
                Toast.makeText(this, "Volume deve ser maior que zero", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!verificarServico()) return;
            estadoCal = EstadoCal.CAL_CONCLUIDA;
            atualizarUiEstado();
            mBluetoothService.sendCommand("$CF:" + mlReal + "\n");
        });

        // ── Botao: Modificar Timeout ──────────────────────────────────────
        btnTimeout.setOnClickListener(v -> {
            Intent i = new Intent(this, ModificarTimeout.class);
            startActivity(i);
        });

        // ── Botao: Voltar ─────────────────────────────────────────────────
        btnVoltar.setOnClickListener(v -> navegarHome());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Processamento de mensagens recebidas do ESP32
    // ─────────────────────────────────────────────────────────────────────
    private void processarMensagem(String msg) {

        // ── VP: — volume parcial em tempo real ────────────────────────────
        if (msg.startsWith("VP:")) {
            try {
                double ml = Double.parseDouble(msg.substring(3));
                runOnUiThread(() ->
                    txtVolumeLiberado.setText(String.format("%.3f ML", ml))
                );
            } catch (NumberFormatException ignored) {}
            return;
        }

        // ── IN: — valvula abriu ───────────────────────────────────────────
        if (msg.equals("IN:")) {
            runOnUiThread(() -> {
                if (estadoCal == EstadoCal.CAL_DISPENSANDO) {
                    setStatusOperacao("Calibrando — valvula aberta, aguardando fluxo...", true);
                } else if (mLiberando100ml) {
                    setStatusOperacao("Valvula aberta — liberando 100 mL...", true);
                } else if (mLiberandoContinuo) {
                    setStatusOperacao("Liberacao continua — valvula aberta", true);
                }
            });
            return;
        }

        // ── QP: — total de pulsos contados (durante calibracao) ───────────
        if (msg.startsWith("QP:")) {
            // Apenas exibe no status; o calculo e feito pelo ESP32
            try {
                int qp = Integer.parseInt(msg.substring(3));
                runOnUiThread(() ->
                    setStatusOperacao("Pulsos contados: " + qp + " — aguardando medicao na proveta", true)
                );
            } catch (NumberFormatException ignored) {}
            return;
        }

        // ── CA: — fim da dispensacao de calibracao, aguardando $CF: ──────
        if (msg.equals("CA:")) {
            runOnUiThread(() -> {
                estadoCal = EstadoCal.CAL_AGUARDANDO;
                atualizarUiEstado();
            });
            return;
        }

        // ── ML: — fim de ciclo normal (100mL ou continuo) ─────────────────
        if (msg.startsWith("ML:")) {
            try {
                double ml = Double.parseDouble(msg.substring(3));
                runOnUiThread(() -> {
                    txtVolumeLiberado.setText(String.format("%.3f ML", ml));
                    mLiberando100ml = false;
                    if (!mLiberandoContinuo) {
                        setStatusOperacao("Ciclo encerrado: " + String.format("%.3f mL", ml), true);
                    }
                });
            } catch (NumberFormatException ignored) {}
            return;
        }

        // ── FN: — ciclo completamente encerrado (normal ou calibracao) ────
        if (msg.equals("FN:")) {
            runOnUiThread(() -> {
                mLiberando100ml    = false;
                mLiberandoContinuo = false;
                btnLiberacaoContinua.setText("Liberacao Continua");
                btnLiberacaoContinua.setBackgroundTintList(
                        getColorStateList(android.R.color.holo_orange_dark));
                if (estadoCal == EstadoCal.CAL_CONCLUIDA) {
                    // Calibracao concluida com sucesso
                    estadoCal = EstadoCal.IDLE;
                    atualizarUiEstado();
                    Toast.makeText(CalibrarPulsos.this,
                            "Calibracao concluida com sucesso!", Toast.LENGTH_LONG).show();
                } else {
                    setStatusOperacao("", false);
                }
            });
            return;
        }

        // ── PL: — valor atual de pulsos/litro ─────────────────────────────
        if (msg.startsWith("PL:")) {
            String val = msg.substring(3);
            runOnUiThread(() -> txtTimeoutAtual.setText(val));
            return;
        }

        // ── TO: — valor atual de timeout ──────────────────────────────────
        if (msg.startsWith("TO:")) {
            // Apenas informativo; timeout e gerenciado em ModificarTimeout
            return;
        }

        // ── OK — confirmacao de comando recebido ──────────────────────────
        if (msg.equals("OK")) {
            return;
        }

        // ── ERRO — ESP32 rejeitou o comando ───────────────────────────────
        if (msg.equals("ERRO")) {
            runOnUiThread(() -> {
                if (estadoCal != EstadoCal.IDLE) {
                    estadoCal = EstadoCal.IDLE;
                    atualizarUiEstado();
                }
                Toast.makeText(CalibrarPulsos.this,
                        "ESP32 rejeitou o comando. Reinicie a calibracao.", Toast.LENGTH_LONG).show();
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Atualiza a UI conforme o estado da maquina de calibracao
    // ─────────────────────────────────────────────────────────────────────
    private void atualizarUiEstado() {
        switch (estadoCal) {
            case IDLE:
                btnIniciarCal.setEnabled(true);
                btnIniciarCal.setText("Iniciar Calibracao (300 mL)");
                btnIniciarCal.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#1565C0")));
                painelConfirmacao.setVisibility(View.GONE);
                edtVolumeMedido.setText("");
                setStatusOperacao("", false);
                setAllButtonsEnabled(true);
                break;

            case CAL_DISPENSANDO:
                btnIniciarCal.setEnabled(false);
                btnIniciarCal.setText("Calibrando...");
                btnIniciarCal.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(Color.GRAY));
                painelConfirmacao.setVisibility(View.GONE);
                setStatusOperacao("Dispensando 300 mL para calibracao...", true);
                // Desabilita outros botoes durante calibracao
                btnSalvarTimeout.setEnabled(false);
                btnLiberacaoContinua.setEnabled(false);
                btnChangePulsos.setEnabled(false);
                break;

            case CAL_AGUARDANDO:
                btnIniciarCal.setEnabled(false);
                btnIniciarCal.setText("Aguardando medicao...");
                painelConfirmacao.setVisibility(View.VISIBLE);
                setStatusOperacao("Meca o volume na proveta e confirme abaixo", true);
                break;

            case CAL_CONCLUIDA:
                btnIniciarCal.setEnabled(false);
                btnIniciarCal.setText("Gravando calibracao...");
                btnIniciarCal.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#2E7D32")));
                painelConfirmacao.setVisibility(View.GONE);
                setStatusOperacao("Gravando novo PL na EEPROM...", true);
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers de UI
    // ─────────────────────────────────────────────────────────────────────
    private void resetarVolumeDisplay() {
        runOnUiThread(() -> txtVolumeLiberado.setText("0.000 ML"));
    }

    private void setStatusOperacao(String msg, boolean visivel) {
        runOnUiThread(() -> {
            txtStatusOperacao.setText(msg);
            txtStatusOperacao.setVisibility(visivel && !msg.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void setAllButtonsEnabled(boolean enabled) {
        runOnUiThread(() -> {
            btnChangePulsos.setEnabled(enabled);
            btnSalvarTimeout.setEnabled(enabled);
            btnLiberacaoContinua.setEnabled(enabled);
            btnIniciarCal.setEnabled(enabled);
            btnTimeout.setEnabled(enabled);
        });
    }

    private boolean verificarServico() {
        if (!mIsServiceBound || mBluetoothService == null) {
            Toast.makeText(this, "Servico BLE nao disponivel", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void navegarHome() {
        Intent i = new Intent(this, Home.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void mostrarSnackbarReconectar() {
        if (mainView == null) return;
        Snackbar.make(mainView, "Sem conexao com a TAP", Snackbar.LENGTH_INDEFINITE)
                .setAction("Reconectar", v -> {
                    String mac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                            .getString("esp32_mac", "");
                    if (mIsServiceBound && mBluetoothService != null
                            && mac != null && !mac.isEmpty()) {
                        mBluetoothService.connectWithMac(mac);
                    }
                }).show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────
    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothServiceIndustrial.BLE_STATUS_ACTION);
        filter.addAction(BluetoothServiceIndustrial.BLE_DATA_ACTION);
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceUpdateReceiver, filter);

        Intent serviceIntent = new Intent(this, BluetoothServiceIndustrial.class);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mServiceUpdateReceiver);
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }
}
