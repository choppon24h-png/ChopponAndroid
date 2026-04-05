package com.example.choppontap;

import android.util.Log;

/**
 * BleManager — Orquestrador central da camada BLE para protocolo NUS v4.0.
 *
 * ═══════════════════════════════════════════════════════════════════
 * ARQUITETURA
 * ═══════════════════════════════════════════════════════════════════
 *
 *   BluetoothService
 *        │
 *        ▼
 *   BleManager
 *        │
 *        ├── ConnectionManager (estados + keepalive + reconexão)
 *        └── CommandQueueManager (fila FIFO simplificada)
 *
 * Protocolo NUS v4.0:
 *   - Sem AUTH/HMAC/SESSION_ID/CMD_ID
 *   - Comandos: $ML:, $LB:, $PL:, $TO:
 *   - Respostas: OK, ERRO, VP:, ML:, PL:, QP:, TO:
 *
 * @version 4.0.0
 */
public class BleManager {

    private static final String TAG = "BLE_MANAGER";

    // ── Módulos ───────────────────────────────────────────────────────────────
    private final ConnectionManager     mConnectionManager;
    private final CommandQueueManager   mCommandQueue;

    // ── Interface de escrita BLE (injetada pelo BluetoothService) ─────────────
    private CommandQueueManager.BleWriter mWriter;

    // ── Callbacks para o BluetoothService ─────────────────────────────────────
    public interface Callback {
        void onStateChanged(ConnectionManager.State newState, ConnectionManager.State oldState);
        void onConnectRequested(String mac, boolean autoConnect);
        void onCommandSent(String command);
        void onCommandResponse(String command, String response);
        void onCommandError(String command, String reason);
        void onHeartbeatFailed();
    }

    private final Callback mCallback;

    // ── Construtor ────────────────────────────────────────────────────────────
    public BleManager(Callback callback) {
        this.mCallback = callback;

        // Inicializa ConnectionManager
        mConnectionManager = new ConnectionManager(new ConnectionManager.Callback() {
            @Override
            public void onStateChanged(ConnectionManager.State newState,
                                       ConnectionManager.State oldState) {
                Log.i(TAG, "[BLE] " + oldState.name() + " -> " + newState.name());
                if (mCallback != null) mCallback.onStateChanged(newState, oldState);

                if (newState == ConnectionManager.State.READY) {
                    mCommandQueue.onBleReady();
                } else if (newState == ConnectionManager.State.DISCONNECTED) {
                    mCommandQueue.onBleDisconnected();
                }
            }

            @Override
            public void onHeartbeatFailed() {
                Log.e(TAG, "[BLE] Keepalive falhou — solicitando reconexão");
                if (mCallback != null) mCallback.onHeartbeatFailed();
            }

            @Override
            public void onConnectRequested(String mac, boolean autoConnect) {
                Log.i(TAG, "[BLE] Conexão solicitada -> " + mac
                        + " (autoConnect=" + autoConnect + ")");
                if (mCallback != null) mCallback.onConnectRequested(mac, autoConnect);
            }
        });

        // Inicializa CommandQueueManager
        mCommandQueue = new CommandQueueManager(
                data -> {
                    if (mWriter == null) {
                        Log.e(TAG, "[QUEUE] write() bloqueado — BleWriter não configurado");
                        return false;
                    }
                    if (!mConnectionManager.isReady()) {
                        Log.e(TAG, "[QUEUE] write() bloqueado — BLE não está READY (estado="
                                + mConnectionManager.getState() + ")");
                        return false;
                    }
                    return mWriter.write(data);
                },
                new CommandQueueManager.Callback() {
                    @Override
                    public void onSend(String command) {
                        Log.i(TAG, "[QUEUE] SEND " + command);
                        if (mCallback != null) mCallback.onCommandSent(command);
                    }

                    @Override
                    public void onResponse(String command, String response) {
                        Log.i(TAG, "[QUEUE] RESPONSE " + command + " -> " + response);
                        mConnectionManager.onDataReceived();
                        if (mCallback != null) mCallback.onCommandResponse(command, response);
                    }

                    @Override
                    public void onError(String command, String reason) {
                        Log.e(TAG, "[QUEUE] ERROR " + command + " | " + reason);
                        if (mCallback != null) mCallback.onCommandError(command, reason);
                    }
                }
        );
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Configuração
    // ═════════════════════════════════════════════════════════════════════════

    public void setWriter(CommandQueueManager.BleWriter writer) {
        this.mWriter = writer;
    }

    public void setTargetMac(String mac) {
        mConnectionManager.setTargetMac(mac);
    }

    public void setAutoReconnect(boolean enabled) {
        mConnectionManager.setAutoReconnect(enabled);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Eventos de scan
    // ═════════════════════════════════════════════════════════════════════════

    public void onScanStarted() {
        mConnectionManager.onScanStarted();
    }

    public void onDeviceFoundInScan(String mac) {
        mConnectionManager.onDeviceFoundInScan(mac);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Eventos GATT
    // ═════════════════════════════════════════════════════════════════════════

    public void onGattConnected(String mac) {
        Log.i(TAG, "[BLE] CONNECTED -> " + mac);
        mConnectionManager.setTargetMac(mac);
        mConnectionManager.onGattConnected();
    }

    public void onGattDisconnected(int status, boolean closeGatt) {
        Log.w(TAG, "[BLE] DISCONNECTED | status=" + status);
        mConnectionManager.onGattDisconnected(status, closeGatt);
    }

    /**
     * Notifica que as notificações NUS foram habilitadas — transiciona para READY.
     * No protocolo NUS v4.0, não há AUTH:OK.
     */
    public void onNotificationsEnabled() {
        Log.i(TAG, "[BLE] Notificações NUS habilitadas -> READY");
        mConnectionManager.onNotificationsEnabled();
    }

    /**
     * Compatibilidade: chamado por código que ainda usa onAuthOk().
     */
    public void onAuthOk() {
        onNotificationsEnabled();
    }

    /**
     * Processa dados recebidos do ESP32 via BLE.
     * Deve ser chamado em onCharacteristicChanged().
     */
    public void onBleDataReceived(String raw) {
        if (raw == null || raw.isEmpty()) return;

        // Notifica ConnectionManager de qualquer RX (reseta keepalive)
        mConnectionManager.onDataReceived();

        // Roteia para a fila de comandos
        mCommandQueue.onBleResponse(raw);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API para Activities e BluetoothService
    // ═════════════════════════════════════════════════════════════════════════

    public ConnectionManager.State getConnectionState() {
        return mConnectionManager.getState();
    }

    public boolean isReady() {
        return mConnectionManager.isReady();
    }

    public boolean isConnected() {
        return mConnectionManager.isConnected();
    }

    public CommandQueueManager getCommandQueue() {
        return mCommandQueue;
    }

    public ConnectionManager getConnectionManager() {
        return mConnectionManager;
    }

    public void destroy() {
        mConnectionManager.destroy();
        mCommandQueue.reset();
    }

    @Override
    public String toString() {
        return "BleManager{conn=" + mConnectionManager.getState()
                + ", queue=" + mCommandQueue.size() + "}";
    }
}
