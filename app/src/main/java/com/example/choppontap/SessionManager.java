package com.example.choppontap;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Response;

/**
 * SessionManager — Gerenciador de sessão anti-fraude para liberação de chopp.
 *
 * ═══════════════════════════════════════════════════════════════════
 * REGRA DE OURO (spec BLE Industrial)
 * ═══════════════════════════════════════════════════════════════════
 *
 *   NUNCA enviar SERVE sem sessão válida.
 *   NUNCA reenviar SERVE automaticamente sem validação de SESSION.
 *
 * ═══════════════════════════════════════════════════════════════════
 * FLUXO DE SESSÃO
 * ═══════════════════════════════════════════════════════════════════
 *
 *   1. startSession(checkoutId, volumeMl, deviceId)
 *      → POST /api/start_session.php
 *      → retorna { session_id: "SES_001", status: "paid" }
 *      → onSessionStarted(sessionId) chamado
 *
 *   2. Android envia: SERVE|300|ID=CMD123|SESSION=SES_001
 *
 *   3. finishSession(mlReal)
 *      → POST /api/finish_session.php
 *      → { session_id, ml_real, device_id, status: "completed" }
 *      → onSessionFinished() chamado
 *
 *   4. Se falhar:
 *      → failSession(motivo, mlParcial)
 *      → POST /api/finish_session.php com status: "error"
 *      → onSessionFailed() chamado
 *
 * ═══════════════════════════════════════════════════════════════════
 * ESTADOS
 * ═══════════════════════════════════════════════════════════════════
 *
 *   IDLE → STARTING → ACTIVE → FINISHING → COMPLETED / FAILED
 */
public class SessionManager {

    private static final String TAG = "BLE_SESSION";

    // ── Estados ───────────────────────────────────────────────────────────────
    public enum State {
        IDLE,
        STARTING,
        ACTIVE,
        FINISHING,
        COMPLETED,
        FAILED
    }

    // ── Dados da sessão ───────────────────────────────────────────────────────
    private State  mState      = State.IDLE;
    private String mSessionId  = null;
    private String mCheckoutId = null;
    private String mDeviceId   = null;
    private String mCommandId  = null;
    private int    mVolumeMl   = 0;

    // v2.3.0 FIX: Rastrear se session_id veio da API ou é fallback local
    private boolean mIsLocalFallback = false;
    // v2.3.0 FIX: Contar tentativas de API antes de gerar local
    private int mApiRetryAttempts = 0;
    private static final int MAX_API_RETRY = 2;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // ── Callbacks ─────────────────────────────────────────────────────────────
    public interface Callback {
        /** Sessão iniciada com sucesso — sessionId disponível para enviar ao ESP32 */
        void onSessionStarted(String sessionId, String checkoutId);
        /** Sessão finalizada com sucesso */
        void onSessionFinished(String sessionId, int mlReal);
        /** Sessão falhou (start ou finish) */
        void onSessionFailed(String sessionId, String reason);
    }

    private final Callback   mCallback;
    private final ApiHelper  mApiHelper;

    // ── Construtor ────────────────────────────────────────────────────────────
    public SessionManager(Context context, Callback callback) {
        this.mCallback  = callback;
        this.mApiHelper = new ApiHelper(context);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública
    // ═════════════════════════════════════════════════════════════════════════

    /** Retorna o estado atual da sessão. */
    public synchronized State getState() { return mState; }

    /** Retorna true se há uma sessão ativa (ACTIVE). */
    public synchronized boolean isActive() { return mState == State.ACTIVE; }

    /** v2.3.0 FIX: Retorna true se session_id é local (SES_LOCAL_*) — incompatível com ESP32. */
    public synchronized boolean isLocalFallback() { return mIsLocalFallback; }

    /** Retorna o SESSION_ID atual (null se não houver sessão). */
    public synchronized String getSessionId() { return mSessionId; }

    /** Retorna o CHECKOUT_ID atual. */
    public synchronized String getCheckoutId() { return mCheckoutId; }

    /**
     * Define o command_id do BleCommand associado a esta sessão.
     * Chamado após enqueueServe() retornar o BleCommand.
     */
    public synchronized void setCommandId(String commandId) {
        this.mCommandId = commandId;
        Log.i(TAG, "[SESSION] command_id definido: " + commandId);
    }

    /**
     * Inicia uma nova sessão de venda.
     *
     * NOTA DE SINCRONIZAÇÃO: Este método é chamado APENAS após isReadyWithGuardBand()
     * retornar true em PagamentoConcluido.java. O guard-band (800-1000ms após READY)
     * é validado antes desta chamada, garantindo sincronização com ESP32.
     *
     * Chama POST /api/start_session.php e, em caso de sucesso,
     * transiciona para ACTIVE e chama onSessionStarted(sessionId).
     *
     * @param checkoutId  ID do checkout/pedido
     * @param volumeMl    Volume em ml solicitado
     * @param deviceId    android_id do tablet
     */
    public synchronized void startSession(String checkoutId, int volumeMl, String deviceId) {
        if (mState == State.ACTIVE) {
            Log.w(TAG, "[SESSION] startSession() — sessão já ACTIVE, reaproveitando session_id existente");
            if (mCallback != null && mSessionId != null) {
                mCallback.onSessionStarted(mSessionId, mCheckoutId != null ? mCheckoutId : checkoutId);
            }
            return;
        }
        if (mState != State.IDLE && mState != State.FAILED && mState != State.COMPLETED) {
            Log.w(TAG, "[SESSION] startSession() ignorado — estado=" + mState);
            return;
        }

        mCheckoutId = checkoutId;
        mVolumeMl   = volumeMl;
        mDeviceId   = deviceId;
        mSessionId  = null;
        mCommandId  = null;
        mState      = State.STARTING;

        Log.i(TAG, "[SESSION] Iniciando sessão | checkout=" + checkoutId
                + " | vol=" + volumeMl + "ml | device=" + deviceId);
        Log.i(TAG, "[SYNC] Sessão iniciada com guard-band já expirado (verificado por PagamentoConcluido)");

        Map<String, String> body = new HashMap<>();
        body.put("checkout_id", checkoutId);
        body.put("volume_ml",   String.valueOf(volumeMl));
        body.put("qtd_ml",      String.valueOf(volumeMl));
        body.put("device_id",   deviceId);
        body.put("android_id",  deviceId);

        mApiHelper.sendPost(body, "start_session.php", new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "[SESSION] start_session falhou (rede): " + e.getMessage());
                // Fallback: usa start_sale.php como alternativa
                chamarStartSaleFallback(checkoutId, volumeMl, deviceId);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = response.body() != null ? response.body().string() : "";
                Log.i(TAG, "[SESSION] HTTP " + response.code() + " | start_session.php");
                Log.d(TAG, "[SESSION] Response body: " + (bodyStr.length() > 200 ? bodyStr.substring(0, 200) + "..." : bodyStr));

                if (!response.isSuccessful()) {
                    Log.w(TAG, "[SESSION] ❌ HTTP " + response.code() + " — fallback para start_sale.php");
                    chamarStartSaleFallback(checkoutId, volumeMl, deviceId);
                    return;
                }

                try {
                    JSONObject json = new JSONObject(bodyStr);
                    String sessionId = json.optString("session_id", null);
                    String status    = json.optString("status", "");

                    if (sessionId == null || sessionId.isEmpty()) {
                        // Tenta extrair de start_sale response (campo alternativo)
                        sessionId = json.optString("ble_session_id", null);
                    }

                    if (sessionId != null && !sessionId.isEmpty()) {
                        final String finalSessionId = sessionId;
                        Log.i(TAG, "[SESSION] ✅ HTTP 200 | session_id=" + finalSessionId + " | status=" + status);
                        mMainHandler.post(() -> {
                            synchronized (SessionManager.this) {
                                mSessionId = finalSessionId;
                                mState     = State.ACTIVE;
                                Log.i(TAG, "[SESSION] Estado → ACTIVE | session_id=" + finalSessionId);
                                if (mCallback != null) {
                                    mCallback.onSessionStarted(finalSessionId, checkoutId);
                                }
                            }
                        });
                    } else {
                        // Sem session_id na resposta — gera um local como fallback
                        Log.w(TAG, "[SESSION] HTTP 200 mas session_id ausente → fallback local");
                        gerarSessionIdLocal(checkoutId);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[SESSION] Erro ao parsear JSON: " + e.getMessage());
                    gerarSessionIdLocal(checkoutId);
                }
            }
        });
    }

    /**
     * Finaliza a sessão com sucesso após DONE do ESP32.
     *
     * @param mlReal       Volume real dispensado (do ESP32)
     * @param totalPulsos  Total de pulsos do sensor de fluxo
     */
    public synchronized void finishSession(int mlReal, int totalPulsos) {
        if (mState != State.ACTIVE) {
            Log.w(TAG, "[SESSION] finishSession() ignorado — estado=" + mState);
            return;
        }
        mState = State.FINISHING;

        Log.i(TAG, "[SESSION] Finalizando | session=" + mSessionId
                + " | ml_real=" + mlReal + " | pulsos=" + totalPulsos);

        Map<String, String> body = new HashMap<>();
        body.put("session_id",    mSessionId != null ? mSessionId : "");
        body.put("checkout_id",   mCheckoutId);
        body.put("ml_real",       String.valueOf(mlReal));
        body.put("ml_dispensado", String.valueOf(mlReal));
        body.put("total_pulsos",  String.valueOf(totalPulsos));
        body.put("device_id",     mDeviceId);
        body.put("android_id",    mDeviceId);
        body.put("status",        "completed");
        if (mCommandId != null) body.put("command_id", mCommandId);

        final String sessionIdSnapshot = mSessionId;
        final int    mlRealSnapshot    = mlReal;

        // Tenta finish_session.php primeiro, depois finish_sale.php como fallback
        mApiHelper.sendPost(body, "finish_session.php", new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[SESSION] finish_session falhou (rede) — tentando finish_sale.php");
                chamarFinishSaleFallback(body, sessionIdSnapshot, mlRealSnapshot);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = response.body() != null ? response.body().string() : "";
                Log.i(TAG, "[SESSION] finish_session HTTP " + response.code());
                if (!response.isSuccessful()) {
                    Log.w(TAG, "[SESSION] finish_session HTTP " + response.code()
                            + " — tentando finish_sale.php");
                    chamarFinishSaleFallback(body, sessionIdSnapshot, mlRealSnapshot);
                    return;
                }
                mMainHandler.post(() -> {
                    synchronized (SessionManager.this) {
                        mState = State.COMPLETED;
                        Log.i(TAG, "[SESSION] FINISHED " + sessionIdSnapshot);
                        if (mCallback != null) {
                            mCallback.onSessionFinished(sessionIdSnapshot, mlRealSnapshot);
                        }
                    }
                });
            }
        });
    }

    /**
     * Registra falha na sessão (timeout BLE, ERROR:BUSY, etc.).
     *
     * @param motivo     Descrição do erro
     * @param mlParcial  Volume parcialmente liberado (0 se nenhum)
     */
    public synchronized void failSession(String motivo, int mlParcial) {
        if (mState == State.COMPLETED || mState == State.FAILED) {
            Log.w(TAG, "[SESSION] failSession() ignorado — estado=" + mState);
            return;
        }
        mState = State.FAILED;

        Log.e(TAG, "[SESSION] FAILED | session=" + mSessionId
                + " | motivo=" + motivo + " | ml_parcial=" + mlParcial);

        Map<String, String> body = new HashMap<>();
        body.put("session_id",  mSessionId != null ? mSessionId : "");
        body.put("checkout_id", mCheckoutId);
        body.put("motivo",      motivo);
        body.put("error_msg",   motivo);
        body.put("ml_liberado", String.valueOf(mlParcial));
        body.put("ml_parcial",  String.valueOf(mlParcial));
        body.put("device_id",   mDeviceId);
        body.put("android_id",  mDeviceId);
        body.put("status",      "error");
        if (mCommandId != null) body.put("command_id", mCommandId);

        final String sessionIdSnapshot = mSessionId;

        // Tenta fail_session.php, depois fail_sale.php como fallback
        mApiHelper.sendPost(body, "fail_session.php", new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[SESSION] fail_session falhou — tentando fail_sale.php");
                chamarFailSaleFallback(body, sessionIdSnapshot, motivo);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.i(TAG, "[SESSION] fail_session HTTP " + response.code());
                if (!response.isSuccessful()) {
                    chamarFailSaleFallback(body, sessionIdSnapshot, motivo);
                    return;
                }
                mMainHandler.post(() -> {
                    if (mCallback != null) {
                        mCallback.onSessionFailed(sessionIdSnapshot, motivo);
                    }
                });
            }
        });
    }

    /**
     * Reseta o estado para IDLE.
     * Chamar após navegar para Home.
     */
    public synchronized void reset() {
        Log.i(TAG, "[SESSION] reset() | estado anterior=" + mState);
        mState      = State.IDLE;
        mSessionId  = null;
        mCheckoutId = null;
        mDeviceId   = null;
        mCommandId  = null;
        mVolumeMl   = 0;
        mIsLocalFallback = false;
        mApiRetryAttempts = 0;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Fallbacks
    // ═════════════════════════════════════════════════════════════════════════

    private void chamarStartSaleFallback(String checkoutId, int volumeMl, String deviceId) {
        Log.i(TAG, "[SESSION] Fallback → start_sale.php após start_session.php falhar");
        Map<String, String> body = new HashMap<>();
        body.put("checkout_id", checkoutId);
        body.put("volume_ml",   String.valueOf(volumeMl));
        body.put("qtd_ml",      String.valueOf(volumeMl));
        body.put("device_id",   deviceId);
        body.put("android_id",  deviceId);

        mApiHelper.sendPost(body, "start_sale.php", new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "[SESSION] start_sale fallback também falhou: " + e.getMessage());
                gerarSessionIdLocal(checkoutId);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = response.body() != null ? response.body().string() : "";
                Log.i(TAG, "[SESSION] start_sale fallback HTTP " + response.code());
                try {
                    JSONObject json = new JSONObject(bodyStr);
                    String sessionId = json.optString("session_id",
                            json.optString("ble_session_id", null));
                    if (sessionId != null && !sessionId.isEmpty()) {
                        final String sid = sessionId;
                        mMainHandler.post(() -> {
                            synchronized (SessionManager.this) {
                                mSessionId = sid;
                                mState     = State.ACTIVE;
                                Log.i(TAG, "[SESSION] ACTIVE (fallback) | session_id=" + sid);
                                if (mCallback != null) mCallback.onSessionStarted(sid, checkoutId);
                            }
                        });
                    } else {
                        gerarSessionIdLocal(checkoutId);
                    }
                } catch (Exception e) {
                    gerarSessionIdLocal(checkoutId);
                }
            }
        });
    }

    private void chamarFinishSaleFallback(Map<String, String> body,
                                          String sessionId, int mlReal) {
        mApiHelper.sendPost(body, "finish_sale.php", new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "[SESSION] finish_sale fallback falhou: " + e.getMessage());
                mMainHandler.post(() -> {
                    synchronized (SessionManager.this) {
                        mState = State.COMPLETED;
                        if (mCallback != null) {
                            mCallback.onSessionFinished(sessionId, mlReal);
                        }
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.i(TAG, "[SESSION] finish_sale fallback HTTP " + response.code());
                mMainHandler.post(() -> {
                    synchronized (SessionManager.this) {
                        mState = State.COMPLETED;
                        if (mCallback != null) {
                            mCallback.onSessionFinished(sessionId, mlReal);
                        }
                    }
                });
            }
        });
    }

    private void chamarFailSaleFallback(Map<String, String> body,
                                        String sessionId, String motivo) {
        mApiHelper.sendPost(body, "fail_sale.php", new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "[SESSION] fail_sale fallback falhou: " + e.getMessage());
                mMainHandler.post(() -> {
                    if (mCallback != null) {
                        mCallback.onSessionFailed(sessionId, motivo);
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.i(TAG, "[SESSION] fail_sale fallback HTTP " + response.code());
                mMainHandler.post(() -> {
                    if (mCallback != null) {
                        mCallback.onSessionFailed(sessionId, motivo);
                    }
                });
            }
        });
    }

    /**
     * Gera um SESSION_ID local como último recurso quando a API não responde.
     * v2.3.0 FIX: Apenas após MAX_API_RETRY tentativas.
     *
     * IMPORTANTE: SESSION_ID local (SES_LOCAL_*) é incompatível com ESP32 em produção.
     * Use apenas em modo offline - em produção, forçar recusa de venda sem API confirmada.
     */
    private void gerarSessionIdLocal(String checkoutId) {
        mApiRetryAttempts++;
        Log.w(TAG, "[SESSION] FALLBACK LOCAL → Tentativa #" + mApiRetryAttempts + "/" + MAX_API_RETRY);
        if (mApiRetryAttempts < MAX_API_RETRY) {
            Log.w(TAG, "[SESSION] Agendando retry de API em 2s (máx " + MAX_API_RETRY + " tentativas)");
            mMainHandler.postDelayed(() -> {
                if (mState == State.STARTING) {
                    Log.w(TAG, "[SESSION] Retry #" + mApiRetryAttempts + ": reiniciando startSession()");
                    startSession(checkoutId, mVolumeMl, mDeviceId);
                }
            }, 2_000L);
            return;
        }

        // Último recurso: gerar SES_LOCAL
        String localId = "SES_LOCAL_" + checkoutId + "_"
                + Long.toHexString(System.currentTimeMillis()).toUpperCase();
        Log.w(TAG, "[SESSION] ⚠️  API indisponível após " + MAX_API_RETRY
                + " tentativas — GERANDO SESSION_ID LOCAL: " + localId);
        Log.e(TAG, "[SESSION] 🚫 AVISO CRÍTICO: SES_LOCAL_* é rejeitado pelo ESP32 em produção!");
        Log.e(TAG, "[SESSION] SERVE será BLOQUEADO por validação de segurança em PagamentoConcluido");

        mIsLocalFallback = true;
        mMainHandler.post(() -> {
            synchronized (SessionManager.this) {
                mSessionId = localId;
                mState     = State.ACTIVE;
                Log.i(TAG, "[SESSION] Estado → ACTIVE (local fallback, bloqueado em produção)");
                if (mCallback != null) mCallback.onSessionStarted(localId, checkoutId);
            }
        });
    }

    @Override
    public String toString() {
        return "SessionManager{state=" + mState
                + ", session=" + mSessionId
                + ", checkout=" + mCheckoutId + "}";
    }
}
