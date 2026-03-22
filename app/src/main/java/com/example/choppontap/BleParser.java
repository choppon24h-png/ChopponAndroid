package com.example.choppontap;

import android.util.Log;

/**
 * BleParser — Parser centralizado de respostas BLE do ESP32 v2.0 FRANQ.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * PROTOCOLO SUPORTADO v2.0 (FONTE DA VERDADE)
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  OPERACIONAIS:
 *    ✔ ACK|<CMD_ID>
 *    ✔ DONE|<CMD_ID>|<ML_REAL>|<SESSION_ID>
 *    ✔ PONG|<CMD_ID>
 *    ✔ VP:<ML_PARCIAL>
 *
 *  AUTENTICAÇÃO:
 *    ✔ AUTH:OK
 *    ✔ AUTH:FAIL
 *    ✔ AUTH_OK|<CMD_ID>|<SESSION_ID>
 *    ✔ AUTH_FAIL|<CMD_ID>|<SESSION_ID>
 *
 *  ERROS:
 *    ✔ ERROR:SESSION_MISMATCH
 *    ✔ ERROR:NOT_AUTHENTICATED
 *    ✔ ERROR:VOLUME_EXCEEDED
 *    ✔ ERROR:TIMEOUT
 *    ✔ ERROR:BUSY
 *    ✔ ERROR:WATCHDOG / ERROR:WDG
 *    ✔ ERROR:HMAC_INVALID
 *    ✔ ERROR:DUPLICATE
 *    ✔ ERROR:QUEUE_FULL
 *    ✔ ERROR:VALVE_STUCK
 *
 *  ALERTAS (novos no v2.0):
 *    ✔ WARN:FLOW_TIMEOUT      — barril vazio / sem fluxo
 *    ✔ WARN:VOLUME_EXCEEDED   — volume real excedeu o solicitado
 *
 *  VÁLVULA:
 *    ✔ VALVE:OPEN
 *    ✔ VALVE:CLOSED
 *
 *  ESTADOS:
 *    ✔ STATUS:IDLE
 *    ✔ STATUS:RUNNING
 *    ✔ STATUS:ERROR
 *    ✔ STATUS:READY
 *    ✔ STATUS:BUSY
 *
 *  LITERAIS:
 *    ✔ READY
 *    ✔ BUSY
 *    ✔ DUPLICATE / ML:DUPLICATE
 *    ✔ QUEUE:FULL
 *    ✔ ML:ACK (compatibilidade legada)
 *
 * @version 2.0.0
 * @since   2026-03-22
 */
public class BleParser {

    private static final String TAG = "BLE_PARSER";

    public enum MessageType {
        // ── Operacionais ─────────────────────────────────────────────────────
        ACK,
        DONE,
        PONG,
        VP,

        // ── Autenticação ─────────────────────────────────────────────────────
        AUTH_OK,
        AUTH_FAIL,

        // ── Erros do ESP32 v2.0 ──────────────────────────────────────────────
        ERROR_SESSION_MISMATCH,
        ERROR_NOT_AUTHENTICATED,
        ERROR_VOLUME_EXCEEDED,      // NOVO v2.0
        ERROR_TIMEOUT,
        ERROR_BUSY,
        ERROR_WATCHDOG,
        ERROR_HMAC_INVALID,         // NOVO v2.0 — token HMAC inválido
        ERROR_DUPLICATE,            // NOVO v2.0 — CMD_ID já processado
        ERROR_QUEUE_FULL,
        ERROR_VALVE_STUCK,          // NOVO v2.0 — válvula travada

        // ── Alertas operacionais (NOVO v2.0) ──────────────────────────────────
        WARN_FLOW_TIMEOUT,          // Barril vazio / sem fluxo detectado
        WARN_VOLUME_EXCEEDED,       // Volume real excedeu o solicitado

        // ── Válvula ───────────────────────────────────────────────────────────
        VALVE_OPEN,
        VALVE_CLOSED,

        // ── Estados ───────────────────────────────────────────────────────────
        STATUS_IDLE,
        STATUS_RUNNING,
        STATUS_ERROR,
        STATUS_READY,
        STATUS_BUSY,

        // ── Fila ─────────────────────────────────────────────────────────────
        QUEUE_FULL,

        // ── Duplicidade ───────────────────────────────────────────────────────
        DUPLICATE,

        // ── Desconhecido ─────────────────────────────────────────────────────
        UNKNOWN
    }

    // ── Resultado do parse ────────────────────────────────────────────────────
    public static class ParsedMessage {
        public final MessageType type;
        public final String      raw;
        public final String      commandId;
        public final String      sessionId;
        public final int         mlReal;
        public final String      warnType;   // NOVO v2.0 — tipo do alerta WARN:*

        private ParsedMessage(MessageType type, String raw,
                              String commandId, String sessionId,
                              int mlReal, String warnType) {
            this.type      = type;
            this.raw       = raw;
            this.commandId = commandId;
            this.sessionId = sessionId;
            this.mlReal    = mlReal;
            this.warnType  = warnType;
        }

        // Construtor sem warnType (compatibilidade)
        private ParsedMessage(MessageType type, String raw,
                              String commandId, String sessionId, int mlReal) {
            this(type, raw, commandId, sessionId, mlReal, null);
        }

        /** Retorna true se é um alerta operacional (WARN:*). */
        public boolean isWarning() {
            return type == MessageType.WARN_FLOW_TIMEOUT
                    || type == MessageType.WARN_VOLUME_EXCEEDED;
        }

        /** Retorna true se é um erro do ESP32. */
        public boolean isError() {
            return type.name().startsWith("ERROR_");
        }

        @Override
        public String toString() {
            return String.format(
                    "ParsedMessage{type=%s, cmd=%s, session=%s, ml=%d, warn=%s, raw=[%s]}",
                    type, commandId, sessionId, mlReal,
                    (warnType != null ? warnType : "-"), raw);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Parser principal
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Faz o parse de uma string recebida via BLE do ESP32 v2.0.
     *
     * Implementação resiliente com split por pipe "|" e fallback seguro.
     * Compatível com protocolo v2.0 (HMAC) e legado v1.x.
     *
     * @param raw String bruta recebida via BLE
     * @return    ParsedMessage com tipo e campos extraídos
     */
    public static ParsedMessage parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new ParsedMessage(MessageType.UNKNOWN, "", null, null, 0);
        }

        String s = raw.trim();
        String[] parts = s.split("\\|");

        // ── 1. Mensagens baseadas em PIPE (ACK, DONE, PONG) ──────────────────

        if (s.startsWith("ACK|")) {
            return new ParsedMessage(MessageType.ACK, s,
                    extractCmdId(parts, 1), null, 0);
        }

        if (s.startsWith("DONE|") || s.equals("DONE")) {
            String cmdId     = extractCmdId(parts, 1);
            int    ml        = parseIntSafe(getPart(parts, 2));
            String sessionId = getPart(parts, 3);
            return new ParsedMessage(MessageType.DONE, s, cmdId, sessionId, ml);
        }

        if (s.startsWith("PONG|")) {
            return new ParsedMessage(MessageType.PONG, s,
                    extractCmdId(parts, 1), null, 0);
        }

        // ── 2. WARN: alertas operacionais (NOVO v2.0) ─────────────────────────

        if (s.startsWith("WARN:")) {
            String warnType = s.substring(5).trim();
            switch (warnType) {
                case "FLOW_TIMEOUT":
                    Log.w(TAG, "[WARN] Barril vazio ou sem fluxo detectado!");
                    return new ParsedMessage(MessageType.WARN_FLOW_TIMEOUT,
                            s, null, null, 0, warnType);
                case "VOLUME_EXCEEDED":
                    Log.w(TAG, "[WARN] Volume real excedeu o solicitado!");
                    return new ParsedMessage(MessageType.WARN_VOLUME_EXCEEDED,
                            s, null, null, 0, warnType);
                default:
                    Log.w(TAG, "[WARN] Alerta desconhecido: " + warnType);
                    return new ParsedMessage(MessageType.UNKNOWN,
                            s, null, null, 0, warnType);
            }
        }

        // ── 3. ERROR: erros do ESP32 ──────────────────────────────────────────

        if (s.startsWith("ERROR:")) {
            String err = s.substring(6).trim();
            switch (err) {
                case "SESSION_MISMATCH":
                    return new ParsedMessage(MessageType.ERROR_SESSION_MISMATCH,
                            s, null, null, 0);
                case "NOT_AUTHENTICATED":
                    return new ParsedMessage(MessageType.ERROR_NOT_AUTHENTICATED,
                            s, null, null, 0);
                case "VOLUME_EXCEEDED":
                    return new ParsedMessage(MessageType.ERROR_VOLUME_EXCEEDED,
                            s, null, null, 0);
                case "TIMEOUT":
                    return new ParsedMessage(MessageType.ERROR_TIMEOUT,
                            s, null, null, 0);
                case "BUSY":
                    return new ParsedMessage(MessageType.ERROR_BUSY,
                            s, null, null, 0);
                case "WATCHDOG":
                case "WDG":
                    return new ParsedMessage(MessageType.ERROR_WATCHDOG,
                            s, null, null, 0);
                case "HMAC_INVALID":
                    Log.e(TAG, "[SECURITY] Token HMAC inválido — possível replay attack!");
                    return new ParsedMessage(MessageType.ERROR_HMAC_INVALID,
                            s, null, null, 0);
                case "DUPLICATE":
                    return new ParsedMessage(MessageType.ERROR_DUPLICATE,
                            s, null, null, 0);
                case "QUEUE_FULL":
                    return new ParsedMessage(MessageType.ERROR_QUEUE_FULL,
                            s, null, null, 0);
                case "VALVE_STUCK":
                    Log.e(TAG, "[ERROR] Válvula travada — verificar hardware!");
                    return new ParsedMessage(MessageType.ERROR_VALVE_STUCK,
                            s, null, null, 0);
                default:
                    Log.e(TAG, "[ERROR] Código desconhecido: " + err);
                    return new ParsedMessage(MessageType.UNKNOWN, s, null, null, 0);
            }
        }

        // ── 4. STATUS: ────────────────────────────────────────────────────────

        if (s.startsWith("STATUS:")) {
            String st = s.substring(7).trim();
            switch (st) {
                case "IDLE":    return new ParsedMessage(MessageType.STATUS_IDLE,    s, null, null, 0);
                case "RUNNING": return new ParsedMessage(MessageType.STATUS_RUNNING, s, null, null, 0);
                case "ERROR":   return new ParsedMessage(MessageType.STATUS_ERROR,   s, null, null, 0);
                case "READY":   return new ParsedMessage(MessageType.STATUS_READY,   s, null, null, 0);
                case "BUSY":    return new ParsedMessage(MessageType.STATUS_BUSY,    s, null, null, 0);
                default:        return new ParsedMessage(MessageType.UNKNOWN,        s, null, null, 0);
            }
        }

        // ── 5. VALVE: ─────────────────────────────────────────────────────────

        if (s.startsWith("VALVE:")) {
            String v = s.substring(6).trim();
            if (v.equals("OPEN"))   return new ParsedMessage(MessageType.VALVE_OPEN,   s, null, null, 0);
            if (v.equals("CLOSED")) return new ParsedMessage(MessageType.VALVE_CLOSED, s, null, null, 0);
        }

        // ── 6. VP: progresso parcial ──────────────────────────────────────────

        if (s.startsWith("VP:")) {
            int ml = (int) Math.round(parseDoubleSafe(s.substring(3).trim()));
            return new ParsedMessage(MessageType.VP, s, null, null, ml);
        }

        // ── 7. AUTH ───────────────────────────────────────────────────────────

        if (s.startsWith("AUTH_OK|")) {
            return new ParsedMessage(MessageType.AUTH_OK, s,
                    getPart(parts, 1), getPart(parts, 2), 0);
        }
        if (s.startsWith("AUTH_FAIL|")) {
            return new ParsedMessage(MessageType.AUTH_FAIL, s,
                    getPart(parts, 1), getPart(parts, 2), 0);
        }
        if (s.startsWith("AUTH:")) {
            String authResult = s.substring(5).trim();
            if (authResult.equals("OK"))   return new ParsedMessage(MessageType.AUTH_OK,   s, null, null, 0);
            if (authResult.equals("FAIL")) return new ParsedMessage(MessageType.AUTH_FAIL, s, null, null, 0);
        }

        // ── 8. Literais fixos ─────────────────────────────────────────────────

        if (s.equals("QUEUE:FULL"))
            return new ParsedMessage(MessageType.QUEUE_FULL, s, null, null, 0);
        if (s.equals("READY"))
            return new ParsedMessage(MessageType.STATUS_READY, s, null, null, 0);
        if (s.equals("BUSY"))
            return new ParsedMessage(MessageType.STATUS_BUSY, s, null, null, 0);
        if (s.equals("DUPLICATE") || s.equals("ML:DUPLICATE"))
            return new ParsedMessage(MessageType.DUPLICATE, s, null, null, 0);

        // ── 9. Compatibilidade legada ─────────────────────────────────────────

        if (s.equals("ML:ACK"))
            return new ParsedMessage(MessageType.ACK, s, null, null, 0);

        // ── 10. Desconhecido ──────────────────────────────────────────────────

        Log.d(TAG, "[PARSE] Mensagem não reconhecida: [" + s + "]");
        return new ParsedMessage(MessageType.UNKNOWN, s, null, null, 0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Utilitários internos
    // ═══════════════════════════════════════════════════════════════════════

    private static String getPart(String[] parts, int index) {
        if (parts == null || index >= parts.length) return null;
        String p = parts[index].trim();
        return p.isEmpty() ? null : p;
    }

    /** Extrai CMD_ID de um campo, removendo prefixo "ID=" se presente. */
    private static String extractCmdId(String[] parts, int index) {
        String part = getPart(parts, index);
        if (part == null) return null;
        if (part.startsWith("ID=")) return part.substring(3).trim();
        return part;
    }

    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static double parseDoubleSafe(String s) {
        if (s == null) return 0.0;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Testes de validação (executar no onCreate ou em modo debug)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Executa bateria de testes do parser v2.0.
     * Chamar em modo debug para validar o parser antes de produção.
     */
    public static void runSimulationTests() {
        Log.i(TAG, "═══ INICIANDO VALIDAÇÃO DO PARSER v2.0 ═══");

        // Operacionais
        validate("ACK|A1B2C3D4");
        validate("DONE|A1B2C3D4|300|SES_8472ABCD");
        validate("PONG|A1B2C3D4");
        validate("VP:150.5");

        // Autenticação
        validate("AUTH:OK");
        validate("AUTH:FAIL");
        validate("AUTH_OK|A1B2C3D4|SES_8472ABCD");
        validate("AUTH_FAIL|A1B2C3D4|SES_8472ABCD");

        // Erros v2.0
        validate("ERROR:SESSION_MISMATCH");
        validate("ERROR:NOT_AUTHENTICATED");
        validate("ERROR:VOLUME_EXCEEDED");
        validate("ERROR:TIMEOUT");
        validate("ERROR:BUSY");
        validate("ERROR:WATCHDOG");
        validate("ERROR:HMAC_INVALID");
        validate("ERROR:DUPLICATE");
        validate("ERROR:QUEUE_FULL");
        validate("ERROR:VALVE_STUCK");

        // Alertas v2.0 (NOVO)
        validate("WARN:FLOW_TIMEOUT");
        validate("WARN:VOLUME_EXCEEDED");

        // Válvula
        validate("VALVE:OPEN");
        validate("VALVE:CLOSED");

        // Estados
        validate("STATUS:IDLE");
        validate("STATUS:RUNNING");
        validate("STATUS:ERROR");
        validate("STATUS:READY");
        validate("STATUS:BUSY");

        // Literais
        validate("QUEUE:FULL");
        validate("READY");
        validate("BUSY");
        validate("DUPLICATE");

        // Legado
        validate("ML:ACK");
        validate("ML:DUPLICATE");

        Log.i(TAG, "═══ VALIDAÇÃO FINALIZADA ═══");
    }

    private static void validate(String raw) {
        ParsedMessage pm = parse(raw);
        String status = (pm.type != MessageType.UNKNOWN) ? "✔ OK" : "✖ FAILED";
        Log.d(TAG, String.format("[%s] %-30s → %-25s | cmd=%-8s | ml=%d | warn=%s",
                status, raw, pm.type,
                (pm.commandId != null ? pm.commandId : "-"),
                pm.mlReal,
                (pm.warnType  != null ? pm.warnType  : "-")));
    }
}
