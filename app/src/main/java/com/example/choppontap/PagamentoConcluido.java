package com.example.choppontap;

import android.content.BroadcastReceiver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * PagamentoConcluido â Tela de liberaÃ§Ã£o do chopp apÃ³s pagamento confirmado.
 *
 * Protocolo NUS v4.0:
 *   Envio:    $ML:<volume_ml>
 *   Respostas: OK, VP:<parcial>, QP:<pulsos>, ML:<final>
 *
 * NÃO usa mais: READY/READY_OK, SERVE, ACK|, DONE|, PING, HMAC, SESSION_ID, CMD_ID
 */
public class PagamentoConcluido extends AppCompatActivity {

    private static final String TAG = "PAGAMENTO_CONCLUIDO";

    // ââ Timeouts e delays âââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private static final long ML_SEND_DELAY_MS       = 800L;
    private static final long HOME_NAVIGATE_DELAY_MS = 3_000L;
    private static final long WATCHDOG_TIMEOUT_MS    = 30_000L;

    // ââ Handlers ââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private final Handler mMainHandler     = new Handler(Looper.getMainLooper());
    private final Handler mWatchdogHandler = new Handler(Looper.getMainLooper());

    // ââ Estado da liberaÃ§Ã£o âââââââââââââââââââââââââââââââââââââââââââââââââââ
    private int     qtd_ml               = 0;
    private int     liberado             = 0;
    private boolean mLiberacaoFinalizada = false;
    private boolean mComandoEnviado      = false;

    // ââ Dados do pedido âââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private String checkout_id;
    private String android_id;
    private String imagemUrl;

    // ââ Views âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private TextView    txtQtd;
    private TextView    txtMls;
    private TextView    txtStatus;
    private Button      btnLiberar;
    private ImageView   imageView;
    private ProgressBar progressBar;

    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private Future<?> currentImageTask = null;

    private BluetoothServiceIndustrial mBluetoothService;
    private boolean          mIsServiceBound = false;
    private SessionManager   mSessionManager;

    // ââ Watchdog ââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private boolean mWatchdogActive = false;

    private final Runnable mWatchdogRunnable = () -> {
        Log.e(TAG, "[APP] WATCHDOG disparado!");
        mWatchdogActive  = false;
        atualizarStatus("Timeout: fluxo nao detectado.");
        runOnUiThread(() -> {
            if (liberado < qtd_ml) {
                int restante = qtd_ml - liberado;
                btnLiberar.setText("Tentar novamente (" + restante + "ml)");
                btnLiberar.setVisibility(View.VISIBLE);
                mLiberacaoFinalizada = false;
            }
            mostrarSnackbar("Tempo esgotado.");
        });
    };

    // ââ Auto-Retry â retomada automática após interrupção âââââââââââââââââââââââ
    private Runnable mAutoRetryRunnable = null;

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // BroadcastReceiver â escuta eventos do BluetoothServiceIndustrial
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ

    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            if (BluetoothServiceIndustrial.BLE_STATUS_ACTION.equals(action)) {
                String status = intent.getStringExtra("status");
                if (status != null && status.startsWith("disconnected")) {
                    atualizarStatus("Reconectando...");
                    cancelarWatchdog();
                } else if ("connected".equals(status)) {
                    atualizarStatus("Conectado. Aguardando BLE pronto...");
                } else if ("ready".equals(status)) {
                    atualizarStatus("Dispositivo pronto.");
                    // Dispara envio do comando ao ficar pronto
                    mMainHandler.postDelayed(() -> {
                        if (!mLiberacaoFinalizada && liberado == 0) {
                            mComandoEnviado = false;
                            iniciarVendaEEnfileirar();
                        }
                    }, ML_SEND_DELAY_MS);
                }
            } else if (BluetoothServiceIndustrial.BLE_DATA_ACTION.equals(action)) {
                String data = intent.getStringExtra("data");
                if (data != null) {
                    processarMensagem(data.trim());
                }
            }
        }
    };

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Processamento de mensagens do ESP32 (protocolo NUS v4.0)
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ

    private void processarMensagem(String msg) {
        Log.d(TAG, "[ESP32] " + msg);

        // ââ OK â comando aceito pelo ESP32 âââââââââââââââââââââââââââââââââââ
        if ("OK".equalsIgnoreCase(msg)) {
            Log.i(TAG, "[BLE] Comando $ML aceito pelo ESP32");
            atualizarStatus("Comando aceito. Liberando chopp...");
            iniciarWatchdog();
            return;
        }

        // ââ ERRO â comando com erro ââââââââââââââââââââââââââââââââââââââââââ
        if ("ERRO".equalsIgnoreCase(msg)) {
            Log.e(TAG, "[BLE] ESP32 reportou ERRO no comando");
            atualizarStatus("Erro no comando. Tentando novamente...");
            mComandoEnviado = false;
            return;
        }

        // ââ VP: â volume parcial durante liberaÃ§Ã£o âââââââââââââââââââââââââââ
        if (msg.startsWith("VP:")) {
            resetarWatchdog();
            try {
                double mlFloat = Double.parseDouble(msg.substring(3).trim());
                liberado = (int) Math.round(mlFloat);
                runOnUiThread(() -> {
                    txtMls.setText(liberado + " ML");
                    if (progressBar != null && qtd_ml > 0) {
                        progressBar.setProgress((int) ((liberado / (float) qtd_ml) * 100));
                    }
                });
            } catch (Exception ignored) {}
            return;
        }

        // ââ QP: â quantidade de pulsos ao final ââââââââââââââââââââââââââââââ
        if (msg.startsWith("QP:")) {
            Log.i(TAG, "[BLE] Pulsos reportados: " + msg);
            return;
        }

        // ââ ML: â volume final liberado (CONCLUSÃO da liberaÃ§Ã£o) âââââââââââââ
        if (msg.startsWith("ML:")) {
            cancelarWatchdog();
            try {
                double mlFinal = Double.parseDouble(msg.substring(3).trim());
                liberado = (int) Math.round(mlFinal);
            } catch (Exception ignored) {}

            mComandoEnviado = false;
            Log.i(TAG, "[BLE] Liberacao encerrada: " + liberado + "mL de " + qtd_ml + "mL");

            runOnUiThread(() -> {
                txtMls.setText(liberado + " ML");
                if (progressBar != null && qtd_ml > 0) {
                    progressBar.setProgress((int) ((liberado / (float) qtd_ml) * 100));
                }

                if (liberado >= qtd_ml) {
                    // Fluxo completo
                    mLiberacaoFinalizada = true;
                    btnLiberar.setVisibility(View.GONE);
                    chamarFinishSale(liberado);
                    atualizarStatus("Dosagem completa! " + liberado + "mL servidos.");
                    mMainHandler.postDelayed(() -> {
                        startActivity(new Intent(PagamentoConcluido.this, Home.class));
                        finish();
                    }, HOME_NAVIGATE_DELAY_MS);
                } else {
                    // Fluxo interrompido por inatividade (cliente soltou a alavanca)
                    int restante = qtd_ml - liberado;
                    atualizarStatus("Aguardando... Faltam " + restante + "mL.");

                    // Retomada automática após 2 segundos
                    btnLiberar.setText("Continuar Servindo (" + restante + "mL)");
                    btnLiberar.setVisibility(View.VISIBLE);
                    mLiberacaoFinalizada = false;

                    // Auto-retry após 2s se o cliente não interagir
                    mAutoRetryRunnable = () -> {
                        if (!mLiberacaoFinalizada && liberado < qtd_ml) {
                            Log.i(TAG, "[AUTO-RETRY] Retomando automaticamente | restante=" + restante + "mL");
                            btnLiberar.setVisibility(View.GONE);
                            atualizarStatus("Retomando dispensação...");
                            mComandoEnviado = false;
                            enviarComandoML(restante);
                        }
                    };
                    mMainHandler.postDelayed(mAutoRetryRunnable, 2000);
                }
            });
            return;
        }

        // ââ PL: â resposta de pulsos/litro (nÃ£o esperado aqui) âââââââââââââââ
        if (msg.startsWith("PL:")) {
            Log.d(TAG, "[BLE] Pulsos/litro: " + msg);
            return;
        }

        // ââ Mensagem nÃ£o reconhecida ââââââââââââââââââââââââââââââââââââââââââ
        Log.d(TAG, "[BLE] Mensagem nÃ£o tratada: " + msg);
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Envio de comando $ML:<volume> ao ESP32
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ

    /**
     * Envia comando $TO:10 seguido de $ML:<volume> ao ESP32.
     * Protocolo NUS v4.0: timeout de 10s, depois liberação.
     */
    private void enviarComandoML(int volumeMl) {
        if (mBluetoothService == null) {
            Log.e(TAG, "[BLE] BluetoothService nulo — nao foi possivel enviar $ML");
            atualizarStatus("Aguardando conexao BLE...");
            return;
        }
        
        String status = mBluetoothService.getCurrentStatus();
        Log.i(TAG, "[BLE] Preparando comando $ML:" + volumeMl + " | BLE status=" + status);
        
        if (!status.equals("ready")) {
            Log.w(TAG, "[BLE] BLE nao READY (status=" + status + ") — aguardando ready");
            mComandoEnviado = false;
            atualizarStatus("Aguardando conexao BLE...");
            return;
        }
        
        mComandoEnviado = true;
        atualizarStatus("Configurando timeout do sensor...");
        
        // 1. Envia comando de Timeout (10 segundos)
        boolean toOk = mBluetoothService.sendCommand("$TO:10");
        if (toOk) {
            Log.i(TAG, "[BLE] Timeout configurado para 10s de inatividade");
        } else {
            Log.e(TAG, "[BLE] Falha ao enviar $TO:10");
        }
        
        // 2. Aguarda 300ms e envia o comando de liberação ($ML)
        mMainHandler.postDelayed(() -> {
            String command = "$ML:" + volumeMl;
            Log.i(TAG, "[BLE] Enviando: " + command);
            boolean mlOk = mBluetoothService.sendCommand(command);
            if (mlOk) {
                atualizarStatus("Enviando comando de liberacao...");
            } else {
                Log.e(TAG, "[BLE] Falha ao enviar $ML");
                mComandoEnviado = false;
                atualizarStatus("Erro ao enviar comando. Tentando novamente...");
            }
        }, 300);
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Fluxo de venda
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ

    private void iniciarVendaEEnfileirar() {
        if (!isInternetAvailable()) {
            Log.e(TAG, "[NET] Sem internet â venda bloqueada, comando BLE NAO enviado");
            atualizarStatus("Sem internet. Verifique sua rede.");
            runOnUiThread(() ->
                Toast.makeText(PagamentoConcluido.this,
                        "Sem conexao com a internet. Verifique sua rede.",
                        Toast.LENGTH_LONG).show()
            );
            return;
        }
        if (mComandoEnviado) {
            Log.w(TAG, "[PAYMENT] iniciarVendaEEnfileirar() BLOQUEADO â mComandoEnviado=true");
            return;
        }

        Log.i(TAG, "[PAYMENT] Iniciando venda v4.0 NUS â checkout_id=" + checkout_id
                + " | qtd_ml=" + qtd_ml);

        // Usa SessionManager se disponÃ­vel
        if (mSessionManager != null) {
            Log.i(TAG, "[SESSION] Iniciando sessao via SessionManager");
            mSessionManager.startSession(checkout_id, qtd_ml, android_id);
        } else {
            // Fallback: fluxo legado com start_sale.php
            Log.w(TAG, "[PAYMENT] SessionManager nao disponivel â usando fluxo legado");
            chamarStartSale(checkout_id, qtd_ml, android_id, () -> enviarComandoML(qtd_ml));
        }
    }

    private boolean isInternetAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        return ni != null && ni.isConnected();
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Service Connection
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBluetoothService = ((BluetoothServiceIndustrial.LocalBinder) service).getService();
            mIsServiceBound   = true;

            // Inicializa SessionManager
            if (mSessionManager == null) {
                mSessionManager = new SessionManager(PagamentoConcluido.this, new SessionManager.Callback() {
                    @Override
                    public void onSessionStarted(String sessionId, String checkoutId) {
                        Log.i(TAG, "[SESSION] Sessao iniciada | session_id=" + sessionId);
                        // Protocolo NUS v4.0: envia $ML diretamente quando a sessão é iniciada
                        enviarComandoML(qtd_ml);
                    }
                    @Override
                    public void onSessionFinished(String sessionId, int mlReal) {
                        Log.i(TAG, "[SESSION] Sessao finalizada | session_id=" + sessionId + " | ml_real=" + mlReal);
                    }
                    @Override
                    public void onSessionFailed(String sessionId, String reason) {
                        Log.e(TAG, "[SESSION] Sessao falhou | session_id=" + sessionId + " | motivo=" + reason);
                    }
                });
            }

            String status = mBluetoothService.getCurrentStatus();
            Log.i(TAG, "[PAYMENT] onServiceConnected | BLE status=" + status);
            mMainHandler.postDelayed(() -> iniciarVendaEEnfileirar(), ML_SEND_DELAY_MS);
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mIsServiceBound = false;
        }
    };

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Lifecycle
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pagamento_concluido);
        setupFullscreen();
        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        Bundle extras = getIntent().getExtras();
        if (extras == null) { finish(); return; }
        qtd_ml = Integer.parseInt(extras.get("qtd_ml").toString());
        checkout_id = extras.get("checkout_id").toString();
        imagemUrl = extras.getString("imagem_url");

        btnLiberar  = findViewById(R.id.btnLiberarRestante);
        imageView   = findViewById(R.id.imageBeer2);
        txtQtd      = findViewById(R.id.txtQtdPulsos);
        txtMls      = findViewById(R.id.txtMls);
        txtStatus   = findViewById(R.id.txtStatusLiberacao);
        progressBar = findViewById(R.id.progressLiberacao);

        btnLiberar.setVisibility(View.GONE);
        txtQtd.setText(qtd_ml + " ML");
        carregarImagemComFallback();

        // BotÃ£o "Continuar Servindo" â recuperaÃ§Ã£o apÃ³s timeout
        btnLiberar.setOnClickListener(v -> {
            // Cancela auto-retry pendente para evitar envio duplo
            if (mAutoRetryRunnable != null) {
                mMainHandler.removeCallbacks(mAutoRetryRunnable);
                mAutoRetryRunnable = null;
            }

            int restante = qtd_ml - liberado;
            if (restante > 0 && !mLiberacaoFinalizada) {
                btnLiberar.setVisibility(View.GONE);
                atualizarStatus("Retomando dispensação manualmente...");
                mComandoEnviado = false;
                Log.i(TAG, "[MANUAL] Retomando por botão | restante=" + restante + "mL");
                enviarComandoML(restante);
            }
        });

        bindService(new Intent(this, BluetoothServiceIndustrial.class), mServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothServiceIndustrial.BLE_STATUS_ACTION);
        filter.addAction(BluetoothServiceIndustrial.BLE_DATA_ACTION);
        registerReceiver(mServiceUpdateReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mServiceUpdateReceiver);
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelarWatchdog();
        mMainHandler.removeCallbacksAndMessages(null);
        if (currentImageTask != null) currentImageTask.cancel(true);
        imageExecutor.shutdown();
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // API calls
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ

    private void chamarStartSale(String checkoutId, int volumeMl, String deviceId, Runnable onSuccess) {
        Map<String, String> body = new HashMap<>();
        body.put("checkout_id", checkoutId);
        body.put("volume_ml", String.valueOf(volumeMl));
        new ApiHelper(this).sendPost(body, "start_sale.php", new Callback() {
            @Override public void onFailure(Call call, IOException e) { mMainHandler.post(onSuccess); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                response.close();
                mMainHandler.post(onSuccess);
            }
        });
    }

    private void chamarFinishSale(int mlDispensado) {
        Map<String, String> body = new HashMap<>();
        body.put("checkout_id", checkout_id);
        body.put("ml_dispensado", String.valueOf(mlDispensado));
        new ApiHelper(this).sendPost(body, "finish_sale.php", new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
        });
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Imagem
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ

    private void carregarImagemComFallback() {
        if (currentImageTask != null && !currentImageTask.isDone()) {
            currentImageTask.cancel(true);
        }

        currentImageTask = imageExecutor.submit(() -> {
            try {
                Sqlite banco = new Sqlite(getApplicationContext());
                byte[] img = banco.getActiveImageData();
                if (img != null && img.length > 0) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(img, 0, img.length);
                    if (bmp != null) {
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed() && imageView != null) {
                                imageView.setImageBitmap(bmp);
                            }
                        });
                        return;
                    }
                }

                if (imagemUrl != null && !imagemUrl.isEmpty()) {
                    Tap tempTap = new Tap();
                    tempTap.image = imagemUrl;
                    Bitmap bmp = new ApiHelper(getApplicationContext()).getImage(tempTap);
                    if (bmp != null) {
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed() && imageView != null) {
                                imageView.setImageBitmap(bmp);
                            }
                        });
                    } else {
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed() && imageView != null) {
                                imageView.setImageResource(android.R.drawable.ic_menu_report_image);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "[IMG] Erro ao carregar imagem: " + e.getMessage());
            }
        });
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Watchdog
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ

    private void iniciarWatchdog() {
        cancelarWatchdog();
        mWatchdogActive = true;
        mWatchdogHandler.postDelayed(mWatchdogRunnable, WATCHDOG_TIMEOUT_MS);
    }

    private void resetarWatchdog() {
        if (mWatchdogActive) {
            mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
            mWatchdogHandler.postDelayed(mWatchdogRunnable, WATCHDOG_TIMEOUT_MS);
        }
    }

    private void cancelarWatchdog() {
        mWatchdogActive = false;
        mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // UI helpers
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ

    private void atualizarStatus(String msg) {
        runOnUiThread(() -> { if (txtStatus != null) txtStatus.setText(msg); });
    }

    private void mostrarSnackbar(String msg) {
        runOnUiThread(() -> {
            View root = findViewById(android.R.id.content);
            if (root != null) Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show();
        });
    }

    private void setupFullscreen() {
        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        wic.hide(WindowInsetsCompat.Type.systemBars());
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }
}
