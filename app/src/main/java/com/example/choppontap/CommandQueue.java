package com.example.choppontap;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.LinkedList;
import java.util.Queue;

/**
 * CommandQueue — Fila de comandos BLE para protocolo NUS v4.0.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * PROTOCOLO NUS v4.0 (simplificado)
 * ═══════════════════════════════════════════════════════════════════════
 *
 *   enqueue() → processQueue() → enviarComandoAtivo()
 *        │                                    │
 *        ▼                                    ▼
 *   QUEUED                            SENT (write BLE)
 *                                             │
 *                              ┌──────────────┴──────────────┐
 *                              ▼                             ▼
 *                     Resposta recebida              Timeout (sem resposta)
 *                     OK/VP:/ML:/PL:/ERRO            retry ou ERROR
 *                              │
 *                              ▼
 *                           DONE
 *
 * NÃO existe mais: ACK, DONE|cmd_id, PONG, HMAC, SESSION_ID, CMD_ID,
 *                  ERROR:HMAC_INVALID, ERROR:DUPLICATE, ERROR:SESSION_MISMATCH
 *
 * Comandos suportados:
 *   $ML:<volume>  — Liberar volume em ml
 *   $LB:          — Liberação contínua
 *   $ML:0         — Parar liberação
 *   $PL:<pulsos>  — Configurar pulsos/litro
 *   $PL:0         — Consultar pulsos/litro
 *   $TO:<ms>      — Configurar timeout
 *   $TO:          — Consultar timeout
 *
 * Respostas esperadas:
 *   OK            — Comando aceito
 *   ERRO          — Comando rejeitado
 *   VP:<valor>    — Volume parcial liberado
 *   ML:<valor>    — Volume total final (operação concluída)
 *   PL:<valor>    — Pulsos por litro atual
 *   QP:<valor>    — Quantidade de pulsos
 *   TO:<valor>    — Timeout atual
 *
 * @version 4.0.0
 */
public class CommandQueue {

    private static final String TAG = "BLE_CMD_QUEUE";

    public static final int   MAX_QUEUE_SIZE     = 10;
    private static final long RESPONSE_TIMEOUT_MS = 10_000L;
    public static final int   MAX_RETRIES        = 2;

    private final Queue<String> mQueue  = new LinkedList<>();
    private String              mActive = null;
    private boolean             mPaused = false;

    private final Handler mHandler              = new Handler(Looper.getMainLooper());
    private Runnable      mResponseTimeoutRunnable = null;

    // ── Interfaces de callback ────────────────────────────────────────────────

    public interface Callback {
        void onSend(String command);
        void onResponse(String command, String response);
        void onError(String command, String reason);
        void onQueueFull();
        /** Alerta operacional */
        default void onWarning(String warnType) {
            Log.w("BLE_CMD_QUEUE", "[WARN] " + warnType);
        }
    }

    public interface BleWriter {
        boolean write(String data);
    }

    private final Callback  mCallback;
    private final BleWriter mWriter;
    private int mRetryCount = 0;

    public CommandQueue(BleWriter writer, Callback callback) {
        this.mWriter   = writer;
        this.mCallback = callback;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Enfileiramento
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Enfileira um comando para envio ao ESP32.
     * Formato: "$ML:100", "$PL:0", "$LB:", "$TO:5000", etc.
     */
    public synchronized boolean enqueue(String command) {
        if (command == null || command.isEmpty()) {
            Log.w(TAG, "[QUEUE] Comando vazio — ignorando");
            return false;
        }
        if (mQueue.size() >= MAX_QUEUE_SIZE) {
            Log.e(TAG, "[QUEUE] Fila cheia (" + MAX_QUEUE_SIZE + ") — rejeitando: " + command);
            if (mCallback != null) mCallback.onQueueFull();
            return false;
        }

        mQueue.add(command);
        Log.i(TAG, "[QUEUE] enqueue → " + command + " | fila=" + mQueue.size());
        processQueue();
        return true;
    }

    /**
     * Enfileira um comando de liberação de volume.
     * Compatibilidade com código legado.
     */
    public synchronized boolean enqueueServe(int volumeMl) {
        return enqueue("$ML:" + volumeMl);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Processamento de respostas BLE — Protocolo NUS v4.0
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Processa resposta recebida do ESP32 via notificação BLE.
     * Respostas válidas: OK, ERRO, VP:, ML:, PL:, QP:, TO:
     */
    public synchronized void onBleResponse(String response) {
        if (response == null || response.isEmpty()) return;

        String trimmed = response.trim();
        Log.d(TAG, "[QUEUE] resposta: " + trimmed + " | ativo=" + mActive);

        // OK — comando aceito
        if ("OK".equalsIgnoreCase(trimmed)) {
            Log.i(TAG, "[QUEUE] OK recebido para: " + mActive);
            // OK não finaliza o comando — aguardamos ML: ou PL: como resposta final
            // Mas para comandos de configuração ($PL:, $TO:), OK pode ser a resposta final
            if (mActive != null && !mActive.startsWith("$ML:") && !mActive.equals("$LB:")) {
                completarComando(trimmed);
            }
            return;
        }

        // ERRO — comando rejeitado
        if ("ERRO".equalsIgnoreCase(trimmed)) {
            Log.e(TAG, "[QUEUE] ERRO recebido para: " + mActive);
            if (mActive != null) {
                falharComando(mActive, "ESP32 retornou ERRO");
            }
            return;
        }

        // ML: — Volume total final (operação de liberação concluída)
        if (trimmed.startsWith("ML:")) {
            Log.i(TAG, "[QUEUE] ML recebido — operação concluída: " + trimmed);
            completarComando(trimmed);
            return;
        }

        // VP: — Volume parcial (não finaliza o comando, apenas notifica)
        if (trimmed.startsWith("VP:")) {
            Log.d(TAG, "[QUEUE] VP parcial: " + trimmed);
            if (mCallback != null && mActive != null) {
                mCallback.onResponse(mActive, trimmed);
            }
            // Renova timeout — ainda esperando ML:
            if (mActive != null && (mActive.startsWith("$ML:") || mActive.equals("$LB:"))) {
                iniciarResponseTimeout();
            }
            return;
        }

        // QP: — Quantidade de pulsos
        if (trimmed.startsWith("QP:")) {
            Log.d(TAG, "[QUEUE] QP: " + trimmed);
            if (mCallback != null && mActive != null) {
                mCallback.onResponse(mActive, trimmed);
            }
            return;
        }

        // PL: — Pulsos por litro (resposta a $PL:0 ou $PL:<valor>)
        if (trimmed.startsWith("PL:")) {
            Log.d(TAG, "[QUEUE] PL: " + trimmed);
            completarComando(trimmed);
            return;
        }

        // TO: — Timeout (resposta a $TO: ou $TO:<valor>)
        if (trimmed.startsWith("TO:")) {
            Log.d(TAG, "[QUEUE] TO: " + trimmed);
            completarComando(trimmed);
            return;
        }

        // Resposta desconhecida
        Log.w(TAG, "[QUEUE] Resposta desconhecida: " + trimmed);
        if (mCallback != null && mActive != null) {
            mCallback.onResponse(mActive, trimmed);
        }
    }

    public synchronized void onBleDisconnected() {
        if (mActive != null) {
            Log.w(TAG, "[RESET] BLE desconectado durante comando: " + mActive);
            cancelResponseTimeout();
            mPaused = true;
            return;
        }
        reset();
        mPaused = true;
    }

    public synchronized void onBleReady() {
        Log.i(TAG, "[QUEUE] BLE READY — ativo=" + mActive + " | fila=" + mQueue.size());
        mPaused = false;
        processQueue();
    }

    public synchronized String getActiveCommand() { return mActive; }
    public synchronized int size()                 { return mQueue.size(); }

    public synchronized void reset() {
        Log.w(TAG, "[QUEUE] reset() — limpando fila e cancelando ativo");
        cancelResponseTimeout();
        mQueue.clear();
        mActive = null;
        mPaused = false;
        mRetryCount = 0;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Handlers internos
    // ═══════════════════════════════════════════════════════════════════════

    private void completarComando(String response) {
        cancelResponseTimeout();
        String cmd = mActive;
        mActive = null;
        mRetryCount = 0;
        if (mCallback != null && cmd != null) {
            mCallback.onResponse(cmd, response);
        }
        processQueue();
    }

    private void processQueue() {
        if (mPaused) {
            Log.d(TAG, "[QUEUE] processQueue() — PAUSADO");
            return;
        }
        if (mActive != null) {
            Log.d(TAG, "[QUEUE] processQueue() — aguardando resposta de: " + mActive);
            return;
        }
        if (mQueue.isEmpty()) {
            Log.d(TAG, "[QUEUE] processQueue() — fila vazia");
            return;
        }
        mActive = mQueue.poll();
        mRetryCount = 0;
        enviarComandoAtivo();
    }

    private void enviarComandoAtivo() {
        if (mActive == null) return;

        Log.i(TAG, "[QUEUE] SEND [" + mActive + "]");

        boolean ok = mWriter.write(mActive);
        if (ok) {
            if (mCallback != null) mCallback.onSend(mActive);
            iniciarResponseTimeout();
        } else {
            Log.e(TAG, "[QUEUE] write() falhou para: " + mActive);
            mRetryCount++;
            if (mRetryCount <= MAX_RETRIES) {
                mHandler.postDelayed(this::enviarComandoAtivo, 1_000L);
            } else {
                falharComando(mActive,
                        "BLE write falhou após " + MAX_RETRIES + " tentativas");
            }
        }
    }

    private void falharComando(String cmd, String reason) {
        Log.e(TAG, "[QUEUE] ERROR → " + cmd + " | motivo=" + reason);
        cancelResponseTimeout();
        if (mCallback != null) mCallback.onError(cmd, reason);
        mActive = null;
        mRetryCount = 0;
        mQueue.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Timeout
    // ═══════════════════════════════════════════════════════════════════════

    private void iniciarResponseTimeout() {
        cancelResponseTimeout();
        mResponseTimeoutRunnable = () -> {
            synchronized (CommandQueue.this) {
                if (mActive == null) return;
                Log.e(TAG, "[TIMEOUT] Sem resposta em " + RESPONSE_TIMEOUT_MS + "ms para: " + mActive);
                mRetryCount++;
                if (mRetryCount <= MAX_RETRIES) {
                    Log.w(TAG, "[RETRY] Tentativa " + mRetryCount + "/" + MAX_RETRIES);
                    enviarComandoAtivo();
                } else {
                    falharComando(mActive, "Timeout após " + MAX_RETRIES + " tentativas");
                }
            }
        };
        mHandler.postDelayed(mResponseTimeoutRunnable, RESPONSE_TIMEOUT_MS);
    }

    private void cancelResponseTimeout() {
        if (mResponseTimeoutRunnable != null) {
            mHandler.removeCallbacks(mResponseTimeoutRunnable);
            mResponseTimeoutRunnable = null;
        }
    }
}
