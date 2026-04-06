package com.example.choppontap;
/*
 * âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
 * BluetoothServiceIndustrial.java â ServiÃ§o BLE NUS v6.0 (Sem Bond)
 * âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
 *
 * VersÃ£o: 6.0-NUS-DIRECT
 * Protocolo: Nordic UART Service (NUS) sobre BLE â Firmware ESP32 operacional.cpp
 * Target: ESP32 CHOPP Self-Service
 * Compatibilidade: Android 8+ (API 26+), Android 12+ permissÃµes, Android 14 FGS
 *
 * âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
 * FLUXO ÃNICO DE CONEXÃO (SEM BOND)
 * âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
 *
 *   API retorna MAC
 *       â
 *   connectWithMac(mac)
 *       â
 *   conectarComScan(mac) â scan 4s filtrando pelo MAC
 *       â
 *   device encontrado â conectarGatt(mac)
 *       â
 *   onConnectionStateChange(CONNECTED)
 *       â
 *   requestConnectionPriority(HIGH) + requestMtu(247)
 *       â
 *   onMtuChanged() â discoverServices()
 *       â
 *   onServicesDiscovered() â enableNotifications()
 *       â
 *   onDescriptorWrite() â READY
 *       â
 *   ComunicaÃ§Ã£o fluida ($ML:, $PL:, $TO:, $LB:)
 *
 * âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
 * PROTOCOLO DE COMUNICAÃÃO (Firmware ESP32 operacional.cpp)
 * âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
 *
 *   COMANDOS (App â ESP32):
 *     $ML:<volume_ml>    â Liberar volume em mL (ex: $ML:100)
 *     $PL:<pulsos>       â Configurar pulsos/litro (ex: $PL:5880)
 *     $TO:<timeout_ms>   â Configurar timeout (ex: $TO:5000)
 *     $LB:               â LiberaÃ§Ã£o contÃ­nua
 *
 *   RESPOSTAS (ESP32 â App):
 *     OK                 â Comando aceito e enfileirado
 *     ERRO               â Comando com erro
 *     VP:<ml_parcial>    â Volume parcial durante liberaÃ§Ã£o
 *     QP:<pulsos>        â Quantidade de pulsos ao final
 *     ML:<ml_final>      â Volume final liberado (sinal de conclusÃ£o)
 *     PL:<pulsos>        â Resposta de leitura de pulsos/litro
 *
 *   AUTENTICAÃÃO: Nenhuma â ESP32 aceita conexÃ£o direta sem bond
 *   NÃO existe: HMAC, SESSION_ID, CMD_ID, AUTH, SERVE, STOP, STATUS, PING,
 *               ACK, DONE, AUTH:OK/FAIL, PONG, READY/READY_OK, PIN, BOND
 *
 * âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
 */

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
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.choppontap.BleCommand;
import com.example.choppontap.ble.CommandQueue;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * ServiÃ§o BLE NUS v6.0 â conexÃ£o direta sem bond.
 * O ESP32 aceita conexÃ£o direta igual ao nRF Connect.
 *
 * REMOVIDO: iniciarBondEConectar, mPairingReceiver, mBondStateReceiver,
 *           iniciarBondTimeout, cancelarBondTimeout, injetarPinViaReflection,
 *           removerBondViaReflection, pairingTypeName, mBondFailCount,
 *           mBondRetryCount, mBondTimeoutRunnable, BOND_TIMEOUT_MS, MAX_BOND_RETRIES
 */
public class BluetoothServiceIndustrial extends Service {

    private static final String TAG = "BLE_INDUSTRIAL";

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Singleton
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private static volatile BluetoothServiceIndustrial sInstance;

    public static BluetoothServiceIndustrial getInstance() { return sInstance; }
    public static boolean isRunning() { return sInstance != null; }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Estado interno
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    public enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        READY,
        ERROR
    }

    private volatile State mState = State.DISCONNECTED;

    private void transitionTo(State newState) {
        State old = mState;
        if (old == newState) return;
        mState = newState;
        Log.i(TAG, "âââ STATE: " + old.name() + " â " + newState.name() + " âââ");
        broadcastBleState(newState);
        switch (newState) {
            case DISCONNECTED:
                cancelarConnectingWatchdog();
                pararPing();
                mWriteCharacteristic = null;
                mNotifyCharacteristic = null;
                mWriteBusy.set(false);
                mReadyTimestamp = 0;
                mBleReady = false;
                break;
            case READY:
                cancelarConnectingWatchdog();
                iniciarPing();
                mReconnectAttempts = 0;
                mBackoffIndex = 0;
                mReadyTimestamp = System.currentTimeMillis();
                Log.i(TAG, "[STATE] READY â aguardando pagamento");
                broadcastWriteReady();
                mMainHandler.post(this::drainCommandQueue);
                break;
            case ERROR:
                cancelarConnectingWatchdog();
                pararPing();
                pararReconexao();
                mBleReady = false;
                Log.e(TAG, "[STATE] ERROR â verifique firmware ESP32");
                break;
            default:
                break;
        }
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // UUIDs NUS (Nordic UART Service) â Firmware ESP32 operaBLE.cpp
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    public static final String BLE_NAME_PREFIX = "CHOPP_";
    private static final UUID NUS_SERVICE_UUID           = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_RX_CHARACTERISTIC_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_TX_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CCCD_UUID                  = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // AÃ§Ãµes de Broadcast
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    public static final String ACTION_DATA_AVAILABLE    = "com.example.choppontap.ACTION_DATA_AVAILABLE";
    public static final String ACTION_CONNECTION_STATUS = "com.example.choppontap.ACTION_CONNECTION_STATUS";
    public static final String ACTION_WRITE_READY       = "com.example.choppontap.ACTION_WRITE_READY";
    public static final String ACTION_DEVICE_FOUND      = "com.example.choppontap.ACTION_DEVICE_FOUND";
    public static final String ACTION_BLE_STATE_CHANGED = "com.example.choppontap.ACTION_BLE_STATE_CHANGED";
    public static final String EXTRA_DATA      = "com.example.choppontap.EXTRA_DATA";
    public static final String EXTRA_STATUS    = "com.example.choppontap.EXTRA_STATUS";
    public static final String EXTRA_DEVICE    = "com.example.choppontap.EXTRA_DEVICE";
    public static final String EXTRA_BLE_STATE = "com.example.choppontap.EXTRA_BLE_STATE";

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // CÃ³digos de status GATT
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private static final int STATUS_GATT_ERROR = 133;

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Backoff exponencial de reconexÃ£o
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private static final long[] BACKOFF_DELAYS = { 3_000L, 6_000L, 12_000L, 20_000L };
    private int  mReconnectAttempts = 0;
    private int  mBackoffIndex      = 0;

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Guard-band (evita comandos logo apÃ³s READY)
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private static final long GUARD_BAND_MS = 900L;
    private static final long CONNECTING_TIMEOUT_MS = 15_000L; // watchdog: tempo máx em CONNECTING
    private volatile long mReadyTimestamp = 0;

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Foreground Service
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private static final String NOTIF_CHANNEL_ID = "ble_industrial_channel";
    private static final int    NOTIF_ID         = 1001;

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Campos de instÃ¢ncia
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private BluetoothAdapter            mBluetoothAdapter;
    private BluetoothLeScanner          mBleScanner;
    private BluetoothGatt               mBluetoothGatt;
    private BluetoothGattCharacteristic mWriteCharacteristic;
    private BluetoothGattCharacteristic mNotifyCharacteristic;
    private Handler                     mMainHandler;
    private String                      mTargetMac;
    private String                      mMacAlvo;
    private boolean                     mAutoReconnect = true;
    private Runnable                    mReconnectRunnable;
    private final AtomicBoolean         mWriteBusy = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<String> mCommandQueue = new ConcurrentLinkedQueue<>();
    // Comando pendente: armazena $ML: se o BLE nÃ£o estiver READY no momento do pagamento
    private volatile String              mComandoPendente = null;
    private volatile boolean             mBleReady        = false;
    private Runnable                    mPingRunnable    = null;
    private int                         mPingMisses      = 0;
    private Runnable                    mConnectingWatchdog = null;

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Binder
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    public class LocalBinder extends Binder {
        public BluetoothServiceIndustrial getService() {
            return BluetoothServiceIndustrial.this;
        }
    }
    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Lifecycle
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mMainHandler = new Handler(Looper.getMainLooper());
        Log.i(TAG, "[SERVICE] BluetoothServiceIndustrial v6.0-NUS-DIRECT iniciado");
        try {
            criarNotificacaoForeground();
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null) {
                Log.e(TAG, "[SERVICE] BluetoothManager null â abortando");
                stopSelf();
                return;
            }
            mBluetoothAdapter = bm.getAdapter();
            if (mBluetoothAdapter == null) {
                Log.e(TAG, "[SERVICE] BluetoothAdapter null â abortando");
                stopSelf();
                return;
            }
            mBleScanner = mBluetoothAdapter.getBluetoothLeScanner();

            // Recupera MAC salvo de sessÃ£o anterior
            String savedMac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                    .getString("esp32_mac", null);
            if (savedMac != null) {
                mTargetMac = savedMac;
                mMacAlvo   = savedMac;
                Log.i(TAG, "[SERVICE] MAC recuperado: " + savedMac);
            }

            // NÃO inicia conexÃ£o aqui.
            // A conexÃ£o sÃ³ inicia quando connectWithMac(mac) for chamado.
            Log.i(TAG, "[SERVICE] Aguardando connectWithMac(mac) para iniciar conexÃ£o BLE");
        } catch (Exception e) {
            Log.e(TAG, "[SERVICE] CRASH no onCreate: " + e.getMessage(), e);
            sInstance = null;
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
        mAutoReconnect = false;
        pararReconexao();
        closeGatt();
        transitionTo(State.DISCONNECTED);
        super.onDestroy();
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // PONTO DE ENTRADA ÃNICO: connectWithMac(mac)
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    /**
     * Ponto de entrada principal para conexÃ£o BLE.
     * Deve ser chamado apÃ³s a API retornar o MAC do ESP32.
     *
     * Fluxo: connectWithMac(mac) â conectarComScan(mac) â conectarGatt(mac) â READY
     *
     * @param mac endereÃ§o MAC do ESP32 (ex: "DC:B4:D9:99:B8:E2")
     */
    public void connectWithMac(String mac) {
        if (mac == null || mac.isEmpty()) {
            Log.e(TAG, "[CONNECT] MAC invÃ¡lido");
            return;
        }
        Log.i(TAG, "[CONNECT] connectWithMac(" + mac + ")");
        salvarMac(mac);
        mAutoReconnect = true;

        if (!hasConnectPermission()) {
            Log.e(TAG, "[CONNECT] BLUETOOTH_CONNECT nÃ£o concedida â aguardando permissÃ£o");
            mMainHandler.postDelayed(() -> connectWithMac(mac), 3_000L);
            return;
        }
        if (!mBluetoothAdapter.isEnabled()) {
            Log.w(TAG, "[CONNECT] Bluetooth desativado â aguardando ativaÃ§Ã£o");
            return;
        }

        // Se jÃ¡ estÃ¡ conectado/conectando ao mesmo MAC, nÃ£o faz nada
        if ((mState == State.CONNECTING || mState == State.CONNECTED || mState == State.READY)
                && mac.equalsIgnoreCase(mTargetMac)) {
            Log.i(TAG, "[CONNECT] JÃ¡ conectado/conectando ao MAC " + mac + " â ignorando");
            return;
        }

        // Se estÃ¡ conectado a outro MAC, desconecta primeiro
        if (mState != State.DISCONNECTED) {
            Log.i(TAG, "[CONNECT] Desconectando MAC anterior para conectar ao novo: " + mac);
            closeGatt();
            transitionTo(State.DISCONNECTED);
        }

        pararReconexao();
        conectarComScan(mac);
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Scan por MAC â GATT direto (sem bond)
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ

    /**
     * Inicia scan BLE curto (4s) filtrando pelo MAC.
     * Se encontrar: conectarGatt(mac).
     * Se timeout: conectarGatt(mac) diretamente (fallback).
     */
    private void conectarComScan(final String mac) {
        Log.i(TAG, "[SCAN] Iniciando scan para " + mac);

        BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.e(TAG, "[SCAN] Scanner indisponÃ­vel â conectando direto");
            conectarGatt(mac);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "[SCAN] BLUETOOTH_SCAN nÃ£o concedido â conectando direto");
                conectarGatt(mac);
                return;
            }
        }

        ScanFilter filtro = new ScanFilter.Builder()
                .setDeviceAddress(mac)
                .build();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        final boolean[] encontrado = {false};
        final ScanCallback[] cbRef = {null};

        cbRef[0] = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (result.getDevice().getAddress().equalsIgnoreCase(mac) && !encontrado[0]) {
                    encontrado[0] = true;
                    Log.i(TAG, "[SCAN] " + mac + " encontrado â conectando");
                    try { scanner.stopScan(cbRef[0]); } catch (Exception ignored) {}
                    mMainHandler.removeCallbacksAndMessages(null);
                    conectarGatt(mac);
                }
            }
            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "[SCAN] Falhou (" + errorCode + ") â conectando direto");
                conectarGatt(mac);
            }
        };

        try {
            scanner.startScan(Collections.singletonList(filtro), settings, cbRef[0]);
        } catch (Exception e) {
            Log.e(TAG, "[SCAN] startScan exception: " + e.getMessage() + " â conectando direto");
            conectarGatt(mac);
            return;
        }

        // Timeout 4s â se nÃ£o encontrar, conecta direto
        mMainHandler.postDelayed(() -> {
            if (!encontrado[0]) {
                Log.w(TAG, "[SCAN] Timeout â conectando direto");
                try { scanner.stopScan(cbRef[0]); } catch (Exception ignored) {}
                conectarGatt(mac);
            }
        }, 4000);
    }

    /**
     * Conecta via GATT diretamente ao MAC (sem bond).
     * O ESP32 aceita conexÃ£o direta igual ao nRF Connect.
     */
    private void conectarGatt(String mac) {
        if (!hasConnectPermission()) {
            Log.e(TAG, "[GATT] BLUETOOTH_CONNECT nÃ£o concedida â abortando");
            return;
        }
        if (mState == State.CONNECTING || mState == State.CONNECTED || mState == State.READY) {
            Log.w(TAG, "[GATT] JÃ¡ conectado/conectando (estado=" + mState.name() + ") â ignorando");
            return;
        }

        BluetoothDevice device;
        try {
            device = mBluetoothAdapter.getRemoteDevice(mac);
        } catch (Exception e) {
            Log.e(TAG, "[GATT] getRemoteDevice(" + mac + ") exception: " + e.getMessage());
            return;
        }
        if (device == null) {
            Log.e(TAG, "[GATT] getRemoteDevice(" + mac + ") retornou null â abortando");
            return;
        }

        transitionTo(State.CONNECTING);
        Log.i(TAG, "[GATT] Conectando via GATT direto (sem bond) â " + mac
                + " | tentativa=" + (mReconnectAttempts + 1));

        // Reutiliza GATT existente se for o mesmo MAC
        if (mBluetoothGatt != null
                && mBluetoothGatt.getDevice().getAddress().equalsIgnoreCase(mac)) {
            Log.i(TAG, "[GATT] GATT existente â gatt.connect() (reconexÃ£o rÃ¡pida)");
            boolean ok = mBluetoothGatt.connect();
            if (ok) {
                Log.i(TAG, "[GATT] gatt.connect() â OK");
                return;
            }
            Log.w(TAG, "[GATT] gatt.connect() falhou â fechando e criando novo GATT");
            closeGatt();
        } else if (mBluetoothGatt != null) {
            Log.i(TAG, "[GATT] GATT de outro MAC â fechando");
            closeGatt();
        }

        // autoConnect=false: conexão direta — garante onConnectionStateChange real
        // autoConnect=true causava phantom connection (GATT registrado, ESP32 nunca conectava) â sem timeout, sem GATT_ERROR 133
        Log.i(TAG, "[GATT] connectGatt(autoConnect=false, TRANSPORT_LE) â " + mac);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mBluetoothGatt = device.connectGatt(
                    this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        }

        if (mBluetoothGatt == null) {
            Log.e(TAG, "[GATT] connectGatt() retornou null â reagendando");
            transitionTo(State.DISCONNECTED);
            reconectarComBackoff();
        }
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // ReconexÃ£o com backoff exponencial
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private void reconectarComBackoff() {
        if (!mAutoReconnect) {
            Log.d(TAG, "[RECONNECT] autoReconnect=false â nÃ£o reconectando");
            return;
        }
        if (mReconnectRunnable != null) {
            Log.d(TAG, "[RECONNECT] ReconexÃ£o jÃ¡ agendada â ignorando duplicata");
            return;
        }
        if (mMacAlvo == null && mTargetMac == null) {
            Log.w(TAG, "[RECONNECT] Sem MAC â aguardando connectWithMac()");
            return;
        }

        final String mac = mMacAlvo != null ? mMacAlvo : mTargetMac;

        if (mReconnectAttempts >= 5) {
            Log.e(TAG, "[RECONNECT] Limite de 5 tentativas atingido â parando reconexÃ£o");
            transitionTo(State.ERROR);
            return;
        }

        long delay = BACKOFF_DELAYS[Math.min(mBackoffIndex, BACKOFF_DELAYS.length - 1)];
        mBackoffIndex = Math.min(mBackoffIndex + 1, BACKOFF_DELAYS.length - 1);
        mReconnectAttempts++;

        Log.i(TAG, "[RECONNECT] Tentativa #" + mReconnectAttempts
                + " em " + delay + "ms â " + mac);

        mReconnectRunnable = () -> {
            mReconnectRunnable = null;
            if (!mAutoReconnect || mac == null) return;
            Log.i(TAG, "[RECONNECT] Executando reconexÃ£o â " + mac);
            if (hasConnectPermission()) {
                conectarComScan(mac);
            } else {
                Log.w(TAG, "[RECONNECT] BLUETOOTH_CONNECT nÃ£o concedida â retry em 5s");
                mMainHandler.postDelayed(() -> reconectarComBackoff(), 5_000L);
            }
        };
        mMainHandler.postDelayed(mReconnectRunnable, delay);
    }

    private void pararReconexao() {
        if (mReconnectRunnable != null) {
            mMainHandler.removeCallbacks(mReconnectRunnable);
            mReconnectRunnable = null;
            Log.d(TAG, "[RECONNECT] ReconexÃ£o cancelada");
        }
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // GATT Callback
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newConnectionState) {
            final String mac = gatt.getDevice().getAddress();
            Log.i(TAG, "[BLE] onConnectionStateChange | status=" + status
                    + " | newState=" + gattStateName(newConnectionState)
                    + " | mac=" + mac);
            if (newConnectionState == BluetoothProfile.STATE_CONNECTED) {
                handleGattConnected(gatt, status);
            } else if (newConnectionState == BluetoothProfile.STATE_DISCONNECTED) {
                handleGattDisconnected(gatt, status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "[BLE] onMtuChanged | mtu=" + mtu + " | status=" + status);
            boolean ok = gatt.discoverServices();
            Log.i(TAG, "[BLE] onMtuChanged â discoverServices() â " + (ok ? "INICIADO" : "FALHOU"));
            if (!ok) {
                mMainHandler.postDelayed(() -> {
                    if (mBluetoothGatt != null) {
                        boolean retry = mBluetoothGatt.discoverServices();
                        Log.i(TAG, "[GATT] discoverServices() retry â " + (retry ? "INICIADO" : "FALHOU"));
                    }
                }, 600L);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.i(TAG, "[GATT] onServicesDiscovered | status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[GATT] discoverServices falhou (status=" + status + ") â reconectando");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }
            BluetoothGattService nusService = gatt.getService(NUS_SERVICE_UUID);
            if (nusService == null) {
                Log.e(TAG, "[BLE] NUS SERVICE NOT FOUND (6E400001) â reconectando");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }
            Log.i(TAG, "[BLE] NUS SERVICE FOUND â");
            BluetoothGattCharacteristic rxChar = nusService.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
            BluetoothGattCharacteristic txChar = nusService.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID);
            if (rxChar == null) {
                Log.e(TAG, "[BLE] NUS RX NOT FOUND (6E400002) â reconectando");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }
            if (txChar == null) {
                Log.e(TAG, "[BLE] NUS TX NOT FOUND (6E400003) â reconectando");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }
            mBluetoothGatt = gatt;
            mWriteCharacteristic = rxChar;
            mNotifyCharacteristic = txChar;
            Log.i(TAG, "[BLE] NUS RX (write) + TX (notify) prontos â");
            habilitarNotificacoes(gatt, txChar);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.i(TAG, "[GATT] onDescriptorWrite | status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "[BLE] NOTIFY ENABLED â â READY");
                mMainHandler.post(() -> {
                    if (isBleStackPronto()) {
                        transitionTo(State.READY);
                        broadcastConnectionStatus("ready");
                        mBleReady = true;
                        Log.i(TAG, "[BLE] READY");
                        // Verificar se hÃ¡ comando pendente do pagamento
                        if (mComandoPendente != null) {
                            Log.i(TAG, "[SERVE] Enviando comando pendente: " + mComandoPendente);
                            writeImediato(mComandoPendente);
                            mComandoPendente = null;
                        }
                    } else {
                        Log.e(TAG, "[BLE] READY BLOQUEADO â handles BLE ausentes");
                    }
                });
            } else {
                Log.w(TAG, "[GATT] onDescriptorWrite falhou (status=" + status
                        + ") â tentando READY mesmo assim");
                mMainHandler.post(() -> {
                    if (isBleStackPronto()) {
                        transitionTo(State.READY);
                        broadcastConnectionStatus("ready");
                        mBleReady = true;
                        Log.i(TAG, "[BLE] READY");
                        // Verificar se hÃ¡ comando pendente do pagamento
                        if (mComandoPendente != null) {
                            Log.i(TAG, "[SERVE] Enviando comando pendente: " + mComandoPendente);
                            writeImediato(mComandoPendente);
                            mComandoPendente = null;
                        }
                    }
                });
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "[GATT] onCharacteristicWrite | status=" + status);
            mWriteBusy.set(false);
            mMainHandler.post(BluetoothServiceIndustrial.this::drainCommandQueue);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[GATT] Write falhou (status=" + status + ")");
                broadcastData("WRITE_ERROR:" + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            byte[] raw = characteristic.getValue();
            if (raw == null || raw.length == 0) return;
            String data = new String(raw, StandardCharsets.UTF_8).trim();
            Log.i(TAG, "[RX] " + data);
            processarRespostaBle(data);
        }
    };

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Handlers de conexÃ£o/desconexÃ£o GATT
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private void handleGattConnected(BluetoothGatt gatt, int status) {
        Log.i(TAG, "[BLE] CONNECTED â â " + gatt.getDevice().getAddress()
                + " | status=" + status);
        pararReconexao();
        mReconnectAttempts = 0;
        mBackoffIndex = 0;
        mBluetoothGatt = gatt;
        mWriteCharacteristic = null;
        mNotifyCharacteristic = null;
        transitionTo(State.CONNECTED);
        broadcastConnectionStatus("connected");

        // Prioridade HIGH para menor latÃªncia
        boolean priOk = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        Log.i(TAG, "[BLE] requestConnectionPriority(HIGH) â " + (priOk ? "OK" : "FALHOU"));

        // MTU 247 â mÃ¡ximo prÃ¡tico para NUS
        boolean mtuOk = gatt.requestMtu(247);
        Log.i(TAG, "[BLE] requestMtu(247) â "
                + (mtuOk ? "AGUARDANDO onMtuChanged" : "FALHOU â discoverServices direto"));
        if (!mtuOk) {
            mMainHandler.postDelayed(() -> {
                if (mBluetoothGatt != null) {
                    boolean ok = mBluetoothGatt.discoverServices();
                    Log.i(TAG, "[GATT] discoverServices() (fallback MTU) â "
                            + (ok ? "INICIADO" : "FALHOU"));
                }
            }, 300L);
        }
    }

    private void handleGattDisconnected(BluetoothGatt gatt, int status) {
        Log.i(TAG, "[GATT] DESCONECTADO | status=" + status
                + " | mac=" + gatt.getDevice().getAddress()
                + " | estado_anterior=" + mState.name());
        transitionTo(State.DISCONNECTED);
        mBleReady = false;
        broadcastConnectionStatus("disconnected:" + status);

        if (!mAutoReconnect) {
            Log.i(TAG, "[GATT] autoReconnect=false â nÃ£o reconectando");
            return;
        }
        // Com autoConnect=true, basta fechar o GATT e reconectar â o Android gerencia o background scan
        final String mac = mMacAlvo != null ? mMacAlvo : mTargetMac;
        if (mac == null) {
            Log.w(TAG, "[GATT] Sem MAC alvo â aguardando connectWithMac()");
            return;
        }
        if (status == STATUS_GATT_ERROR) {
            // GATT error 133: fecha GATT e reconecta apÃ³s 1s com autoConnect=true
            Log.w(TAG, "[GATT] GATT_ERROR (133) â closeGatt + reconectar em 1s (autoConnect=true)");
            closeGatt();
            mMainHandler.postDelayed(() -> {
                if (mAutoReconnect) conectarGatt(mac);
            }, 1_000L);
        } else {
            // Qualquer outro status: fecha GATT e reconecta com autoConnect=true
            Log.i(TAG, "[GATT] status=" + status + " â closeGatt + reconectar em 1s (autoConnect=true) â " + mac);
            closeGatt();
            mMainHandler.postDelayed(() -> {
                if (mAutoReconnect) conectarGatt(mac);
            }, 1_000L);
        }
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Habilitar notificaÃ§Ãµes (CCCD)
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private void habilitarNotificacoes(BluetoothGatt gatt, BluetoothGattCharacteristic txChar) {
        mNotifyCharacteristic = txChar;
        boolean ok = gatt.setCharacteristicNotification(txChar, true);
        Log.i(TAG, "[GATT] setCharacteristicNotification(TX, true) â " + (ok ? "OK" : "FALHOU"));
        BluetoothGattDescriptor cccd = txChar.getDescriptor(CCCD_UUID);
        if (cccd == null) {
            Log.e(TAG, "[GATT] CCCD nÃ£o encontrado â indo para READY sem notificaÃ§Ãµes");
            mMainHandler.post(() -> {
                if (isBleStackPronto()) {
                    transitionTo(State.READY);
                    broadcastConnectionStatus("ready");
                    mBleReady = true;
                    Log.i(TAG, "[BLE] READY");
                    if (mComandoPendente != null) {
                        Log.i(TAG, "[SERVE] Enviando comando pendente: " + mComandoPendente);
                        writeImediato(mComandoPendente);
                        mComandoPendente = null;
                    }
                }
            });
            return;
        }
        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        boolean writeOk = gatt.writeDescriptor(cccd);
        Log.i(TAG, "[GATT] writeDescriptor(CCCD) â "
                + (writeOk ? "OK â aguardando onDescriptorWrite" : "FALHOU"));
        if (!writeOk) {
            mMainHandler.post(() -> {
                if (isBleStackPronto()) {
                    transitionTo(State.READY);
                    broadcastConnectionStatus("ready");
                    mBleReady = true;
                    Log.i(TAG, "[BLE] READY");
                    if (mComandoPendente != null) {
                        Log.i(TAG, "[SERVE] Enviando comando pendente: " + mComandoPendente);
                        writeImediato(mComandoPendente);
                        mComandoPendente = null;
                    }
                }
            });
        }
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Processamento de respostas BLE do ESP32
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private void processarRespostaBle(String data) {
        if ("PONG".equalsIgnoreCase(data)) {
            mPingMisses = 0;
            Log.d(TAG, "[PING] PONG recebido — conexão confirmada ativa");
            return;
        }
        if ("OK".equalsIgnoreCase(data)) {
            Log.i(TAG, "[BLE] Comando aceito (OK)");
            broadcastData(data);
            return;
        }
        if ("ERRO".equalsIgnoreCase(data)) {
            Log.e(TAG, "[BLE] ESP32 reportou ERRO");
            broadcastData(data);
            return;
        }
        if (data.startsWith("VP:")) {
            String val = data.substring(3).trim();
            Log.i(TAG, "[BLE] Volume parcial: " + val + " mL");
            broadcastData(data);
            return;
        }
        if (data.startsWith("QP:")) {
            String val = data.substring(3).trim();
            Log.i(TAG, "[BLE] Pulsos finais: " + val);
            broadcastData(data);
            return;
        }
        if (data.startsWith("ML:")) {
            String val = data.substring(3).trim();
            Log.i(TAG, "[BLE] onCharacteristicChanged: ML:" + val);
            broadcastData(data);
            return;
        }
        if (data.startsWith("PL:")) {
            String val = data.substring(3).trim();
            Log.i(TAG, "[BLE] Pulsos/litro: " + val);
            broadcastData(data);
            return;
        }
        Log.w(TAG, "[BLE] Resposta desconhecida: \"" + data + "\"");
        broadcastData(data);
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Fila de comandos
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private void enqueueCommand(String cmd) {
        mCommandQueue.offer(cmd);
        drainCommandQueue();
    }

    private void drainCommandQueue() {
        if (mWriteBusy.get()) return;
        if (mState != State.READY) return;
        if (!isBleStackPronto()) return;
        String cmd = mCommandQueue.poll();
        if (cmd == null) return;
        writeImediato(cmd);
    }

    private void writeImediato(String data) {
        if (!hasConnectPermission()) {
            Log.e(TAG, "[WRITE] BLUETOOTH_CONNECT nÃ£o concedida â abortando write");
            return;
        }
        if (!isBleStackPronto()) {
            Log.e(TAG, "[WRITE] BLE stack nÃ£o pronto â abortando write");
            return;
        }
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        mWriteCharacteristic.setValue(bytes);
        mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        mWriteBusy.set(true);
        boolean ok = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
        Log.i(TAG, "[WRITE] writeCharacteristic(\"" + data + "\") â " + (ok ? "OK" : "FALHOU"));
        if (!ok) {
            mWriteBusy.set(false);
        }
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Helpers internos
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private boolean isBleStackPronto() {
        return mBluetoothGatt != null
                && mWriteCharacteristic != null
                && mNotifyCharacteristic != null;
    }


    // ═══════════════════════════════════════════════════════════════════════════
    // Watchdog de CONNECTING — evita estado fantasma (GATT registrado sem conexão real)
    // ═══════════════════════════════════════════════════════════════════════════
    private void iniciarConnectingWatchdog() {
        cancelarConnectingWatchdog();
        mConnectingWatchdog = () -> {
            mConnectingWatchdog = null;
            if (mState == State.CONNECTING) {
                Log.w(TAG, "[WATCHDOG] CONNECTING > 15s sem READY — fechando GATT e reconectando");
                closeGatt();
                transitionTo(State.DISCONNECTED);
                reconectarComBackoff();
            }
        };
        mMainHandler.postDelayed(mConnectingWatchdog, CONNECTING_TIMEOUT_MS);
        Log.d(TAG, "[WATCHDOG] Connecting watchdog iniciado (15s)");
    }

    private void cancelarConnectingWatchdog() {
        if (mConnectingWatchdog != null) {
            mMainHandler.removeCallbacks(mConnectingWatchdog);
            mConnectingWatchdog = null;
            Log.d(TAG, "[WATCHDOG] Connecting watchdog cancelado");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PING/PONG — keep-alive: confirma que a conexão BLE é real (dados trafegam)
    // Android envia PING a cada 5s; ESP32 deve responder PONG
    // 3 PINGs sem PONG = reconecta
    // ═══════════════════════════════════════════════════════════════════════════
    private static final long PING_INTERVAL_MS  = 5_000L;
    private static final int  PING_MAX_MISSES   = 3;

    private void iniciarPing() {
        pararPing();
        mPingMisses = 0;
        mPingRunnable = new Runnable() {
            @Override
            public void run() {
                if (mState != State.READY || !isBleStackPronto()) {
                    pararPing();
                    return;
                }
                if (!hasConnectPermission()) return;
                mPingMisses++;
                if (mPingMisses > PING_MAX_MISSES) {
                    Log.e(TAG, "[PING] " + PING_MAX_MISSES + " PINGs sem PONG — ESP32 não responde, reconectando");
                    pararPing();
                    closeGatt();
                    transitionTo(State.DISCONNECTED);
                    reconectarComBackoff();
                    return;
                }
                Log.d(TAG, "[PING] Enviando PING (miss=" + mPingMisses + "/" + PING_MAX_MISSES + ")");
                writeImediato("PING");
                mMainHandler.postDelayed(this, PING_INTERVAL_MS);
            }
        };
        mMainHandler.postDelayed(mPingRunnable, PING_INTERVAL_MS);
        Log.i(TAG, "[PING] Keep-alive PING iniciado (intervalo=5s, maxMisses=3)");
    }

    private void pararPing() {
        if (mPingRunnable != null) {
            mMainHandler.removeCallbacks(mPingRunnable);
            mPingRunnable = null;
            mPingMisses = 0;
            Log.d(TAG, "[PING] Keep-alive PING parado");
        }
    }

    private void closeGatt() {
        if (mBluetoothGatt != null) {
            try {
                if (hasConnectPermission()) {
                    mBluetoothGatt.disconnect();
                }
            } catch (Exception ignored) {}
            try {
                mBluetoothGatt.close();
            } catch (Exception ignored) {}
            mBluetoothGatt = null;
            Log.d(TAG, "[GATT] closeGatt() â GATT fechado");
        }
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // VerificaÃ§Ã£o de permissÃ£o BLUETOOTH_CONNECT (Android 12+)
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean granted = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Log.w(TAG, "[PERM] BLUETOOTH_CONNECT nÃ£o concedida");
            }
            return granted;
        }
        return true;
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Salvar MAC
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private void salvarMac(String mac) {
        if (mac == null) return;
        mTargetMac = mac;
        mMacAlvo   = mac;
        getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .edit().putString("esp32_mac", mac).apply();
        Log.i(TAG, "[MAC] MAC salvo: " + mac);
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Broadcasts
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private void broadcastConnectionStatus(String status) {
        Intent i = new Intent(ACTION_CONNECTION_STATUS);
        i.putExtra(EXTRA_STATUS, status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
        Log.d(TAG, "[BROADCAST] CONNECTION_STATUS=" + status);
    }

    private void broadcastData(String data) {
        Intent i = new Intent(ACTION_DATA_AVAILABLE);
        i.putExtra(EXTRA_DATA, data);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void broadcastWriteReady() {
        Log.i(TAG, "[BROADCAST] ACTION_WRITE_READY â READY para comandos");
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(ACTION_WRITE_READY));
    }

    private void broadcastBleState(State state) {
        Intent i = new Intent(ACTION_BLE_STATE_CHANGED);
        i.putExtra(EXTRA_BLE_STATE, state.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // JWT para validaÃ§Ã£o de MAC na API (scan manual)
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private String gerarJwtToken() {
        try {
            long nowSec = System.currentTimeMillis() / 1000L;
            String headerJson  = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
            String jti = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            String payloadJson = "{\"iat\":" + (nowSec - 300)
                    + ",\"exp\":" + (nowSec + 7200)
                    + ",\"jti\":\"" + jti + "\""
                    + ",\"app\":\"choppon_tap\"}";
            String h   = Base64.encodeToString(headerJson.getBytes("UTF-8"),
                             Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            String p   = Base64.encodeToString(payloadJson.getBytes("UTF-8"),
                             Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            String msg = h + "." + p;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("teaste".getBytes("UTF-8"), "HmacSHA256"));
            byte[] sig = mac.doFinal(msg.getBytes("UTF-8"));
            String s   = Base64.encodeToString(sig,
                             Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return msg + "." + s;
        } catch (Exception e) {
            Log.e(TAG, "[JWT] Erro ao gerar token: " + e.getMessage());
            return "";
        }
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Scan BLE manual (fallback â apenas quando nÃ£o hÃ¡ MAC)
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private boolean mScanning = false;
    private Runnable mScanStopRunnable = null;
    private final Set<String> mMacsValidando = new HashSet<>();

    /**
     * Scan BLE manual â apenas como fallback quando nÃ£o hÃ¡ MAC disponÃ­vel.
     */
    private void iniciarScanManual() {
        if (mTargetMac != null) {
            Log.i(TAG, "[SCAN] MAC disponÃ­vel â usando connectWithMac() em vez de scan");
            connectWithMac(mTargetMac);
            return;
        }
        if (mBleScanner == null || !mBluetoothAdapter.isEnabled()) {
            Log.w(TAG, "[SCAN] Scanner nÃ£o disponÃ­vel");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "[SCAN] BLUETOOTH_SCAN nÃ£o concedido â scan cancelado");
                return;
            }
        }
        if (mScanning) return;
        synchronized (mMacsValidando) { mMacsValidando.clear(); }
        mScanning = true;
        Log.i(TAG, "[SCAN] Iniciando scan manual por CHOPP_*");
        broadcastConnectionStatus("scanning");
        mBleScanner.startScan(mScanCallback);
        mScanStopRunnable = () -> {
            if (mScanning) {
                Log.w(TAG, "[SCAN] Timeout 15s â nenhum CHOPP_ encontrado");
                pararScan();
                broadcastConnectionStatus("scan_timeout");
            }
        };
        mMainHandler.postDelayed(mScanStopRunnable, 15_000L);
    }

    private void pararScan() {
        if (!mScanning) return;
        mScanning = false;
        if (mScanStopRunnable != null) {
            mMainHandler.removeCallbacks(mScanStopRunnable);
            mScanStopRunnable = null;
        }
        if (mBleScanner != null) {
            try { mBleScanner.stopScan(mScanCallback); } catch (Exception ignored) {}
        }
        Log.i(TAG, "[SCAN] Scan parado");
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            if (name == null || !name.startsWith(BLE_NAME_PREFIX)) return;
            String mac = device.getAddress();
            Log.i(TAG, "[SCAN] Encontrado: " + name + " | " + mac);
            synchronized (mMacsValidando) {
                if (mMacsValidando.contains(mac)) return;
                mMacsValidando.add(mac);
            }
            validarMacNaApi(device);
        }
        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "[SCAN] Falhou: " + errorCode);
            mScanning = false;
        }
    };

    private void validarMacNaApi(BluetoothDevice device) {
        final String mac = device.getAddress();
        final String androidId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        new Thread(() -> {
            try {
                String token = gerarJwtToken();
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build();
                okhttp3.RequestBody body = new FormBody.Builder()
                        .add("android_id", androidId != null ? androidId : "")
                        .add("mac", mac)
                        .build();
                Request request = new Request.Builder()
                        .url("https://ochoppoficial.com.br/api/verify_tap_mac.php")
                        .header("token", token)
                        .post(body)
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (response.isSuccessful() && responseBody.contains("\"valid\":true")) {
                        Log.i(TAG, "[SCAN] MAC vÃ¡lido: " + mac + " â conectando");
                        mMainHandler.post(() -> {
                            pararScan();
                            connectWithMac(mac);
                        });
                    } else {
                        Log.i(TAG, "[SCAN] MAC invÃ¡lido â ignorando: " + mac);
                        synchronized (mMacsValidando) { mMacsValidando.remove(mac); }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "[SCAN] Erro ao validar MAC: " + e.getMessage());
                synchronized (mMacsValidando) { mMacsValidando.remove(mac); }
            }
        }).start();
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // NotificaÃ§Ã£o Foreground
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private void criarNotificacaoForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID, "Chopp BLE Industrial", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("ServiÃ§o BLE Chopp Self-Service");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        Intent notifIntent = new Intent(this, getClass());
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent, flags);
        Notification notification = new Notification.Builder(
                this,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? NOTIF_CHANNEL_ID : null)
                .setContentTitle("Chopp BLE Industrial")
                .setContentText("ServiÃ§o BLE ativo â aguardando ESP32")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notification);
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // Helpers de nomes
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    private String gattStateName(int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:     return "CONNECTED";
            case BluetoothProfile.STATE_CONNECTING:    return "CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTED:  return "DISCONNECTED";
            case BluetoothProfile.STATE_DISCONNECTING: return "DISCONNECTING";
            default:                                   return "UNKNOWN(" + state + ")";
        }
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // API PÃºblica
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ

    /**
     * Envia um comando ao ESP32 via fila de comandos.
     * Formato: "$ML:100", "$PL:5880", "$TO:5000", "$LB:", etc.
     */
    public boolean write(String data) {
        if (data == null || data.isEmpty()) return false;
        if (mState != State.READY) {
            Log.e(TAG, "[WRITE] BLOCKED â BLE nÃ£o estÃ¡ READY (estado=" + mState.name() + ")");
            return false;
        }
        if (!isBleStackPronto()) {
            Log.e(TAG, "[WRITE] BLOCKED â handles BLE ausentes");
            return false;
        }
        Log.i(TAG, "[WRITE] enfileirando: \"" + data + "\"");
        enqueueCommand(data);
        return true;
    }

    public boolean isReady()     { return mState == State.READY; }
    public State   getState()    { return mState; }
    public String  getTargetMac(){ return mTargetMac; }

    public long getTimeSinceReady() {
        if (mReadyTimestamp == 0) return -1;
        return System.currentTimeMillis() - mReadyTimestamp;
    }

    public boolean isReadyWithGuardBand() {
        if (!isReady()) return false;
        long t = getTimeSinceReady();
        if (t < 0) return false;
        boolean ok = t >= GUARD_BAND_MS;
        if (!ok) Log.w(TAG, "[GUARD-BAND] BLOQUEADO â " + (GUARD_BAND_MS - t) + "ms faltando");
        return ok;
    }

    public long getReadyTimestamp() { return mReadyTimestamp; }
    public long getGuardBandMs()    { return GUARD_BAND_MS; }

    public void disconnect() {
        Log.i(TAG, "[API] disconnect()");
        mAutoReconnect = false;
        pararReconexao();
        closeGatt();
        transitionTo(State.DISCONNECTED);
    }

    public void resetMac() {
        Log.i(TAG, "[API] resetMac() â limpando MAC salvo");
        mTargetMac = null;
        mMacAlvo   = null;
        getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .edit().remove("esp32_mac").apply();
        disconnect();
        mAutoReconnect = true;
    }

    public void salvarMacExterno(String mac) {
        if (mac == null || mac.isEmpty()) return;
        if (mac.equalsIgnoreCase(mTargetMac)
                && (mState == State.CONNECTED || mState == State.READY)) {
            Log.d(TAG, "[MAC] salvarMacExterno: MAC jÃ¡ conectado (" + mac + ") â sem aÃ§Ã£o");
            return;
        }
        Log.i(TAG, "[MAC] salvarMacExterno: " + mac);
        connectWithMac(mac);
    }

    public int  getQueueSize() { return mCommandQueue.size(); }
    public void clearQueue() {
        int size = mCommandQueue.size();
        mCommandQueue.clear();
        mWriteBusy.set(false);
        Log.i(TAG, "[API] clearQueue() â " + size + " comandos descartados");
    }

    public boolean isInternetAvailable() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    // API de compatibilidade com BluetoothService (drop-in replacement)
    // âââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââââ
    public enum BleState { DISCONNECTED, CONNECTED, READY }

    public BleState getBleState() {
        switch (mState) {
            case READY:      return BleState.READY;
            case CONNECTED:
            case CONNECTING: return BleState.CONNECTED;
            default:         return BleState.DISCONNECTED;
        }
    }

    public boolean connected() {
        return mBluetoothGatt != null && mState != State.DISCONNECTED;
    }

    public void forceReady() {
        Log.i(TAG, "[COMPAT] forceReady() â estado=" + mState.name());
        if (!isBleStackPronto()) {
            Log.e(TAG, "[COMPAT] forceReady BLOQUEADO â BLE nÃ£o pronto");
            return;
        }
        if (mState != State.READY) transitionTo(State.READY);
    }

    /**
     * Compatibilidade: scanLeDevice(true) inicia conexÃ£o pelo MAC salvo ou scan manual.
     */
    public void scanLeDevice(boolean enable) {
        mAutoReconnect = true;
        if (!enable) {
            pararScan();
            return;
        }
        if (mTargetMac != null) {
            Log.i(TAG, "[COMPAT] scanLeDevice() â MAC salvo: " + mTargetMac + " â connectWithMac()");
            connectWithMac(mTargetMac);
        } else {
            Log.i(TAG, "[COMPAT] scanLeDevice() â sem MAC â scan manual por CHOPP_*");
            iniciarScanManual();
        }
    }

    public void enableAutoReconnect() {
        mAutoReconnect = true;
        mReconnectAttempts = 0;
        mBackoffIndex = 0;
        Log.i(TAG, "[COMPAT] enableAutoReconnect()");
    }

    public BluetoothDevice getBoundDevice() {
        return mBluetoothGatt != null ? mBluetoothGatt.getDevice() : null;
    }

     /** @deprecated NÃ£o utilizado no protocolo v6.0 NUS */
    @Deprecated
    public CommandQueueManager getCommandQueue() { return null; }
    /** @deprecated NÃ£o utilizado no protocolo v6.0 NUS */
    @Deprecated
    public CommandQueue getCommandQueueV2() { return null; }

    /**
     * Enfileira comando de liberaÃ§Ã£o de chopp.
     * Formato: $ML:<volume_ml>
     */
    public BleCommand enqueueServeCommand(int volumeMl, String sessionId) {
        BleCommand cmd = BleCommand.buildMl(volumeMl, sessionId);
        String command = cmd.toBleString(); // "$ML:<volumeMl>"
        if (mBleReady && isBleStackPronto()) {
            // BLE pronto â envia imediatamente
            Log.i(TAG, "[SERVE] Comando enviado imediatamente: " + command);
            boolean ok = write(command);
            if (!ok) {
                Log.e(TAG, "[SERVE] Falha ao escrever: " + command + " â armazenando como pendente");
                mComandoPendente = command;
                cmd.state = com.example.choppontap.BleCommand.State.PENDING;
                return cmd;
            }
            cmd.state = com.example.choppontap.BleCommand.State.SENT;
        } else {
            // BLE nÃ£o pronto â armazena para enviar quando conectar
            mComandoPendente = command;
            cmd.state = com.example.choppontap.BleCommand.State.PENDING;
            Log.w(TAG, "[SERVE] BLE nÃ£o pronto â comando pendente: " + command);
        }
        return cmd;
    }

    /**
     * Alias para connectWithMac() â mantido para compatibilidade.
     */
    public void connect(String mac) {
        connectWithMac(mac);
    }
}
