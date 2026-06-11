package com.example.choppontap;

import android.util.Log;

/**
 * BleCommand — Protocolo NUS v5.0 (firmware 2026-05-05)
 *
 * Comandos Android -> ESP32 (escrita na RX 6E400002):
 *   PING         -- keepalive opcional, retorna PONG
 *   $ML:<ml>     -- libera quantidade em mL (inteiro > 0)
 *   $LB:         -- libera modo continuo (fecha pelo timeout de inatividade)
 *   $PL:<pulsos> -- configura pulsos/litro (persiste EEPROM); $PL:0 = GET
 *   $TO:<s>      -- configura timeout de inatividade em SEGUNDOS (persiste EEPROM); $TO:0 = GET (retorna ms)
 *   $RS:         -- retoma ciclo anterior incompleto
 *   $DB:         -- diagnostico: retorna PIN=, VAL=, TO=, PL=, QP=
 *
 * Respostas ESP32 -> Android (notificacoes na TX 6E400003), terminadas com '\n':
 *   PONG         -- resposta ao PING
 *   OK           -- comando aceito/enfileirado
 *   ERRO         -- comando invalido ou operacao em andamento
 *   IN:          -- valvula abriu, ciclo iniciado
 *   VP:<ml>      -- volume parcial acumulado durante liberacao (~2s)
 *   QP:<pulsos>  -- total de pulsos ao fim do ciclo
 *   ML:<ml>      -- volume final: inteiro se completo (ML:300), float 2 casas se interrompido
 *   FN:          -- ciclo encerrado, valvula fechada — sinal definitivo de fim
 *   PL:<valor>   -- resposta ao $PL:0 (pulsos/litro atual)
 *   TO:<valor>   -- resposta ao $TO:0 (timeout atual em ms)
 *   RS:<ml>      -- ciclo retomado: mL que serao dispensados; RS:0 = ciclo anterior ja completo
 *
 * IMPORTANTE: $TO recebe SEGUNDOS. FN: e o sinal definitivo de fim de ciclo.
 * Android deve aguardar FN: antes de enviar novo $ML: ou $RS:.
 */
public class BleCommand {

    private static final String TAG = "BleCommand";

    // -------------------------------------------------------------------------
    // Prefixos de comando (Android -> ESP32)
    // -------------------------------------------------------------------------
    public static final String CMD_ML      = "$ML:";   // libera mL
    public static final String CMD_LB      = "$LB:";   // libera continuo
    public static final String CMD_PL      = "$PL:";   // pulsos/litro
    public static final String CMD_TO      = "$TO:";   // timeout em SEGUNDOS
    public static final String CMD_RS      = "$RS:";   // retoma ciclo incompleto
    public static final String CMD_DB      = "$DB:";   // diagnostico
    public static final String CMD_PING    = "PING";   // keepalive (sem $)
    /**
     * Comando de parada de emergência da solenoide.
     * $ML:0 instrui o firmware a fechar a válvula imediatamente e encerrar
     * qualquer ciclo ativo ($ML: ou $LB:), sem aguardar o volume alvo.
     * O ESP32 responde com FN: após fechar a válvula.
     *
     * v5.12: Substituiu o uso incorreto de $TO:0 (que é apenas um GET do
     * timeout atual, não fecha a solenoide) como kill-switch de emergência.
     */
    public static final String CMD_ML_STOP = "$ML:0"; // fecha solenoide (emergência)

    // -------------------------------------------------------------------------
    // Prefixos de resposta (ESP32 -> Android)
    // -------------------------------------------------------------------------
    public static final String RESP_PONG = "PONG";
    public static final String RESP_OK   = "OK";
    public static final String RESP_ERRO = "ERRO";
    public static final String RESP_IN   = "IN:";    // inicio do ciclo
    public static final String RESP_VP   = "VP:";    // volume parcial acumulado
    public static final String RESP_QP   = "QP:";    // pulsos ao fim
    public static final String RESP_ML   = "ML:";    // volume final
    public static final String RESP_FN   = "FN:";    // fim do ciclo
    public static final String RESP_PL   = "PL:";    // resposta ao $PL:0
    public static final String RESP_TO   = "TO:";    // resposta ao $TO:0 (em ms)
    public static final String RESP_RS   = "RS:";    // confirmacao de resume

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

    /**
     * Configura pulsos por litro (persiste EEPROM).
     * buildSetPulsos(0) envia "$PL:0" = GET do valor atual.
     */
    public static String buildSetPulsos(int pulsosPorLitro) {
        if (pulsosPorLitro < 0) return null;
        return CMD_PL + pulsosPorLitro;
    }

    /**
     * Configura timeout de inatividade em SEGUNDOS (persiste EEPROM).
     * O firmware recebe segundos e armazena internamente em ms.
     * buildSetTimeout(0) envia "$TO:0" = GET do valor atual (retorna TO:<ms>).
     *
     * @param timeoutSegundos timeout em segundos (> 0 para SET, 0 para GET)
     */
    public static String buildSetTimeout(int timeoutSegundos) {
        if (timeoutSegundos < 0) return null;
        return CMD_TO + timeoutSegundos;
    }

    /**
     * Retoma ciclo anterior incompleto.
     * ESP32 responde RS:<restante_ml> se ha pendencia, RS:0 se ja completo,
     * ou ERRO se nenhum ciclo $ML: anterior registrado.
     */
    public static String buildResume() {
        return CMD_RS;
    }

    /** Diagnostico: retorna PIN=, VAL=, TO=, PL=, QP=. */
    public static String buildDiagnostico() {
        return CMD_DB;
    }

    /**
     * Comando de parada de emergência: fecha a solenoide imediatamente.
     * Deve ser enviado em onPause/onStop/onDestroy de qualquer Activity
     * que possa ter iniciado um ciclo de dispenção.
     * O ESP32 responde com FN: após fechar a válvula.
     */
    public static String buildEmergencyStop() {
        return CMD_ML_STOP;
    }

    // -------------------------------------------------------------------------
    // Parser de respostas recebidas via BLE (ESP32 -> Android)
    // -------------------------------------------------------------------------

    public static class Response {
        public enum Type {
            PONG,
            OK,
            ERRO,
            INICIO_CICLO,      // IN:
            VOLUME_PARCIAL,    // VP:<ml>
            PULSOS_FINAL,      // QP:<pulsos>
            VOLUME_FINAL,      // ML:<ml>
            FIM_CICLO,         // FN:
            PULSOS_LITRO,      // PL:<valor>
            TIMEOUT_ATUAL,     // TO:<valor_ms>
            RESUME,            // RS:<restante_ml> ou RS:0
            DESCONHECIDO
        }

        public final Type   type;
        public final String raw;
        public final float  value;  // para VP, QP, ML, PL, TO, RS

        public Response(Type type, String raw, float value) {
            this.type  = type;
            this.raw   = raw;
            this.value = value;
        }

        public boolean isPong()          { return type == Type.PONG; }
        public boolean isOk()            { return type == Type.OK; }
        public boolean isErro()          { return type == Type.ERRO; }
        public boolean isInicioCiclo()   { return type == Type.INICIO_CICLO; }
        public boolean isVolumeParcial() { return type == Type.VOLUME_PARCIAL; }
        public boolean isPulsosFinal()   { return type == Type.PULSOS_FINAL; }
        public boolean isVolumeFinal()   { return type == Type.VOLUME_FINAL; }
        public boolean isFimCiclo()      { return type == Type.FIM_CICLO; }
        public boolean isPulsosLitro()   { return type == Type.PULSOS_LITRO; }
        public boolean isTimeoutAtual()  { return type == Type.TIMEOUT_ATUAL; }
        public boolean isResume()        { return type == Type.RESUME; }
    }

    /**
     * Parseia uma string recebida via notificacao BLE.
     * O firmware termina cada mensagem com '\n' — trim() remove o '\n'.
     */
    public static Response parse(String raw) {
        if (raw == null) return new Response(Response.Type.DESCONHECIDO, "", 0);
        String msg = raw.trim();

        if (msg.equals(RESP_PONG))   return new Response(Response.Type.PONG,         msg, 0);
        if (msg.equals(RESP_OK))     return new Response(Response.Type.OK,            msg, 0);
        if (msg.equals(RESP_ERRO))   return new Response(Response.Type.ERRO,          msg, 0);
        if (msg.equals(RESP_IN))     return new Response(Response.Type.INICIO_CICLO,  msg, 0);
        if (msg.equals(RESP_FN))     return new Response(Response.Type.FIM_CICLO,     msg, 0);

        if (msg.startsWith(RESP_VP)) {
            return new Response(Response.Type.VOLUME_PARCIAL,
                    msg, parseFloat(msg.substring(RESP_VP.length())));
        }
        if (msg.startsWith(RESP_QP)) {
            return new Response(Response.Type.PULSOS_FINAL,
                    msg, parseFloat(msg.substring(RESP_QP.length())));
        }
        if (msg.startsWith(RESP_ML)) {
            return new Response(Response.Type.VOLUME_FINAL,
                    msg, parseFloat(msg.substring(RESP_ML.length())));
        }
        if (msg.startsWith(RESP_FN)) {
            // FN: pode ter sufixo no futuro
            return new Response(Response.Type.FIM_CICLO, msg, 0);
        }
        if (msg.startsWith(RESP_PL)) {
            return new Response(Response.Type.PULSOS_LITRO,
                    msg, parseFloat(msg.substring(RESP_PL.length())));
        }
        if (msg.startsWith(RESP_TO)) {
            return new Response(Response.Type.TIMEOUT_ATUAL,
                    msg, parseFloat(msg.substring(RESP_TO.length())));
        }
        if (msg.startsWith(RESP_RS)) {
            return new Response(Response.Type.RESUME,
                    msg, parseFloat(msg.substring(RESP_RS.length())));
        }

        Log.w(TAG, "Resposta desconhecida: " + msg);
        return new Response(Response.Type.DESCONHECIDO, msg, 0);
    }

    private static float parseFloat(String s) {
        try   { return Float.parseFloat(s.trim()); }
        catch (NumberFormatException e) { return 0f; }
    }
}
