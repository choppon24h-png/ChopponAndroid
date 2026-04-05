package com.example.choppontap;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.LinkedList;
import java.util.Queue;

/**
 * CommandQueueManager — Fila FIFO de comandos BLE para protocolo NUS v4.0.
 *
 * ═══════════════════════════════════════════════════════════════════
 * PROTOCOLO NUS v4.0 (simplificado)
 * ═══════════════════════════════════════════════════════════════════
 *
 * Fila FIFO: apenas 1 comando ativo por vez.
 * Fluxo por comando:
 *   enqueue() → send → aguarda resposta (OK/VP:/ML:/PL:/ERRO) → remove → próximo
 *
 * NÃO existe mais: ACK|, DONE|, PONG, HMAC, SESSION_ID, CMD_ID,
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
public class CommandQueueManager {

    private static final String TAG = "BLE_CMD_QUEUE";
    public static final String VERSION = "4.0.0";

    // ── Timeouts ──────────────────────────────────────────────────────────────
    /** Timeout para receber resposta após envio (ms). */
    private static final long RESPONSE_TIMEOUT_MS = 15_000L;
    /** Timeout para liberação contínua — mais longo pois não tem ML: final até parar */
    private static final long CONTINUOUS_TIMEOUT_MS = 60_000L;

    // ── Estado interno ────────────────────────────────────────────────────────
    private final Queue<String> mQueue   = new LinkedList<>();
    private String              mActive  = null;
    private boolean             mPaused  = false;
    private int                 mRetryCount = 0;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable      mResponseTimeoutRunnable = null;

    // ── Callbacks ─────────────────────────────────────────────────────────────
    public interface Callback {
        void onSend(String command);
        void onResponse(String command, String response);
        void onError(String command, String reason);
    }

    private Callback mCallback;

    // ── Interface de envio BLE ────────────────────────────────────────────────
    public interface BleWriter {
        boolean write(String data);
    }

    private BleWriter mWriter;

    // ── Construtor ────────────────────────────────────────────────────────────
    public CommandQueueManager(BleWriter writer, Callback callback) {
        this.mWriter   = writer;
        this.mCallback = callback;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Enfileira um comando genérico para envio ao ESP32.
     */
    public synchronized boolean enqueue(String command) {
        if (command == null || command.isEmpty()) {
            Log.w(TAG, "[QUEUE] Comando vazio — ignorando");
            return false;
        }
        mQueue.add(command);
        Log.i(TAG, "[BLE_CMD] enqueue → " + command + " | fila=" + mQueue.size());
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

    /**
     * Processa a resposta BLE recebida do ESP32.
     * Deve ser chamado pelo BluetoothService em onCharacteristicChanged().
     *
     * Formatos tratados:
     *   OK, ERRO, VP:, ML:, PL:, QP:, TO:
     */
    public synchronized void onBleResponse(String response) {
        if (response == null || response.isEmpty()) return;

        String trimmed = response.trim();
        Log.d(TAG, "[BLE_CMD] resposta recebida: [" + trimmed + "] | ativo=" + mActive);

        // OK — comando aceito
        if ("OK".equalsIgnoreCase(trimmed)) {
            Log.i(TAG, "[BLE_CMD] OK recebido para: " + mActive);
            // Para comandos de configuração, OK é a resposta final
            if (mActive != null && !mActive.startsWith("$ML:") && !mActive.equals("$LB:")) {
                completarComando(trimmed);
            }
            return;
        }

        // ERRO — comando rejeitado
        if ("ERRO".equalsIgnoreCase(trimmed)) {
            Log.e(TAG, "[BLE_CMD] ERRO recebido para: " + mActive);
            if (mActive != null) {
                falharComando(mActive, "ESP32 retornou ERRO");
            }
            return;
        }

        // ML: — Volume total final (operação concluída)
        if (trimmed.startsWith("ML:")) {
            Log.i(TAG, "[BLE_CMD] ML recebido — operação concluída: " + trimmed);
            completarComando(trimmed);
            return;
        }

        // VP: — Volume parcial (não finaliza, apenas notifica)
        if (trimmed.startsWith("VP:")) {
            Log.d(TAG, "[BLE_CMD] VP parcial: " + trimmed);
            if (mCallback != null && mActive != null) {
                mCallback.onResponse(mActive, trimmed);
            }
            // Renova timeout — ainda esperando ML:
            if (mActive != null) {
                iniciarResponseTimeout();
            }
            return;
        }

        // QP: — Quantidade de pulsos
        if (trimmed.startsWith("QP:")) {
            Log.d(TAG, "[BLE_CMD] QP: " + trimmed);
            if (mCallback != null && mActive != null) {
                mCallback.onResponse(mActive, trimmed);
            }
            return;
        }

        // PL: — Pulsos por litro
        if (trimmed.startsWith("PL:")) {
            Log.d(TAG, "[BLE_CMD] PL: " + trimmed);
            completarComando(trimmed);
            return;
        }

        // TO: — Timeout
        if (trimmed.startsWith("TO:")) {
            Log.d(TAG, "[BLE_CMD] TO: " + trimmed);
            completarComando(trimmed);
            return;
        }

        // Resposta desconhecida — notifica callback
        Log.w(TAG, "[BLE_CMD] Resposta desconhecida: " + trimmed);
        if (mCallback != null && mActive != null) {
            mCallback.onResponse(mActive, trimmed);
        }
    }

    /**
     * Chamado quando BLE desconecta.
     */
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

    /**
     * Chamado quando BLE reconecta e está pronto.
     */
    public synchronized void onBleReady() {
        Log.i(TAG, "[QUEUE] BLE READY — ativo=" + mActive + " | fila=" + mQueue.size());
        mPaused = false;
        // Se havia comando ativo, reenvia
        if (mActive != null) {
            enviarComandoAtivo();
        } else {
            processQueue();
        }
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

        Log.i(TAG, "[BLE_CMD] SEND [" + mActive + "]");

        boolean ok = mWriter.write(mActive);
        if (ok) {
            if (mCallback != null) mCallback.onSend(mActive);
            iniciarResponseTimeout();
        } else {
            Log.e(TAG, "[BLE_CMD] write() falhou para: " + mActive);
            mRetryCount++;
            if (mRetryCount <= 2) {
                mHandler.postDelayed(this::enviarComandoAtivo, 1_000L);
            } else {
                falharComando(mActive, "BLE write falhou após tentativas");
            }
        }
    }

    private void falharComando(String cmd, String reason) {
        Log.e(TAG, "[BLE_CMD] ERROR → " + cmd + " | motivo=" + reason);
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
        long timeout = (mActive != null && mActive.equals("$LB:"))
                ? CONTINUOUS_TIMEOUT_MS : RESPONSE_TIMEOUT_MS;
        mResponseTimeoutRunnable = () -> {
            synchronized (CommandQueueManager.this) {
                if (mActive == null) return;
                Log.e(TAG, "[TIMEOUT] Sem resposta em " + timeout + "ms para: " + mActive);
                mRetryCount++;
                if (mRetryCount <= 2) {
                    Log.w(TAG, "[RETRY] Tentativa " + mRetryCount + "/2");
                    enviarComandoAtivo();
                } else {
                    falharComando(mActive, "Timeout após tentativas");
                }
            }
        };
        mHandler.postDelayed(mResponseTimeoutRunnable, timeout);
    }

    private void cancelResponseTimeout() {
        if (mResponseTimeoutRunnable != null) {
            mHandler.removeCallbacks(mResponseTimeoutRunnable);
            mResponseTimeoutRunnable = null;
        }
    }
}
