package com.example.choppontap;
/*
 * ═══════════════════════════════════════════════════════════════════════════════
 * BluetoothServiceIndustrial.java — Serviço BLE NUS v6.0 (Sem Bond)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Versão: 6.0-NUS-DIRECT
 * Protocolo: Nordic UART Service (NUS) sobre BLE — Firmware ESP32 operacional.cpp
 * Target: ESP32 CHOPP Self-Service
 * Compatibilidade: Android 8+ (API 26+), Android 12+ permissões, Android 14 FGS
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * FLUXO ÚNICO DE CONEXÃO (SEM BOND)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   API retorna MAC
 *       ↓
 *   connectWithMac(mac)
 *       ↓
 *   conectarComScan(mac) — scan 4s filtrando pelo MAC
 *       ↓
 *   device encontrado → conectarGatt(mac)
 *       ↓
 *   onConnectionStateChange(CONNECTED)
 *       ↓
 *   requestConnectionPriority(HIGH) + requestMtu(247)
 *       ↓
 *   onMtuChanged() → discoverServices()
 *       ↓
 *   onServicesDiscovered() → enableNotifications()
 *       ↓
 *   onDescriptorWrite() → READY
 *       ↓
 *   Comunicação fluida ($ML:, $PL:, $TO:, $LB:)
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * PROTOCOLO DE COMUNICAÇÃO (Firmware ESP32 operacional.cpp)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   COMANDOS (App → ESP32):
 *     $ML:<volume_ml>    — Liberar volume em mL (ex: $ML:100)
 *     $PL:<pulsos>       — Configurar pulsos/litro (ex: $PL:5880)
 *     $TO:<timeout_ms>   — Configurar timeout (ex: $TO:5000)
 *     $LB:               — Liberação contínua
 *
 *   RESPOSTAS (ESP32 → App):
 *     OK                 — Comando aceito e enfileirado
 *     ERRO               — Comando com erro
 *     VP:<ml_parcial>    — Volume parcial durante liberação
 *     QP:<pulsos>        — Quantidade de pulsos ao final
 *     ML:<ml_final>      — Volume final liberado (sinal de conclusão)
 *     PL:<pulsos>        — Resposta de leitura de pulsos/litro
 *
 *   AUTENTICAÇÃO: Nenhuma — ESP32 aceita conexão direta sem bond
 *   NÃO existe: HMAC, SESSION_ID, CMD_ID, AUTH, SERVE, STOP, STATUS, PING,
 *               ACK, DONE, AUTH:OK/FAIL, PONG, READY/READY_OK, PIN, BOND
 *
 * ═══════════════════════════════════════════════════════════════════════════════
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

import com.example.choppontap.ble.BleCommand;
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
 * Serviço BLE NUS v6.0 — conexão direta sem bond.
 * O ESP32 aceita conexão direta igual ao nRF Connect.
 *
 * REMOVIDO: iniciarBondEConectar, mPairingReceiver, mBondStateReceiver,
 *           iniciarBondTimeout, cancelarBondTimeout, injetarPinViaReflection,
 *           removerBondViaReflection, pairingTypeName, mBondFailCount,
 *           mBondRetryCount, mBondTimeoutRunnable, BOND_TIMEOUT_MS, MAX_BOND_RETRIES
 */
public class BluetoothServiceIndustrial extends Service {

    private static final String TAG = "BLE_INDUSTRIAL";

    // ═══════════════════════════════════════════════════════════════════════════
    // Singleton
    // ═══════════════════════════════════════════════════════════════════════════
    private static volatile BluetoothServiceIndustrial sInstance;

    public static BluetoothServiceIndustrial getInstance() { return sInstance; }
    public static boolean isRunning() { return sInstance != null; }

    // ═══════════════════════════════════════════════════════════════════════════
    // Estado interno
    // ═══════════════════════════════════════════════════════════════════════════
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
        Log.i(TAG, "═══ STATE: " + old.name() + " → " + newState.name() + " ═══");
        broadcastBleState(newState);
        switch (newState) {
            case DISCONNECTED:
                mWriteCharacteristic = null;
                mNotifyCharacteristic = null;
                mWriteBusy.set(false);
                mReadyTimestamp = 0;
                break;
            case READY:
                mReconnectAttempts = 0;
                mBackoffIndex = 0;
                mReadyTimestamp = System.currentTimeMillis();
                Log.i(TAG, "[STATE] READY — aguardando pagamento");
                broadcastWriteReady();
                mMainHandler.post(this::drainCommandQueue);
                break;
            case ERROR:
                pararReconexao();
                Log.e(TAG, "[STATE] ERROR — verifique firmware ESP32");
                break;
            default:
                break;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UUIDs NUS (Nordic UART Service) — Firmware ESP32 operaBLE.cpp
    // ═══════════════════════════════════════════════════════════════════════════
    public static final String BLE_NAME_PREFIX = "CHOPP_";
    private static final UUID NUS_SERVICE_UUID           = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_RX_CHARACTERISTIC_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_TX_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CCCD_UUID                  = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ═══════════════════════════════════════════════════════════════════════════
    // Ações de Broadcast
    // ═══════════════════════════════════════════════════════════════════════════
    public static final String ACTION_DATA_AVAILABLE    = "com.example.choppontap.ACTION_DATA_AVAILABLE";
    public static final String ACTION_CONNECTION_STATUS = "com.example.choppontap.ACTION_CONNECTION_STATUS";
    public static final String ACTION_WRITE_READY       = "com.example.choppontap.ACTION_WRITE_READY";
    public static final String ACTION_DEVICE_FOUND      = "com.example.choppontap.ACTION_DEVICE_FOUND";
    public static final String ACTION_BLE_STATE_CHANGED = "com.example.choppontap.ACTION_BLE_STATE_CHANGED";
    public static final String EXTRA_DATA      = "com.example.choppontap.EXTRA_DATA";
    public static final String EXTRA_STATUS    = "com.example.choppontap.EXTRA_STATUS";
    public static final String EXTRA_DEVICE    = "com.example.choppontap.EXTRA_DEVICE";
    public static final String EXTRA_BLE_STATE = "com.example.choppontap.EXTRA_BLE_STATE";

    // ═══════════════════════════════════════════════════════════════════════════
    // Códigos de status GATT
    // ═══════════════════════════════════════════════════════════════════════════
    private static final int STATUS_GATT_ERROR = 133;

    // ═══════════════════════════════════════════════════════════════════════════
    // Backoff exponencial de reconexão
    // ═══════════════════════════════════════════════════════════════════════════
    private static final long[] BACKOFF_DELAYS = { 3_000L, 6_000L, 12_000L, 20_000L };
    private int  mReconnectAttempts = 0;
    private int  mBackoffIndex      = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Guard-band (evita comandos logo após READY)
    // ═══════════════════════════════════════════════════════════════════════════
    private static final long GUARD_BAND_MS = 900L;
    private volatile long mReadyTimestamp = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Foreground Service
    // ═══════════════════════════════════════════════════════════════════════════
    private static final String NOTIF_CHANNEL_ID = "ble_industrial_channel";
    private static final int    NOTIF_ID         = 1001;

    // ═══════════════════════════════════════════════════════════════════════════
    // Campos de instância
    // ═══════════════════════════════════════════════════════════════════════════
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Binder
    // ═══════════════════════════════════════════════════════════════════════════
    public class LocalBinder extends Binder {
        public BluetoothServiceIndustrial getService() {
            return BluetoothServiceIndustrial.this;
        }
    }
    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════
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
                Log.e(TAG, "[SERVICE] BluetoothManager null — abortando");
                stopSelf();
                return;
            }
            mBluetoothAdapter = bm.getAdapter();
            if (mBluetoothAdapter == null) {
                Log.e(TAG, "[SERVICE] BluetoothAdapter null — abortando");
                stopSelf();
                return;
            }
            mBleScanner = mBluetoothAdapter.getBluetoothLeScanner();

            // Recupera MAC salvo de sessão anterior
            String savedMac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                    .getString("esp32_mac", null);
            if (savedMac != null) {
                mTargetMac = savedMac;
                mMacAlvo   = savedMac;
                Log.i(TAG, "[SERVICE] MAC recuperado: " + savedMac);
            }

            // NÃO inicia conexão aqui.
            // A conexão só inicia quando connectWithMac(mac) for chamado.
            Log.i(TAG, "[SERVICE] Aguardando connectWithMac(mac) para iniciar conexão BLE");
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

    // ═══════════════════════════════════════════════════════════════════════════
    // PONTO DE ENTRADA ÚNICO: connectWithMac(mac)
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * Ponto de entrada principal para conexão BLE.
     * Deve ser chamado após a API retornar o MAC do ESP32.
     *
     * Fluxo: connectWithMac(mac) → conectarComScan(mac) → conectarGatt(mac) → READY
     *
     * @param mac endereço MAC do ESP32 (ex: "DC:B4:D9:99:B8:E2")
     */
    public void connectWithMac(String mac) {
        if (mac == null || mac.isEmpty()) {
            Log.e(TAG, "[CONNECT] MAC inválido");
            return;
        }
        Log.i(TAG, "[CONNECT] connectWithMac(" + mac + ")");
        salvarMac(mac);
        mAutoReconnect = true;

        if (!hasConnectPermission()) {
            Log.e(TAG, "[CONNECT] BLUETOOTH_CONNECT não concedida — aguardando permissão");
            mMainHandler.postDelayed(() -> connectWithMac(mac), 3_000L);
            return;
        }
        if (!mBluetoothAdapter.isEnabled()) {
            Log.w(TAG, "[CONNECT] Bluetooth desativado — aguardando ativação");
            return;
        }

        // Se já está conectado/conectando ao mesmo MAC, não faz nada
        if ((mState == State.CONNECTING || mState == State.CONNECTED || mState == State.READY)
                && mac.equalsIgnoreCase(mTargetMac)) {
            Log.i(TAG, "[CONNECT] Já conectado/conectando ao MAC " + mac + " — ignorando");
            return;
        }

        // Se está conectado a outro MAC, desconecta primeiro
        if (mState != State.DISCONNECTED) {
            Log.i(TAG, "[CONNECT] Desconectando MAC anterior para conectar ao novo: " + mac);
            closeGatt();
            transitionTo(State.DISCONNECTED);
        }

        pararReconexao();
        conectarComScan(mac);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Scan por MAC → GATT direto (sem bond)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Inicia scan BLE curto (4s) filtrando pelo MAC.
     * Se encontrar: conectarGatt(mac).
     * Se timeout: conectarGatt(mac) diretamente (fallback).
     */
    private void conectarComScan(final String mac) {
        Log.i(TAG, "[SCAN] Iniciando scan para " + mac);

        BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.e(TAG, "[SCAN] Scanner indisponível — conectando direto");
            conectarGatt(mac);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "[SCAN] BLUETOOTH_SCAN não concedido — conectando direto");
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
                    Log.i(TAG, "[SCAN] " + mac + " encontrado — conectando");
                    try { scanner.stopScan(cbRef[0]); } catch (Exception ignored) {}
                    mMainHandler.removeCallbacksAndMessages(null);
                    conectarGatt(mac);
                }
            }
            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "[SCAN] Falhou (" + errorCode + ") — conectando direto");
                conectarGatt(mac);
            }
        };

        try {
            scanner.startScan(Collections.singletonList(filtro), settings, cbRef[0]);
        } catch (Exception e) {
            Log.e(TAG, "[SCAN] startScan exception: " + e.getMessage() + " — conectando direto");
            conectarGatt(mac);
            return;
        }

        // Timeout 4s — se não encontrar, conecta direto
        mMainHandler.postDelayed(() -> {
            if (!encontrado[0]) {
                Log.w(TAG, "[SCAN] Timeout — conectando direto");
                try { scanner.stopScan(cbRef[0]); } catch (Exception ignored) {}
                conectarGatt(mac);
            }
        }, 4000);
    }

    /**
     * Conecta via GATT diretamente ao MAC (sem bond).
     * O ESP32 aceita conexão direta igual ao nRF Connect.
     */
    private void conectarGatt(String mac) {
        if (!hasConnectPermission()) {
            Log.e(TAG, "[GATT] BLUETOOTH_CONNECT não concedida — abortando");
            return;
        }
        if (mState == State.CONNECTING || mState == State.CONNECTED || mState == State.READY) {
            Log.w(TAG, "[GATT] Já conectado/conectando (estado=" + mState.name() + ") — ignorando");
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
            Log.e(TAG, "[GATT] getRemoteDevice(" + mac + ") retornou null — abortando");
            return;
        }

        transitionTo(State.CONNECTING);
        Log.i(TAG, "[GATT] Conectando via GATT direto (sem bond) → " + mac
                + " | tentativa=" + (mReconnectAttempts + 1));

        // Reutiliza GATT existente se for o mesmo MAC
        if (mBluetoothGatt != null
                && mBluetoothGatt.getDevice().getAddress().equalsIgnoreCase(mac)) {
            Log.i(TAG, "[GATT] GATT existente → gatt.connect() (reconexão rápida)");
            boolean ok = mBluetoothGatt.connect();
            if (ok) {
                Log.i(TAG, "[GATT] gatt.connect() → OK");
                return;
            }
            Log.w(TAG, "[GATT] gatt.connect() falhou → fechando e criando novo GATT");
            closeGatt();
        } else if (mBluetoothGatt != null) {
            Log.i(TAG, "[GATT] GATT de outro MAC — fechando");
            closeGatt();
        }

        // Cria novo GATT com autoConnect=false (conexão manual, igual ao nRF Connect)
        Log.i(TAG, "[GATT] connectGatt(autoConnect=false, TRANSPORT_LE) → " + mac);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mBluetoothGatt = device.connectGatt(
                    this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        }

        if (mBluetoothGatt == null) {
            Log.e(TAG, "[GATT] connectGatt() retornou null — reagendando");
            transitionTo(State.DISCONNECTED);
            reconectarComBackoff();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Reconexão com backoff exponencial
    // ═══════════════════════════════════════════════════════════════════════════
    private void reconectarComBackoff() {
        if (!mAutoReconnect) {
            Log.d(TAG, "[RECONNECT] autoReconnect=false — não reconectando");
            return;
        }
        if (mReconnectRunnable != null) {
            Log.d(TAG, "[RECONNECT] Reconexão já agendada — ignorando duplicata");
            return;
        }
        if (mMacAlvo == null && mTargetMac == null) {
            Log.w(TAG, "[RECONNECT] Sem MAC — aguardando connectWithMac()");
            return;
        }

        final String mac = mMacAlvo != null ? mMacAlvo : mTargetMac;

        if (mReconnectAttempts >= 5) {
            Log.e(TAG, "[RECONNECT] Limite de 5 tentativas atingido — parando reconexão");
            transitionTo(State.ERROR);
            return;
        }

        long delay = BACKOFF_DELAYS[Math.min(mBackoffIndex, BACKOFF_DELAYS.length - 1)];
        mBackoffIndex = Math.min(mBackoffIndex + 1, BACKOFF_DELAYS.length - 1);
        mReconnectAttempts++;

        Log.i(TAG, "[RECONNECT] Tentativa #" + mReconnectAttempts
                + " em " + delay + "ms → " + mac);

        mReconnectRunnable = () -> {
            mReconnectRunnable = null;
            if (!mAutoReconnect || mac == null) return;
            Log.i(TAG, "[RECONNECT] Executando reconexão → " + mac);
            if (hasConnectPermission()) {
                conectarComScan(mac);
            } else {
                Log.w(TAG, "[RECONNECT] BLUETOOTH_CONNECT não concedida — retry em 5s");
                mMainHandler.postDelayed(() -> reconectarComBackoff(), 5_000L);
            }
        };
        mMainHandler.postDelayed(mReconnectRunnable, delay);
    }

    private void pararReconexao() {
        if (mReconnectRunnable != null) {
            mMainHandler.removeCallbacks(mReconnectRunnable);
            mReconnectRunnable = null;
            Log.d(TAG, "[RECONNECT] Reconexão cancelada");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GATT Callback
    // ═══════════════════════════════════════════════════════════════════════════
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
            Log.i(TAG, "[BLE] onMtuChanged → discoverServices() → " + (ok ? "INICIADO" : "FALHOU"));
            if (!ok) {
                mMainHandler.postDelayed(() -> {
                    if (mBluetoothGatt != null) {
                        boolean retry = mBluetoothGatt.discoverServices();
                        Log.i(TAG, "[GATT] discoverServices() retry → " + (retry ? "INICIADO" : "FALHOU"));
                    }
                }, 600L);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.i(TAG, "[GATT] onServicesDiscovered | status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[GATT] discoverServices falhou (status=" + status + ") — reconectando");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }
            BluetoothGattService nusService = gatt.getService(NUS_SERVICE_UUID);
            if (nusService == null) {
                Log.e(TAG, "[BLE] NUS SERVICE NOT FOUND (6E400001) — reconectando");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }
            Log.i(TAG, "[BLE] NUS SERVICE FOUND ✓");
            BluetoothGattCharacteristic rxChar = nusService.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
            BluetoothGattCharacteristic txChar = nusService.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID);
            if (rxChar == null) {
                Log.e(TAG, "[BLE] NUS RX NOT FOUND (6E400002) — reconectando");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }
            if (txChar == null) {
                Log.e(TAG, "[BLE] NUS TX NOT FOUND (6E400003) — reconectando");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }
            mBluetoothGatt = gatt;
            mWriteCharacteristic = rxChar;
            mNotifyCharacteristic = txChar;
            Log.i(TAG, "[BLE] NUS RX (write) + TX (notify) prontos ✓");
            habilitarNotificacoes(gatt, txChar);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.i(TAG, "[GATT] onDescriptorWrite | status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "[BLE] NOTIFY ENABLED ✓ → READY");
                mMainHandler.post(() -> {
                    if (isBleStackPronto()) {
                        transitionTo(State.READY);
                        broadcastConnectionStatus("ready");
                    } else {
                        Log.e(TAG, "[BLE] READY BLOQUEADO — handles BLE ausentes");
                    }
                });
            } else {
                Log.w(TAG, "[GATT] onDescriptorWrite falhou (status=" + status
                        + ") — tentando READY mesmo assim");
                mMainHandler.post(() -> {
                    if (isBleStackPronto()) {
                        transitionTo(State.READY);
                        broadcastConnectionStatus("ready");
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Handlers de conexão/desconexão GATT
    // ═══════════════════════════════════════════════════════════════════════════
    private void handleGattConnected(BluetoothGatt gatt, int status) {
        Log.i(TAG, "[BLE] CONNECTED ✓ → " + gatt.getDevice().getAddress()
                + " | status=" + status);
        pararReconexao();
        mReconnectAttempts = 0;
        mBackoffIndex = 0;
        mBluetoothGatt = gatt;
        mWriteCharacteristic = null;
        mNotifyCharacteristic = null;
        transitionTo(State.CONNECTED);
        broadcastConnectionStatus("connected");

        // Prioridade HIGH para menor latência
        boolean priOk = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        Log.i(TAG, "[BLE] requestConnectionPriority(HIGH) → " + (priOk ? "OK" : "FALHOU"));

        // MTU 247 — máximo prático para NUS
        boolean mtuOk = gatt.requestMtu(247);
        Log.i(TAG, "[BLE] requestMtu(247) → "
                + (mtuOk ? "AGUARDANDO onMtuChanged" : "FALHOU — discoverServices direto"));
        if (!mtuOk) {
            mMainHandler.postDelayed(() -> {
                if (mBluetoothGatt != null) {
                    boolean ok = mBluetoothGatt.discoverServices();
                    Log.i(TAG, "[GATT] discoverServices() (fallback MTU) → "
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
        broadcastConnectionStatus("disconnected:" + status);

        if (!mAutoReconnect) {
            Log.i(TAG, "[GATT] autoReconnect=false — não reconectando");
            return;
        }

        if (status == STATUS_GATT_ERROR) {
            // GATT error 133 — fecha e recria GATT
            Log.w(TAG, "[GATT] GATT_ERROR (133) → fechando GATT e reconectando");
            closeGatt();
            mMainHandler.postDelayed(() -> reconectarComBackoff(), 1_000L);
        } else {
            // Todos os outros status — reconecta com backoff via scan
            final String mac = mMacAlvo != null ? mMacAlvo : mTargetMac;
            if (mac != null) {
                Log.i(TAG, "[GATT] status=" + status + " → reconectando em 3s via scan → " + mac);
                mMainHandler.postDelayed(() -> {
                    if (mAutoReconnect) {
                        Log.i(TAG, "[RECONNECT] Reconectando após desconexão → " + mac);
                        conectarComScan(mac);
                    }
                }, 3_000L);
            } else {
                reconectarComBackoff();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Habilitar notificações (CCCD)
    // ═══════════════════════════════════════════════════════════════════════════
    private void habilitarNotificacoes(BluetoothGatt gatt, BluetoothGattCharacteristic txChar) {
        mNotifyCharacteristic = txChar;
        boolean ok = gatt.setCharacteristicNotification(txChar, true);
        Log.i(TAG, "[GATT] setCharacteristicNotification(TX, true) → " + (ok ? "OK" : "FALHOU"));
        BluetoothGattDescriptor cccd = txChar.getDescriptor(CCCD_UUID);
        if (cccd == null) {
            Log.e(TAG, "[GATT] CCCD não encontrado — indo para READY sem notificações");
            mMainHandler.post(() -> {
                if (isBleStackPronto()) {
                    transitionTo(State.READY);
                    broadcastConnectionStatus("ready");
                }
            });
            return;
        }
        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        boolean writeOk = gatt.writeDescriptor(cccd);
        Log.i(TAG, "[GATT] writeDescriptor(CCCD) → "
                + (writeOk ? "OK — aguardando onDescriptorWrite" : "FALHOU"));
        if (!writeOk) {
            mMainHandler.post(() -> {
                if (isBleStackPronto()) {
                    transitionTo(State.READY);
                    broadcastConnectionStatus("ready");
                }
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Processamento de respostas BLE do ESP32
    // ═══════════════════════════════════════════════════════════════════════════
    private void processarRespostaBle(String data) {
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Fila de comandos
    // ═══════════════════════════════════════════════════════════════════════════
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
            Log.e(TAG, "[WRITE] BLUETOOTH_CONNECT não concedida — abortando write");
            return;
        }
        if (!isBleStackPronto()) {
            Log.e(TAG, "[WRITE] BLE stack não pronto — abortando write");
            return;
        }
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        mWriteCharacteristic.setValue(bytes);
        mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        mWriteBusy.set(true);
        boolean ok = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
        Log.i(TAG, "[WRITE] writeCharacteristic(\"" + data + "\") → " + (ok ? "OK" : "FALHOU"));
        if (!ok) {
            mWriteBusy.set(false);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers internos
    // ═══════════════════════════════════════════════════════════════════════════
    private boolean isBleStackPronto() {
        return mBluetoothGatt != null
                && mWriteCharacteristic != null
                && mNotifyCharacteristic != null;
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
            Log.d(TAG, "[GATT] closeGatt() — GATT fechado");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Verificação de permissão BLUETOOTH_CONNECT (Android 12+)
    // ═══════════════════════════════════════════════════════════════════════════
    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean granted = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Log.w(TAG, "[PERM] BLUETOOTH_CONNECT não concedida");
            }
            return granted;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Salvar MAC
    // ═══════════════════════════════════════════════════════════════════════════
    private void salvarMac(String mac) {
        if (mac == null) return;
        mTargetMac = mac;
        mMacAlvo   = mac;
        getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .edit().putString("esp32_mac", mac).apply();
        Log.i(TAG, "[MAC] MAC salvo: " + mac);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Broadcasts
    // ═══════════════════════════════════════════════════════════════════════════
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
        Log.i(TAG, "[BROADCAST] ACTION_WRITE_READY — READY para comandos");
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(ACTION_WRITE_READY));
    }

    private void broadcastBleState(State state) {
        Intent i = new Intent(ACTION_BLE_STATE_CHANGED);
        i.putExtra(EXTRA_BLE_STATE, state.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JWT para validação de MAC na API (scan manual)
    // ═══════════════════════════════════════════════════════════════════════════
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Scan BLE manual (fallback — apenas quando não há MAC)
    // ═══════════════════════════════════════════════════════════════════════════
    private boolean mScanning = false;
    private Runnable mScanStopRunnable = null;
    private final Set<String> mMacsValidando = new HashSet<>();

    /**
     * Scan BLE manual — apenas como fallback quando não há MAC disponível.
     */
    private void iniciarScanManual() {
        if (mTargetMac != null) {
            Log.i(TAG, "[SCAN] MAC disponível — usando connectWithMac() em vez de scan");
            connectWithMac(mTargetMac);
            return;
        }
        if (mBleScanner == null || !mBluetoothAdapter.isEnabled()) {
            Log.w(TAG, "[SCAN] Scanner não disponível");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "[SCAN] BLUETOOTH_SCAN não concedido — scan cancelado");
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
                Log.w(TAG, "[SCAN] Timeout 15s — nenhum CHOPP_ encontrado");
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
                        Log.i(TAG, "[SCAN] MAC válido: " + mac + " — conectando");
                        mMainHandler.post(() -> {
                            pararScan();
                            connectWithMac(mac);
                        });
                    } else {
                        Log.i(TAG, "[SCAN] MAC inválido — ignorando: " + mac);
                        synchronized (mMacsValidando) { mMacsValidando.remove(mac); }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "[SCAN] Erro ao validar MAC: " + e.getMessage());
                synchronized (mMacsValidando) { mMacsValidando.remove(mac); }
            }
        }).start();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Notificação Foreground
    // ═══════════════════════════════════════════════════════════════════════════
    private void criarNotificacaoForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID, "Chopp BLE Industrial", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Serviço BLE Chopp Self-Service");
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
                .setContentText("Serviço BLE ativo — aguardando ESP32")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notification);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers de nomes
    // ═══════════════════════════════════════════════════════════════════════════
    private String gattStateName(int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:     return "CONNECTED";
            case BluetoothProfile.STATE_CONNECTING:    return "CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTED:  return "DISCONNECTED";
            case BluetoothProfile.STATE_DISCONNECTING: return "DISCONNECTING";
            default:                                   return "UNKNOWN(" + state + ")";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API Pública
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Envia um comando ao ESP32 via fila de comandos.
     * Formato: "$ML:100", "$PL:5880", "$TO:5000", "$LB:", etc.
     */
    public boolean write(String data) {
        if (data == null || data.isEmpty()) return false;
        if (mState != State.READY) {
            Log.e(TAG, "[WRITE] BLOCKED — BLE não está READY (estado=" + mState.name() + ")");
            return false;
        }
        if (!isBleStackPronto()) {
            Log.e(TAG, "[WRITE] BLOCKED — handles BLE ausentes");
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
        if (!ok) Log.w(TAG, "[GUARD-BAND] BLOQUEADO — " + (GUARD_BAND_MS - t) + "ms faltando");
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
        Log.i(TAG, "[API] resetMac() — limpando MAC salvo");
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
            Log.d(TAG, "[MAC] salvarMacExterno: MAC já conectado (" + mac + ") — sem ação");
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
        Log.i(TAG, "[API] clearQueue() — " + size + " comandos descartados");
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

    // ═══════════════════════════════════════════════════════════════════════════
    // API de compatibilidade com BluetoothService (drop-in replacement)
    // ═══════════════════════════════════════════════════════════════════════════
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
        Log.i(TAG, "[COMPAT] forceReady() — estado=" + mState.name());
        if (!isBleStackPronto()) {
            Log.e(TAG, "[COMPAT] forceReady BLOQUEADO — BLE não pronto");
            return;
        }
        if (mState != State.READY) transitionTo(State.READY);
    }

    /**
     * Compatibilidade: scanLeDevice(true) inicia conexão pelo MAC salvo ou scan manual.
     */
    public void scanLeDevice(boolean enable) {
        mAutoReconnect = true;
        if (!enable) {
            pararScan();
            return;
        }
        if (mTargetMac != null) {
            Log.i(TAG, "[COMPAT] scanLeDevice() — MAC salvo: " + mTargetMac + " → connectWithMac()");
            connectWithMac(mTargetMac);
        } else {
            Log.i(TAG, "[COMPAT] scanLeDevice() — sem MAC → scan manual por CHOPP_*");
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

    /** @deprecated Não utilizado no protocolo v6.0 NUS */
    public CommandQueueManager getCommandQueue() { return null; }

    /** @deprecated Não utilizado no protocolo v6.0 NUS */
    public CommandQueue getCommandQueueV2() { return null; }

    /**
     * Enfileira comando de liberação de chopp.
     * Formato: $ML:<volume_ml>
     */
    public BleCommand enqueueServeCommand(int volumeMl, String sessionId) {
        if (mState != State.READY || !isBleStackPronto()) {
            Log.e(TAG, "[SERVE] BLE não está READY — comando descartado");
            return null;
        }
        String command = "$ML:" + volumeMl;
        Log.i(TAG, "[BLE] Enviando " + command);
        BleCommand cmd = new BleCommand(BleCommand.Type.ML, sessionId, volumeMl);
        boolean ok = write(command);
        if (!ok) {
            Log.e(TAG, "[SERVE] Falha ao enfileirar: " + command);
            return null;
        }
        cmd.state = BleCommand.State.SENT;
        return cmd;
    }

    /**
     * Alias para connectWithMac() — mantido para compatibilidade.
     */
    public void connect(String mac) {
        connectWithMac(mac);
    }
}
