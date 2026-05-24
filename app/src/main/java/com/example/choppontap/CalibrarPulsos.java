package com.example.choppontap;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.util.Log;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.snackbar.Snackbar;

/**
 * CalibrarPulsos — Tela de calibracao e testes do sensor de fluxo.
 *
 * Protocolo ESP32 NUS v1.0.0 (2026-05-05)
 *
 * Funcoes disponíveis:
 *   1. Liberar 100 mL ($ML:100) — teste rapido
 *   2. Liberacao continua ($LB:) — fecha por timeout forcado ($TO:1)
 *   3. Calibracao automatica — NOVO FLUXO 3 ETAPAS:
 *        ETAPA 1: Usuario informa volume desejado → confirma
 *        ETAPA 2: Sistema envia $RS: + $CA:<volume> → ESP32 dispensa
 *        ETAPA 3: Usuario mede na proveta → informa volume real → $CF:<ml_real>
 *   4. Salvar pulsos/litro manual ($PL:<n>)
 *   5. Modificar Timeout → navega para ModificarTimeout
 *
 * Maquina de estados de calibracao:
 *   IDLE → CAL_DISPENSANDO → CAL_AGUARDANDO → CAL_CONCLUIDA → IDLE
 */
public class CalibrarPulsos extends AppCompatActivity {

    // ── Estados da maquina de calibracao ──────────────────────────────────
    private enum EstadoCal { IDLE, CAL_DISPENSANDO, CAL_AGUARDANDO, CAL_CONCLUIDA }
    private EstadoCal estadoCal = EstadoCal.IDLE;

    // ── Volume alvo definido pelo usuario na Etapa 1 ──────────────────────
    private double mVolumeAlvo = 300.0;  // default seguro

    // ── Flags de operacao ativa ───────────────────────────────────────────
    private boolean mLiberandoContinuo = false;   // $LB: ativo
    private boolean mLiberando100ml    = false;   // $ML:100 ativo

    // ── Ultimo VP recebido ────────────────────────────────────────────────
    private double mUltimoVp = 0.0;

    // ── Servico BLE ───────────────────────────────────────────────────────
    private BluetoothServiceIndustrial mBluetoothService;
    private boolean mIsServiceBound = false;
    private final Handler handler = new Handler();

    // ── Views ─────────────────────────────────────────────────────────────
    private TextView     txtTimeoutAtual;
    private TextView     txtVolumeLiberado;
    private TextView     txtStatusOperacao;
    private TextView     txtVolumeAlvo;
    private EditText     edtNovoTimeout;
    private EditText     edtVolumeDesejado;
    private EditText     edtVolumeMedido;
    private Button       btnChangePulsos;
    private Button       btnSalvarTimeout;
    private Button       btnLiberacaoContinua;
    private Button       btnIniciarCal;
    private Button       btnConfirmarCal;
    private Button       btnCancelarCal;
    private Button       btnTimeout;
    private Button       btnVoltar;
    private LinearLayout painelVolumeDesejado;
    private LinearLayout painelDispensando;
    private LinearLayout painelMedicao;
    private LinearLayout painelConfirmacao;   // legado — mantido oculto
    private ProgressBar  progressCal;
    private View         mainView;

    private static final String TAG_CAL = "CALIBRAR_PULSOS";

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
                Log.d(TAG_CAL, "[BLE] Status: " + status);
                if (status.equals("Not found") || status.startsWith("disconnected")) {
                    setAllButtonsEnabled(false);
                    mostrarSnackbarReconectar();
                    // Aborta calibracao se estava em andamento
                    if (estadoCal != EstadoCal.IDLE) {
                        estadoCal = EstadoCal.IDLE;
                        runOnUiThread(() -> {
                            atualizarUiEstado();
                            Toast.makeText(CalibrarPulsos.this,
                                    "Conexao perdida — calibracao abortada", Toast.LENGTH_LONG).show();
                        });
                    }
                } else if (status.equals("connected") || status.startsWith("ready")) {
                    setAllButtonsEnabled(true);
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
                if (msg == null) msg = intent.getStringExtra("message");
                if (msg == null) return;
                Log.d(TAG_CAL, "[RX] Recebido: " + msg.trim());
                final String msgFinal = msg.trim();
                runOnUiThread(() -> processarMensagem(msgFinal));
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

        // Tela cheia
        WindowInsetsControllerCompat insetsController =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        insetsController.hide(WindowInsetsCompat.Type.systemBars());
        insetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        // Bloquear botao Voltar (kiosk mode)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                navegarHome();
            }
        });

        // Bind das views
        mainView              = findViewById(R.id.mainCalibrar);
        txtTimeoutAtual       = findViewById(R.id.txtTimeoutAtual);
        txtVolumeLiberado     = findViewById(R.id.txtVolumeLiberado);
        txtStatusOperacao     = findViewById(R.id.txtStatusOperacao);
        txtVolumeAlvo         = findViewById(R.id.txtVolumeAlvo);
        edtNovoTimeout        = findViewById(R.id.edtNovoTimeout);
        edtVolumeDesejado     = findViewById(R.id.edtVolumeDesejado);
        edtVolumeMedido       = findViewById(R.id.edtVolumeMedido);
        btnChangePulsos       = findViewById(R.id.btnChangePulsos);
        btnSalvarTimeout      = findViewById(R.id.btnSalvarTimeout);
        btnLiberacaoContinua  = findViewById(R.id.btnLiberacaoContinua);
        btnIniciarCal         = findViewById(R.id.btnIniciarCal);
        btnConfirmarCal       = findViewById(R.id.btnConfirmarCal);
        btnCancelarCal        = findViewById(R.id.btnCancelarCal);
        btnTimeout            = findViewById(R.id.btnTimeout);
        btnVoltar             = findViewById(R.id.btnVoltar);
        painelVolumeDesejado  = findViewById(R.id.painelVolumeDesejado);
        painelDispensando     = findViewById(R.id.painelDispensando);
        painelMedicao         = findViewById(R.id.painelMedicao);
        painelConfirmacao     = findViewById(R.id.painelConfirmacao);
        progressCal           = findViewById(R.id.progressCal);

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
                mBluetoothService.sendCommand("$ML:0\n");
                mLiberandoContinuo = false;
            }
            mLiberando100ml = true;
            resetarVolumeDisplay();
            setStatusOperacao("Liberando 100 mL...", true);
            Log.d(TAG_CAL, "[RESET] Enviando $RS: antes de $ML:100");
            mBluetoothService.sendCommand("$RS:\n");
            handler.postDelayed(() -> {
                if (mIsServiceBound && mBluetoothService != null) {
                    mBluetoothService.sendCommand("$ML:100\n");
                }
            }, 150);
        });

        // ── Botao: Liberacao continua ($LB: / para com timeout forcado) ──
        btnLiberacaoContinua.setOnClickListener(v -> {
            if (!verificarServico()) return;
            if (mLiberandoContinuo) {
                // Segundo clique: forca encerramento via timeout minimo
                Log.d(TAG_CAL, "[PARAR] Iniciando sequencia de encerramento de $LB:");
                mLiberandoContinuo = false;
                btnLiberacaoContinua.setText("Parando...");
                btnLiberacaoContinua.setEnabled(false);
                btnLiberacaoContinua.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(Color.GRAY));
                setStatusOperacao("Encerrando liberacao — aguarde...", true);
                mBluetoothService.sendCommand("$TO:1\n");
                Log.d(TAG_CAL, "[PARAR] $TO:1 enviado (1/4)");
                for (int i = 1; i <= 3; i++) {
                    final int tentativa = i + 1;
                    handler.postDelayed(() -> {
                        if (mIsServiceBound && mBluetoothService != null) {
                            mBluetoothService.sendCommand("$TO:1\n");
                            Log.d(TAG_CAL, "[PARAR] $TO:1 enviado (" + tentativa + "/4)");
                        }
                    }, i * 1000L);
                }
                handler.postDelayed(() -> {
                    if (mIsServiceBound && mBluetoothService != null) {
                        mBluetoothService.sendCommand("$TO:10000\n");
                        Log.d(TAG_CAL, "[PARAR] Timeout restaurado para 10000ms");
                    }
                    runOnUiThread(() -> {
                        if (!mLiberandoContinuo) {
                            btnLiberacaoContinua.setText("Liberacao Continua");
                            btnLiberacaoContinua.setEnabled(true);
                            btnLiberacaoContinua.setBackgroundTintList(
                                    android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6F00")));
                            setStatusOperacao("", false);
                            Log.d(TAG_CAL, "[PARAR] Botao restaurado apos 5s");
                        }
                    });
                }, 5000L);
            } else {
                // Primeiro clique: inicia liberacao continua
                mLiberandoContinuo = true;
                resetarVolumeDisplay();
                Log.d(TAG_CAL, "[RESET] Enviando $RS: antes de $LB:");
                mBluetoothService.sendCommand("$RS:\n");
                btnLiberacaoContinua.setText("PARAR Liberacao");
                btnLiberacaoContinua.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252")));
                setStatusOperacao("Liberacao continua ativa — clique novamente para parar", true);
                handler.postDelayed(() -> {
                    if (mIsServiceBound && mBluetoothService != null) {
                        mBluetoothService.sendCommand("$LB:\n");
                    }
                }, 150);
            }
        });

        // ── NOVO FLUXO: Botao "Confirmar e Iniciar Calibracao" (Etapa 1) ─
        // Lê o volume desejado, valida e inicia a dispensacao
        btnIniciarCal.setOnClickListener(v -> {
            if (!verificarServico()) return;
            if (estadoCal != EstadoCal.IDLE) {
                Toast.makeText(this, "Calibracao ja em andamento", Toast.LENGTH_SHORT).show();
                return;
            }

            // Valida o campo de volume desejado
            String val = edtVolumeDesejado.getText().toString().trim();
            if (TextUtils.isEmpty(val)) {
                edtVolumeDesejado.setError("Informe o volume desejado para calibracao");
                edtVolumeDesejado.requestFocus();
                return;
            }
            double volumeDesejado;
            try {
                volumeDesejado = Double.parseDouble(val);
            } catch (NumberFormatException e) {
                edtVolumeDesejado.setError("Valor invalido");
                return;
            }
            if (volumeDesejado <= 0) {
                edtVolumeDesejado.setError("Volume deve ser maior que zero");
                return;
            }
            if (volumeDesejado > 2000) {
                edtVolumeDesejado.setError("Volume maximo: 2000 mL");
                return;
            }

            // Salva o volume alvo para exibicao e para o $CA:
            mVolumeAlvo = volumeDesejado;

            // Oculta teclado
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);

            // Confirma com o usuario antes de iniciar
            new AlertDialog.Builder(this)
                    .setTitle("Confirmar Calibracao")
                    .setMessage("Sera dispensado " + String.format("%.0f mL", mVolumeAlvo) +
                            " para calibracao.\n\nPosicione a proveta e confirme.")
                    .setPositiveButton("Iniciar", (dialog, which) -> iniciarDispensacaoCalibrar())
                    .setNegativeButton("Cancelar", null)
                    .show();
        });

        // ── Botao: Confirmar Medicao ($CF:<ml_real>) — Etapa 3 ───────────
        btnConfirmarCal.setOnClickListener(v -> {
            if (estadoCal != EstadoCal.CAL_AGUARDANDO) {
                Toast.makeText(this, "Aguarde a dispensacao terminar", Toast.LENGTH_SHORT).show();
                return;
            }
            String val = edtVolumeMedido.getText().toString().trim();
            if (TextUtils.isEmpty(val)) {
                edtVolumeMedido.setError("Informe o volume medido na proveta");
                edtVolumeMedido.requestFocus();
                return;
            }
            double mlReal;
            try {
                mlReal = Double.parseDouble(val);
            } catch (NumberFormatException e) {
                edtVolumeMedido.setError("Valor invalido");
                return;
            }
            if (mlReal <= 0) {
                edtVolumeMedido.setError("Volume deve ser maior que zero");
                return;
            }
            if (!verificarServico()) return;

            // Oculta teclado
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);

            // Confirma com o usuario
            new AlertDialog.Builder(this)
                    .setTitle("Confirmar Medicao")
                    .setMessage("Volume real medido: " + String.format("%.1f mL", mlReal) +
                            "\nVolume alvo: " + String.format("%.0f mL", mVolumeAlvo) +
                            "\n\nO sistema calculara e gravara o novo fator de calibracao.")
                    .setPositiveButton("Confirmar", (dialog, which) -> {
                        estadoCal = EstadoCal.CAL_CONCLUIDA;
                        atualizarUiEstado();
                        // Envia o volume real como inteiro (protocolo ESP32 aceita int)
                        int mlRealInt = (int) Math.round(mlReal);
                        Log.d(TAG_CAL, "[CAL] Enviando $CF:" + mlRealInt +
                                " (volume alvo=" + mVolumeAlvo + ", medido=" + mlReal + ")");
                        mBluetoothService.sendCommand("$CF:" + mlRealInt + "\n");
                    })
                    .setNegativeButton("Corrigir", null)
                    .show();
        });

        // ── Botao: Cancelar Calibracao (Etapa 3) ─────────────────────────
        btnCancelarCal.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Cancelar Calibracao")
                    .setMessage("Deseja cancelar a calibracao? O fator atual sera mantido.")
                    .setPositiveButton("Sim, cancelar", (dialog, which) -> {
                        Log.d(TAG_CAL, "[CAL] Calibracao cancelada pelo usuario na Etapa 3");
                        estadoCal = EstadoCal.IDLE;
                        edtVolumeMedido.setText("");
                        edtVolumeDesejado.setText("");
                        atualizarUiEstado();
                        Toast.makeText(CalibrarPulsos.this,
                                "Calibracao cancelada. Fator anterior mantido.", Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Continuar", null)
                    .show();
        });

        // ── Botao: Modificar Timeout ──────────────────────────────────────
        btnTimeout.setOnClickListener(v -> {
            Intent i = new Intent(this, ModificarTimeout.class);
            startActivity(i);
        });

        // ── Botao: Voltar ─────────────────────────────────────────────────
        btnVoltar.setOnClickListener(v -> {
            if (estadoCal != EstadoCal.IDLE) {
                new AlertDialog.Builder(this)
                        .setTitle("Sair da Calibracao")
                        .setMessage("Ha uma calibracao em andamento. Deseja sair mesmo assim?")
                        .setPositiveButton("Sair", (dialog, which) -> navegarHome())
                        .setNegativeButton("Continuar", null)
                        .show();
            } else {
                navegarHome();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // Inicia a dispensacao de calibracao (chamado apos confirmacao do dialogo)
    // ─────────────────────────────────────────────────────────────────────
    private void iniciarDispensacaoCalibrar() {
        if (!verificarServico()) return;
        estadoCal = EstadoCal.CAL_DISPENSANDO;
        resetarVolumeDisplay();
        atualizarUiEstado();

        // Atualiza o texto do painel de dispensacao com o volume alvo
        if (txtVolumeAlvo != null) {
            txtVolumeAlvo.setText("Dispensando " + String.format("%.0f mL", mVolumeAlvo) +
                    " — aguarde o termino");
        }

        // Zera o acumulador VP no ESP32 antes de iniciar
        Log.d(TAG_CAL, "[RESET] Enviando $RS: antes de $CA:" + (int) mVolumeAlvo);
        mBluetoothService.sendCommand("$RS:\n");
        handler.postDelayed(() -> {
            if (mIsServiceBound && mBluetoothService != null) {
                // Envia o volume como inteiro (protocolo ESP32)
                int volumeInt = (int) Math.round(mVolumeAlvo);
                Log.d(TAG_CAL, "[CAL] Enviando $CA:" + volumeInt);
                mBluetoothService.sendCommand("$CA:" + volumeInt + "\n");
            }
        }, 150);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Processamento de mensagens recebidas do ESP32
    // ─────────────────────────────────────────────────────────────────────
    private void processarMensagem(String msg) {

        // ── VP: — volume parcial em tempo real ────────────────────────────
        if (msg.startsWith("VP:")) {
            try {
                double ml = Double.parseDouble(msg.substring(3));
                boolean operacaoAtiva = mLiberando100ml || mLiberandoContinuo
                        || estadoCal == EstadoCal.CAL_DISPENSANDO;
                if (!operacaoAtiva && ml > 0 && mUltimoVp == 0.0) {
                    Log.d(TAG_CAL, "[VP] Ignorando VP: residual (" + ml + ") — nenhuma operacao ativa");
                    return;
                }
                mUltimoVp = ml;
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
                    setStatusOperacao("Calibrando — valvula aberta, dispensando " +
                            String.format("%.0f mL", mVolumeAlvo) + "...", true);
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
            try {
                int qp = Integer.parseInt(msg.substring(3));
                runOnUiThread(() ->
                    setStatusOperacao("Pulsos contados: " + qp + " — aguardando medicao na proveta", true)
                );
            } catch (NumberFormatException ignored) {}
            return;
        }

        // ── CA: — fim da dispensacao de calibracao, aguardando $CF: ──────
        // ESP32 enviou CA: indicando que a dispensacao terminou.
        // Avanca para Etapa 3: usuario deve medir e informar o volume real.
        if (msg.equals("CA:")) {
            runOnUiThread(() -> {
                estadoCal = EstadoCal.CAL_AGUARDANDO;
                edtVolumeMedido.setText("");
                atualizarUiEstado();
                // Foca no campo de medicao automaticamente
                edtVolumeMedido.requestFocus();
                Log.d(TAG_CAL, "[CAL] Dispensacao concluida — aguardando medicao do usuario");
            });
            return;
        }

        // ── ML: — fim de ciclo normal (100mL ou continuo) ─────────────────
        if (msg.startsWith("ML:")) {
            try {
                double ml = Double.parseDouble(msg.substring(3));
                runOnUiThread(() -> {
                    txtVolumeLiberado.setText(String.format("%.3f ML", ml));
                    mLiberando100ml    = false;
                    mLiberandoContinuo = false;
                    btnLiberacaoContinua.setText("Liberacao Continua");
                    btnLiberacaoContinua.setEnabled(true);
                    btnLiberacaoContinua.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6F00")));
                    setStatusOperacao("Ciclo encerrado: " + String.format("%.3f mL", ml), true);
                });
            } catch (NumberFormatException ignored) {}
            return;
        }

        // ── FN: — ciclo completamente encerrado ───────────────────────────
        if (msg.equals("FN:")) {
            runOnUiThread(() -> {
                mLiberando100ml    = false;
                mLiberandoContinuo = false;
                btnLiberacaoContinua.setText("Liberacao Continua");
                btnLiberacaoContinua.setEnabled(true);
                btnLiberacaoContinua.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6F00")));

                if (estadoCal == EstadoCal.CAL_CONCLUIDA) {
                    // Calibracao concluida com sucesso — volta para IDLE
                    estadoCal = EstadoCal.IDLE;
                    edtVolumeDesejado.setText("");
                    edtVolumeMedido.setText("");
                    atualizarUiEstado();
                    // Consulta o novo PL gravado
                    handler.postDelayed(() -> {
                        if (mIsServiceBound && mBluetoothService != null) {
                            mBluetoothService.sendCommand("$PL:0\n");
                        }
                    }, 500);
                    new AlertDialog.Builder(CalibrarPulsos.this)
                            .setTitle("Calibracao Concluida!")
                            .setMessage("O novo fator de calibracao foi gravado na EEPROM do ESP32.\n\n" +
                                    "O valor de Pulsos/Litro foi atualizado acima.")
                            .setPositiveButton("OK", null)
                            .show();
                    Log.d(TAG_CAL, "[CAL] Calibracao concluida com sucesso");
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
            return; // Gerenciado em ModificarTimeout
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
                Log.e(TAG_CAL, "[ERRO] ESP32 rejeitou o ultimo comando");
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Atualiza a UI conforme o estado da maquina de calibracao
    // ─────────────────────────────────────────────────────────────────────
    private void atualizarUiEstado() {
        switch (estadoCal) {

            case IDLE:
                // Etapa 1 visivel, Etapa 2 e 3 ocultas
                painelVolumeDesejado.setVisibility(View.VISIBLE);
                painelDispensando.setVisibility(View.GONE);
                painelMedicao.setVisibility(View.GONE);
                // Restaura botao de iniciar
                btnIniciarCal.setEnabled(true);
                btnIniciarCal.setText("Confirmar e Iniciar Calibracao");
                btnIniciarCal.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#1565C0")));
                setStatusOperacao("", false);
                setAllButtonsEnabled(true);
                break;

            case CAL_DISPENSANDO:
                // Etapa 1 oculta, Etapa 2 visivel, Etapa 3 oculta
                painelVolumeDesejado.setVisibility(View.GONE);
                painelDispensando.setVisibility(View.VISIBLE);
                painelMedicao.setVisibility(View.GONE);
                // Desabilita todos os outros botoes durante dispensacao
                btnSalvarTimeout.setEnabled(false);
                btnLiberacaoContinua.setEnabled(false);
                btnChangePulsos.setEnabled(false);
                setStatusOperacao("Dispensando " + String.format("%.0f mL", mVolumeAlvo) +
                        " para calibracao...", true);
                break;

            case CAL_AGUARDANDO:
                // Etapa 1 oculta, Etapa 2 oculta, Etapa 3 visivel
                painelVolumeDesejado.setVisibility(View.GONE);
                painelDispensando.setVisibility(View.GONE);
                painelMedicao.setVisibility(View.VISIBLE);
                setStatusOperacao("Dispensacao concluida! Meca o volume na proveta.", true);
                break;

            case CAL_CONCLUIDA:
                // Todos os paineis ocultos — aguardando FN: do ESP32
                painelVolumeDesejado.setVisibility(View.GONE);
                painelDispensando.setVisibility(View.GONE);
                painelMedicao.setVisibility(View.GONE);
                setStatusOperacao("Gravando novo fator de calibracao na EEPROM...", true);
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers de UI
    // ─────────────────────────────────────────────────────────────────────
    private void resetarVolumeDisplay() {
        mUltimoVp = 0.0;
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
        runOnUiThread(() -> {
            if (mainView != null) {
                Snackbar.make(mainView, "BLE desconectado — reconectando...",
                        Snackbar.LENGTH_LONG).show();
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────
    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothServiceIndustrial.BLE_STATUS_ACTION);
        filter.addAction(BluetoothServiceIndustrial.BLE_DATA_ACTION);
        registerReceiver(mServiceUpdateReceiver, filter);

        Intent serviceIntent = new Intent(this, BluetoothServiceIndustrial.class);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(mServiceUpdateReceiver); } catch (Exception ignored) {}
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }
}
