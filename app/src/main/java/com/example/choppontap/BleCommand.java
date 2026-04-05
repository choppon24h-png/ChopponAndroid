package com.example.choppontap;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * BleCommand.java — Representação de um comando BLE para o ESP32
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Versão: 4.0-NUS
 * Protocolo: Nordic UART Service (NUS) — Firmware ESP32 operacional.cpp
 *
 * Comandos suportados:
 *   $ML:<volume_ml>    — Liberar volume em mL
 *   $PL:<pulsos>       — Configurar pulsos/litro
 *   $TO:<timeout_ms>   — Configurar timeout
 *   $LB:               — Liberação contínua
 *
 * NÃO existe mais: HMAC, SESSION_ID, CMD_ID, AUTH, SERVE, STOP, STATUS, PING
 */
public class BleCommand {

    private static final String TAG = "BLE_COMMAND";

    // ═══════════════════════════════════════════════════════════════════════════
    // Tipos de comando
    // ═══════════════════════════════════════════════════════════════════════════

    public enum Type {
        /** $ML:<volume_ml> — Liberar volume em mL */
        ML,
        /** $PL:<pulsos> — Configurar/consultar pulsos/litro */
        PL,
        /** $TO:<timeout_ms> — Configurar/consultar timeout */
        TO,
        /** $LB: — Liberação contínua */
        LB,
        /** Comando genérico (string livre) */
        RAW
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Estados do comando
    // ═══════════════════════════════════════════════════════════════════════════

    public enum State {
        /** Criado, aguardando envio */
        PENDING,
        /** Enviado ao ESP32 via BLE */
        SENT,
        /** ESP32 respondeu OK */
        ACCEPTED,
        /** ESP32 respondeu ERRO */
        ERROR,
        /** Liberação concluída (recebeu ML:) */
        DONE,
        /** Cancelado */
        CANCELLED
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Campos
    // ═══════════════════════════════════════════════════════════════════════════

    /** Tipo do comando */
    public final Type type;

    /** Identificador de sessão (para rastreamento no app — não enviado ao ESP32) */
    public final String sessionId;

    /** Valor numérico do comando (volume em mL, pulsos, timeout, etc.) */
    public final int value;

    /** String bruta do comando (para tipo RAW) */
    public final String rawCommand;

    /** Estado atual do comando */
    public State state;

    /** Timestamp de criação */
    public final long createdAt;

    /** Timestamp de envio */
    public long sentAt;

    /** Timestamp de conclusão */
    public long completedAt;

    /** Volume real reportado pelo ESP32 (campo ML:) */
    public int mlReal = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Construtores
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Construtor principal para comandos tipados.
     *
     * @param type      tipo do comando
     * @param sessionId identificador de sessão (pode ser null)
     * @param value     valor numérico do comando
     */
    public BleCommand(Type type, String sessionId, int value) {
        this.type = type;
        this.sessionId = sessionId;
        this.value = value;
        this.rawCommand = null;
        this.state = State.PENDING;
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * Construtor para comandos RAW (string livre).
     *
     * @param rawCommand string do comando a enviar
     */
    public BleCommand(String rawCommand) {
        this.type = Type.RAW;
        this.sessionId = null;
        this.value = 0;
        this.rawCommand = rawCommand;
        this.state = State.PENDING;
        this.createdAt = System.currentTimeMillis();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Builders de conveniência
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Cria comando de liberação de volume.
     * Formato BLE: $ML:<volumeMl>
     */
    public static BleCommand buildMl(int volumeMl, String sessionId) {
        return new BleCommand(Type.ML, sessionId, volumeMl);
    }

    /**
     * Cria comando de liberação contínua.
     * Formato BLE: $LB:
     */
    public static BleCommand buildLb(String sessionId) {
        return new BleCommand(Type.LB, sessionId, 0);
    }

    /**
     * Cria comando de configuração de pulsos/litro.
     * Formato BLE: $PL:<pulsos>
     */
    public static BleCommand buildPl(int pulsos) {
        return new BleCommand(Type.PL, null, pulsos);
    }

    /**
     * Cria comando de configuração de timeout.
     * Formato BLE: $TO:<timeout>
     */
    public static BleCommand buildTo(int timeout) {
        return new BleCommand(Type.TO, null, timeout);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Serialização para BLE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Retorna a string de comando pronta para envio ao ESP32 via BLE.
     */
    public String toBleString() {
        switch (type) {
            case ML:  return "$ML:" + value;
            case PL:  return "$PL:" + value;
            case TO:  return "$TO:" + value;
            case LB:  return "$LB:";
            case RAW: return rawCommand != null ? rawCommand : "";
            default:  return "";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utilitários
    // ═══════════════════════════════════════════════════════════════════════════

    /** Retorna true se o comando está em estado terminal (DONE ou ERROR). */
    public boolean isTerminal() {
        return state == State.DONE || state == State.ERROR;
    }

    /** Retorna true se o comando expirou (mais de 30s sem resposta). */
    public boolean isExpired() {
        return (System.currentTimeMillis() - createdAt) > 30_000L;
    }

    @Override
    public String toString() {
        return "BleCommand{" +
                "type=" + type +
                ", value=" + value +
                ", state=" + state +
                ", sessionId='" + sessionId + '\'' +
                ", ble='" + toBleString() + '\'' +
                '}';
    }
}
