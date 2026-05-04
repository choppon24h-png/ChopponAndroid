package com.example.choppontap;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BluetoothServiceIndustrial - compativel com firmware ASOARESBH/ESP32
 *
 * Protocolo: Nordic UART Service (NUS) - Just Works (sem PIN, sem bond)
 * Scan: por prefixo de nome "CHOPP_" (nao por MAC direto)
 * UUIDs: 6E400001/2/3-B5A3-F393-E0A9-E50E24DCCA9E
 *
 * Fluxo de conexao (conforme ANDROID_BLE_INTEGRACAO.md atualizado 24/04/2026):
 *   1. Scan BLE filtrando nome com prefixo "CHOPP_"
 *   2. connectGatt(context, false, callback, TRANSPORT_LE)
 *   3. onConnectionStateChange(CONNECTED) -> requestMtu(247)
 *   4. onMtuChanged -> discoverServices()
 *   5. onServicesDiscovered -> habilitar notificacoes TX (descriptor 0x2902)
 *   6. onDescriptorWrite -> estado READY
 *   7. READY -> PING a cada 5s; ao receber PONG sessao esta valida
 *
 * Broadcasts emitidos:
 *   BLE_STATUS_ACTION  com extra "status": scanning / connected / ready / disconnected:<motivo>
 *   BLE_DATA_ACTION    com extra "data":   string recebida do ESP32
 */
@SuppressLint("MissingPermission")
public class BluetoothServiceIndustrial extends Service {

    // -------------------------------------------------------------------------
    // Constantes publicas
    // -------------------------------------------------------------------------
    public static final String TAG = "BLE_INDUSTRIAL";

    public static final String BLE_STATUS_ACTION = "com.example.choppontap.BLE_STATUS";
    public static final String BLE_DATA_ACTION   = "com.example.choppontap.BLE_DATA";

    public static final String STATUS_SCANNING     = "scanning";
    public static final String STATUS_CONNECTED    = "connected";
    public static final String STATUS_READY        = "ready";
    public static final String STATUS_DISCONNECTED = "disconnected";

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------
    private static volatile boolean sRunning = false;

    // -------------------------------------------------------------------------
    // UUIDs (de BleConfigUtils)
    // -------------------------------------------------------------------------
    private static final UUID UUID_SERVICE = UUID.fromString(BleConfigUtils.SERVICE_UUID);
    private static final UUID UUID_RX      = UUID.fromString(BleConfigUtils.CHARACTERISTIC_UUID_RX);
    private static final UUID UUID_TX      = UUID.fromString(BleConfigUtils.CHARACTERISTIC_UUID_TX);
    private static final UUID UUID_CCCD    = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    // -------------------------------------------------------------------------
    // Estado interno
    // -------------------------------------------------------------------------
    private enum State { IDLE, SCANNING, CONNECTING, CONNECTED, READY }
    private volatile State mState = State.IDLE;

    private BluetoothAdapter            mAdapter;
    private BluetoothLeScanner          mScanner;
    private BluetoothGatt               mGatt;
    private BluetoothGattCharacteristic mRxChar;
    private BluetoothGattCharacteristic mTxChar;

    private String mTargetMac;
    private String mTargetWifiMac;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // Reconexao com backoff exponencial
    private int    mReconnectCount = 0;
    private static final int    MAX_RECONNECT  = 10;
    private static final long[] BACKOFF_MS     = {2000,4000,8000,15000,30000,30000,30000,30000,30000,30000};

    // PING keepalive (a cada 5s em estado READY)
    private static final long PING_INTERVAL_MS = 5000L;
    private final Runnable mPingRunnable        = this::sendPing;
    private final Runnable mScanTimeoutRunnable = this::onScanTimeout;

    // Notification Foreground Service
    private static final String CHANNEL_ID = "ble_industrial_channel";
    private static final int    NOTIF_ID   = 1001;

    // -------------------------------------------------------------------------
    // Binder
    // -------------------------------------------------------------------------
    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        public BluetoothServiceIndustrial getService() {
            return BluetoothServiceIndustrial.this;
        }
    }

    // =========================================================================
    // Ciclo de vida do Service
    // =========================================================================

    @Override
    public void onCreate() {
        super.onCreate();
        if (sRunning) {
            Log.w(TAG, "[SERVICE] Servico BLE ja esta rodando! Abortando onCreate duplicado");
            stopSelf();
            return;
        }
        try {
            sRunning = true;
            Log.i(TAG, "=================================");
            Log.i(TAG, "[SERVICE] BluetoothServiceIndustrial v3.0 SINGLETON iniciado");
            Log.i(TAG, "[SERVICE] Protocolo: Nordic UART Service (NUS)");
            Log.i(TAG, "=================================");

            createNotificationChannel();
            startForeground(NOTIF_ID, buildNotification("BLE inicializando..."));

            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            mAdapter = bm != null ? bm.getAdapter() : null;

            if (mAdapter == null || !mAdapter.isEnabled()) {
                Log.e(TAG, "[SERVICE] Bluetooth nao disponivel ou desligado");
                sRunning = false;
                stopSelf();
                return;
            }

            Log.i(TAG, "[BOND] BroadcastReceiver de pareamento registrado.");

        } catch (Exception e) {
            Log.e(TAG, "[SERVICE] Excecao em onCreate: " + e.getMessage(), e);
            sRunning = false;
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "[SERVICE] SERVICE DESTROYED");
        sRunning = false;
        disconnect(true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public static boolean isRunning() {
        return sRunning;
    }

    // =========================================================================
    // API publica
    // =========================================================================

    /**
     * Inicia conexao BLE buscando o dispositivo pelo nome CHOPP_XXXX.
     *
     * @param mac     MAC BLE do dispositivo (ex: "48:F6:EE:23:2A:6C") — usado para validacao pos-scan.
     * @param wifiMac MAC WiFi do dispositivo — usado para derivar o nome BLE esperado.
     *                Se null, usa mac como referencia de derivacao.
     */
    public void connectWithMac(String mac, String wifiMac) {
        mTargetMac     = mac;
        mTargetWifiMac = wifiMac != null ? wifiMac : mac;
        Log.i(TAG, "[CONNECT] connectWithMac(" + mac + ")");
        Log.i(TAG, "[CONNECT] Nome BLE direto esperado: "
                + BleConfigUtils.deriveBleNameFromWifiMac(mTargetWifiMac));
        Log.i(TAG, "[CONNECT] Nome BLE invertido esperado: "
                + BleConfigUtils.deriveBleNameFromWifiMacInverted(mTargetWifiMac));
        mReconnectCount = 0;
        startScanCycle();
    }

    /** Sobrecarga de compatibilidade — quando so ha um MAC (BLE = WiFi). */
    public void connectWithMac(String mac) {
        connectWithMac(mac, mac);
    }

    /** Envia um comando para o ESP32 via caracteristica RX. */
    public boolean sendCommand(String command) {
        if (mState != State.READY && mState != State.CONNECTED) {
            Log.w(TAG, "[CMD] Ignorado (estado=" + mState + "): " + command);
            return false;
        }
        if (mRxChar == null || mGatt == null) {
            Log.w(TAG, "[CMD] RxChar ou GATT nulo");
            return false;
        }
        byte[] bytes = (command + "\n").getBytes();
        mRxChar.setValue(bytes);
        mRxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        boolean ok = mGatt.writeCharacteristic(mRxChar);
        Log.d(TAG, "[CMD] Enviado=" + ok + " cmd=" + command.trim());
        return ok;
    }

    /** Desconecta e para de reconectar. */
    public void disconnect(boolean stopReconnect) {
        if (stopReconnect) {
            mReconnectCount = MAX_RECONNECT;
            mHandler.removeCallbacks(mPingRunnable);
            mHandler.removeCallbacks(mScanTimeoutRunnable);
        }
        stopScan();
        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
            mGatt = null;
        }
        mState  = State.IDLE;
        mRxChar = null;
        mTxChar = null;
    }

    public String getCurrentStatus() {
        return mState.name().toLowerCase();
    }

    // =========================================================================
    // Scan BLE - por prefixo de nome "CHOPP_" (nao por MAC direto)
    // =========================================================================

    private void startScanCycle() {
        stopScan();
        if (mAdapter == null || !mAdapter.isEnabled()) return;

        mScanner = mAdapter.getBluetoothLeScanner();
        if (mScanner == null) {
            Log.e(TAG, "[SCAN] BluetoothLeScanner nulo");
            scheduleReconnect("scanner_null");
            return;
        }

        boolean fallback = mReconnectCount >= 2;
        Log.i(TAG, "[SCAN] Iniciando ciclo de conexao" + (fallback ? " [MODO FALLBACK - prefixo CHOPP_]" : ""));
        Log.i(TAG, "[SCAN] MAC alvo: " + mTargetMac);
        Log.i(TAG, "[SCAN] Nome esperado: " + BleConfigUtils.deriveBleNameFromWifiMac(mTargetWifiMac));

        if (fallback) {
            Log.w(TAG, "[FALLBACK] Iniciando scan permissivo - procurando qualquer dispositivo com prefixo 'CHOPP_'");
            Log.w(TAG, "[FALLBACK] MAC esperado (referencia): " + mTargetMac);
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        // Sem filtro de nome no ScanFilter para aceitar as duas variantes (direta e invertida)
        List<ScanFilter> filters = new ArrayList<>();

        mState = State.SCANNING;
        broadcastStatus(STATUS_SCANNING);
        mScanner.startScan(filters, settings, mScanCallback);
        mHandler.postDelayed(mScanTimeoutRunnable, BleConfigUtils.SCAN_TIMEOUT_MS);
    }

    private void stopScan() {
        mHandler.removeCallbacks(mScanTimeoutRunnable);
        if (mScanner != null) {
            try { mScanner.stopScan(mScanCallback); } catch (Exception ignored) {}
            mScanner = null;
        }
    }

    private void onScanTimeout() {
        if (mState != State.SCANNING) return;
        boolean fallback = mReconnectCount >= 2;
        if (fallback) {
            Log.w(TAG, "[FALLBACK] Timeout - nenhum dispositivo CHOPP_ encontrado no ar.");
        }
        stopScan();
        scheduleReconnect("scan_timeout");
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            ScanRecord     record  = result.getScanRecord();
            String deviceName = (record != null) ? record.getDeviceName() : null;
            if (deviceName == null) deviceName = device.getName();

            // So processa dispositivos CHOPP_
            if (!BleConfigUtils.isChoppDevice(deviceName)) return;

            Log.d(TAG, "[SCAN] Encontrado: " + deviceName + " | MAC=" + device.getAddress());

            boolean nameMatch   = BleConfigUtils.matchesBleNameForMac(deviceName, mTargetWifiMac);
            boolean macMatch    = device.getAddress().equalsIgnoreCase(mTargetMac);
            boolean fallbackOk  = mReconnectCount >= 2;

            if (nameMatch || macMatch || fallbackOk) {
                // Guard: evita múltiplas conexões GATT quando o scanner
                // entrega o mesmo dispositivo várias vezes antes de parar.
                if (mState != State.SCANNING) return;
                mState = State.CONNECTING;

                if (!nameMatch && !macMatch) {
                    Log.w(TAG, "[FALLBACK] Aceitando " + deviceName + " por fallback apos " + mReconnectCount + " falhas");
                }
                stopScan();
                connectGatt(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "[SCAN] Falha no scan, codigo=" + errorCode);
            scheduleReconnect("scan_failed:" + errorCode);
        }
    };

    // =========================================================================
    // Conexao GATT - Just Works (sem createBond, sem PIN)
    // =========================================================================

    private void connectGatt(BluetoothDevice device) {
        Log.i(TAG, "[GATT] Conectando a " + device.getAddress());
        // Fecha GATT residual de sessão anterior para não acumular instâncias
        if (mGatt != null) {
            try { mGatt.close(); } catch (Exception ignored) {}
            mGatt = null;
        }
        mState = State.CONNECTING;
        // Just Works: autoConnect=false, TRANSPORT_LE, sem createBond()
        mGatt = device.connectGatt(this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "[GATT] Conectado");
                mState = State.CONNECTED;
                broadcastStatus(STATUS_CONNECTED);
                // Conforme doc: sem createBond() - ir direto para requestMtu
                gatt.requestMtu(BleConfigUtils.MTU_REQUESTED);

            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.w(TAG, "[GATT] Desconectado (status=" + status + ")");
                mState  = State.IDLE;
                mRxChar = null;
                mTxChar = null;
                mHandler.removeCallbacks(mPingRunnable);
                if (mGatt != null) { mGatt.close(); mGatt = null; }
                broadcastStatus(STATUS_DISCONNECTED + ":gatt_" + status);
                scheduleReconnect("gatt_disconnect");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "[GATT] MTU negociado: " + mtu);
            // Conforme doc: apos MTU, descobrir servicos
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[GATT] Falha ao descobrir servicos: " + status);
                gatt.disconnect();
                return;
            }
            Log.i(TAG, "[GATT] Servicos descobertos");

            BluetoothGattService service = gatt.getService(UUID_SERVICE);
            if (service == null) {
                Log.e(TAG, "[GATT] Servico NUS nao encontrado! UUID=" + UUID_SERVICE);
                gatt.disconnect();
                return;
            }

            mRxChar = service.getCharacteristic(UUID_RX);
            mTxChar = service.getCharacteristic(UUID_TX);

            if (mRxChar == null || mTxChar == null) {
                Log.e(TAG, "[GATT] Caracteristicas RX/TX nao encontradas");
                gatt.disconnect();
                return;
            }

            // Habilitar notificacoes na TX (descriptor 0x2902)
            gatt.setCharacteristicNotification(mTxChar, true);
            BluetoothGattDescriptor descriptor = mTxChar.getDescriptor(UUID_CCCD);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
                Log.d(TAG, "[GATT] Habilitando notificacoes TX");
            } else {
                Log.w(TAG, "[GATT] Descriptor 0x2902 nao encontrado - prosseguindo sem ele");
                onReady();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.i(TAG, "[GATT] onDescriptorWrite status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Conforme doc: apos onDescriptorWrite, estado READY
                onReady();
            } else {
                Log.e(TAG, "[GATT] Falha ao escrever descriptor: " + status);
                gatt.disconnect();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null) return;
            String msg = new String(data).trim();
            Log.d(TAG, "[RX] Recebido: " + msg);

            // Processar PONG internamente para keepalive
            BleCommand.Response r = BleCommand.parse(msg);
            if (r.isPong()) {
                Log.d(TAG, "[PING] PONG recebido - sessao valida");
            }
            broadcastData(msg);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "[GATT] onCharacteristicWrite status=" + status);
        }
    };

    // =========================================================================
    // Estado READY
    // =========================================================================

    private void onReady() {
        mState = State.READY;
        mReconnectCount = 0;
        Log.i(TAG, "[READY] Conexao BLE pronta - iniciando PING keepalive a cada " + PING_INTERVAL_MS + "ms");
        broadcastStatus(STATUS_READY);
        updateNotification("BLE conectado");
        mHandler.removeCallbacks(mPingRunnable);
        mHandler.postDelayed(mPingRunnable, PING_INTERVAL_MS);
    }

    private void sendPing() {
        if (mState != State.READY) return;
        sendCommand(BleCommand.buildPing());
        mHandler.postDelayed(mPingRunnable, PING_INTERVAL_MS);
    }

    // =========================================================================
    // Reconexao com backoff exponencial
    // =========================================================================

    private void scheduleReconnect(String reason) {
        if (mReconnectCount >= MAX_RECONNECT) {
            Log.e(TAG, "[RECONNECT] Maximo de tentativas atingido - desistindo");
            broadcastStatus(STATUS_DISCONNECTED + ":max_retries");
            return;
        }

        long delay = BACKOFF_MS[Math.min(mReconnectCount, BACKOFF_MS.length - 1)];
        mReconnectCount++;

        Log.w(TAG, "[RECONNECT] Falha #" + mReconnectCount
                + " - proxima tentativa (com scan) em " + delay + "ms");

        if (mReconnectCount == 2) {
            Log.w(TAG, "[RECONNECT] " + mReconnectCount + " falhas acumuladas - proximo ciclo usara scan de fallback (CHOPP_)");
        }
        if (mReconnectCount == 3) {
            Log.w(TAG, "[RECONNECT] 3 falhas - tentando refresh do cache GATT");
            refreshGattCache();
        }

        broadcastStatus(STATUS_DISCONNECTED + ":" + reason);
        mHandler.postDelayed(() -> {
            if (mState == State.IDLE || mState == State.SCANNING) {
                startScanCycle();
            }
        }, delay);
    }

    private void refreshGattCache() {
        if (mGatt == null) return;
        try {
            java.lang.reflect.Method refresh = mGatt.getClass().getMethod("refresh");
            boolean ok = (boolean) refresh.invoke(mGatt);
            Log.d(TAG, "[GATT] refresh() = " + ok);
        } catch (Exception e) {
            Log.w(TAG, "[GATT] refresh() nao disponivel: " + e.getMessage());
        }
    }

    // =========================================================================
    // Broadcasts
    // =========================================================================

    /**
     * Envia broadcast de status BLE para receivers internos do app.
     *
     * Android 13+ (API 33): sendBroadcast() implícito (sem pacote destino) NÃO
     * entrega para receivers registrados com RECEIVER_NOT_EXPORTED. É obrigatório
     * definir o pacote destino via setPackage() para que a entrega funcione.
     * Ref: https://developer.android.com/about/versions/13/behavior-changes-13#runtime-receivers
     */
    private void broadcastStatus(String status) {
        Log.d(TAG, "[STATUS] " + status);
        Intent i = new Intent(BLE_STATUS_ACTION);
        i.putExtra("status", status);
        i.setPackage(getPackageName()); // Android 13+: necessário para RECEIVER_NOT_EXPORTED
        sendBroadcast(i);
    }

    /**
     * Envia broadcast de dados recebidos do ESP32 para receivers internos do app.
     *
     * Mesmo requisito do Android 13+: setPackage() obrigatório para entrega
     * a receivers registrados com RECEIVER_NOT_EXPORTED.
     */
    private void broadcastData(String data) {
        Intent i = new Intent(BLE_DATA_ACTION);
        i.putExtra("data", data);
        i.setPackage(getPackageName()); // Android 13+: necessário para RECEIVER_NOT_EXPORTED
        sendBroadcast(i);
    }

    // =========================================================================
    // Notification (Foreground Service)
    // =========================================================================

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Servico BLE", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Conexao Bluetooth com a chopeira");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ChoppOn BLE")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
