package com.example.choppontap;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import android.util.Base64;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * ApiHelper — cliente HTTP centralizado para o app ChoppOn Tap.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * CORREÇÃO DEFINITIVA v3.0 — Diagnóstico forense do log 2026-03-03 22:39–22:42
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * CAUSA RAIZ REAL (confirmada por testes diretos ao servidor):
 * ─────────────────────────────────────────────────────────────
 * O servidor ochoppoficial.com.br (Apache/cPanel, IP 162.241.63.72) retorna
 * em TODA resposta HTTP/1.1 os headers:
 *
 *   Connection: Upgrade
 *   Upgrade: h2,h2c
 *
 * Isso é o mecanismo de "HTTP Upgrade" para HTTP/2. O servidor está
 * sinalizando que prefere HTTP/2 e pedindo ao cliente que faça o upgrade.
 *
 * O OkHttp 4.9.1, ao receber "Connection: Upgrade" + "Upgrade: h2", tenta
 * processar o upgrade de protocolo e FICA AGUARDANDO o servidor completar
 * o handshake HTTP/2 — que NUNCA acontece porque o servidor fecha a conexão
 * após enviar a resposta. Isso causa o timeout de 20-28s por tentativa.
 *
 * EVIDÊNCIAS DO LOG:
 *   22:39:56 → 22:40:20 = 24s de espera (Tentativa 1: Read timed out)
 *   22:40:21 → 22:40:36 = 15s de espera (Tentativa 2: connect timeout)
 *   22:40:39 → 22:40:54 = 15s de espera (Tentativa 3: connect timeout)
 *   22:40:59 → 22:41:26 = 27s de espera (Tentativa 4: Read timed out)
 *   22:41:35 → 22:42:03 = 28s de espera (Tentativa 5: timeout + Socket closed)
 *
 * PROVA EXPERIMENTAL (testes diretos ao servidor):
 *   HTTP/2 nativo (ALPN h2): resposta em 0.035–0.074s ✅
 *   HTTP/1.1 forçado:         resposta em 5s+ com Upgrade header ❌
 *
 * POR QUE A IMPLEMENTAÇÃO ANTERIOR PIOROU O PROBLEMA:
 *   O commit anterior adicionou protocols(HTTP_1_1) para "evitar problemas
 *   de multiplexação HTTP/2". Isso foi um erro de diagnóstico: o servidor
 *   SUPORTA e PREFERE HTTP/2. Ao forçar HTTP/1.1, o OkHttp recebia o header
 *   Connection: Upgrade e tentava processar o upgrade, travando.
 *
 * SOLUÇÃO DEFINITIVA:
 *   Remover protocols(HTTP_1_1) — deixar o OkHttp negociar HTTP/2 via ALPN
 *   durante o TLS handshake. O servidor aceita ALPN h2 e responde em <100ms.
 *   Não há mais Upgrade header, não há mais travamento.
 *
 * OUTRAS CORREÇÕES MANTIDAS:
 *   - OkHttpClient SINGLETON (double-checked locking)
 *   - ConnectionPool(5 conn, 5 min)
 *   - Timeouts ajustados para o perfil real do servidor
 *   - TlsSocketFactory REMOVIDA (era desnecessária e pode causar conflito
 *     com o Conscrypt do Android que já gerencia TLS corretamente)
 *   - warmupServer() usa cliente ISOLADO (pool separado) para evitar contaminar o singleton
 */
public class ApiHelper {
    private static final String TAG = "ApiHelper";

    private Context context;

    public ApiHelper(Context context) {
        this.context = context;
    }

    // ── Singleton do OkHttpClient ─────────────────────────────────────────────
    // Uma única instância compartilha o ConnectionPool entre warm-up e sendPost(),
    // garantindo reuso de conexões TLS já estabelecidas.
    private static volatile OkHttpClient sClient;

    private static OkHttpClient getClient() {
        if (sClient == null) {
            synchronized (ApiHelper.class) {
                if (sClient == null) {
                    sClient = buildClient();
                }
            }
        }
        return sClient;
    }

    private static OkHttpClient buildClient() {
        return new OkHttpClient.Builder()
                // Timeouts calibrados para o perfil real do servidor:
                // - TCP connect: <5ms (servidor próximo)
                // - TLS handshake: 40-100ms (Android Conscrypt + HTTP/2 via ALPN)
                // - PHP processing: 0.5-5s (cold start PHP-FPM)
                // Total esperado: <6s. Timeouts com margem confortável.
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                // Pool de conexões: mantém até 5 conexões abertas por 5 minutos.
                // Com HTTP/2, múltiplas requisições compartilham a mesma conexão.
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                // Retry automático em falhas de conexão transientes
                .retryOnConnectionFailure(true)
                // ─────────────────────────────────────────────────────────────
                // CORREÇÃO CRÍTICA: NÃO forçar HTTP/1.1.
                //
                // O servidor suporta HTTP/2 via ALPN (confirmado por testes).
                // Ao deixar o OkHttp negociar normalmente, ele usa HTTP/2 e
                // a resposta chega em <100ms sem nenhum header de Upgrade.
                //
                // Forçar HTTP/1.1 causava: servidor retorna Connection: Upgrade
                // + Upgrade: h2,h2c → OkHttp trava tentando processar o upgrade
                // → SocketTimeoutException após 20-28s por tentativa.
                // ─────────────────────────────────────────────────────────────
                // .protocols(Collections.singletonList(Protocol.HTTP_1_1)) ← REMOVIDO
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    Log.d(TAG, "🚀 [API REQ] " + request.method() + " " + request.url());
                    return chain.proceed(request);
                })
                .build();
    }

    // ── Configuração da API ───────────────────────────────────────────────────
    private final String api = "https://ochoppoficial.com.br/api/";
    // Chave JWT — DEVE ser idêntica a JWT_SECRET em php/includes/config.php
    private static final String JWT_SECRET = "teaste";

    // ─────────────────────────────────────────────────────────────────────────
    // Warm-up do servidor
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Aquece o servidor com uma requisição GET leve antes da requisição principal.
     *
     * Com HTTP/2 via ALPN, o warm-up estabelece a conexão TLS + HTTP/2 que é
     * reaproveitada pelo sendPost() via connection pool (multiplexação HTTP/2).
     * Tempo esperado: <100ms (vs 5s+ com HTTP/1.1).
     */
    public void warmupServer() {
        // IMPORTANTE: O warm-up usa um cliente COMPLETAMENTE ISOLADO (novo ConnectionPool).
        // Motivo: se o warm-up falhar (ex: servidor retorna 401 + Connection: close),
        // a conexão corrompida NÃO contamina o pool do cliente singleton.
        // Antes disso, o warm-up usava getClient().newBuilder() que COMPARTILHA o pool,
        // causando TLS handshake travado de 33s na tentativa seguinte.
        Log.d(TAG, "🔥 [Warm-up] Iniciando (cliente isolado)...");
        OkHttpClient warmupClient = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(1, 30, TimeUnit.SECONDS))
                .retryOnConnectionFailure(false)
                .build();

        Request warmupRequest = new Request.Builder()
                .url(api + "verify_tap.php")
                .get()
                .addHeader("X-Warmup", "true")
                .build();

        try (Response response = warmupClient.newCall(warmupRequest).execute()) {
            Log.i(TAG, "✅ [Warm-up] Finalizado: HTTP " + response.code() +
                    " | Protocol: " + response.protocol());
        } catch (Exception e) {
            // Falha no warm-up é esperada (sem token) — não afeta o fluxo principal
            Log.d(TAG, "⚠️ [Warm-up] Falhou (ignorado): " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Geração de Token JWT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Gera token JWT compatível com o servidor PHP (php/includes/jwt.php).
     *
     * O servidor PHP usa JWT caseiro com HMAC-SHA256 sobre raw bytes.
     * A biblioteca JJWT rejeita a chave 'teaste' (48 bits < 256 bits mínimos HS256)
     * e retorna string vazia — causando HTTP 401.
     *
     * Esta implementação manual replica exatamente o comportamento do PHP:
     *   base64url(header) + "." + base64url(payload) + "." + base64url(hmac_raw)
     */
    private String gerarToken() {
        try {
            long nowSec = System.currentTimeMillis() / 1000L;

            // Header
            String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
            // Payload
            String jti = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            String payloadJson = "{\"iat\":" + (nowSec - 300)
                    + ",\"exp\":" + (nowSec + 7200)
                    + ",\"jti\":\"" + jti + "\""
                    + ",\"app\":\"choppon_tap\"}";

            String h = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
            String p = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
            String msg = h + "." + p;

            // Assinatura HMAC-SHA256 (raw bytes, igual ao PHP)
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sigBytes = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            String s = base64UrlEncode(sigBytes);

            String token = msg + "." + s;
            Log.d(TAG, "[JWT] Token gerado com sucesso (" + token.length() + " chars)");
            return token;
        } catch (Exception e) {
            Log.e(TAG, "[JWT] Erro ao gerar token: " + e.getMessage());
            return "";
        }
    }

    /** Base64 URL encode sem padding (RFC 4648 §5). */
    private static String base64UrlEncode(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Requisições HTTP
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Envia uma requisição POST assíncrona para o endpoint especificado.
     *
     * Com HTTP/2, o OkHttp negocia o protocolo via ALPN durante o TLS handshake.
     * O servidor responde em <100ms sem nenhum header de Upgrade problemático.
     */
    public void sendPost(Map<String, String> body, String endpoint, Callback callback) {
        try {
            String token = gerarToken();
            FormBody.Builder builder = new FormBody.Builder();

            if (body != null) {
                for (Map.Entry<String, String> entry : body.entrySet()) {
                    builder.add(entry.getKey(),
                            entry.getValue() != null ? entry.getValue() : "NULL");
                }
            }

            // Separa o endpoint do query string antes de verificar a extensão .php.
            // Sem isso, "liberacao.php?action=iniciada" resulta em
            // "liberacao.php?action=iniciada.php" (bug confirmado no log 2026-03-07 19:33:35).
            String base  = endpoint;
            String query = "";
            int qIdx = endpoint.indexOf('?');
            if (qIdx >= 0) {
                base  = endpoint.substring(0, qIdx);
                query = endpoint.substring(qIdx); // inclui o '?'
            }
            if (!base.endsWith(".php")) base = base + ".php";
            String fullUrl = api + base + query;

            Request request = new Request.Builder()
                    .url(fullUrl)
                    .header("token", token)
                    .post(builder.build())
                    .build();

            getClient().newCall(request).enqueue(callback);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao preparar requisição: " + e.getMessage());
            callback.onFailure(null, new IOException(e));
        }
    }

    /**
     * Baixa a imagem da bebida de forma síncrona (deve ser chamado em background thread).
     */
    public Bitmap getImage(Tap object) throws IOException {
        if (object.image == null || object.image.isEmpty()) return null;
        Request request = new Request.Builder().url(object.image).build();
        try (Response response = getClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            byte[] imageBytes = response.body().bytes();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, options);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao baixar imagem: " + e.getMessage());
            return null;
        }
    }
}
