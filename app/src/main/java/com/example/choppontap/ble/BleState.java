package com.example.choppontap.ble;

/**
 * State machine para gerenciamento de conexão BLE — Protocolo NUS v4.0
 *
 * Fluxo de estados:
 *   IDLE → CONNECTING → CONNECTED → READY → PROCESSING → COMPLETED
 *                                      ↓
 *                                 DISCONNECTED ← (desconexão)
 *                                      ↓
 *                                    ERROR (fatal)
 *
 * CONNECTED: GATT conectado, serviços NUS descobertos, aguardando notificações.
 * READY:     Notificações NUS habilitadas. Pronto para enviar comandos.
 *
 * NÃO existe mais: AUTH:OK, PING/PONG heartbeat
 */
public enum BleState {
    IDLE("Ocioso"),
    CONNECTING("Conectando"),
    CONNECTED("Conectado"),
    READY("Pronto"),
    PROCESSING("Processando comando"),
    COMPLETED("Completo"),
    ERROR("Erro"),
    DISCONNECTED("Desconectado");

    private final String description;

    BleState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return description;
    }
}
