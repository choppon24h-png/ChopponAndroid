package com.example.choppontap.ble;

import android.util.Log;

/**
 * Gerenciador de estado da máquina BLE
 * Previne transições inválidas e race conditions
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
        return currentState == BleState.READY || currentState == BleState.PROCESSING;
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
        // Define transições válidas
        switch (from) {
            case IDLE:
                return to == BleState.CONNECTING || to == BleState.DISCONNECTED;
            case CONNECTING:
                return to == BleState.READY || to == BleState.ERROR || to == BleState.IDLE;
            case READY:
                return to == BleState.PROCESSING || to == BleState.DISCONNECTED || to == BleState.IDLE;
            case PROCESSING:
                return to == BleState.COMPLETED || to == BleState.ERROR || to == BleState.DISCONNECTED;
            case COMPLETED:
                return to == BleState.READY || to == BleState.IDLE || to == BleState.DISCONNECTED;
            case ERROR:
                return to == BleState.IDLE || to == BleState.CONNECTING || to == BleState.DISCONNECTED;
            case DISCONNECTED:
                return to == BleState.IDLE || to == BleState.CONNECTING;
            default:
                return false;
        }
    }
}
