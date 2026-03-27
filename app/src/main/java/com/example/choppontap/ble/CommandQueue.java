package com.example.choppontap.ble;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Fila thread-safe de comandos BLE
 * Garante que apenas um comando seja processado por vez
 */
public class CommandQueue {
    private final Queue<BleCommand> queue = new LinkedList<>();
    private BleCommand currentCommand = null;
    private final Object lock = new Object();

    public synchronized void enqueue(BleCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Command cannot be null");
        }
        queue.offer(command);
        logDebug("Comando adicionado à fila. Total na fila: " + queue.size());
    }

    public synchronized BleCommand dequeue() {
        BleCommand cmd = queue.poll();
        if (cmd != null) {
            logDebug("Comando removido da fila. Restante: " + queue.size());
        }
        return cmd;
    }

    public synchronized boolean hasCommand() {
        return !queue.isEmpty();
    }

    public synchronized void setCurrentCommand(BleCommand command) {
        this.currentCommand = command;
        if (command != null) {
            logDebug("Comando em processamento: " + command.getCommand());
        }
    }

    public synchronized BleCommand getCurrentCommand() {
        return currentCommand;
    }

    public synchronized void clearCurrentCommand() {
        if (currentCommand != null) {
            logDebug("Comando completo. Limpando: " + currentCommand.getCommand());
        }
        this.currentCommand = null;
    }

    public synchronized boolean isProcessing() {
        return currentCommand != null;
    }

    public synchronized void clear() {
        queue.clear();
        currentCommand = null;
        logDebug("Fila limpa");
    }

    public synchronized int getQueueSize() {
        return queue.size();
    }

    private void logDebug(String msg) {
        android.util.Log.d("CommandQueue", msg);
    }
}
