package com.example.choppontap;

import android.util.Log;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.UUID;

/**
 * BleCommand — Modelo de um comando BLE v2.0 para ESP32 FRANQ.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * PROTOCOLO v2.0 — FORMATO DE ENVIO
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  SERVE:   SERVE|<ml>|<CMD_ID>|<SESSION_ID>|<HMAC>
 *  AUTH:    AUTH|<CMD_ID>|<SESSION_ID>|<HMAC>
 *  STOP:    STOP|<CMD_ID>|<SESSION_ID>|<HMAC>
 *  STATUS:  STATUS|<CMD_ID>
 *  PING:    PING|<CMD_ID>
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CICLO DE VIDA DO COMANDO
 * ═══════════════════════════════════════════════════════════════════════
 *
 *   QUEUED → SENT → ACKED → DONE
 *                         ↘ ERROR
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CÓDIGOS DE ERRO DO ESP32 (protocolo v2.0)
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  ERROR_SESSION_MISMATCH   — SESSION_ID não corresponde ao esperado
 *  ERROR_NOT_AUTHENTICATED  — Comando enviado antes de AUTH:OK
 *  ERROR_VOLUME_EXCEEDED    — Volume solicitado excede o máximo permitido
 *  ERROR_TIMEOUT            — Timeout na operação de dispensação
 *  ERROR_BUSY               — ESP32 ocupado com outro comando
 *  ERROR_WATCHDOG           — Watchdog de hardware disparou (reset)
 *  ERROR_HMAC_INVALID       — Token HMAC inválido (possível replay attack)
 *  ERROR_DUPLICATE          — CMD_ID já processado (anti-replay)
 *  ERROR_QUEUE_FULL         — Fila interna do ESP32 cheia
 *  ERROR_VALVE_STUCK        — Válvula travada (falha mecânica)
 *
 * ═══════════════════════════════════════════════════════════════════════
 * ALERTAS DO ESP32 (protocolo v2.0)
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  WARN_FLOW_TIMEOUT        — Sem fluxo detectado (barril vazio)
 *  WARN_VOLUME_EXCEEDED     — Volume real excedeu o solicitado
 *
 * @version 2.0.0
 * @since   2026-03-22
 */
public class BleCommand {

    private static final String TAG = "BLE_COMMAND";

    // ═══════════════════════════════════════════════════════════════════════
    // Chave HMAC — DEVE ser idêntica à HMAC_SECRET_KEY no config.h do ESP32
    // ═══════════════════════════════════════════════════════════════════════
    private static final String HMAC_SECRET_KEY  = "ChoppFranquia2024SecretKey!@#";
    private static final String HMAC_ALGORITHM   = "HmacSHA256";
    private static final int    HMAC_TOKEN_LENGTH = 16;

    // ── Tipos de comando ──────────────────────────────────────────────────────
    public enum Type {
        /** AUTH|<CMD_ID>|<SESSION_ID>|<HMAC> — autenticação HMAC */
        AUTH,
        /** SERVE|<ml>|<CMD_ID>|<SESSION_ID>|<HMAC> — liberação de chopp */
        SERVE,
        /** STOP|<CMD_ID>|<SESSION_ID>|<HMAC> — parar dispensação */
        STOP,
        /** STATUS|<CMD_ID> — consultar estado do ESP32 */
        STATUS,
        /** PING|<CMD_ID> — heartbeat */
        PING
    }

    // ── Estados do ciclo de vida ──────────────────────────────────────────────
    public enum State {
        QUEUED,   // Na fila, aguardando envio
        SENT,     // Enviado via BLE, aguardando ACK
        ACKED,    // ACK recebido, aguardando DONE
        DONE,     // DONE recebido — operação concluída com sucesso
        ERROR     // Erro (timeout, BUSY, watchdog, HMAC inválido, etc.)
    }

    public static final String CMD_READY = "READY";
    public static final String RESP_READY_OK = "READY_OK";

    // ── Códigos de erro do protocolo v2.0 ────────────────────────────────────
    public static final class ErrorCode {
        public static final String SESSION_MISMATCH   = "SESSION_MISMATCH";
        public static final String NOT_AUTHENTICATED  = "NOT_AUTHENTICATED";
        public static final String VOLUME_EXCEEDED    = "VOLUME_EXCEEDED";
        public static final String TIMEOUT            = "TIMEOUT";
        public static final String BUSY               = "BUSY";
        public static final String WATCHDOG           = "WATCHDOG";
        public static final String HMAC_INVALID       = "HMAC_INVALID";
        public static final String DUPLICATE          = "DUPLICATE";
        public static final String QUEUE_FULL         = "QUEUE_FULL";
        public static final String VALVE_STUCK        = "VALVE_STUCK";
        public static final String BLE_NOT_READY      = "BLE_NOT_READY";
        public static final String BLE_WRITE_FAILED   = "BLE_WRITE_FAILED";
        public static final String AUTH_FAIL          = "AUTH_FAIL";

        private ErrorCode() {} // Classe utilitária — não instanciar
    }

    // ── Códigos de alerta do protocolo v2.0 ──────────────────────────────────
    public static final class WarnCode {
        public static final String FLOW_TIMEOUT    = "FLOW_TIMEOUT";
        public static final String VOLUME_EXCEEDED = "VOLUME_EXCEEDED";

        private WarnCode() {} // Classe utilitária — não instanciar
    }

    // ── Campos do comando ─────────────────────────────────────────────────────
    public final Type   type;
    public final String commandId;   // ID único de 8 chars (ex: "A1B2C3D4")
    public final String sessionId;   // SESSION_ID da venda (ex: "SES_8472ABCD")
    public final int    volumeMl;    // Volume em ml (apenas para SERVE)
    public final long   timestamp;   // Timestamp de criação (ms)

    public State  state        = State.QUEUED;
    public int    mlReal       = 0;    // Volume real confirmado pelo ESP32 no DONE
    public int    retryCount   = 0;    // Número de tentativas de reenvio
    public String errorMessage = null; // Mensagem de erro se state == ERROR

    // ── Constantes ────────────────────────────────────────────────────────────
    public static final int MAX_RETRIES = 3;

    // ── Construtor principal ──────────────────────────────────────────────────
    public BleCommand(Type type, String commandId, String sessionId, int volumeMl) {
        this.type      = type;
        this.commandId = commandId;
        this.sessionId = sessionId;
        this.volumeMl  = volumeMl;
        this.timestamp = System.currentTimeMillis();
    }

    // ── Construtor de conveniência (gera CMD_ID automaticamente) ─────────────
    public BleCommand(Type type, String sessionId, int volumeMl) {
        this(type, generateCmdId(), sessionId, volumeMl);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Geração de Token HMAC-SHA256
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Gera um token HMAC-SHA256 para autenticação do comando.
     *
     * O token é calculado sobre: "<TIPO>|<CMD_ID>|<SESSION_ID>"
     * Apenas os primeiros 16 caracteres hexadecimais são usados para
     * manter o pacote BLE dentro do limite de 512 bytes.
     *
     * IMPORTANTE: A chave HMAC_SECRET_KEY deve ser idêntica à definida
     * no arquivo config.h do firmware ESP32 (HMAC_SECRET_KEY).
     *
     * @return Token HMAC truncado em maiúsculas (16 chars hex)
     */
    public String generateHmacToken() {
        try {
            String payload = type.name() + "|" + commandId + "|" + sessionId;
            SecretKeySpec keySpec = new SecretKeySpec(
                    HMAC_SECRET_KEY.getBytes("UTF-8"), HMAC_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(payload.getBytes("UTF-8"));

            // Converte para hex e trunca
            StringBuilder sb = new StringBuilder();
            for (byte b : hmacBytes) {
                sb.append(String.format("%02x", b));
            }
            String token = sb.toString().substring(0, HMAC_TOKEN_LENGTH).toUpperCase();

            Log.d(TAG, "[HMAC] payload='" + payload + "' token='" + token + "'");
            return token;

        } catch (Exception e) {
            Log.e(TAG, "[HMAC] Erro ao gerar token: " + e.getMessage());
            return "0".repeat(HMAC_TOKEN_LENGTH);
        }
    }

    /**
     * Gera um CMD_ID único de 8 caracteres alfanuméricos maiúsculos.
     */
    public static String generateCmdId() {
        return UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase();
    }

    /**
     * Gera o comando READY para inicializar emissões de SERVE.
     * Formato: READY|<cmdId>|<sessionId>
     */
    public static String buildReady(String cmdId, String sessionId) {
        if (cmdId == null) cmdId = generateCmdId();
        if (sessionId == null) sessionId = "";
        return CMD_READY + "|" + cmdId + "|" + sessionId;
    }

    /**
     * Retorna o cmdId de mensagens em formato PIPE-separated.
     */
    public static String parseCmdId(String message) {
        if (message == null) return null;
        String[] parts = message.split("\\|");
        return parts.length > 1 ? parts[1] : null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Serialização para BLE — Protocolo v2.0
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Gera a string de comando BLE para envio ao ESP32 (protocolo v2.0).
     *
     * Formatos:
     *   SERVE:   SERVE|<ml>|<CMD_ID>|<SESSION_ID>
     *   AUTH:    AUTH|<CMD_ID>|<SESSION_ID>|<HMAC>
     *   STOP:    STOP|<CMD_ID>|<SESSION_ID>|<HMAC>
     *   STATUS:  STATUS|<CMD_ID>
     *   PING:    PING|<CMD_ID>
     *
     * Compatibilidade retroativa:
     *   O firmware ESP32 v2.0 aceita tanto o formato novo (com HMAC para AUTH/STOP)
     *   quanto o formato legado (SERVE|<ml>|ID=<id>|SESSION=<session>).
     *   Para SERVE, HMAC não é validado, então usa-se sem HMAC.
     *   Recomenda-se sempre usar o formato novo para segurança máxima.
     */
    public String toBleString() {
        String hmac = generateHmacToken();

        switch (type) {
            case SERVE:
                // Protocolo v2.0: SERVE|<ml>|<CMD_ID>|<SESSION_ID>
                return "SERVE|" + volumeMl + "|" + commandId + "|" + sessionId;

            case AUTH:
                // Protocolo v2.0: AUTH|<CMD_ID>|<SESSION_ID>|<HMAC>
                return "AUTH|" + commandId + "|" + sessionId + "|" + hmac;

            case STOP:
                // Protocolo v2.0: STOP|<CMD_ID>|<SESSION_ID>|<HMAC>
                return "STOP|" + commandId + "|" + sessionId + "|" + hmac;

            case STATUS:
                // Sem HMAC — comando de consulta
                return "STATUS|" + commandId;

            case PING:
                // Sem HMAC — heartbeat
                return "PING|" + commandId;

            default:
                return type.name() + "|" + commandId;
        }
    }

    /**
     * Gera string no formato legado para compatibilidade com firmware < v2.0.
     * Use apenas durante período de transição.
     *
     * @deprecated Use toBleString() para protocolo v2.0
     */
    @Deprecated
    public String toLegacyBleString() {
        switch (type) {
            case SERVE:
                return "SERVE|" + volumeMl + "|ID=" + commandId + "|SESSION=" + sessionId;
            case AUTH:
                // ESP32_PIN removido — autenticação agora usa HMAC-SHA256 via BluetoothServiceIndustrial.gerarAuth()
                return "$AUTH:HMAC_AUTH:" + commandId;
            case STOP:
                return "$STOP:" + commandId;
            case STATUS:
                return "$STATUS:" + commandId;
            case PING:
                return "$PING:" + commandId;
            default:
                return "$" + type.name() + ":" + commandId;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════════════

    /** Retorna true se o comando pode ser reenviado (não excedeu MAX_RETRIES). */
    public boolean canRetry() {
        return retryCount < MAX_RETRIES;
    }

    /** Retorna true se o comando está em estado terminal (DONE ou ERROR). */
    public boolean isTerminal() {
        return state == State.DONE || state == State.ERROR;
    }

    /** Retorna true se o comando expirou (mais de 30s sem resposta). */
    public boolean isExpired() {
        return (System.currentTimeMillis() - timestamp) > 30_000L;
    }

    @Override
    public String toString() {
        return "BleCommand{"
                + "type=" + type
                + ", id=" + commandId
                + ", session=" + sessionId
                + ", vol=" + volumeMl + "ml"
                + ", state=" + state
                + ", retry=" + retryCount
                + (errorMessage != null ? ", error=" + errorMessage : "")
                + "}";
    }
}
