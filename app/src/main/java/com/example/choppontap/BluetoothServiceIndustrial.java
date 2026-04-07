package com.example.choppontap;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Serviço BLE NUS Simplificado - Conexão Direta
 * Focado exclusivamente na função de "Abrir Válvula -> Monitorar Volume -> Fechar Válvula".
 */
@SuppressLint("MissingPermission") // Assumindo que as permissões são tratadas na Activity
public class BluetoothServiceIndustrial extends Service {

    private static final String TAG = "BLE_INDUSTRIAL";

    // Singleton
    private static volatile BluetoothServiceIndustrial sInstance;
    public static BluetoothServiceIndustrial getInstance() { return sInstance; }
    public static boolean isRunning() { return sInstance != null; }

    // Estados
    public enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        READY,
        ERROR
    }
    private volatile State mState = State.DISCONNECTED;

    // UUIDs NUS (Nordic UART Service)
    private static final UUID NUS_SERVICE_UUID           = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_RX_CHARACTERISTIC_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_TX_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CCCD_UUID                  = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Ações de Broadcast
    public static final String ACTION_DATA_AVAILABLE    = "com.example.choppontap.ACTION_DATA_AVAILABLE";
    public static final String ACTION_CONNECTION_STATUS = "com.example.choppontap.ACTION_CONNECTION_STATUS";
    public static final String ACTION_BLE_STATE_CHANGED = "com.example.choppontap.ACTION_BLE_STATE_CHANGED";
    public static final String EXTRA_DATA      = "com.example.choppontap.EXTRA_DATA";
    public static final String EXTRA_STATUS    = "com.example.choppontap.EXTRA_STATUS";
    public static final String EXTRA_BLE_STATE = "com.example.choppontap.EXTRA_BLE_STATE";
    public static final String ACTION_DEVICE_FOUND      = "com.example.choppontap.ACTION_DEVICE_FOUND";
    public static final String EXTRA_DEVICE    = "com.example.choppontap.EXTRA_DEVICE";
    public static final String ACTION_WRITE_READY       = "com.example.choppontap.ACTION_WRITE_READY";

    // Foreground Service
    private static final String NOTIF_CHANNEL_ID = "ble_industrial_channel";
    private static final int    NOTIF_ID         = 1001;

    // Variáveis de Instância
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothGattCharacteristic mWriteCharacteristic;
    private Handler mMainHandler;
    private String mTargetMac;

    // Binder
    public class LocalBinder extends Binder {
        public BluetoothServiceIndustrial getService() {
            return BluetoothServiceIndustrial.this;
        }
    }
    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mMainHandler = new Handler(Looper.getMainLooper());
        Log.i(TAG, "[SERVICE] BluetoothServiceIndustrial Simplificado iniciado");

        criarNotificacaoForeground();

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            mBluetoothAdapter = bm.getAdapter();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "[SERVICE] SERVICE DESTROYED");
        sInstance = null;
        closeGatt();
        transitionTo(State.DISCONNECTED);
        super.onDestroy();
    }

    private void transitionTo(State newState) {
        if (mState == newState) return;
        mState = newState;
        Log.i(TAG, "=== STATE: " + newState.name() + " ===");
        broadcastBleState(newState);
        
        if (newState == State.DISCONNECTED || newState == State.ERROR) {
            mWriteCharacteristic = null;
        }
    }

    /**
     * Conecta diretamente ao MAC fornecido.
     */
    public void connectWithMac(String mac) {
        if (mac == null || mac.isEmpty() || mBluetoothAdapter == null) {
            Log.e(TAG, "[CONNECT] MAC inválido ou BluetoothAdapter nulo");
            return;
        }
        
        mTargetMac = mac;
        Log.i(TAG, "[CONNECT] Conectando diretamente ao MAC: " + mac);
        
        if (mState != State.DISCONNECTED) {
            closeGatt();
        }

        transitionTo(State.CONNECTING);
        broadcastConnectionStatus("connecting");

        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mac);
        
        // Conexão direta (autoConnect = false para conexão mais rápida)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mBluetoothGatt = device.connectGatt(this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        }
    }

    /**
     * Envia o comando de volume para o ESP32.
     * Exemplo: enviarVolume(300) enviará "$ML:300"
     */
    public State getState() { return mState; }
    public boolean isReady() { return mState == State.READY; }
    public boolean connected() { return mState == State.CONNECTED || mState == State.READY; }
    public void disconnect() { closeGatt(); transitionTo(State.DISCONNECTED); }
    public void scanLeDevice(boolean enable) { /* No-op in simplified version */ }
    public void enableAutoReconnect() { /* No-op in simplified version */ }
    public void salvarMacExterno(String mac) {
        if (mac != null && !mac.isEmpty()) {
            mTargetMac = mac;
            getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .edit().putString("esp32_mac", mac).apply();
        }
    }
    public boolean write(String command) {
        if (mState != State.READY || mWriteCharacteristic == null || mBluetoothGatt == null) return false;
        byte[] value = command.getBytes(StandardCharsets.UTF_8);
        mWriteCharacteristic.setValue(value);
        mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        boolean success = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
        if (!success) {
            mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            success = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
        }
        return success;
    }
    public void enqueueServeCommand(int volumeMl, String checkoutId) {
        if (isReady()) {
            write("$ML:" + volumeMl);
        }
    }

    public void enviarVolume(int ml) {
        if (mState != State.READY || mWriteCharacteristic == null || mBluetoothGatt == null) {
            Log.e(TAG, "[TX] Erro: BLE não está READY ou Characteristic nula");
            return;
        }

        String comando = "$ML:" + ml;
        Log.i(TAG, "[TX] Enviando comando: " + comando);
        
        byte[] value = comando.getBytes(StandardCharsets.UTF_8);
        mWriteCharacteristic.setValue(value);
        
        // Tenta usar WRITE_TYPE_NO_RESPONSE primeiro (mais rápido)
        mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        boolean success = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
        
        if (!success) {
            Log.w(TAG, "[TX] Falha no WRITE_TYPE_NO_RESPONSE, tentando WRITE_TYPE_DEFAULT");
            mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
        }
    }

    /**
     * Envia comando para parada imediata (opcional, caso o usuário cancele)
     */
    public void pararChopp() {
        if (mState == State.READY && mWriteCharacteristic != null && mBluetoothGatt != null) {
            Log.i(TAG, "[TX] Enviando comando de parada: $ML:0");
            mWriteCharacteristic.setValue("$ML:0".getBytes(StandardCharsets.UTF_8));
            mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
        }
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.i(TAG, "[BLE] onConnectionStateChange | status=" + status + " | newState=" + newState);
            
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "[BLE] Conectado! Solicitando MTU e descobrindo serviços...");
                transitionTo(State.CONNECTED);
                broadcastConnectionStatus("connected");
                
                // Solicita MTU maior para evitar fragmentação
                gatt.requestMtu(247);
                
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "[BLE] Desconectado.");
                closeGatt();
                transitionTo(State.DISCONNECTED);
                broadcastConnectionStatus("disconnected");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "[BLE] MTU alterado para: " + mtu + ". Iniciando discoverServices()");
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[BLE] Falha ao descobrir serviços: " + status);
                return;
            }

            BluetoothGattService nusService = gatt.getService(NUS_SERVICE_UUID);
            if (nusService == null) {
                Log.e(TAG, "[BLE] Serviço NUS não encontrado!");
                return;
            }

            mWriteCharacteristic = nusService.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
            BluetoothGattCharacteristic notifyChar = nusService.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID);

            if (mWriteCharacteristic != null && notifyChar != null) {
                Log.i(TAG, "[BLE] Características RX e TX encontradas. Habilitando notificações...");
                habilitarNotificacoes(gatt, notifyChar);
            } else {
                Log.e(TAG, "[BLE] Características RX/TX ausentes!");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "[BLE] Notificações habilitadas com sucesso! Estado: READY");
                mMainHandler.post(() -> {
                    transitionTo(State.READY);
                    broadcastConnectionStatus("ready");
                });
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] raw = characteristic.getValue();
            if (raw != null && raw.length > 0) {
                String data = new String(raw, StandardCharsets.UTF_8).trim();
                Log.i(TAG, "[RX] Recebido: " + data);
                
                // Repassa o dado recebido (ex: "VP:150" ou "ML:300") para a Activity/ViewModel
                broadcastData(data);
            }
        }
    };

    private void habilitarNotificacoes(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        } else {
            Log.e(TAG, "[BLE] Descritor CCCD não encontrado na característica TX");
        }
    }

    private void closeGatt() {
        if (mBluetoothGatt != null) {
            try {
                mBluetoothGatt.disconnect();
                mBluetoothGatt.close();
            } catch (Exception e) {
                Log.e(TAG, "[BLE] Erro ao fechar GATT", e);
            }
            mBluetoothGatt = null;
        }
    }

    private void criarNotificacaoForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID, "Chopp BLE", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Intent notifIntent = new Intent(this, getClass()); // Idealmente apontar para a MainActivity
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent, flags);

        Notification.Builder builder = new Notification.Builder(this, 
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? NOTIF_CHANNEL_ID : null)
                .setContentTitle("Conexão Chopp Ativa")
                .setContentText("Mantendo comunicação com a chopeira...")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        startForeground(NOTIF_ID, builder.build());
    }

    // Broadcasts
    private void broadcastConnectionStatus(String status) {
        Intent i = new Intent(ACTION_CONNECTION_STATUS);
        i.putExtra(EXTRA_STATUS, status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void broadcastData(String data) {
        Intent i = new Intent(ACTION_DATA_AVAILABLE);
        i.putExtra(EXTRA_DATA, data);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void broadcastBleState(State state) {
        Intent i = new Intent(ACTION_BLE_STATE_CHANGED);
        i.putExtra(EXTRA_BLE_STATE, state.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }
}
