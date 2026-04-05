package com.example.choppontap.ble;

import android.util.Log;

/**
 * Gerenciador de estado da máquina BLE — Protocolo NUS v4.0
 *
 * Fluxo de estados:
 *   IDLE → CONNECTING → CONNECTED → READY → PROCESSING → COMPLETED → READY
 *                          ↓           ↓         ↓
 *                       ERROR       DISCONNECTED  ERROR
 *                          ↓           ↓
 *                        IDLE       CONNECTING
 *
 * CONNECTED: GATT conectado, serviço NUS descoberto, aguardando notificações.
 * READY:     Notificações habilitadas. Pronto para enviar comandos.
 */
public class StateManager {
    private BleState currentState = BleState.IDLE;
    private final Object lock = new Object();
    private StateListener listener;

    public interface StateListener {
        void onStateChanged(BleState oldState, BleState newState);
    }

    public void setStateListener(StateListener listener) {
        this.listener = listener;
    }

    public synchronized boolean setState(BleState newState) {
        if (!isValidTransition(currentState, newState)) {
            Log.w("StateManager", "Transição inválida de " + currentState + " para " + newState);
            return false;
        }

        BleState oldState = currentState;
        this.currentState = newState;
        Log.d("StateManager", "[STATE] " + oldState + " → " + newState);

        if (listener != null) {
            listener.onStateChanged(oldState, newState);
        }

        return true;
    }

    public synchronized BleState getState() {
        return currentState;
    }

    public synchronized boolean isState(BleState state) {
        return currentState == state;
    }

    public synchronized boolean canSendCommand() {
        return currentState == BleState.READY;
    }

    public synchronized boolean isConnected() {
        return currentState == BleState.CONNECTED
                || currentState == BleState.READY
                || currentState == BleState.PROCESSING;
    }

    public synchronized void reset() {
        BleState oldState = currentState;
        this.currentState = BleState.IDLE;
        Log.d("StateManager", "[RESET] " + oldState + " → IDLE");

        if (listener != null) {
            listener.onStateChanged(oldState, BleState.IDLE);
        }
    }

    private boolean isValidTransition(BleState from, BleState to) {
        switch (from) {
            case IDLE:
                return to == BleState.CONNECTING
                        || to == BleState.DISCONNECTED;

            case CONNECTING:
                // CONNECTED: GATT conectado, serviços descobertos
                // READY: fallback direto (sem etapa CONNECTED explícita)
                return to == BleState.CONNECTED
                        || to == BleState.READY
                        || to == BleState.ERROR
                        || to == BleState.DISCONNECTED
                        || to == BleState.IDLE;

            case CONNECTED:
                // Após habilitar notificações NUS → READY
                return to == BleState.READY
                        || to == BleState.ERROR
                        || to == BleState.DISCONNECTED;

            case READY:
                return to == BleState.PROCESSING
                        || to == BleState.DISCONNECTED
                        || to == BleState.IDLE;

            case PROCESSING:
                return to == BleState.COMPLETED
                        || to == BleState.ERROR
                        || to == BleState.DISCONNECTED;

            case COMPLETED:
                return to == BleState.READY
                        || to == BleState.IDLE
                        || to == BleState.DISCONNECTED;

            case ERROR:
                return to == BleState.IDLE
                        || to == BleState.CONNECTING
                        || to == BleState.DISCONNECTED;

            case DISCONNECTED:
                return to == BleState.IDLE
                        || to == BleState.CONNECTING;

            default:
                return false;
        }
    }
}
