package com.example.choppontap;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * ConnectionManager — Máquina de estados BLE para protocolo NUS v4.0
 *
 * ═══════════════════════════════════════════════════════════════════
 * ESTADOS
 * ═══════════════════════════════════════════════════════════════════
 *
 *   DISCONNECTED → SCANNING → CONNECTING → CONNECTED → READY
 *        ↑                                                 │
 *        └─────────────────────────────────────────────────┘
 *                         (reconexão automática)
 *
 *   DISCONNECTED  — sem conexão BLE ativa
 *   SCANNING      — scan BLE ativo procurando CHOPP_*
 *   CONNECTING    — connectGatt() chamado, aguardando STATE_CONNECTED
 *   CONNECTED     — GATT conectado, serviço NUS descoberto
 *   READY         — Notificações habilitadas, pronto para enviar comandos
 *
 * NÃO existe mais: AUTH:OK, PING/PONG heartbeat
 * A transição CONNECTED → READY ocorre quando as notificações NUS
 * são habilitadas com sucesso (sem necessidade de autenticação).
 *
 * ═══════════════════════════════════════════════════════════════════
 * REGRAS
 * ═══════════════════════════════════════════════════════════════════
 *
 *   - SEMPRE usar autoConnect=true para reconexão após primeira conexão
 *   - NUNCA conectar direto do callback do scan
 *   - Aguardar SCAN_TO_CONNECT_DELAY_MS (800ms) após scan antes de conectar
 *   - Reconectar sempre pelo MAC salvo (não pelo scan)
 *   - NUNCA chamar gatt.close() em status=8 (GATT_CONN_TIMEOUT)
 *   - Usar apenas disconnect() em status=8
 *   - Retry exponencial: 1s → 2s → 5s → 10s (loop)
 *
 * @version 4.0.0
 */
public class ConnectionManager {

    private static final String TAG = "BLE_CONN_MGR";

    // ── Estados ───────────────────────────────────────────────────────────────
    public enum State {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED,
        READY
    }

    // ── Delay pós-scan antes de conectar (800ms) ─────────────────────────────
    public static final long SCAN_TO_CONNECT_DELAY_MS = 800L;

    // ── Retry exponencial: 1s → 2s → 5s → 10s (loop) ────────────────────────
    private static final long[] RETRY_DELAYS_MS = { 1_000L, 2_000L, 5_000L, 10_000L };

    // ── Keepalive simples (sem PING/PONG) ────────────────────────────────────
    private static final long KEEPALIVE_TIMEOUT_MS = 30_000L;
    private int mKeepaliveFailures = 0;
    private static final int KEEPALIVE_MAX_FAILURES = 3;

    // ── Estado interno ────────────────────────────────────────────────────────
    private State   mState         = State.DISCONNECTED;
    private boolean mAutoReconnect = true;
    private String  mTargetMac     = null;
    private int     mRetryIndex    = 0;
    private int     mRetryCount    = 0;

    private final Handler mHandler              = new Handler(Looper.getMainLooper());
    private Runnable      mReconnectRunnable    = null;
    private Runnable      mScanConnectRunnable  = null;
    private Runnable      mKeepaliveRunnable    = null;

    // ── Callbacks ─────────────────────────────────────────────────────────────
    public interface Callback {
        /** Estado transitou para newState */
        void onStateChanged(State newState, State oldState);
        /** Keepalive timeout — deve reconectar */
        void onHeartbeatFailed();
        /**
         * Solicitar conexão GATT.
         * @param mac         Endereço MAC do dispositivo
         * @param autoConnect true para reconexão automática, false para primeira conexão
         */
        void onConnectRequested(String mac, boolean autoConnect);
    }

    private final Callback mCallback;

    // ── Construtor ────────────────────────────────────────────────────────────
    public ConnectionManager(Callback callback) {
        this.mCallback = callback;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública — leitura de estado
    // ═════════════════════════════════════════════════════════════════════════

    public synchronized State getState() {
        return mState;
    }

    public synchronized boolean isReady() {
        return mState == State.READY;
    }

    public synchronized boolean isConnected() {
        return mState == State.CONNECTED || mState == State.READY;
    }

    public synchronized String getTargetMac() {
        return mTargetMac;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública — configuração
    // ═════════════════════════════════════════════════════════════════════════

    public synchronized void setTargetMac(String mac) {
        if (mac != null && !mac.equals(mTargetMac)) {
            Log.i(TAG, "[CONN] MAC alvo definido: " + mac);
            mTargetMac = mac;
        }
    }

    public synchronized void setAutoReconnect(boolean enabled) {
        mAutoReconnect = enabled;
        Log.i(TAG, "[CONN] autoReconnect=" + enabled);
        if (!enabled) {
            cancelarReconexao();
            pararKeepalive();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública — eventos de scan
    // ═════════════════════════════════════════════════════════════════════════

    public synchronized void onScanStarted() {
        Log.i(TAG, "[CONN] Scan iniciado -> SCANNING");
        transitionTo(State.SCANNING);
    }

    public synchronized void onDeviceFoundInScan(String mac) {
        if (mac == null) return;
        Log.i(TAG, "[CONN] Dispositivo encontrado no scan: " + mac
                + " | Agendando conexao em " + SCAN_TO_CONNECT_DELAY_MS + "ms");
        mTargetMac = mac;

        if (mScanConnectRunnable != null) {
            mHandler.removeCallbacks(mScanConnectRunnable);
        }

        mScanConnectRunnable = () -> {
            synchronized (ConnectionManager.this) {
                if (mTargetMac == null) return;
                Log.i(TAG, "[CONN] Delay pos-scan concluido -> conectando " + mTargetMac);
                transitionTo(State.CONNECTING);
                if (mCallback != null) mCallback.onConnectRequested(mTargetMac, false);
            }
        };
        mHandler.postDelayed(mScanConnectRunnable, SCAN_TO_CONNECT_DELAY_MS);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública — eventos GATT
    // ═════════════════════════════════════════════════════════════════════════

    public synchronized void onGattConnected() {
        Log.i(TAG, "[CONN] GATT conectado -> CONNECTED");
        cancelarReconexao();
        if (mScanConnectRunnable != null) {
            mHandler.removeCallbacks(mScanConnectRunnable);
            mScanConnectRunnable = null;
        }
        mRetryIndex = 0;
        mRetryCount = 0;
        transitionTo(State.CONNECTED);
    }

    /**
     * Notifica que as notificações NUS foram habilitadas — transiciona para READY.
     * No protocolo NUS v4.0, não há AUTH:OK — basta habilitar notificações.
     */
    public synchronized void onNotificationsEnabled() {
        Log.i(TAG, "[CONN] Notificações NUS habilitadas -> READY");
        transitionTo(State.READY);
    }

    /**
     * Compatibilidade: chamado por código que ainda usa onAuthOk().
     * No protocolo NUS v4.0, equivale a onNotificationsEnabled().
     */
    public synchronized void onAuthOk() {
        onNotificationsEnabled();
    }

    /**
     * Notifica que qualquer dado foi recebido do ESP32.
     * Reseta o keepalive timer.
     */
    public synchronized void onDataReceived() {
        mKeepaliveFailures = 0;
        resetKeepaliveTimer();
    }

    public synchronized void onGattDisconnected(int status, boolean closeGatt) {
        Log.w(TAG, "[CONN] GATT desconectado | status=" + status
                + " | closeGatt=" + closeGatt
                + " | estado=" + mState);

        pararKeepalive();

        if (mState == State.DISCONNECTED) {
            Log.d(TAG, "[CONN] Ja DISCONNECTED — ignorando");
            return;
        }

        transitionTo(State.DISCONNECTED);

        if (mAutoReconnect && mTargetMac != null) {
            agendarReconexao();
        }
    }

    public synchronized void destroy() {
        Log.i(TAG, "[CONN] destroy()");
        mAutoReconnect = false;
        cancelarReconexao();
        pararKeepalive();
        if (mScanConnectRunnable != null) {
            mHandler.removeCallbacks(mScanConnectRunnable);
            mScanConnectRunnable = null;
        }
        mState = State.DISCONNECTED;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Reconexão automática com retry exponencial
    // ═════════════════════════════════════════════════════════════════════════

    private void agendarReconexao() {
        cancelarReconexao();
        long delay = RETRY_DELAYS_MS[Math.min(mRetryIndex, RETRY_DELAYS_MS.length - 1)];
        mRetryCount++;
        mRetryIndex = Math.min(mRetryIndex + 1, RETRY_DELAYS_MS.length - 1);

        Log.i(TAG, "[CONN] Reconexao #" + mRetryCount
                + " agendada em " + delay + "ms -> " + mTargetMac);

        mReconnectRunnable = () -> {
            synchronized (ConnectionManager.this) {
                if (!mAutoReconnect || mTargetMac == null) return;
                if (mState != State.DISCONNECTED) {
                    Log.d(TAG, "[CONN] Reconexao cancelada — estado=" + mState);
                    return;
                }
                Log.i(TAG, "[CONN] Executando reconexao #" + mRetryCount
                        + " -> " + mTargetMac + " (autoConnect=true)");
                transitionTo(State.CONNECTING);
                if (mCallback != null) mCallback.onConnectRequested(mTargetMac, true);
            }
        };
        mHandler.postDelayed(mReconnectRunnable, delay);
    }

    private void cancelarReconexao() {
        if (mReconnectRunnable != null) {
            mHandler.removeCallbacks(mReconnectRunnable);
            mReconnectRunnable = null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Keepalive (substitui PING/PONG)
    // ═════════════════════════════════════════════════════════════════════════

    private void iniciarKeepalive() {
        pararKeepalive();
        mKeepaliveFailures = 0;
        resetKeepaliveTimer();
        Log.d(TAG, "[CONN] Keepalive iniciado (timeout=" + KEEPALIVE_TIMEOUT_MS + "ms)");
    }

    private void resetKeepaliveTimer() {
        pararKeepalive();
        mKeepaliveRunnable = () -> {
            synchronized (ConnectionManager.this) {
                if (mState != State.READY) return;
                mKeepaliveFailures++;
                Log.w(TAG, "[CONN] Keepalive timeout | falhas=" + mKeepaliveFailures
                        + "/" + KEEPALIVE_MAX_FAILURES);
                if (mKeepaliveFailures >= KEEPALIVE_MAX_FAILURES) {
                    Log.e(TAG, "[CONN] " + KEEPALIVE_MAX_FAILURES
                            + " keepalive timeouts -> forcando reconexao");
                    pararKeepalive();
                    if (mCallback != null) mCallback.onHeartbeatFailed();
                    return;
                }
                resetKeepaliveTimer();
            }
        };
        mHandler.postDelayed(mKeepaliveRunnable, KEEPALIVE_TIMEOUT_MS);
    }

    private void pararKeepalive() {
        if (mKeepaliveRunnable != null) {
            mHandler.removeCallbacks(mKeepaliveRunnable);
            mKeepaliveRunnable = null;
        }
        mKeepaliveFailures = 0;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Transição de estado
    // ═════════════════════════════════════════════════════════════════════════

    private void transitionTo(State newState) {
        if (mState == newState) return;
        State old = mState;
        mState = newState;
        Log.i(TAG, "[CONN] " + old.name() + " -> " + newState.name());

        if (newState == State.READY) {
            iniciarKeepalive();
        } else if (old == State.READY) {
            pararKeepalive();
        }

        if (mCallback != null) mCallback.onStateChanged(newState, old);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Diagnóstico
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public String toString() {
        return "ConnectionManager{state=" + mState
                + ", mac=" + mTargetMac
                + ", retries=" + mRetryCount
                + ", keepaliveFail=" + mKeepaliveFailures + "}";
    }
}
