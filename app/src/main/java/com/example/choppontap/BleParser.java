package com.example.choppontap;

import android.util.Log;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * BleParser — Parser centralizado de respostas BLE do ESP32 (Protocolo NUS v4.0)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * PROTOCOLO SUPORTADO (Firmware ESP32 operacional.cpp):
 *
 *   OK              — Comando aceito e enfileirado
 *   ERRO            — Comando com erro
 *   VP:<ml_parcial> — Volume parcial durante liberação
 *   QP:<pulsos>     — Quantidade de pulsos ao final
 *   ML:<ml_final>   — Volume final liberado (sinal de conclusão)
 *   PL:<pulsos>     — Resposta de leitura de pulsos/litro
 *
 * NÃO existe mais: ACK|, DONE|, PONG|, AUTH:OK/FAIL, HMAC, SESSION, SERVE, STOP
 *
 * @version 4.0.0
 */
public class BleParser {

    private static final String TAG = "BLE_PARSER";

    // ═══════════════════════════════════════════════════════════════════════════
    // Tipos de mensagem
    // ═══════════════════════════════════════════════════════════════════════════

    public enum MessageType {
        /** OK — Comando aceito pelo ESP32 */
        OK,
        /** ERRO — Comando com erro */
        ERRO,
        /** VP:<ml_parcial> — Volume parcial durante liberação */
        VP,
        /** QP:<pulsos> — Quantidade de pulsos ao final */
        QP,
        /** ML:<ml_final> — Volume final liberado (conclusão) */
        ML,
        /** PL:<pulsos> — Resposta de leitura de pulsos/litro */
        PL,
        /** Mensagem não reconhecida */
        UNKNOWN
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Resultado do parse
    // ═══════════════════════════════════════════════════════════════════════════

    public static class ParsedMessage {
        /** Tipo da mensagem parseada */
        public final MessageType type;
        /** String bruta recebida */
        public final String raw;
        /** Valor numérico extraído (mL, pulsos, etc.) */
        public final int intValue;
        /** Valor decimal extraído (para VP com decimais) */
        public final double doubleValue;

        private ParsedMessage(MessageType type, String raw, int intValue, double doubleValue) {
            this.type = type;
            this.raw = raw;
            this.intValue = intValue;
            this.doubleValue = doubleValue;
        }

        private ParsedMessage(MessageType type, String raw) {
            this(type, raw, 0, 0.0);
        }

        /** Retorna true se é um erro do ESP32. */
        public boolean isError() {
            return type == MessageType.ERRO;
        }

        /** Retorna true se é a conclusão da liberação (ML:). */
        public boolean isDone() {
            return type == MessageType.ML;
        }

        /** Retorna true se é progresso parcial (VP:). */
        public boolean isProgress() {
            return type == MessageType.VP;
        }

        @Override
        public String toString() {
            return String.format(
                    "ParsedMessage{type=%s, int=%d, double=%.1f, raw=[%s]}",
                    type, intValue, doubleValue, raw);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Parser principal
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Faz o parse de uma string recebida via BLE do ESP32 (protocolo NUS v4.0).
     *
     * @param raw String bruta recebida via notificação BLE
     * @return ParsedMessage com tipo e valores extraídos
     */
    public static ParsedMessage parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new ParsedMessage(MessageType.UNKNOWN, "");
        }

        String s = raw.trim();

        // ── OK — comando aceito ──────────────────────────────────────────────
        if ("OK".equalsIgnoreCase(s)) {
            return new ParsedMessage(MessageType.OK, s);
        }

        // ── ERRO — comando com erro ──────────────────────────────────────────
        if ("ERRO".equalsIgnoreCase(s)) {
            return new ParsedMessage(MessageType.ERRO, s);
        }

        // ── VP: — volume parcial durante liberação ───────────────────────────
        if (s.startsWith("VP:")) {
            double val = parseDoubleSafe(s.substring(3).trim());
            int intVal = (int) Math.round(val);
            return new ParsedMessage(MessageType.VP, s, intVal, val);
        }

        // ── QP: — quantidade de pulsos ao final ──────────────────────────────
        if (s.startsWith("QP:")) {
            int val = parseIntSafe(s.substring(3).trim());
            return new ParsedMessage(MessageType.QP, s, val, val);
        }

        // ── ML: — volume final liberado (conclusão) ──────────────────────────
        if (s.startsWith("ML:")) {
            double val = parseDoubleSafe(s.substring(3).trim());
            int intVal = (int) Math.round(val);
            Log.i(TAG, "[PARSE] Liberação concluída: " + intVal + "mL");
            return new ParsedMessage(MessageType.ML, s, intVal, val);
        }

        // ── PL: — resposta de leitura de pulsos/litro ────────────────────────
        if (s.startsWith("PL:")) {
            int val = parseIntSafe(s.substring(3).trim());
            return new ParsedMessage(MessageType.PL, s, val, val);
        }

        // ── Desconhecido ─────────────────────────────────────────────────────
        Log.d(TAG, "[PARSE] Mensagem não reconhecida: [" + s + "]");
        return new ParsedMessage(MessageType.UNKNOWN, s);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utilitários internos
    // ═══════════════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════════════
    // Testes de validação
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Executa bateria de testes do parser v4.0 NUS.
     * Chamar em modo debug para validar o parser antes de produção.
     */
    public static void runSimulationTests() {
        Log.i(TAG, "═══ INICIANDO VALIDAÇÃO DO PARSER v4.0 NUS ═══");

        validate("OK");
        validate("ERRO");
        validate("VP:50");
        validate("VP:150.5");
        validate("QP:2940");
        validate("ML:100");
        validate("ML:300.5");
        validate("PL:5880");
        validate("DESCONHECIDO");
        validate("");
        validate(null);

        Log.i(TAG, "═══ VALIDAÇÃO CONCLUÍDA ═══");
    }

    private static void validate(String input) {
        ParsedMessage result = parse(input);
        Log.d(TAG, "[TEST] input=[" + input + "] → " + result);
    }
}
