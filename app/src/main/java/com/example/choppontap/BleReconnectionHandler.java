package com.example.choppontap;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * BleReconnectionHandler — Gerencia reconexões BLE com validação de estado.
 *
 * ═══════════════════════════════════════════════════════════════════
 * FLUXO DE RECONNECT (NUS v4.0)
 * ═══════════════════════════════════════════════════════════════════
 *
 *   DISCONNECTED (status=8,133,257)
 *   ↓
 *   [Aguardar backoff: 2s → 4s → 8s → 15s]
 *   ↓
 *   CONNECTING (gatt.connect())
 *   ↓
 *   CONNECTED (STATE_CONNECTED callback)
 *   ↓
 *   discoverServices() → onServicesDiscovered()
 *   ↓
 *   habilitarNotificacoes() → CCCD write
 *   ↓
 *   READY (notificações habilitadas — sem AUTH)
 *   ↓
 *   [CommandQueue.onBleReady() → resincroniza comando ativo]
 *   ↓
 *   ✓ SINCRONIZADO
 *
 * Protocolo NUS v4.0: NÃO existe mais AUTH/AUTH_OK.
 * A transição para READY ocorre quando as notificações NUS
 * são habilitadas com sucesso.
 *
 * @version 4.0.0
 */
public class BleReconnectionHandler {

    private static final String TAG = "BLE_RECONNECT";

    public enum ReconnectPhase {
        DISCONNECTED,
        WAITING_BACKOFF,
        CONNECTING,
        CONNECTED,
        DISCOVERING_SERVICES,
        ENABLING_NOTIFICATIONS,
        READY,
        SYNC_COMMAND_QUEUE,
        READY_FOR_SERVE
    }

    private ReconnectPhase mPhase = ReconnectPhase.DISCONNECTED;
    private int mAttemptNumber = 0;
    private long mDisconnectTimestamp = 0;
    private int mLastGattStatus = 0;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // ── Callbacks ─────────────────────────────────────────────────────────────
    public interface Callback {
        void onReconnectStarted(int gattStatus, int attemptNumber);
        void onReconnectCompleted();
        void onReconnectFailed(String reason);
    }

    private final Callback mCallback;

    public BleReconnectionHandler(Callback callback) {
        this.mCallback = callback;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública
    // ═════════════════════════════════════════════════════════════════════════

    public void onDisconnected(int gattStatus, int reconnectAttempt) {
        mPhase = ReconnectPhase.DISCONNECTED;
        mAttemptNumber = reconnectAttempt;
        mLastGattStatus = gattStatus;
        mDisconnectTimestamp = System.currentTimeMillis();

        Log.i(TAG, "[RECONNECT] Ciclo #" + mAttemptNumber + " iniciado"
                + " | status=" + gattStatus
                + " | phase=" + mPhase.name());

        if (mCallback != null) {
            mCallback.onReconnectStarted(gattStatus, mAttemptNumber);
        }
    }

    public void onConnected() {
        mPhase = ReconnectPhase.CONNECTED;
        Log.i(TAG, "[RECONNECT] CONECTADO! (" + getElapsedTimeSinceDisconnect() + "ms)");
    }

    public void onServicesDiscovered() {
        mPhase = ReconnectPhase.DISCOVERING_SERVICES;
        Log.i(TAG, "[RECONNECT] Serviços descobertos");
    }

    /**
     * Chamado quando as notificações NUS são habilitadas.
     * No protocolo NUS v4.0, isso equivale ao antigo onAuthOk().
     */
    public void onNotificationsEnabled() {
        mPhase = ReconnectPhase.READY;
        Log.i(TAG, "[RECONNECT] Notificações habilitadas! (" + getElapsedTimeSinceDisconnect() + "ms) -> PRONTO");
        if (mCallback != null) {
            mCallback.onReconnectCompleted();
        }
    }

    /**
     * Compatibilidade: chamado por código que ainda usa onAuthOk().
     */
    public void onAuthOk() {
        onNotificationsEnabled();
    }

    /**
     * Compatibilidade: chamado por código que ainda usa onAuthSent().
     * No protocolo NUS v4.0, não há AUTH — apenas habilitar notificações.
     */
    public void onAuthSent() {
        mPhase = ReconnectPhase.ENABLING_NOTIFICATIONS;
        Log.i(TAG, "[RECONNECT] Habilitando notificações NUS...");
    }

    /**
     * Compatibilidade: chamado por código que ainda usa onAuthTimeout().
     * No protocolo NUS v4.0, timeout de notificações.
     */
    public void onAuthTimeout() {
        Log.e(TAG, "[RECONNECT] Timeout habilitando notificações");
        if (mCallback != null) {
            mCallback.onReconnectFailed("Notifications timeout");
        }
    }

    public void onCommandQueueSync() {
        mPhase = ReconnectPhase.SYNC_COMMAND_QUEUE;
        Log.i(TAG, "[RECONNECT] CommandQueue sincronizado");
    }

    public void onReconnectFailed(String reason) {
        Log.e(TAG, "[RECONNECT] FALHA: " + reason + " | attempt=" + mAttemptNumber);
        mPhase = ReconnectPhase.DISCONNECTED;
        if (mCallback != null) {
            mCallback.onReconnectFailed(reason);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Utilitários
    // ═════════════════════════════════════════════════════════════════════════

    public ReconnectPhase getPhase() {
        return mPhase;
    }

    public int getAttemptNumber() {
        return mAttemptNumber;
    }

    public int getLastGattStatus() {
        return mLastGattStatus;
    }

    public long getElapsedTimeSinceDisconnect() {
        if (mDisconnectTimestamp == 0) return 0;
        return System.currentTimeMillis() - mDisconnectTimestamp;
    }

    public boolean isReconnecting() {
        return mPhase != ReconnectPhase.DISCONNECTED
                && mPhase != ReconnectPhase.READY_FOR_SERVE;
    }

    @Override
    public String toString() {
        return "BleReconnectionHandler{attempt=" + mAttemptNumber
                + ", phase=" + mPhase.name()
                + ", elapsed=" + getElapsedTimeSinceDisconnect() + "ms"
                + ", status=" + mLastGattStatus + "}";
    }
}
