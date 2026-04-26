package com.example.choppontap;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.snackbar.Snackbar;

/**
 * CalibrarPulsos — Tela de calibração de pulsos e teste de fluxo.
 *
 * Protocolo NUS v4.0:
 *   $PL:<pulsos>  — Configurar/consultar pulsos por litro
 *   $ML:100       — Liberar 100ml para teste
 *   $LB:          — Liberação contínua
 *   $ML:0         — Parar liberação contínua
 *
 * NÃO usa mais: SERVE|100|CALIBRATE|DUMMY, SERVE|9999|..., SERVE|0|...
 */
public class CalibrarPulsos extends AppCompatActivity {

    private static final String TAG = "CALIBRAR_PULSOS";

    private Handler handler = new Handler();
    private ConstraintLayout main;
    private BluetoothServiceIndustrial mBluetoothService;
    private boolean mIsServiceBound = false;

    // Flag para controle de liberação contínua
    private boolean mLiberacaoContinuaAtiva = false;

    TextView qtdAtual;
    TextView txtVolumeLiberado;
    Button btnPulsos;
    Button btnLiberar;
    Button btnTimeout;
    Button btnLiberacaoContinua;

    // ─────────────────────────────────────────────────────────────────────────
    // BroadcastReceiver: recebe eventos do BluetoothService
    // ─────────────────────────────────────────────────────────────────────────
    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            if (BluetoothServiceIndustrial.BLE_STATUS_ACTION.equals(action)) {
                String status = intent.getStringExtra("status");
                if (status == null) return;
                Log.d(TAG, "Status BLE: " + status);

                if (status.startsWith("disconnected")) {
                    changeButtons(false);
                    mLiberacaoContinuaAtiva = false;
                    View contextView = findViewById(R.id.mainCalibrar);
                    if (contextView != null) {
                        Snackbar.make(contextView, "TAP Desconectada", Snackbar.LENGTH_SHORT)
                                .setAction("Conectar", v -> {
                                    if (mBluetoothService != null && mDeviceMac != null) mBluetoothService.connectWithMac(mDeviceMac);
                                }).show();
                    }
                } else if (status.equals("connected") || status.equals("ready")) {
                    changeButtons(true);
                    // Solicita valor atual de pulsos ao conectar
                    if (mBluetoothService != null) mBluetoothService.write("$PL:0");
                }

            } else if (BluetoothServiceIndustrial.BLE_DATA_ACTION.equals(action)) {
                String receivedData = intent.getStringExtra("data");
                if (receivedData == null) return;
                Log.d(TAG, "Dado BLE recebido: " + receivedData);

                // Atualiza quantidade de pulsos atual (resposta PL:<valor>)
                if (receivedData.startsWith("PL:")) {
                    qtdAtual.setText(receivedData.replace("\n", "").trim());
                }
                // Atualiza volume liberado (VP:<valor>)
                if (receivedData.startsWith("VP:")) {
                    try {
                        String vp = receivedData.substring(3).trim();
                        Double mlsFloat = Double.valueOf(vp);
                        int mls = (int) Math.round(mlsFloat);
                        txtVolumeLiberado.setText(mls + "ML");
                    } catch (Exception e) {
                        Log.e(TAG, "Erro parse VP: " + e.getMessage());
                    }
                }
                // Encerramento de liberação (ML:<valor> = conclusão)
                if (receivedData.startsWith("ML:")) {
                    Log.d(TAG, "Liberacao concluida: " + receivedData);
                    mLiberacaoContinuaAtiva = false;
                    runOnUiThread(() -> btnLiberacaoContinua.setText("Liberacao continua"));
                }
                // OK — comando aceito
                if ("OK".equalsIgnoreCase(receivedData.trim())) {
                    Log.d(TAG, "Comando aceito pelo ESP32");
                }
                // ERRO — comando com erro
                if ("ERRO".equalsIgnoreCase(receivedData.trim())) {
                    Log.e(TAG, "ESP32 reportou ERRO no comando");
                    runOnUiThread(() ->
                        Toast.makeText(CalibrarPulsos.this, "Erro no comando BLE", Toast.LENGTH_SHORT).show()
                    );
                }

            } else if (BluetoothServiceIndustrial.BLE_DATA_ACTION.equals(action)) {
                String device = intent.getStringExtra("device");
                if (device != null) {
                    Log.d(TAG, "Dispositivo em alcance: " + device);
                }
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // ServiceConnection: vincula ao BluetoothService
    // ─────────────────────────────────────────────────────────────────────────
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothServiceIndustrial.LocalBinder binder = (BluetoothServiceIndustrial.LocalBinder) service;
            mBluetoothService = binder.getService();
            mIsServiceBound = true;
            String status = mBluetoothService.getCurrentStatus();
            Log.d(TAG, "BluetoothService vinculado. Status: " + status);

            if (status.equals("ready") || status.equals("connected")) {
                changeButtons(true);
                // Protocolo NUS v4.0: $PL:0 consulta pulsos/litro atuais
                mBluetoothService.sendCommand("$PL:0");
            } else {
                changeButtons(false);
                if (mDeviceMac != null) mBluetoothService.connectWithMac(mDeviceMac);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "BluetoothService desvinculado");
            mIsServiceBound = false;
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate iniciado");

        EdgeToEdge.enable(this);
        setContentView(R.layout.calibrar_pulsos);
        setupFullscreen();

        // Bind de views
        qtdAtual           = findViewById(R.id.txtTimeoutAtual);
        txtVolumeLiberado  = findViewById(R.id.txtVolumeLiberado);
        main               = findViewById(R.id.mainCalibrar);
        btnPulsos          = findViewById(R.id.btnChangePulsos);
        btnLiberar         = findViewById(R.id.btnSalvarTimeout);
        btnTimeout         = findViewById(R.id.btnTimeout);
        btnLiberacaoContinua = findViewById(R.id.btnLiberacaoContinua);
        Button btnConfig   = findViewById(R.id.btnConfig);
        Button btnVoltar   = findViewById(R.id.btnVoltar);
        EditText novaQtd   = findViewById(R.id.edtNovoTimeout);

        // Garante que a tela está visível
        if (main != null) main.setVisibility(View.VISIBLE);

        // Desabilita botões até BLE conectar
        changeButtons(false);

        // Vincula ao BluetoothService
        Intent serviceIntent = new Intent(this, BluetoothServiceIndustrial.class);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        // ── Listeners ──────────────────────────────────────────────────────

        btnConfig.setOnClickListener(v -> abrirConfiguracoesPrincipais());

        // Calibrar: calcula novos pulsos por litro e envia ao ESP32
        btnPulsos.setOnClickListener(v -> {
            try {
                String input = novaQtd.getText().toString().trim();
                if (input.isEmpty()) {
                    Toast.makeText(this, "Informe o volume aferido", Toast.LENGTH_SHORT).show();
                    return;
                }
                int volumeAferido = Integer.parseInt(input);
                if (volumeAferido <= 0) {
                    Toast.makeText(this, "Volume deve ser maior que zero", Toast.LENGTH_SHORT).show();
                    return;
                }
                String pulsoStr = qtdAtual.getText().toString().replace("PL:", "").trim();
                if (pulsoStr.isEmpty()) {
                    Toast.makeText(this, "Aguardando leitura de pulsos do ESP32", Toast.LENGTH_SHORT).show();
                    return;
                }
                int pulsosAtual = Integer.parseInt(pulsoStr);
                int qtd = (pulsosAtual * 100) / volumeAferido;
                Log.d(TAG, "Calibrando: pulsosAtual=" + pulsosAtual + " volumeAferido=" + volumeAferido + " novosPL=" + qtd);
                // Protocolo NUS v4.0: $PL:<novos_pulsos>
                if (mBluetoothService != null) mBluetoothService.sendCommand("$PL:" + qtd);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Valor invalido", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Erro ao calibrar: " + e.getMessage());
                Toast.makeText(this, "Erro ao calcular pulsos", Toast.LENGTH_SHORT).show();
            }
        });

        // Liberar líquido: envia $ML:100 (libera 100ml para teste)
        btnLiberar.setOnClickListener(v -> {
            Log.d(TAG, "Enviando $ML:100 para teste de fluxo");
            if (mBluetoothService != null) mBluetoothService.sendCommand("$ML:100");
        });

        // Liberação contínua: alterna entre $LB: (iniciar) e $ML:0 (parar)
        btnLiberacaoContinua.setOnClickListener(v -> {
            if (mBluetoothService == null) return;
            if (!mLiberacaoContinuaAtiva) {
                Log.d(TAG, "Iniciando liberacao continua com $LB:");
                mBluetoothService.sendCommand("$LB:");
                mLiberacaoContinuaAtiva = true;
                btnLiberacaoContinua.setText("Parar liberacao");
            } else {
                Log.d(TAG, "Parando liberacao continua com $ML:0");
                mBluetoothService.sendCommand("$ML:0");
                mLiberacaoContinuaAtiva = false;
                btnLiberacaoContinua.setText("Liberacao continua");
            }
        });

        // Modificar Timeout: navega para ModificarTimeout
        btnTimeout.setOnClickListener(v ->
                startActivity(new Intent(CalibrarPulsos.this, ModificarTimeout.class)));

        // Voltar para Home
        btnVoltar.setOnClickListener(v -> {
            Log.d(TAG, "Voltando para Home");
            Intent intent = new Intent(CalibrarPulsos.this, Home.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        Log.d(TAG, "onCreate concluido — layout visivel");
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

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private void setupFullscreen() {
        WindowInsetsControllerCompat wic =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        wic.hide(WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    public void abrirConfiguracoesPrincipais() {
        startActivity(new Intent(Settings.ACTION_SETTINGS));
    }

    /**
     * Habilita ou desabilita os botões de ação conforme o estado da conexão BLE.
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
}
