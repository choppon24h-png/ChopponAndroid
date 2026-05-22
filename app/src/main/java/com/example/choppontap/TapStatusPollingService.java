package com.example.choppontap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * TapStatusPollingService — Serviço de background que monitora o status da TAP no servidor.
 *
 * RESPONSABILIDADE:
 *   Faz polling periódico ao endpoint /api/tap_status_poll.php e emite
 *   LocalBroadcasts quando o status muda. As Activities (Home, OfflineTap)
 *   recebem o broadcast e executam a ação correspondente:
 *
 *   - TAP DESATIVADA (status=0): Home desconecta BLE e navega para OfflineTap
 *   - TAP ATIVADA   (status=1): OfflineTap reconecta BLE e navega para Home
 *
 * BROADCASTS EMITIDOS:
 *   Action: TapStatusPollingService.ACTION_TAP_STATUS_CHANGED
 *   Extras:
 *     - "status"        (int)    : 0=offline, 1=online
 *     - "status_label"  (String) : "offline" | "online"
 *     - "bebida"        (String) : nome da bebida
 *     - "esp32_mac"     (String) : MAC do ESP32
 *     - "preco"         (float)  : preço base por 100ml
 *     - "image"         (String) : URL da imagem da bebida
 *     - "cartao"        (boolean): cartão habilitado
 *
 * CICLO DE VIDA:
 *   - Iniciado por Home.onCreate() e OfflineTap.onResume()
 *   - Parado por Home.onDestroy() e OfflineTap.onDestroy()
 *   - Roda como Foreground Service (notificação discreta)
 *
 * INTERVALO:
 *   - POLL_INTERVAL_ACTIVE_MS  (15s): quando a TAP está online (Home ativa)
 *   - POLL_INTERVAL_OFFLINE_MS (10s): quando a TAP está offline (aguardando reativação)
 */
public class TapStatusPollingService extends Service {

    private static final String TAG = "TAP_POLL_SVC";

    // ── Broadcast ─────────────────────────────────────────────────────────────
    public static final String ACTION_TAP_STATUS_CHANGED =
            "com.example.choppontap.TAP_STATUS_CHANGED";

    // ── Extras do broadcast ───────────────────────────────────────────────────
    public static final String EXTRA_STATUS       = "status";
    public static final String EXTRA_STATUS_LABEL = "status_label";
    public static final String EXTRA_BEBIDA       = "bebida";
    public static final String EXTRA_ESP32_MAC    = "esp32_mac";
    public static final String EXTRA_PRECO        = "preco";
    public static final String EXTRA_IMAGE        = "image";
    public static final String EXTRA_CARTAO       = "cartao";

    // ── Intent extras para controle do serviço ────────────────────────────────
    /** Informa ao serviço qual é o status atual esperado (0=offline, 1=online) */
    public static final String EXTRA_CURRENT_STATUS = "current_status";

    // ── Intervalos de polling ─────────────────────────────────────────────────
    /** Intervalo quando TAP está ONLINE: verifica se foi desativada */
    private static final long POLL_INTERVAL_ACTIVE_MS  = 15_000L;
    /** Intervalo quando TAP está OFFLINE: verifica se foi reativada */
    private static final long POLL_INTERVAL_OFFLINE_MS = 10_000L;

    // ── Notificação foreground ────────────────────────────────────────────────
    private static final String NOTIF_CHANNEL_ID = "tap_poll_channel";
    private static final int    NOTIF_ID         = 1002;

    // ── Estado interno ────────────────────────────────────────────────────────
    private static final AtomicBoolean sRunning = new AtomicBoolean(false);

    private String  android_id;
    private int     lastKnownStatus = -1; // -1 = desconhecido (primeiro poll)
    private ApiHelper apiHelper;

    private final Handler        mainHandler  = new Handler(Looper.getMainLooper());
    private final Runnable       pollRunnable = this::executePoll;

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida do Service
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        apiHelper  = new ApiHelper(this);
        Log.i(TAG, "TapStatusPollingService criado | android_id=" + android_id);
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Monitorando status da TAP..."));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            int currentStatus = intent.getIntExtra(EXTRA_CURRENT_STATUS, -1);
            if (currentStatus != -1) {
                lastKnownStatus = currentStatus;
                Log.d(TAG, "Status inicial recebido via Intent: " + currentStatus);
            }
        }

        if (!sRunning.getAndSet(true)) {
            Log.i(TAG, "Iniciando loop de polling");
            schedulePoll(0); // Primeiro poll imediato
        } else {
            Log.d(TAG, "Polling já estava rodando — ignorando start duplicado");
        }

        return START_STICKY; // Reinicia automaticamente se o sistema matar o serviço
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sRunning.set(false);
        mainHandler.removeCallbacks(pollRunnable);
        Log.i(TAG, "TapStatusPollingService destruído — polling parado");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Serviço não vinculável
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Polling
    // ─────────────────────────────────────────────────────────────────────────

    private void schedulePoll(long delayMs) {
        if (!sRunning.get()) return;
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.postDelayed(pollRunnable, delayMs);
    }

    private void executePoll() {
        if (!sRunning.get()) return;

        Log.d(TAG, "Executando poll | lastKnownStatus=" + lastKnownStatus);

        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id != null ? android_id : "");

        apiHelper.sendPost(body, "tap_status_poll", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Poll falhou (rede): " + e.getMessage());
                // Reagenda com intervalo padrão mesmo em falha
                schedulePoll(getInterval());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String rb = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "Poll resposta (HTTP " + response.code() + "): " + rb);

                try {
                    JSONObject json = new JSONObject(rb);

                    if (!json.optBoolean("success", false)) {
                        Log.w(TAG, "Poll: servidor retornou success=false: " + json.optString("error"));
                        schedulePoll(getInterval());
                        return;
                    }

                    int newStatus = json.optInt("status", -1);
                    if (newStatus == -1) {
                        Log.w(TAG, "Poll: campo 'status' ausente na resposta");
                        schedulePoll(getInterval());
                        return;
                    }

                    // Salva MAC do ESP32 nas SharedPreferences para reconexão BLE
                    String mac = json.optString("esp32_mac", "");
                    if (!mac.isEmpty()) {
                        getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                                .edit()
                                .putString("esp32_mac", mac)
                                .apply();
                    }

                    // Emite broadcast SEMPRE no primeiro poll ou quando status muda
                    boolean statusChanged = (lastKnownStatus != newStatus);
                    boolean firstPoll     = (lastKnownStatus == -1);

                    if (firstPoll || statusChanged) {
                        Log.i(TAG, "Status " + (firstPoll ? "inicial" : "mudou")
                                + ": " + lastKnownStatus + " → " + newStatus
                                + " (" + json.optString("status_label") + ")");

                        lastKnownStatus = newStatus;

                        // Atualiza notificação foreground
                        String notifText = newStatus == 1
                                ? "TAP online — " + json.optString("bebida", "")
                                : "TAP OFFLINE — aguardando reativação";
                        updateNotification(notifText);

                        // Emite LocalBroadcast para as Activities
                        Intent broadcast = new Intent(ACTION_TAP_STATUS_CHANGED);
                        broadcast.putExtra(EXTRA_STATUS,       newStatus);
                        broadcast.putExtra(EXTRA_STATUS_LABEL, json.optString("status_label", ""));
                        broadcast.putExtra(EXTRA_BEBIDA,       json.optString("bebida", ""));
                        broadcast.putExtra(EXTRA_ESP32_MAC,    mac);
                        broadcast.putExtra(EXTRA_PRECO,        (float) json.optDouble("preco", 0.0));
                        broadcast.putExtra(EXTRA_IMAGE,        json.optString("image", ""));
                        broadcast.putExtra(EXTRA_CARTAO,       json.optBoolean("cartao", false));
                        LocalBroadcastManager.getInstance(TapStatusPollingService.this)
                                .sendBroadcast(broadcast);

                    } else {
                        Log.d(TAG, "Status sem mudança: " + newStatus
                                + " (" + json.optString("status_label") + ")");
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Erro ao parsear resposta do poll: " + e.getMessage());
                }

                schedulePoll(getInterval());
            }
        });
    }

    /** Retorna o intervalo de polling baseado no status atual da TAP */
    private long getInterval() {
        // TAP offline → poll mais frequente para detectar reativação rapidamente
        return lastKnownStatus == 0
                ? POLL_INTERVAL_OFFLINE_MS
                : POLL_INTERVAL_ACTIVE_MS;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notificação Foreground
    // ─────────────────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "Status da TAP",
                    NotificationManager.IMPORTANCE_MIN // Discreta — sem som
            );
            channel.setDescription("Monitoramento do status da TAP ChoppON");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("ChoppON")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilitários estáticos
    // ─────────────────────────────────────────────────────────────────────────

    /** Verifica se o serviço está rodando */
    public static boolean isRunning() {
        return sRunning.get();
    }

    /**
     * Inicia o serviço de polling.
     * @param context     Context da Activity chamadora
     * @param tapStatus   Status atual da TAP (0=offline, 1=online)
     */
    public static void start(Context context, int tapStatus) {
        Intent intent = new Intent(context, TapStatusPollingService.class);
        intent.putExtra(EXTRA_CURRENT_STATUS, tapStatus);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
        Log.i(TAG, "start() chamado | tapStatus=" + tapStatus);
    }

    /** Para o serviço de polling */
    public static void stop(Context context) {
        context.stopService(new Intent(context, TapStatusPollingService.class));
        sRunning.set(false);
        Log.i(TAG, "stop() chamado");
    }
}
