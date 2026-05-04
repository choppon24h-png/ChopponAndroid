package com.example.choppontap;

import android.content.BroadcastReceiver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import androidx.core.content.ContextCompat;
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
 * PagamentoConcluido — Tela de liberação do chopp após pagamento confirmado.
 *
 * Protocolo NUS v4.0:
 *   Envio:    $TO:<timeout>  →  OK  (configuração — NÃO inicia watchdog)
 *             $ML:<volume>   →  OK  (liberação — inicia watchdog)
 *   Respostas: OK, VP:<parcial>, QP:<pulsos>, ML:<final>
 *
 * CORREÇÕES aplicadas (v4.1):
 *   1. BroadcastReceiver usa BLE_STATUS_ACTION / BLE_DATA_ACTION (não mais ACTION_*)
 *   2. Extração de extras usa chaves "status" / "data" (não mais EXTRA_STATUS / EXTRA_DATA)
 *   3. registerReceiver global (não mais LocalBroadcastManager — serviço usa sendBroadcast)
 *   4. enviarComandoML usa sendCommand() (não mais isReady()/write()/enqueueServeCommand())
 *   5. Watchdog só inicia após OK do $ML, não após OK do $TO
 *   6. VP:0.000 exibe "Aguardando fluxo..." em vez de "0 ML" para melhor UX
 */
public class PagamentoConcluido extends AppCompatActivity {

    private static final String TAG = "PAGAMENTO_CONCLUIDO";

    // ── Timeouts e delays ─────────────────────────────────────────────────────
    private static final long ML_SEND_DELAY_MS       = 800L;
    private static final long HOME_NAVIGATE_DELAY_MS = 3_000L;
    private static final long WATCHDOG_TIMEOUT_MS    = 30_000L;

    // ── Handlers ──────────────────────────────────────────────────────────────
    private final Handler mMainHandler     = new Handler(Looper.getMainLooper());
    private final Handler mWatchdogHandler = new Handler(Looper.getMainLooper());

    // ── Estado da liberação ───────────────────────────────────────────────────
    private int     qtd_ml               = 0;
    private int     liberado             = 0;
    private boolean mLiberacaoFinalizada = false;
    private boolean mComandoEnviado      = false;

    /**
     * CORREÇÃO 5: rastreia o último comando enviado para distinguir
     * OK do $TO (configuração) de OK do $ML (liberação).
     * O watchdog só deve iniciar quando o OK for resposta ao $ML.
     */
    private String  mUltimoComandoEnviado = "";

    // ── Dados do pedido ───────────────────────────────────────────────────────
    private String checkout_id;
    private String android_id;
    private String imagemUrl;

    // ── Views ─────────────────────────────────────────────────────────────────
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

    // ── Watchdog ──────────────────────────────────────────────────────────────
    private boolean  mWatchdogActive    = false;
    private Runnable mAutoRetryRunnable = null;

    private final Runnable mWatchdogRunnable = () -> {
        Log.e(TAG, "[APP] WATCHDOG disparado! liberado=" + liberado + " qtd_ml=" + qtd_ml);
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

    // ─────────────────────────────────────────────────────────────────────────
    // BroadcastReceiver — escuta eventos do BluetoothServiceIndustrial
    //
    // CORREÇÃO 1+2+3: usa BLE_STATUS_ACTION / BLE_DATA_ACTION com chaves
    // "status" / "data" e registerReceiver global (não LocalBroadcastManager).
    // ─────────────────────────────────────────────────────────────────────────

    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            if (BluetoothServiceIndustrial.BLE_STATUS_ACTION.equals(action)) {
                // Trata eventos de status de conexão BLE
                String status = intent.getStringExtra("status");
                Log.d(TAG, "[BLE] BLE_STATUS_ACTION status=" + status);
                if (status == null) return;

                if (status.startsWith("disconnected")) {
                    atualizarStatus("Reconectando...");
                    cancelarWatchdog();
                } else if (BluetoothServiceIndustrial.STATUS_CONNECTED.equals(status)) {
                    atualizarStatus("Conectado. Aguardando BLE pronto...");
                } else if (BluetoothServiceIndustrial.STATUS_READY.equals(status)) {
                    // BLE ficou pronto — inicia venda se ainda não enviou
                    Log.i(TAG, "[BLE] STATUS_READY recebido — BLE pronto para envio");
                    atualizarStatus("Dispositivo pronto. Liberando...");
                    mMainHandler.postDelayed(() -> {
                        if (!mLiberacaoFinalizada && liberado == 0 && !mComandoEnviado) {
                            mComandoEnviado = false;
                            iniciarVendaEEnfileirar();
                        }
                    }, ML_SEND_DELAY_MS);
                }
                return;
            }

            if (BluetoothServiceIndustrial.BLE_DATA_ACTION.equals(action)) {
                // Trata dados recebidos do ESP32
                String data = intent.getStringExtra("data");
                if (data != null) processarMensagem(data.trim());
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Processamento de mensagens do ESP32 (protocolo NUS v4.0)
    // ─────────────────────────────────────────────────────────────────────────

    private void processarMensagem(String msg) {
        Log.d(TAG, "[ESP32] " + msg);

        // ── OK — comando aceito pelo ESP32 ────────────────────────────────────
        if ("OK".equalsIgnoreCase(msg)) {
            // CORREÇÃO 5: só inicia watchdog se o OK for resposta ao $ML.
            // O $TO:N também retorna OK mas não deve iniciar o watchdog,
            // pois o $ML ainda não foi enviado nesse momento.
            if (mUltimoComandoEnviado.startsWith("$ML:")) {
                Log.i(TAG, "[BLE] OK do $ML recebido — iniciando watchdog");
                atualizarStatus("Comando aceito. Liberando chopp...");
                iniciarWatchdog();
            } else {
                Log.i(TAG, "[BLE] OK do comando '" + mUltimoComandoEnviado
                        + "' recebido — watchdog NÃO iniciado (não é $ML)");
            }
            return;
        }

        // ── ERRO — comando com erro ───────────────────────────────────────────
        if ("ERRO".equalsIgnoreCase(msg)) {
            Log.e(TAG, "[BLE] ESP32 reportou ERRO no comando '" + mUltimoComandoEnviado + "'");
            atualizarStatus("Erro no comando. Tentando novamente...");
            mComandoEnviado = false;
            return;
        }

        // ── VP: — volume parcial durante liberação ────────────────────────────
        if (msg.startsWith("VP:")) {
            resetarWatchdog();
            try {
                double mlFloat = Double.parseDouble(msg.substring(3).trim());
                int mlArredondado = (int) Math.round(mlFloat);

                // CORREÇÃO 6: enquanto VP:0.000 (fluxo ainda não detectado),
                // exibe mensagem de aguardo em vez de "0 ML" para melhor UX.
                if (mlArredondado == 0 && mlFloat < 0.5) {
                    Log.d(TAG, "[VP] VP=0 — aguardando início do fluxo");
                    atualizarStatus("Aguardando fluxo... Puxe a alavanca.");
                } else {
                    liberado = mlArredondado;
                    final int mlExibir = liberado;
                    runOnUiThread(() -> {
                        txtMls.setText(mlExibir + " ML");
                        if (progressBar != null && qtd_ml > 0) {
                            progressBar.setProgress((int) ((mlExibir / (float) qtd_ml) * 100));
                        }
                        atualizarStatus("Liberando... " + mlExibir + " / " + qtd_ml + " ML");
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "[VP] Erro ao parsear VP: " + msg + " | " + e.getMessage());
            }
            return;
        }

        // ── QP: — quantidade de pulsos ao final ───────────────────────────────
        if (msg.startsWith("QP:")) {
            Log.i(TAG, "[BLE] Pulsos reportados: " + msg);
            return;
        }

        // ── ML: — volume final liberado (CONCLUSÃO da liberação) ──────────────
        if (msg.startsWith("ML:")) {
            cancelarWatchdog();
            try {
                double mlFinal = Double.parseDouble(msg.substring(3).trim());
                liberado = (int) Math.round(mlFinal);
            } catch (Exception ignored) {}

            mLiberacaoFinalizada = true;
            mComandoEnviado      = false;
            mUltimoComandoEnviado = "";

            Log.i(TAG, "[BLE] Liberacao encerrada: " + liberado + "mL de " + qtd_ml + "mL");

            // Verifica se liberou menos do solicitado — exibe botão "Continuar Servindo"
            if (liberado < qtd_ml) {
                int restante = qtd_ml - liberado;
                Log.w(TAG, "[BLE] Volume parcial: liberado=" + liberado + " < solicitado=" + qtd_ml
                        + " | restante=" + restante + "ml");
                chamarFinishSale(liberado);
                runOnUiThread(() -> {
                    txtMls.setText(liberado + " ML");
                    if (progressBar != null && qtd_ml > 0) {
                        progressBar.setProgress((int) ((liberado / (float) qtd_ml) * 100));
                    }
                    atualizarStatus("Fluxo interrompido. " + liberado + "/" + qtd_ml + " ML");
                    btnLiberar.setText("Continuar servindo (" + restante + "ml)");
                    btnLiberar.setVisibility(View.VISIBLE);
                    mLiberacaoFinalizada = false; // permite reenvio
                });
            } else {
                // Liberação completa — navega para Home
                chamarFinishSale(liberado);
                runOnUiThread(() -> {
                    txtMls.setText(liberado + " ML");
                    if (progressBar != null) progressBar.setProgress(100);
                    atualizarStatus("Dosagem completa!");
                    mMainHandler.postDelayed(() -> {
                        startActivity(new Intent(PagamentoConcluido.this, Home.class));
                        finish();
                    }, HOME_NAVIGATE_DELAY_MS);
                });
            }
            return;
        }

        // ── PL: — resposta de pulsos/litro (não esperado aqui) ────────────────
        if (msg.startsWith("PL:")) {
            Log.d(TAG, "[BLE] Pulsos/litro: " + msg);
            return;
        }

        // ── Mensagem não reconhecida ──────────────────────────────────────────
        Log.d(TAG, "[BLE] Mensagem nao tratada: " + msg);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Envio de comando $ML:<volume> ao ESP32
    //
    // CORREÇÃO 4: usa sendCommand() em vez de isReady()/write()/enqueueServeCommand().
    // Registra mUltimoComandoEnviado para que o tratamento do OK seja preciso.
    // ─────────────────────────────────────────────────────────────────────────

    private void enviarComandoML(int volumeMl) {
        if (mBluetoothService == null) {
            Log.e(TAG, "[BLE] BluetoothService nulo — nao foi possivel enviar $ML");
            atualizarStatus("Aguardando conexao BLE...");
            return;
        }
        String command = "$ML:" + volumeMl;
        Log.i(TAG, "[BLE] Enviando: " + command + " | BLE status=" + mBluetoothService.getCurrentStatus());
        mUltimoComandoEnviado = command;
        mComandoEnviado = true;
        boolean ok = mBluetoothService.sendCommand(command);
        if (ok) {
            atualizarStatus("Enviando comando de liberacao...");
            Log.i(TAG, "[BLE] sendCommand() OK — $ML enfileirado");
        } else {
            Log.e(TAG, "[BLE] sendCommand() falhou para: " + command);
            mComandoEnviado = false;
            mUltimoComandoEnviado = "";
            atualizarStatus("Falha ao enviar comando. BLE desconectado?");
        }
    }

    /**
     * Envia $TO:<timeout_ms> antes do $ML para configurar o timeout do sensor no ESP32.
     *
     * ATENÇÃO — unidade do firmware: o operacional.cpp multiplica o valor recebido
     * por 1000 (microsegundos via esp_timer_get_time), portanto a unidade esperada
     * pelo ESP32 é MILISSEGUNDOS.
     *   $TO:10    = 10ms  ← ERRADO (válvula fecha em <1s)
     *   $TO:10000 = 10s   ← CORRETO
     *
     * Este método recebe o timeout em SEGUNDOS e converte para ms antes de enviar.
     *
     * CORREÇÃO 5: registra mUltimoComandoEnviado = "$TO:..." para que o OK
     * resultante NÃO dispare o watchdog.
     */
    private void enviarTimeoutESP32(int timeoutSegundos, Runnable onOk) {
        if (mBluetoothService == null) {
            if (onOk != null) onOk.run();
            return;
        }
        // Converte segundos → milissegundos (unidade esperada pelo firmware ESP32)
        int timeoutMs = timeoutSegundos * 1000;
        String cmd = "$TO:" + timeoutMs;
        Log.i(TAG, "[BLE] Configurando timeout ESP32: " + cmd
                + " (" + timeoutSegundos + "s = " + timeoutMs + "ms)");
        mUltimoComandoEnviado = cmd;
        boolean ok = mBluetoothService.sendCommand(cmd);
        if (ok) {
            Log.i(TAG, "[BLE] Timeout configurado para " + timeoutSegundos
                    + "s (" + timeoutMs + "ms) de inatividade");
            // Aguarda 400ms para o ESP32 processar o $TO antes de enviar o $ML
            mMainHandler.postDelayed(() -> {
                if (onOk != null) onOk.run();
            }, 400L);
        } else {
            Log.w(TAG, "[BLE] Falha ao enviar $TO — prosseguindo com $ML mesmo assim");
            if (onOk != null) onOk.run();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fluxo de venda
    // ─────────────────────────────────────────────────────────────────────────

    private void iniciarVendaEEnfileirar() {
        if (!isInternetAvailable()) {
            Log.e(TAG, "[NET] Sem internet — venda bloqueada, comando BLE NAO enviado");
            atualizarStatus("Sem internet. Verifique sua rede.");
            runOnUiThread(() ->
                Toast.makeText(PagamentoConcluido.this,
                        "Sem conexao com a internet. Verifique sua rede.",
                        Toast.LENGTH_LONG).show()
            );
            return;
        }
        if (mComandoEnviado) {
            Log.w(TAG, "[PAYMENT] iniciarVendaEEnfileirar() BLOQUEADO — mComandoEnviado=true");
            return;
        }

        Log.i(TAG, "[PAYMENT] Iniciando venda v4.0 NUS — checkout_id=" + checkout_id
                + " | qtd_ml=" + qtd_ml);

        // Usa SessionManager se disponível
        if (mSessionManager != null) {
            Log.i(TAG, "[SESSION] Iniciando sessao via SessionManager");
            mSessionManager.startSession(checkout_id, qtd_ml, android_id);
        } else {
            // Fallback: fluxo legado com start_sale.php
            Log.w(TAG, "[PAYMENT] SessionManager nao disponivel — usando fluxo legado");
            chamarStartSale(checkout_id, qtd_ml, android_id, () -> enviarTimeoutESP32(10, () -> enviarComandoML(qtd_ml)));
        }
    }

    private boolean isInternetAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        return ni != null && ni.isConnected();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service Connection
    // ─────────────────────────────────────────────────────────────────────────

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
                        // Protocolo NUS v4.0: envia $TO:10 primeiro, depois $ML
                        // CORREÇÃO 5: mUltimoComandoEnviado será "$TO:10" durante o OK do $TO,
                        // evitando que o watchdog inicie prematuramente.
                        enviarTimeoutESP32(10, () -> enviarComandoML(qtd_ml));
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

            Log.i(TAG, "[PAYMENT] onServiceConnected | BLE status=" + mBluetoothService.getCurrentStatus());
            mMainHandler.postDelayed(() -> iniciarVendaEEnfileirar(), ML_SEND_DELAY_MS);
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            mIsServiceBound = false;
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

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

        // Botão "Continuar Servindo" — recuperação após timeout ou fluxo parcial
        btnLiberar.setOnClickListener(v -> {
            // Cancela auto-retry pendente para evitar envio duplo
            if (mAutoRetryRunnable != null) {
                mMainHandler.removeCallbacks(mAutoRetryRunnable);
                mAutoRetryRunnable = null;
            }

            int restante = qtd_ml - liberado;
            if (restante > 0) {
                Log.i(TAG, "[RETRY] Retomando dispensacao | liberado=" + liberado
                        + " | restante=" + restante + "ml");
                // Reenvia $TO:10 antes do $ML para garantir timeout correto no ESP32
                enviarTimeoutESP32(10, () -> enviarComandoML(restante));
            }
        });

        bindService(new Intent(this, BluetoothServiceIndustrial.class), mServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // CORREÇÃO 3: usa registerReceiver global em vez de LocalBroadcastManager,
        // pois BluetoothServiceIndustrial usa sendBroadcast() (não LocalBroadcastManager).
        //
        // CORREÇÃO 7 (Android 13+ / API 33+): registerReceiver exige a flag
        // RECEIVER_NOT_EXPORTED quando o receiver não é exclusivo de broadcasts do sistema.
        // Sem essa flag, o Android 13+ lança SecurityException e mata a Activity.
        // O receiver é NOT_EXPORTED pois só recebe broadcasts internos do próprio app.
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothServiceIndustrial.BLE_STATUS_ACTION);
        filter.addAction(BluetoothServiceIndustrial.BLE_DATA_ACTION);
        ContextCompat.registerReceiver(
                this,
                mServiceUpdateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        // CORREÇÃO 3: unregister do receiver global
        try {
            unregisterReceiver(mServiceUpdateReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "[BLE] Receiver ja desregistrado: " + e.getMessage());
        }
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

    // ─────────────────────────────────────────────────────────────────────────
    // API calls
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Imagem
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Watchdog
    // ─────────────────────────────────────────────────────────────────────────

    private void iniciarWatchdog() {
        cancelarWatchdog();
        mWatchdogActive = true;
        Log.d(TAG, "[WATCHDOG] Iniciado — timeout=" + WATCHDOG_TIMEOUT_MS + "ms");
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

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

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
