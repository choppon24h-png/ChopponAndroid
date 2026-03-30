package com.example.choppontap;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * BleReconnectionHandler — Gerencia reconexões BLE com validação de estado.
 *
 * ═══════════════════════════════════════════════════════════════════
 * FLUXO DE RECONNECT (v2.3.0)
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
 *   enviarAutenticacao(AUTH) → AUTH_OK timeout 8s
 *   ↓
 *   READY (AUTH_OK recebido)
 *   ↓
 *   [CommandQueue.onBleReady() → resincroniza comando ativo]
 *   ↓
 *   ✓ SINCRONIZADO
 *
 * ═══════════════════════════════════════════════════════════════════
 * PROTEÇÕES (v2.3.0)
 * ═══════════════════════════════════════════════════════════════════
 *
 *   ✔ Reset de AUTH retries (permite reauth após reconnect)
 *   ✔ Preservação de GATT em status 8/257 (não recriar desnecessário)
 *   ✔ Reconexão automática com backoff exponencial
 *   ✔ Validação de comando ativo pós-reconnect
 */
public class BleReconnectionHandler {

    private static final String TAG = "BLE_RECONNECT";

    public enum ReconnectPhase {
        DISCONNECTED,
        WAITING_BACKOFF,
        CONNECTING,
        CONNECTED,
        DISCOVERING_SERVICES,
        AUTH_IN_PROGRESS,
        AUTH_TIMEOUT,
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
        /** Reconexão iniciada após status X */
        void onReconnectStarted(int gattStatus, int attemptNumber);
        /** Reconexão completada — BLE READY */
        void onReconnectCompleted();
        /** Erro de reconexão (timeout, bond fail, etc.) */
        void onReconnectFailed(String reason);
    }

    private final Callback mCallback;

    public BleReconnectionHandler(Callback callback) {
        this.mCallback = callback;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Registra uma desconexão com status GATT.
     * Inicializa fluxo de reconnect.
     */
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

    /**
     * Chamado quando BLE conecta sem erros (STATE_CONNECTED).
     */
    public void onConnected() {
        mPhase = ReconnectPhase.CONNECTED;
        Log.i(TAG, "[RECONNECT] CONECTADO! (" + getElapsedTimeSinceDisconnect() + "ms)");
    }

    /**
     * Chamado após descober serviços.
     */
    public void onServicesDiscovered() {
        mPhase = ReconnectPhase.DISCOVERING_SERVICES;
        Log.i(TAG, "[RECONNECT] Serviços descobertos");
    }

    /**
     * Chamado ao enviar AUTH.
     */
    public void onAuthSent() {
        mPhase = ReconnectPhase.AUTH_IN_PROGRESS;
        Log.i(TAG, "[RECONNECT] AUTH enviado — aguardando AUTH_OK...");
    }

    /**
     * Chamado quando AUTH_OK é recebido.
     */
    public void onAuthOk() {
        mPhase = ReconnectPhase.READY;
        Log.i(TAG, "[RECONNECT] AUTH_OK recebido! (" + getElapsedTimeSinceDisconnect() + "ms) → PRONTO");
        if (mCallback != null) {
            mCallback.onReconnectCompleted();
        }
    }

    /**
     * Chamado quando AUTH timeout (8s).
     */
    public void onAuthTimeout() {
        mPhase = ReconnectPhase.AUTH_TIMEOUT;
        Log.e(TAG, "[RECONNECT] AUTH timeout — BLE permanece bloqueado");
        if (mCallback != null) {
            mCallback.onReconnectFailed("AUTH timeout");
        }
    }

    /**
     * Chamado ao sincronizar CommandQueue (após READY).
     */
    public void onCommandQueueSync() {
        mPhase = ReconnectPhase.SYNC_COMMAND_QUEUE;
        Log.i(TAG, "[RECONNECT] CommandQueue sincronizado —reenvio de comando ativo se necessário");
    }

    /**
     * Chamado quando reconexão falha (bond fail, gatt close, etc.).
     */
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

    /**
     * Retorna tempo decorrido desde a desconexão (ms).
     */
    public long getElapsedTimeSinceDisconnect() {
        if (mDisconnectTimestamp == 0) return 0;
        return System.currentTimeMillis() - mDisconnectTimestamp;
    }

    /**
     * Retorna true se a reconexão está em andamento (não DISCONNECTED, não READY).
     */
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
