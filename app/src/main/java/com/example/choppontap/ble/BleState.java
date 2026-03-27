package com.example.choppontap.ble;

/**
 * State machine para gerenciamento de conexão BLE
 * Previne race conditions e operações simultâneas
 */
public enum BleState {
    IDLE("Ocioso"),
    CONNECTING("Conectando"),
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
