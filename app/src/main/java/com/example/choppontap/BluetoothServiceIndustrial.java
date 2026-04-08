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
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressLint("MissingPermission")
public class BluetoothServiceIndustrial extends Service {

    private static final String TAG = "BLE_INDUSTRIAL";

    public enum State { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, READY, ERROR }

    private static volatile BluetoothServiceIndustrial sInstance;
    public static BluetoothServiceIndustrial getInstance() { return sInstance; }
    public static boolean isRunning() { return sInstance != null; }

    public static final String ACTION_DATA_AVAILABLE    = "com.example.choppontap.ACTION_DATA_AVAILABLE";
    public static final String ACTION_CONNECTION_STATUS = "com.example.choppontap.ACTION_CONNECTION_STATUS";
    public static final String ACTION_BLE_STATE_CHANGED = "com.example.choppontap.ACTION_BLE_STATE_CHANGED";
    public static final String ACTION_DEVICE_FOUND      = "com.example.choppontap.ACTION_DEVICE_FOUND";
    public static final String ACTION_WRITE_READY       = "com.example.choppontap.ACTION_WRITE_READY";

    public static final String EXTRA_DATA      = "com.example.choppontap.EXTRA_DATA";
    public static final String EXTRA_STATUS    = "com.example.choppontap.EXTRA_STATUS";
    public static final String EXTRA_BLE_STATE = "com.example.choppontap.EXTRA_BLE_STATE";
    public static final String EXTRA_DEVICE    = "com.example.choppontap.EXTRA_DEVICE";

    private static final String NOTIF_CHANNEL_ID = "ble_industrial_channel";
    private static final int NOTIF_ID = 1001;

    private static final long SCAN_TIMEOUT_MS = 8_000L;
    private static final long PRE_CONNECT_DELAY_MS = 500L;
    private static final long[] RECONNECT_DELAYS_MS = { 2_000L, 4_000L, 8_000L, 15_000L, 30_000L };
    private static final long HEARTBEAT_INTERVAL_MS = 5_000L;
    private static final long PONG_STALE_MS = 15_000L;
    private static final int MAX_PING_MISSES = 3;

    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private UUID mServiceUuid = UUID.fromString(BleConfigUtils.DEFAULT_SERVICE_UUID);
    private UUID mRxUuid = UUID.fromString(BleConfigUtils.DEFAULT_RX_UUID);
    private UUID mTxUuid = UUID.fromString(BleConfigUtils.DEFAULT_TX_UUID);
    private int mPreferredMtu = BleConfigUtils.DEFAULT_MTU;
    private boolean mConfiguredAutoConnect = false;
    private String mExpectedBleName;
    private String mTargetBleMac;

    private volatile State mState = State.DISCONNECTED;
    private final AtomicInteger mFailCount = new AtomicInteger(0);
    private volatile boolean mAutoReconnect = true;
    private volatile boolean mScanning = false;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBleScanner;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothDevice mConnectedDevice;
    private BluetoothDevice mPendingConnectDevice;

    private BluetoothGattCharacteristic mWriteCharacteristic;
    private BluetoothGattCharacteristic mNotifyCharacteristic;

    private String mPendingCommand;
    private long mLastPongAtMs = 0L;
    private boolean mSessionValid = false;
    private int mPingMisses = 0;

    private Handler mMainHandler;

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

        Log.i(TAG, "[SERVICE] BluetoothServiceIndustrial iniciado");
        Log.i(TAG, "[SERVICE] Protocolo: Nordic UART Service (NUS)");

        criarNotificacaoForeground();

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            mBluetoothAdapter = bm.getAdapter();
            if (mBluetoothAdapter != null) {
                mBleScanner = mBluetoothAdapter.getBluetoothLeScanner();
            }
        }

        carregarConfigBle();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public void onDestroy() {
        mAutoReconnect = false;
        sInstance = null;

        stopHeartbeat();
        pararScan();
        closeGatt();
        mMainHandler.removeCallbacksAndMessages(null);
        transitionTo(State.DISCONNECTED);
        super.onDestroy();
    }

    public void connectWithMac(String mac) {
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "[CONNECT] BluetoothAdapter nulo");
            return;
        }

        Log.i(TAG, "[CONNECT] connectWithMac(" + mac + ")");
        carregarConfigBle();

        String normalized = BleConfigUtils.normalizeMac(mac);
        if (normalized != null) {
            mTargetBleMac = normalized;
            salvarMacPreferencia(normalized);
        } else if (mTargetBleMac == null || mTargetBleMac.isEmpty()) {
            SharedPreferences prefs = getSharedPreferences("tap_config", Context.MODE_PRIVATE);
            mTargetBleMac = BleConfigUtils.normalizeMac(
                    BleConfigUtils.firstNonBlank(
                            prefs.getString(BleConfigUtils.KEY_BLE_MAC, null),
                            prefs.getString("esp32_mac", null)
                    )
            );
        }

        if ((mExpectedBleName == null || mExpectedBleName.isEmpty()) && (mTargetBleMac == null || mTargetBleMac.isEmpty())) {
            broadcastConnectionStatus("error:missing_identity");
            transitionTo(State.ERROR);
            return;
        }

        if ((mState == State.CONNECTED || mState == State.READY)
                && sameAsCurrentTarget(mTargetBleMac, mConnectedDevice)) {
            Log.i(TAG, "[CONNECT] Já conectado ao MAC " + mTargetBleMac + " — ignorando");
            return;
        }

        mAutoReconnect = true;
        mFailCount.set(0);
        iniciarCicloConexao();
    }

    public void enviarVolume(int ml) {
        String command = "$ML:" + ml;
        if (!write(command)) {
            Log.e(TAG, "[TX] Falha ao enviar volume | estado=" + mState + " | cmd=" + command);
        }
    }

    public boolean write(String command) { return writeInternal(command, false); }
    public void pararChopp() { write("$ML:0"); }

    public void enqueueServeCommand(int volumeMl, String checkoutId) {
        String command = "$ML:" + volumeMl;
        if (isReady()) {
            write(command);
        } else {
            mPendingCommand = command;
        }
    }

    public State getState() { return mState; }
    public boolean isReady() { return mState == State.READY; }
    public boolean connected() { return mState == State.CONNECTED || mState == State.READY; }
    public boolean isSessionValid() { return mSessionValid; }

    public void disconnect() {
        mAutoReconnect = false;
        stopHeartbeat();
        mMainHandler.removeCallbacksAndMessages(null);
        pararScan();
        closeGatt();
        transitionTo(State.DISCONNECTED);
        broadcastConnectionStatus("disconnected");
    }

    public void scanLeDevice(boolean enable) {
        if (enable) connectWithMac(mTargetBleMac);
    }

    public void enableAutoReconnect() { mAutoReconnect = true; }

    public void salvarMacExterno(String mac) {
        String normalized = BleConfigUtils.normalizeMac(mac);
        if (normalized == null) return;

        mTargetBleMac = normalized;
        salvarMacPreferencia(normalized);
    }

    private void iniciarCicloConexao() {
        if (!mAutoReconnect) return;

        carregarConfigBle();
        stopHeartbeat();
        mPendingConnectDevice = null;

        pararScan();
        closeGatt();

        Log.i(TAG, "[SCAN] Iniciando ciclo de conexão");
        Log.i(TAG, "[SCAN] MAC alvo: " + (mTargetBleMac != null ? mTargetBleMac : "(nenhum)"));
        Log.i(TAG, "[SCAN] Nome esperado: " + (mExpectedBleName != null ? mExpectedBleName : "(nenhum)"));

        transitionTo(State.SCANNING);
        broadcastConnectionStatus("scanning");
        iniciarScanIdentidade();
    }

    private void iniciarScanIdentidade() {
        if (mBleScanner == null) {
            mMainHandler.postDelayed(() -> executarConnectGatt(mPendingConnectDevice), PRE_CONNECT_DELAY_MS);
            return;
        }

        List<ScanFilter> filters = new ArrayList<>();
        ScanFilter.Builder builder = new ScanFilter.Builder();
        if (mExpectedBleName != null && !mExpectedBleName.isEmpty()) {
            builder.setDeviceName(mExpectedBleName);
        } else if (mTargetBleMac != null && !mTargetBleMac.isEmpty()) {
            builder.setDeviceAddress(mTargetBleMac);
        }
        filters.add(builder.build());

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            mScanning = true;
            mBleScanner.startScan(filters, settings, mScanCallback);
        } catch (Exception e) {
            mScanning = false;
            mMainHandler.postDelayed(() -> executarConnectGatt(mPendingConnectDevice), PRE_CONNECT_DELAY_MS);
            return;
        }

        mMainHandler.postDelayed(() -> {
            if (!mScanning) return;
            pararScan();
            transitionTo(State.DISCONNECTED);
            broadcastConnectionStatus("disconnected:scan_timeout");
            agendarReconexao();
        }, SCAN_TIMEOUT_MS);
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;

            String name = readDeviceName(result);
            if (!isDispositivoEsperado(device, name)) return;

            Log.i(TAG, "[SCAN] ✅ ESP32 encontrado! MAC=" + device.getAddress()
                    + " | Nome=" + (name != null ? name : "(sem nome)")
                    + " | RSSI=" + result.getRssi() + "dBm");

            mPendingConnectDevice = device;
            broadcastDeviceFound(device);
            pararScan();
            mMainHandler.postDelayed(() -> executarConnectGatt(device), PRE_CONNECT_DELAY_MS);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "[SCAN] Falha no scan: errorCode=" + errorCode);
            mScanning = false;
            mMainHandler.postDelayed(() -> executarConnectGatt(mPendingConnectDevice), PRE_CONNECT_DELAY_MS);
        }
    };

    private void executarConnectGatt(BluetoothDevice candidate) {
        if (!mAutoReconnect || mBluetoothAdapter == null) return;

        BluetoothDevice device = candidate;
        if (device == null && mTargetBleMac != null && !mTargetBleMac.isEmpty()) {
            try {
                device = mBluetoothAdapter.getRemoteDevice(mTargetBleMac);
            } catch (Exception ignored) {
                device = null;
            }
        }

        if (device == null) {
            transitionTo(State.DISCONNECTED);
            agendarReconexao();
            return;
        }

        transitionTo(State.CONNECTING);
        broadcastConnectionStatus("connecting");

        try {
            Log.i(TAG, "[CONNECT] connectGatt() | MAC=" + device.getAddress()
                    + " | tentativa=" + (mFailCount.get() + 1)
                    + " | autoConnect=false | TRANSPORT_LE");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mBluetoothGatt = device.connectGatt(this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
            }
            mPendingConnectDevice = device;
        } catch (Exception e) {
            Log.e(TAG, "[CONNECT] Exceção em connectGatt(): " + e.getMessage());
            transitionTo(State.DISCONNECTED);
            agendarReconexao();
        }
    }

    private void agendarReconexao() {
        if (!mAutoReconnect) return;

        int falhas = mFailCount.incrementAndGet();
        int idx = Math.min(falhas - 1, RECONNECT_DELAYS_MS.length - 1);
        long delay = RECONNECT_DELAYS_MS[idx];

        Log.w(TAG, "[RECONNECT] Falha #" + falhas + " — próxima tentativa (com scan) em " + delay + "ms");

        if (falhas == 3) {
            Log.w(TAG, "[RECONNECT] 3 falhas — tentando refresh do cache GATT");
            refreshGattCache();
        }
        mMainHandler.postDelayed(this::iniciarCicloConexao, delay);
    }

    private void reconectarComBackoff() {
        agendarReconexao();
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                mBluetoothGatt = gatt;
                mConnectedDevice = gatt.getDevice();
                mFailCount.set(0);

                Log.i(TAG, "[BLE] ✅ CONECTADO! MAC=" + gatt.getDevice().getAddress()
                        + " | sem pareamento/PIN (firmware atual)");

                transitionTo(State.CONNECTED);
                broadcastConnectionStatus("connected");
                requestMtuSegura(gatt);
                return;
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "[BLE] onConnectionStateChange | status=" + status
                        + " | newState=DISCONNECTED | mac=" + (mTargetBleMac != null ? mTargetBleMac : "?"));
                stopHeartbeat();

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "[BLE] Desconexão limpa (status=0)");
                    closeGatt();
                    transitionTo(State.DISCONNECTED);
                    broadcastConnectionStatus("disconnected");
                    if (mAutoReconnect) {
                        mMainHandler.postDelayed(BluetoothServiceIndustrial.this::iniciarCicloConexao, 2_000L);
                    }
                } else if (status == 133) {
                    Log.w(TAG, "[GATT] GATT_ERROR (133) → closeGatt + backoff (autoConnect=false)");
                    closeGatt();
                    transitionTo(State.DISCONNECTED);
                    broadcastConnectionStatus("disconnected:133");
                    mMainHandler.postDelayed(() -> {
                        if (mAutoReconnect) reconectarComBackoff();
                    }, 1_000L);
                } else if (status == 8) {
                    Log.w(TAG, "[BLE] GATT_CONN_TIMEOUT (8) — conexão perdida por distância/interferência.");
                    closeGatt();
                    transitionTo(State.DISCONNECTED);
                    broadcastConnectionStatus("disconnected");
                    if (mAutoReconnect) {
                        mMainHandler.postDelayed(() -> {
                            mFailCount.set(0);
                            iniciarCicloConexao();
                        }, 3_000L);
                    }
                } else {
                    Log.w(TAG, "[BLE] Erro GATT desconhecido: status=" + status);
                    closeGatt();
                    transitionTo(State.ERROR);
                    broadcastConnectionStatus("error:" + status);
                    if (mAutoReconnect) agendarReconexao();
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "[BLE] MTU negociado: " + mtu + " bytes (status=" + status + "). Iniciando discoverServices()");
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[BLE] Falha ao descobrir serviços: status=" + status);
                agendarReconexao();
                return;
            }

            Log.i(TAG, "[BLE] Serviços descobertos. Procurando NUS (" + mServiceUuid + ")...");

            BluetoothGattService nusService = gatt.getService(mServiceUuid);
            if (nusService == null) {
                Log.e(TAG, "[BLE] Serviço NUS não encontrado! Verifique o firmware do ESP32.");
                refreshGattCache();
                agendarReconexao();
                return;
            }

            mWriteCharacteristic = nusService.getCharacteristic(mRxUuid);
            mNotifyCharacteristic = nusService.getCharacteristic(mTxUuid);

            if (mWriteCharacteristic == null || mNotifyCharacteristic == null) {
                Log.e(TAG, "[BLE] Características RX/TX não encontradas no serviço NUS!");
                agendarReconexao();
                return;
            }

            Log.i(TAG, "[BLE] NUS OK — RX e TX encontrados. Habilitando notificações na TX...");
            habilitarNotificacoes(gatt, mNotifyCharacteristic);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (descriptor == null || descriptor.getUuid() == null) return;
            if (!CCCD_UUID.equals(descriptor.getUuid())) return;

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[BLE] Falha ao escrever CCCD (notificações): status=" + status);
                agendarReconexao();
                return;
            }

            Log.i(TAG, "[BLE] ✅ Notificações habilitadas! Estado: READY");

            mMainHandler.post(() -> {
                transitionTo(State.READY);
                broadcastConnectionStatus("ready");
                LocalBroadcastManager.getInstance(BluetoothServiceIndustrial.this)
                        .sendBroadcast(new Intent(ACTION_WRITE_READY));

                mSessionValid = false;
                mLastPongAtMs = 0L;
                mPingMisses = 0;
                startHeartbeat();  // Inicia PING a cada 5s conforme firmware

                if (mPendingCommand != null) {
                    String cmd = mPendingCommand;
                    mPendingCommand = null;
                    Log.i(TAG, "[BLE] Enviando comando pendente: " + cmd);
                    write(cmd);
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] raw = characteristic.getValue();
            if (raw == null || raw.length == 0) return;

            String payload = new String(raw, StandardCharsets.UTF_8).trim();
            if (payload.isEmpty()) return;

            String[] lines = payload.split("\\r?\\n");
            for (String line : lines) {
                String msg = line.trim();
                if (msg.isEmpty()) continue;
                tratarMensagemEntrada(msg);
            }
        }
    };

    private void requestMtuSegura(BluetoothGatt gatt) {
        if (gatt == null) return;

        int targetMtu = mPreferredMtu > 23 ? mPreferredMtu : BleConfigUtils.DEFAULT_MTU;
        boolean mtuRequested = false;
        try {
            mtuRequested = gatt.requestMtu(targetMtu);
        } catch (Exception e) {
            Log.e(TAG, "[BLE] Erro ao requestMtu: " + e.getMessage());
        }

        if (!mtuRequested) {
            gatt.discoverServices();
        }
    }

    private boolean writeInternal(String command, boolean heartbeat) {
        if (command == null || command.trim().isEmpty()) return false;

        if (mState != State.READY || mWriteCharacteristic == null || mBluetoothGatt == null) {
            if (!heartbeat) {
                Log.w(TAG, "[TX] write() ignorado - estado=" + mState + " | cmd=" + command);
            }
            return false;
        }

        byte[] value = command.getBytes(StandardCharsets.UTF_8);
        mWriteCharacteristic.setValue(value);

        mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        return mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
    }

    private void tratarMensagemEntrada(String msg) {
        Log.i(TAG, "[RX] Recebido do ESP32: " + msg);

        if ("PONG".equalsIgnoreCase(msg)) {
            mLastPongAtMs = SystemClock.elapsedRealtime();
            mSessionValid = true;
            mPingMisses = 0;
            Log.d(TAG, "[PING] PONG recebido — conexão confirmada ativa");
            broadcastData(msg);
            return;
        }

        if ("OK".equalsIgnoreCase(msg)) {
            // ESP32 confirmou recebimento do comando $ML
            Log.i(TAG, "[RX] OK — ESP32 confirmou o comando");

        } else if ("ERRO".equalsIgnoreCase(msg) || "ERROR".equalsIgnoreCase(msg)) {
            Log.w(TAG, "[RX] ERRO recebido do ESP32");

        } else if (msg.startsWith("VP:")) {
            // Volume parcial: VP:50, VP:150...
            Log.i(TAG, "[RX] Volume parcial: " + msg);

        } else if (msg.startsWith("ML:")) {
            // Volume final concluído: ML:300
            Log.i(TAG, "[RX] ✅ Volume final concluído: " + msg);

        } else if (msg.startsWith("QP:")) {
            // Quantidade de pulsos: QP:1764
            Log.d(TAG, "[RX] Pulsos: " + msg);
        }

        broadcastData(msg);
    }

    private final Runnable mHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (mState != State.READY) return;

            mPingMisses++;
            Log.d(TAG, "[PING] Enviando PING (miss=" + mPingMisses + "/" + MAX_PING_MISSES + ")");
            writeInternal("PING", true);

            if (mLastPongAtMs > 0L) {
                long delta = SystemClock.elapsedRealtime() - mLastPongAtMs;
                if (delta > PONG_STALE_MS) {
                    mSessionValid = false;
                    Log.w(TAG, "[HB] ⚠️ Sem PONG há " + delta + "ms — sessão inválida");
                }
            }

            if (mPingMisses >= MAX_PING_MISSES && !mSessionValid) {
                Log.w(TAG, "[PING] Limite de misses atingido (" + mPingMisses + "/" + MAX_PING_MISSES + ")");
            }

            mMainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private void startHeartbeat() {
        stopHeartbeat();
        mMainHandler.post(mHeartbeatRunnable);
    }

    private void stopHeartbeat() {
        mSessionValid = false;
        mLastPongAtMs = 0L;
        mPingMisses = 0;
        mMainHandler.removeCallbacks(mHeartbeatRunnable);
    }

    private void habilitarNotificacoes(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (gatt == null || characteristic == null) return;

        gatt.setCharacteristicNotification(characteristic, true);

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor == null) {
            agendarReconexao();
            return;
        }

        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        boolean cccdOk = gatt.writeDescriptor(descriptor);
        if (!cccdOk) {
            agendarReconexao();
        }
    }

    private void transitionTo(State newState) {
        if (mState == newState) return;

        mState = newState;
        broadcastBleState(newState);

        if (newState != State.READY) {
            stopHeartbeat();
        }

        if (newState == State.DISCONNECTED || newState == State.ERROR) {
            mWriteCharacteristic = null;
            mNotifyCharacteristic = null;
        }
    }

    private void closeGatt() {
        if (mBluetoothGatt == null) return;

        try {
            mBluetoothGatt.disconnect();
        } catch (Exception ignored) {
        }

        try {
            mBluetoothGatt.close();
        } catch (Exception ignored) {
        }

        mBluetoothGatt = null;
        mConnectedDevice = null;
        mWriteCharacteristic = null;
        mNotifyCharacteristic = null;
    }

    private void refreshGattCache() {
        if (mBluetoothGatt == null) return;
        try {
            Method refresh = mBluetoothGatt.getClass().getMethod("refresh");
            refresh.invoke(mBluetoothGatt);
        } catch (Exception ignored) {
        }
    }

    private void pararScan() {
        if (!mScanning || mBleScanner == null) return;
        try {
            mBleScanner.stopScan(mScanCallback);
        } catch (Exception ignored) {
        }
        mScanning = false;
    }

    private boolean isDispositivoEsperado(BluetoothDevice device, String advName) {
        if (device == null) return false;

        String mac = BleConfigUtils.normalizeMac(device.getAddress());
        String expectedName = mExpectedBleName != null ? mExpectedBleName.toUpperCase(Locale.US) : null;
        String name = advName != null ? advName.toUpperCase(Locale.US) : null;

        if (expectedName != null && !expectedName.isEmpty()) {
            if (name == null || !expectedName.equals(name)) {
                return false;
            }
        }

        if (mTargetBleMac != null && !mTargetBleMac.isEmpty()) {
            return mTargetBleMac.equalsIgnoreCase(mac);
        }

        return true;
    }

    private String readDeviceName(ScanResult result) {
        if (result == null) return null;

        BluetoothDevice device = result.getDevice();
        if (device != null && device.getName() != null && !device.getName().trim().isEmpty()) {
            return device.getName().trim();
        }

        ScanRecord record = result.getScanRecord();
        if (record != null && record.getDeviceName() != null && !record.getDeviceName().trim().isEmpty()) {
            return record.getDeviceName().trim();
        }

        return null;
    }

    private boolean isCurrentDevice(BluetoothDevice device) {
        if (device == null) return false;

        String incomingMac = BleConfigUtils.normalizeMac(device.getAddress());
        String connectedMac = mConnectedDevice != null
                ? BleConfigUtils.normalizeMac(mConnectedDevice.getAddress())
                : null;

        if (connectedMac != null) {
            return connectedMac.equalsIgnoreCase(incomingMac);
        }

        if (mTargetBleMac != null) {
            return mTargetBleMac.equalsIgnoreCase(incomingMac);
        }

        return false;
    }

    private BluetoothDevice readDeviceExtra(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private boolean sameAsCurrentTarget(String targetMac, BluetoothDevice currentDevice) {
        if (targetMac == null || currentDevice == null) return false;
        String connectedMac = BleConfigUtils.normalizeMac(currentDevice.getAddress());
        return targetMac.equalsIgnoreCase(connectedMac);
    }

    private void salvarMacPreferencia(String mac) {
        SharedPreferences prefs = getSharedPreferences("tap_config", Context.MODE_PRIVATE);
        prefs.edit()
                .putString(BleConfigUtils.KEY_BLE_MAC, mac)
                .putString("esp32_mac", mac)
                .apply();
    }

    private void carregarConfigBle() {
        SharedPreferences prefs = getSharedPreferences("tap_config", Context.MODE_PRIVATE);

        String wifiMac = prefs.getString(BleConfigUtils.KEY_WIFI_MAC, null);
        String derivedName = BleConfigUtils.deriveBleNameFromWifiMac(wifiMac);

        String expectedName = BleConfigUtils.firstNonBlank(
                derivedName,
                prefs.getString(BleConfigUtils.KEY_BLE_NAME_EXPECTED, null),
                prefs.getString(BleConfigUtils.KEY_BLE_NAME_API, null)
        );
        if (expectedName != null) {
            mExpectedBleName = expectedName.toUpperCase(Locale.US);
        }

        String storedMac = BleConfigUtils.normalizeMac(
                BleConfigUtils.firstNonBlank(
                        prefs.getString(BleConfigUtils.KEY_BLE_MAC, null),
                        prefs.getString("esp32_mac", null)
                )
        );
        if (storedMac != null) {
            mTargetBleMac = storedMac;
        }

        mPreferredMtu = prefs.getInt(BleConfigUtils.KEY_MTU, BleConfigUtils.DEFAULT_MTU);

        String serviceRaw = BleConfigUtils.firstNonBlank(
                prefs.getString(BleConfigUtils.KEY_SERVICE_UUID, null),
                BleConfigUtils.DEFAULT_SERVICE_UUID
        );
        String rxRaw = BleConfigUtils.firstNonBlank(
                prefs.getString(BleConfigUtils.KEY_RX_UUID, null),
                BleConfigUtils.DEFAULT_RX_UUID
        );
        String txRaw = BleConfigUtils.firstNonBlank(
                prefs.getString(BleConfigUtils.KEY_TX_UUID, null),
                BleConfigUtils.DEFAULT_TX_UUID
        );

        try {
            mServiceUuid = UUID.fromString(serviceRaw);
        } catch (Exception ignored) {
            mServiceUuid = UUID.fromString(BleConfigUtils.DEFAULT_SERVICE_UUID);
        }

        try {
            mRxUuid = UUID.fromString(rxRaw);
        } catch (Exception ignored) {
            mRxUuid = UUID.fromString(BleConfigUtils.DEFAULT_RX_UUID);
        }

        try {
            mTxUuid = UUID.fromString(txRaw);
        } catch (Exception ignored) {
            mTxUuid = UUID.fromString(BleConfigUtils.DEFAULT_TX_UUID);
        }

        mConfiguredAutoConnect = prefs.getBoolean(BleConfigUtils.KEY_AUTO_CONNECT, false);
    }

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

    private void broadcastDeviceFound(BluetoothDevice device) {
        if (device == null) return;
        Intent i = new Intent(ACTION_DEVICE_FOUND);
        i.putExtra(EXTRA_DEVICE, device);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void criarNotificacaoForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "Chopp BLE",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Mantem a conexao com a chopeira ativa");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Intent notifIntent = new Intent(this, getClass());
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent, flags);

        Notification.Builder builder = new Notification.Builder(
                this,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? NOTIF_CHANNEL_ID : null
        )
                .setContentTitle("Conexao Chopp Ativa")
                .setContentText("Mantendo comunicacao com a chopeira...")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        startForeground(NOTIF_ID, builder.build());
    }
}
