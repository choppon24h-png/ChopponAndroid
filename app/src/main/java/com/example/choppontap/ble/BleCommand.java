package com.example.choppontap.ble;

import java.util.UUID;

/**
 * Representa um comando BLE a ser enviado para o ESP32
 * Imutável para segurança em multi-threading
 */
public class BleCommand {
    private final String command;
    private final byte[] data;
    private final UUID characteristic;
    private final long createdAt;

    public BleCommand(String command, UUID characteristic) {
        this.command = command;
        this.data = command.getBytes();
        this.characteristic = characteristic;
        this.createdAt = System.currentTimeMillis();
    }

    public String getCommand() {
        return command;
    }

    public byte[] getData() {
        return data;
    }

    public UUID getCharacteristic() {
        return characteristic;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getAgeMs() {
        return System.currentTimeMillis() - createdAt;
    }

    @Override
    public String toString() {
        return "BleCommand{" +
                "command='" + command + '\'' +
                ", ageMs=" + getAgeMs() +
                '}';
    }
}
