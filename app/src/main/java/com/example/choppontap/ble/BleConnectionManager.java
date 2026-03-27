package com.example.choppontap.ble;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.data.Data;

/**
 * Gerenciador de conexão BLE centralizado
 * Uma única instância por dispositivo
 * Gerencia conexão, notificações, envio de comandos
 */
public class BleConnectionManager extends BleManager {
    private static final String TAG = "BleConnectionManager";

    // UUIDs do serviço
    private static final UUID SERVICE_UUID = UUID.fromString("7f0a0001-7b6b-4b5f-9d3e-3c7b9f100001");
    private static final UUID RX_CHARACTERISTIC = UUID.fromString("7f0a0002-7b6b-4b5f-9d3e-3c7b9f100001"); // Write
    private static final UUID TX_CHARACTERISTIC = UUID.fromString("7f0a0003-7b6b-4b5f-9d3e-3c7b9f100001"); // Notify

    private final StateManager stateManager = new StateManager();
    private final CommandQueue commandQueue = new CommandQueue();
    private final NotificationCallbackHolder callbackHolder = new NotificationCallbackHolder();

    private static BleConnectionManager instance;
    private static final Object instanceLock = new Object();

    // Listeners
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

    private ConnectionListener connectionListener;
    private CommandListener commandListener;

    /**
     * Singleton instance
     */
    public static BleConnectionManager getInstance(Context context) {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new BleConnectionManager(context);
                }
            }
        }
        return instance;
    }

    private BleConnectionManager(Context context) {
        super(context);
        setupStateListener();
        setupNotificationListener();
    }

    /**
     * Inicializa suporte a notificações
     */
    @Override
    protected void gattClientReady(@NonNull android.bluetooth.BluetoothGatt gatt) {
        Log.d(TAG, "[GATT] Cliente pronto");
        stateManager.setState(BleState.READY);

        if (connectionListener != null) {
            connectionListener.onConnected();
        }

        // Processa fila de comandos
        processCommandQueue();
    }

    /**
     * Inicializa características
     */
    @Override
    protected void initialize() {
        Log.d(TAG, "[INIT] Inicializando BleManager");

        // Abilita notificações no TX
        enableNotifications(TX_CHARACTERISTIC)
                .done(device -> Log.d(TAG, "[NOTIFY] Notificações habilitadas"))
                .fail((device, status) -> {
                    Log.e(TAG, "[NOTIFY] Falha ao habilitar notificações: " + status);
                    stateManager.setState(BleState.ERROR);
                })
                .enqueue();

        // Registra callback (UMA ÚNICA VEZ)
        setNotificationCallback(TX_CHARACTERISTIC)
                .with(callbackHolder.getCallback());
    }

    /**
     * Valida serviço UART
     */
    @Override
    public boolean isRequiredServiceSupported(@NonNull android.bluetooth.BluetoothGatt gatt) {
        final android.bluetooth.BluetoothGattService service = gatt.getService(SERVICE_UUID);

        if (service == null) {
            Log.e(TAG, "[SERVICE] Serviço não encontrado");
            return false;
        }

        final android.bluetooth.BluetoothGattCharacteristic rxChar = service.getCharacteristic(RX_CHARACTERISTIC);
        final android.bluetooth.BluetoothGattCharacteristic txChar = service.getCharacteristic(TX_CHARACTERISTIC);

        if (rxChar == null || txChar == null) {
            Log.e(TAG, "[SERVICE] Características não encontradas");
            return false;
        }

        Log.d(TAG, "[SERVICE] Serviço verificado com sucesso");
        return true;
    }

    /**
     * Handle disconnect
     */
    @Override
    protected void onDeviceDisconnected() {
        Log.d(TAG, "[DISCONNECT] Dispositivo desconectado");
        stateManager.setState(BleState.DISCONNECTED);
        commandQueue.clear();
        callbackHolder.resetCallback();

        if (connectionListener != null) {
            connectionListener.onDisconnected();
        }
    }

    /**
     * Conecta a dispositivo BLE
     */
    public void connectToDevice(@NonNull android.bluetooth.BluetoothDevice device) {
        if (stateManager.isState(BleState.CONNECTING) || stateManager.isState(BleState.READY)) {
            Log.w(TAG, "[CONNECT] Já conectado ou conectando");
            return;
        }

        stateManager.setState(BleState.CONNECTING);
        Log.d(TAG, "[CONNECT] Iniciando conexão com: " + device.getAddress());

        connect(device)
                .retry(3, 100)
                .timeout(5000)
                .done(d -> {
                    Log.d(TAG, "[CONNECT] Conexão estabelecida");
                })
                .fail((d, status) -> {
                    Log.e(TAG, "[CONNECT] Falha na conexão: " + status);
                    stateManager.setState(BleState.ERROR);
                    if (connectionListener != null) {
                        connectionListener.onError("Falha ao conectar: " + status);
                    }
                })
                .enqueue();
    }

    /**
     * Desconecta do dispositivo
     */
    public void disconnect() {
        Log.d(TAG, "[DISCONNECT] Desconectando...");
        disconnect().enqueue();
    }

    /**
     * Envia comando para ESP32
     */
    public void sendCommand(String command) {
        if (!stateManager.canSendCommand()) {
            String error = "Estado inválido para envio: " + stateManager.getState();
            Log.e(TAG, "[SEND] " + error);
            if (commandListener != null) {
                commandListener.onCommandError(error);
            }
            return;
        }

        if (commandQueue.isProcessing()) {
            String error = "Já há comando em processamento";
            Log.e(TAG, "[SEND] " + error);
            if (commandListener != null) {
                commandListener.onCommandError(error);
            }
            return;
        }

        BleCommand bleCmd = new BleCommand(command, RX_CHARACTERISTIC);
        commandQueue.enqueue(bleCmd);
        Log.d(TAG, "[SEND] Comando enfileirado: " + command);

        processCommandQueue();
    }

    /**
     * Processa fila de comandos sequencialmente
     */
    private synchronized void processCommandQueue() {
        if (!stateManager.canSendCommand()) {
            Log.w(TAG, "[QUEUE] Estado não permite processamento");
            return;
        }

        if (commandQueue.isProcessing()) {
            Log.d(TAG, "[QUEUE] Comando já em processamento");
            return;
        }

        BleCommand command = commandQueue.dequeue();
        if (command == null) {
            Log.d(TAG, "[QUEUE] Fila vazia");
            return;
        }

        stateManager.setState(BleState.PROCESSING);
        commandQueue.setCurrentCommand(command);

        Log.d(TAG, "[QUEUE] Enviando comando: " + command.getCommand());

        writeCharacteristic(RX_CHARACTERISTIC, command.getData(),
                android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .done(device -> {
                    Log.d(TAG, "[WRITE] Comando escrito: " + command.getCommand());
                    if (commandListener != null) {
                        commandListener.onCommandSent(command.getCommand());
                    }
                })
                .fail((device, status) -> {
                    Log.e(TAG, "[WRITE] Falha ao escrever: " + status);
                    stateManager.setState(BleState.ERROR);
                    commandQueue.clearCurrentCommand();
                    if (commandListener != null) {
                        commandListener.onCommandError("Falha ao escrever: " + status);
                    }
                })
                .enqueue();
    }

    /**
     * Setup do listener de resposta
     */
    private void setupNotificationListener() {
        callbackHolder.setResponseListener(new NotificationCallbackHolder.ResponseListener() {
            @Override
            public void onResponseReceived(String response) {
                handleResponse(response);
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "[ERROR] Erro em callback: " + e.getMessage());
                if (commandListener != null) {
                    commandListener.onCommandError("Erro ao processar resposta: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Processa resposta do ESP32
     */
    private void handleResponse(String response) {
        Log.d(TAG, "[RESPONSE] Recebido: " + response);

        if (!stateManager.isState(BleState.PROCESSING)) {
            Log.w(TAG, "[RESPONSE] Estado inválido: " + stateManager.getState());
            return;
        }

        BleCommand currentCmd = commandQueue.getCurrentCommand();
        if (currentCmd == null) {
            Log.w(TAG, "[RESPONSE] Nenhum comando em processamento");
            return;
        }

        // Callback de comando
        if (commandListener != null) {
            commandListener.onResponseReceived(response);
        }

        // Completa e próximo comando
        stateManager.setState(BleState.COMPLETED);
        commandQueue.clearCurrentCommand();

        // Voltar para READY e processar fila
        stateManager.setState(BleState.READY);
        processCommandQueue();
    }

    /**
     * Setup do listener de estado
     */
    private void setupStateListener() {
        stateManager.setStateListener((oldState, newState) -> {
            Log.d(TAG, "[STATE] " + oldState + " → " + newState);
        });
    }

    // Getters para listeners

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    public void setCommandListener(CommandListener listener) {
        this.commandListener = listener;
    }

    public StateManager getStateManager() {
        return stateManager;
    }

    public boolean isConnected() {
        return stateManager.isConnected();
    }

    public BleState getState() {
        return stateManager.getState();
    }
}
