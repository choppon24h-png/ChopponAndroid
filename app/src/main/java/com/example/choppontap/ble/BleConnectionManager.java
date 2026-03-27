package com.example.choppontap.ble;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;

public class BleConnectionManager extends BleManager {
    private static final String TAG = "BleConnectionManager";
    private static final UUID SERVICE_UUID = UUID.fromString("7f0a0001-7b6b-4b5f-9d3e-3c7b9f100001");
    private static final UUID RX_UUID = UUID.fromString("7f0a0002-7b6b-4b5f-9d3e-3c7b9f100001");
    private static final UUID TX_UUID = UUID.fromString("7f0a0003-7b6b-4b5f-9d3e-3c7b9f100001");

    private final StateManager stateManager = new StateManager();
    private final CommandQueue commandQueue = new CommandQueue();
    private final NotificationCallbackHolder callbackHolder = new NotificationCallbackHolder();

    private BluetoothGattCharacteristic rxChar;
    private BluetoothGattCharacteristic txChar;

    private static BleConnectionManager instance;
    private static final Object lock = new Object();

    public interface ConnectionListener {
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    public interface CommandListener {
        void onCommandSent(String command);
        void onResponseReceived(String response);
        void onCommandError(String error);
    }

    private ConnectionListener connListener;
    private CommandListener cmdListener;

    public static BleConnectionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new BleConnectionManager(context);
                }
            }
        }
        return instance;
    }

    private BleConnectionManager(Context context) {
        super(context);
        stateManager.setStateListener((old, neu) -> Log.d(TAG, "[STATE] " + old + " → " + neu));
        callbackHolder.setResponseListener(new NotificationCallbackHolder.ResponseListener() {
            @Override
            public void onResponseReceived(String response) {
                handleResponse(response);
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "[ERROR] " + e.getMessage());
                if (cmdListener != null) {
                    cmdListener.onCommandError(e.getMessage());
                }
            }
        });
    }

    @Override
    protected void initialize() {
        if (txChar != null) {
            enableNotifications(txChar)
                    .done(device -> {
                        Log.d(TAG, "[NOTIFY] OK");
                        stateManager.setState(BleState.READY);
                        if (connListener != null) {
                            connListener.onConnected();
                        }
                        processQueue();
                    })
                    .fail((device, status) -> {
                        Log.e(TAG, "[NOTIFY] FAIL: " + status);
                        stateManager.setState(BleState.ERROR);
                    })
                    .enqueue();

            setNotificationCallback(txChar).with(callbackHolder.getCallback());
        }
    }

    @Override
    public boolean isRequiredServiceSupported(@NonNull android.bluetooth.BluetoothGatt gatt) {
        android.bluetooth.BluetoothGattService svc = gatt.getService(SERVICE_UUID);
        if (svc == null) return false;

        rxChar = svc.getCharacteristic(RX_UUID);
        txChar = svc.getCharacteristic(TX_UUID);

        Log.d(TAG, "[SERVICE] OK");
        return rxChar != null && txChar != null;
    }

    protected void onDeviceDisconnected() {
        Log.d(TAG, "[DISCONNECT] OK");
        stateManager.setState(BleState.DISCONNECTED);
        commandQueue.clear();
        callbackHolder.resetCallback();
        rxChar = null;
        txChar = null;

        if (connListener != null) {
            connListener.onDisconnected();
        }
    }

    public void connectToDevice(@NonNull BluetoothDevice device) {
        if (stateManager.isState(BleState.CONNECTING) || stateManager.isState(BleState.READY)) {
            Log.w(TAG, "[CONNECT] BUSY");
            return;
        }

        stateManager.setState(BleState.CONNECTING);
        Log.d(TAG, "[CONNECT] " + device.getAddress());

        connect(device)
                .retry(3, 100)
                .timeout(5000)
                .done(d -> Log.d(TAG, "[CONNECT] OK"))
                .fail((d, status) -> {
                    Log.e(TAG, "[CONNECT] FAIL: " + status);
                    stateManager.setState(BleState.ERROR);
                    if (connListener != null) {
                        connListener.onError("Fail: " + status);
                    }
                })
                .enqueue();
    }

    public void disconnectDevice() {
        Log.d(TAG, "[DISCONNECT] Requesting...");
        super.disconnect().enqueue();
    }

    public void sendCommand(String command) {
        if (!stateManager.canSendCommand()) {
            Log.e(TAG, "[SEND] Bad state: " + stateManager.getState());
            if (cmdListener != null) {
                cmdListener.onCommandError("Bad state");
            }
            return;
        }

        if (commandQueue.isProcessing()) {
            Log.e(TAG, "[SEND] Busy");
            if (cmdListener != null) {
                cmdListener.onCommandError("Busy");
            }
            return;
        }

        commandQueue.enqueue(new BleCommand(command, RX_UUID));
        Log.d(TAG, "[SEND] Queued: " + command);
        processQueue();
    }

    private synchronized void processQueue() {
        if (!stateManager.canSendCommand() || commandQueue.isProcessing() || rxChar == null) {
            return;
        }

        BleCommand cmd = commandQueue.dequeue();
        if (cmd == null) {
            return;
        }

        stateManager.setState(BleState.PROCESSING);
        commandQueue.setCurrentCommand(cmd);

        Log.d(TAG, "[QUEUE] Sending: " + cmd.getCommand());

        writeCharacteristic(rxChar, cmd.getData(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .done(device -> {
                    Log.d(TAG, "[WRITE] OK: " + cmd.getCommand());
                    if (cmdListener != null) {
                        cmdListener.onCommandSent(cmd.getCommand());
                    }
                })
                .fail((device, status) -> {
                    Log.e(TAG, "[WRITE] FAIL: " + status);
                    stateManager.setState(BleState.ERROR);
                    commandQueue.clearCurrentCommand();
                    if (cmdListener != null) {
                        cmdListener.onCommandError("Write fail");
                    }
                })
                .enqueue();
    }

    private void handleResponse(String response) {
        Log.d(TAG, "[RESPONSE] " + response);

        if (!stateManager.isState(BleState.PROCESSING)) {
            return;
        }

        if (cmdListener != null) {
            cmdListener.onResponseReceived(response);
        }

        stateManager.setState(BleState.COMPLETED);
        commandQueue.clearCurrentCommand();
        stateManager.setState(BleState.READY);
        processQueue();
    }

    public void setConnListener(ConnectionListener listener) {
        this.connListener = listener;
    }

    public void setCmdListener(CommandListener listener) {
        this.cmdListener = listener;
    }

    public BleState getState() {
        return stateManager.getState();
    }

    public boolean isConnectedDevice() {
        return stateManager.isConnected();
    }
}
