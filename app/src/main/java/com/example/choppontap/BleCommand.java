package com.example.choppontap;

import android.util.Log;

/**
 * BleCommand - Protocolo simples compativel com firmware ASOARESBH/ESP32
 *
 * Protocolo definido em operacional.cpp / operaBLE.cpp:
 *
 *   Android -> ESP32:
 *     PING         -- keepalive (sem prefixo $)
 *     $ML:<ml>     -- libera quantidade em mL  ex: "$ML:300"
 *     $LB:         -- libera fluxo continuo
 *     $PL:<pulsos> -- configura pulsos por litro
 *     $TO:<ms>     -- configura timeout do sensor
 *
 *   ESP32 -> Android:
 *     PONG         -- resposta ao PING
 *     OK           -- comando aceito
 *     ERRO         -- comando invalido
 *     VP:<ml>      -- volume parcial durante liberacao (notificacao a cada 2s)
 *     QP:<pulsos>  -- quantidade de pulsos ao final
 *     ML:<ml>      -- volume final liberado (conclusao da operacao)
 *
 * Todos os comandos exceto PING devem iniciar com '$'.
 * O separador e ':' (nao ',').
 */
public class BleCommand {

    private static final String TAG = "BleCommand";

    // Prefixos de comando (conforme config.h do firmware)
    public static final String CMD_ML   = "$ML:";  // libera mL
    public static final String CMD_LB   = "$LB:";  // libera continuo
    public static final String CMD_PL   = "$PL:";  // pulsos/litro
    public static final String CMD_TO   = "$TO:";  // timeout sensor
    public static final String CMD_PING = "PING";  // keepalive (sem $)

    // Prefixos de resposta (ESP32 -> Android)
    public static final String RESP_PONG = "PONG";
    public static final String RESP_OK   = "OK";
    public static final String RESP_ERRO = "ERRO";
    public static final String RESP_VP   = "VP:";   // volume parcial
    public static final String RESP_QP   = "QP:";   // quantidade pulsos
    public static final String RESP_ML   = "ML:";   // volume final

    // -------------------------------------------------------------------------
    // Construtores de comandos
    // -------------------------------------------------------------------------

    /** Comando PING -- keepalive, enviar a cada 5 segundos em estado READY */
    public static String buildPing() {
        return CMD_PING;
    }

    /**
     * Comando de liberacao de volume em mL.
     * Ex: buildServe(300) -> "$ML:300"
     */
    public static String buildServe(int ml) {
        if (ml <= 0) {
            Log.w(TAG, "buildServe: ml invalido (" + ml + "), ignorado");
            return null;
        }
        return CMD_ML + ml;
    }

    /** Comando de liberacao continua. */
    public static String buildLiberar() {
        return CMD_LB;
    }

    /** Comando de configuracao de pulsos por litro. */
    public static String buildSetPulsos(int pulsosPorLitro) {
        if (pulsosPorLitro <= 0) return null;
        return CMD_PL + pulsosPorLitro;
    }

    /** Comando de configuracao do timeout do sensor (ms). */
    public static String buildSetTimeout(int timeoutMs) {
        if (timeoutMs <= 0) return null;
        return CMD_TO + timeoutMs;
    }

    // -------------------------------------------------------------------------
    // Parser de respostas recebidas via BLE (ESP32 -> Android)
    // -------------------------------------------------------------------------

    public static class Response {
        public enum Type {
            PONG,
            OK,
            ERRO,
            VOLUME_PARCIAL,    // VP:<ml>
            PULSOS_FINAL,      // QP:<pulsos>
            VOLUME_FINAL,      // ML:<ml>
            DESCONHECIDO
        }

        public final Type   type;
        public final String raw;
        public final float  value;   // para VP, QP, ML

        public Response(Type type, String raw, float value) {
            this.type  = type;
            this.raw   = raw;
            this.value = value;
        }

        public boolean isPong()          { return type == Type.PONG; }
        public boolean isOk()            { return type == Type.OK; }
        public boolean isErro()          { return type == Type.ERRO; }
        public boolean isVolumeParcial() { return type == Type.VOLUME_PARCIAL; }
        public boolean isVolumeFinal()   { return type == Type.VOLUME_FINAL; }
        public boolean isPulsosFinal()   { return type == Type.PULSOS_FINAL; }
    }

    /**
     * Parseia uma string recebida via notificacao BLE.
     * O firmware termina cada mensagem com '\n' -- trim() remove o '\n'.
     */
    public static Response parse(String raw) {
        if (raw == null) return new Response(Response.Type.DESCONHECIDO, "", 0);
        String msg = raw.trim();

        if (msg.equals(RESP_PONG)) return new Response(Response.Type.PONG,          msg, 0);
        if (msg.equals(RESP_OK))   return new Response(Response.Type.OK,            msg, 0);
        if (msg.equals(RESP_ERRO)) return new Response(Response.Type.ERRO,          msg, 0);

        if (msg.startsWith(RESP_VP)) {
            return new Response(Response.Type.VOLUME_PARCIAL, msg, parseFloat(msg.substring(RESP_VP.length())));
        }
        if (msg.startsWith(RESP_QP)) {
            return new Response(Response.Type.PULSOS_FINAL,   msg, parseFloat(msg.substring(RESP_QP.length())));
        }
        if (msg.startsWith(RESP_ML)) {
            return new Response(Response.Type.VOLUME_FINAL,   msg, parseFloat(msg.substring(RESP_ML.length())));
        }

        Log.w(TAG, "Resposta desconhecida: " + msg);
        return new Response(Response.Type.DESCONHECIDO, msg, 0);
    }

    private static float parseFloat(String s) {
        try   { return Float.parseFloat(s.trim()); }
        catch (NumberFormatException e) { return 0f; }
    }
}
