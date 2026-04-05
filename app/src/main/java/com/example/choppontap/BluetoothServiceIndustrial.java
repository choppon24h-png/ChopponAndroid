package com.example.choppontap;

/*
 * ═══════════════════════════════════════════════════════════════════════════════
 * BluetoothServiceIndustrial.java — Serviço BLE Padrão Industrial 24/7
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Versão: 4.0-NUS
 * Protocolo: Nordic UART Service (NUS) sobre BLE — Firmware ESP32 operacional.cpp
 * Target: ESP32 CHOPP Self-Service
 * Compatibilidade: Android 8+ (API 26+), Android 12+ permissões, Android 14 FGS
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * MÁQUINA DE ESTADOS
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   DISCONNECTED ──scan──► SCANNING ──mac_found──► CONNECTING ──gatt──► CONNECTED
 *        ▲                                                                    │
 *        │                                                              discoverServices
 *        │                                                                    │
 *        │                                                          habilitarNotificacoes
 *        │                                                                    │
 *        └──────────────────── reconexão ◄──────────────────────────── READY ◄┘
 *                                                                         │
 *                                                                    ERROR (fatal)
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
 *   AUTENTICAÇÃO: Via BLE Pairing/Bonding com PIN 259087
 *   NÃO existe: HMAC, SESSION_ID, CMD_ID, AUTH, SERVE, STOP, STATUS, PING,
 *               ACK, DONE, AUTH:OK/FAIL, PONG, READY/READY_OK
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
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import android.util.Base64;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class BluetoothServiceIndustrial extends Service {

    // ═══════════════════════════════════════════════════════════════════════════
    // TAG e identificação
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String TAG = "BLE_INDUSTRIAL";

    // FIX: Singleton para evitar múltiplas instâncias
    private static BluetoothServiceIndustrial sInstance = null;

    // ═══════════════════════════════════════════════════════════════════════════
    // Máquina de estados
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Estados possíveis do serviço BLE industrial.
     * Toda transição é feita exclusivamente via {@link #transitionTo(State)}.
     */
    public enum State {
        /** Sem conexão ativa. */
        DISCONNECTED,
        /** Scan BLE ativo procurando dispositivos CHOPP_*. */
        SCANNING,
        /** connectGatt() foi chamado; aguardando STATE_CONNECTED. */
        CONNECTING,
        /** GATT conectado; discoverServices em andamento ou MTU sendo negociado. */
        CONNECTED,
        /** Notificações habilitadas. Pronto para receber comandos. */
        READY,
        /** Erro fatal. Requer intervenção manual. */
        ERROR
    }

    /** Estado atual — acesso apenas via {@link #transitionTo(State)} e {@link #getState()}. */
    private volatile State mState = State.DISCONNECTED;

    /**
     * Transiciona para um novo estado com log detalhado.
     */
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
            case SCANNING:
                break;
            case CONNECTING:
                break;
            case CONNECTED:
                break;
            case READY:
                mReconnectAttempts = 0;
                mReconnectDelay    = BACKOFF_DELAYS[0];
                mTotalFailures     = 0;
                mReadyTimestamp = System.currentTimeMillis();
                Log.i(TAG, "[STATE] READY [timestamp=" + mReadyTimestamp + "]");
                Log.i(TAG, "[FLOW] Aguardando pagamento");
                broadcastWriteReady();
                mMainHandler.post(this::drainCommandQueue);
                break;
            case ERROR:
                pararReconexao();
                Log.e(TAG, "[INDUSTRIAL] Estado ERROR — verifique bond e firmware ESP32");
                break;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constantes de protocolo
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prefixo do nome BLE do ESP32. Apenas dispositivos com este prefixo são aceitos. */
    public static final String BLE_NAME_PREFIX = "CHOPP_";

    // ═══════════════════════════════════════════════════════════════════════════
    // UUIDs NUS (Nordic UART Service) — Firmware ESP32 operaBLE.cpp
    // ═══════════════════════════════════════════════════════════════════════════

    private static final UUID NUS_SERVICE_UUID           = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    /** RX do ESP32 — Android escreve aqui para enviar dados ao ESP32. */
    private static final UUID NUS_RX_CHARACTERISTIC_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    /** TX do ESP32 — Android recebe notificações aqui. */
    private static final UUID NUS_TX_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    /** Client Characteristic Configuration Descriptor — habilita notificações. */
    private static final UUID CCCD_UUID                  = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ═══════════════════════════════════════════════════════════════════════════
    // Ações de Broadcast (compatíveis com BluetoothService legado)
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
    // Códigos de status GATT (anti-bug Android)
    // ═══════════════════════════════════════════════════════════════════════════

    private static final int STATUS_CONN_TIMEOUT     = 0x08;  // 8
    private static final int STATUS_CONN_TERMINATE   = 0x13;  // 19
    private static final int STATUS_CONN_FAIL        = 0x3E;  // 62
    private static final int STATUS_CONN_257         = 257;
    private static final int STATUS_GATT_ERROR       = 133;
    private static final int STATUS_AUTH_FAIL        = 0x89;  // 137

    // ═══════════════════════════════════════════════════════════════════════════
    // Backoff exponencial de reconexão
    // ═══════════════════════════════════════════════════════════════════════════

    private static final long[] BACKOFF_DELAYS = { 2_000L, 4_000L, 8_000L, 15_000L };
    private static final int MAX_RECONNECT_BEFORE_RESET = 3;
    private static final int MAX_FAILURES_BEFORE_MAC_RESET = 10;
    private int mTotalFailures = 0;

    private long mReconnectDelay    = BACKOFF_DELAYS[0];
    private int  mReconnectAttempts = 0;
    private int  mBackoffIndex      = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Delay inteligente antes de conectar (800–1200ms)
    // ═══════════════════════════════════════════════════════════════════════════

    private static final long CONNECT_DELAY_MIN_MS = 800L;
    private static final long CONNECT_DELAY_MAX_MS = 1_200L;

    private long randomConnectDelay() {
        return CONNECT_DELAY_MIN_MS
                + (long)(Math.random() * (CONNECT_DELAY_MAX_MS - CONNECT_DELAY_MIN_MS));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GUARD-BAND — Sincronização após READY
    // ═══════════════════════════════════════════════════════════════════════════

    private static final long GUARD_BAND_MS = 900L;
    private volatile long mReadyTimestamp = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Timeouts gerais
    // ═══════════════════════════════════════════════════════════════════════════

    private static final long BOND_TIMEOUT_MS = 15_000L;
    private static final long SCAN_TIMEOUT_MS = 15_000L;
    private static final long SCAN_RETRY_DELAY_MS = 5_000L;
    private static final long CONNECT_TIMEOUT_MS = 8_000L;

    // ═══════════════════════════════════════════════════════════════════════════
    // Campos internos — BLE
    // ═══════════════════════════════════════════════════════════════════════════

    private BluetoothAdapter            mBluetoothAdapter;
    private BluetoothLeScanner          mBleScanner;
    private BluetoothGatt               mBluetoothGatt;
    private BluetoothGattCharacteristic mWriteCharacteristic;
    private BluetoothGattCharacteristic mNotifyCharacteristic;

    private String  mTargetMac;
    private boolean mAutoReconnect = true;
    private boolean mScanning      = false;

    // ═══════════════════════════════════════════════════════════════════════════
    // Fila de comandos (apenas 1 write por vez)
    // ═══════════════════════════════════════════════════════════════════════════

    private final Queue<String> mCommandQueue = new ArrayDeque<>();
    private final AtomicBoolean mWriteBusy = new AtomicBoolean(false);

    // ═══════════════════════════════════════════════════════════════════════════
    // Handlers e Runnables
    // ═══════════════════════════════════════════════════════════════════════════

    private final Handler mMainHandler         = new Handler(Looper.getMainLooper());
    private Runnable      mReconnectRunnable   = null;
    private Runnable      mScanStopRunnable    = null;
    private Runnable      mBondTimeoutRunnable = null;
    private Runnable      mConnectTimeoutRunnable = null;

    // ═══════════════════════════════════════════════════════════════════════════
    // MACs em validação
    // ═══════════════════════════════════════════════════════════════════════════

    private final java.util.Set<String> mMacsValidando = new java.util.HashSet<>();

    // ═══════════════════════════════════════════════════════════════════════════
    // Binder
    // ═══════════════════════════════════════════════════════════════════════════

    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        public BluetoothServiceIndustrial getService() {
            return BluetoothServiceIndustrial.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Notificação Foreground
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String NOTIF_CHANNEL_ID = "ble_industrial_channel";
    private static final int    NOTIF_ID         = 2001;

    // ═══════════════════════════════════════════════════════════════════════════
    // Ciclo de vida do Service
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            if (sInstance != null) {
                Log.w(TAG, "[INDUSTRIAL] Serviço BLE já está rodando! Abortando onCreate duplicado.");
                stopSelf();
                return;
            }

            sInstance = this;
            Log.i(TAG, "[SERVICE] SERVICE CREATED - BluetoothServiceIndustrial v4.0 NUS SINGLETON iniciado");

            criarNotificacaoForeground();

            BluetoothManager mgr = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = mgr.getAdapter();
            mBleScanner = mBluetoothAdapter.getBluetoothLeScanner();

            // Carrega MAC salvo de sessão anterior
            mTargetMac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                    .getString("esp32_mac", null);
            Log.i(TAG, "[INDUSTRIAL] MAC salvo: " + (mTargetMac != null ? mTargetMac : "nenhum"));

            // Registra receivers de pareamento e bond
            IntentFilter pf = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
            pf.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
            IntentFilter bf = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mPairingReceiver,   pf, Context.RECEIVER_EXPORTED);
                registerReceiver(mBondStateReceiver, bf, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(mPairingReceiver,   pf);
                registerReceiver(mBondStateReceiver, bf);
            }

            Log.i(TAG, "[INDUSTRIAL] Service iniciado. Iniciando conexão BLE...");
            iniciarConexao();
        } catch (Exception e) {
            Log.e(TAG, "[SERVICE] CRASH no onCreate do Service BLE!");
            Log.e(TAG, "[SERVICE] Exception: " + e.getClass().getName() + " - " + e.getMessage());
            Log.e(TAG, "[SERVICE] StackTrace:", e);
            sInstance = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "[SERVICE] SERVICE DESTROYED - limpando singleton");
        sInstance = null;
        mAutoReconnect = false;
        pararReconexao();
        pararScan();
        cancelarBondTimeout();
        try { unregisterReceiver(mPairingReceiver);   } catch (Exception ignored) {}
        try { unregisterReceiver(mBondStateReceiver); } catch (Exception ignored) {}
        if (mBluetoothGatt != null) {
            mBluetoothGatt.disconnect();
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
        transitionTo(State.DISCONNECTED);
        super.onDestroy();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Singleton methods
    // ═══════════════════════════════════════════════════════════════════════════

    public static BluetoothServiceIndustrial getInstance() {
        return sInstance;
    }

    public static boolean isRunning() {
        return sInstance != null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Ponto de entrada: iniciar conexão
    // ═══════════════════════════════════════════════════════════════════════════

    private void iniciarConexao() {
        if (!mAutoReconnect) {
            Log.d(TAG, "[INDUSTRIAL] autoReconnect=false — conexão não iniciada");
            return;
        }
        if (!mBluetoothAdapter.isEnabled()) {
            Log.w(TAG, "[INDUSTRIAL] Bluetooth desativado — aguardando ativação");
            return;
        }
        if (mTargetMac != null) {
            Log.i(TAG, "[INDUSTRIAL] MAC conhecido: " + mTargetMac + " — reconectando diretamente");
            agendarConexaoDireta(mTargetMac);
        } else {
            Log.i(TAG, "[INDUSTRIAL] Sem MAC — iniciando scan BLE por CHOPP_*");
            iniciarScanComRetry();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Proteção contra dupla conexão
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean podeConectar() {
        State s = mState;
        if (s == State.CONNECTING || s == State.CONNECTED || s == State.READY) {
            Log.w(TAG, "[INDUSTRIAL] podeConectar() → BLOQUEADO (estado=" + s.name() + ")");
            return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Verificação de permissão BLUETOOTH_CONNECT (Android 12+)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifica se a permissão BLUETOOTH_CONNECT foi concedida (Android 12+).
     * No Android 11 e abaixo, a permissão não existe e retorna true.
     *
     * CORREÇÃO CRÍTICA: No Android 12+, TODAS as operações GATT exigem
     * BLUETOOTH_CONNECT: connectGatt(), getBondState(), createBond(),
     * setPin(), setPairingConfirmation(), getConnectedDevices(), etc.
     * Sem essa verificação, o sistema lança SecurityException fatal.
     */
    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            boolean granted = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Log.e(TAG, "[PERM] BLUETOOTH_CONNECT NÃO concedida — operação GATT bloqueada");
            }
            return granted;
        }
        return true; // Android 11 e abaixo: permissão não existe
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Delay inteligente antes de conectar
    // ═══════════════════════════════════════════════════════════════════════════

    private void agendarConexaoDireta(String mac) {
        long delay = randomConnectDelay();
        Log.i(TAG, "[INDUSTRIAL] Agendando conexão em " + delay + "ms → " + mac);
        mMainHandler.postDelayed(() -> {
            if (!podeConectar()) return;
            // CORREÇÃO CRÍTICA: verificar BLUETOOTH_CONNECT antes de getRemoteDevice/connectGatt
            if (!hasConnectPermission()) {
                Log.w(TAG, "[INDUSTRIAL] BLUETOOTH_CONNECT não concedida — agendando retry em 5s");
                mMainHandler.postDelayed(() -> agendarConexaoDireta(mac), 5_000L);
                return;
            }
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mac);
            conectarGatt(device);
        }, delay);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Conexão BLE (autoConnect=false, TRANSPORT_LE)
    // ═══════════════════════════════════════════════════════════════════════════

    private void conectarGatt(BluetoothDevice device) {
        if (!podeConectar()) return;

        // CORREÇÃO CRÍTICA: BLUETOOTH_CONNECT é obrigatório para connectGatt() e getBondState()
        if (!hasConnectPermission()) {
            Log.w(TAG, "[GATT] BLUETOOTH_CONNECT não concedida — conectarGatt() abortado");
            return;
        }

        transitionTo(State.CONNECTING);
        iniciarTimeoutConexao(device);
        Log.i(TAG, "[GATT] conectarGatt() → " + device.getAddress()
                + " | bond=" + bondStateName(device.getBondState())
                + " | tentativa=" + (mReconnectAttempts + 1));

        if (mBluetoothGatt != null
                && mBluetoothGatt.getDevice().getAddress()
                        .equalsIgnoreCase(device.getAddress())) {
            Log.i(TAG, "[GATT] GATT existente → gatt.connect() (reconexão rápida)");
            boolean ok = mBluetoothGatt.connect();
            Log.i(TAG, "[GATT] gatt.connect() → " + (ok ? "OK" : "FALHOU — criando novo GATT"));
            if (ok) return;
            closeGatt();
        } else if (mBluetoothGatt != null) {
            Log.i(TAG, "[GATT] GATT de outro MAC — fechando antes de criar novo");
            closeGatt();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Log.i(TAG, "[GATT] connectGatt(autoConnect=false, TRANSPORT_LE) → " + device.getAddress());
            mBluetoothGatt = device.connectGatt(
                    this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            Log.i(TAG, "[GATT] connectGatt(autoConnect=false) → " + device.getAddress());
            mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        }

        if (mBluetoothGatt == null) {
            cancelarTimeoutConexao();
            Log.e(TAG, "[GATT] connectGatt() retornou null — reagendando reconexão");
            transitionTo(State.DISCONNECTED);
            reconectarComBackoff();
        }
    }

    private void iniciarTimeoutConexao(BluetoothDevice device) {
        cancelarTimeoutConexao();
        final String mac = device != null ? device.getAddress() : "desconhecido";
        mConnectTimeoutRunnable = () -> {
            if (mState == State.CONNECTING) {
                Log.e(TAG, "[BLE] Timeout conexão (" + CONNECT_TIMEOUT_MS + "ms) → reconectando | mac=" + mac);
                closeGatt();
                transitionTo(State.DISCONNECTED);
                reconectarComBackoff();
            }
        };
        mMainHandler.postDelayed(mConnectTimeoutRunnable, CONNECT_TIMEOUT_MS);
    }

    private void cancelarTimeoutConexao() {
        if (mConnectTimeoutRunnable != null) {
            mMainHandler.removeCallbacks(mConnectTimeoutRunnable);
            mConnectTimeoutRunnable = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Reconexão industrial com backoff exponencial
    // ═══════════════════════════════════════════════════════════════════════════

    private void reconectarComBackoff() {
        Log.i(TAG, "[RECONNECT] Tentativa de reconexão automática iniciada");
        if (!mAutoReconnect) return;
        if (mReconnectRunnable != null) {
            Log.d(TAG, "[INDUSTRIAL] Reconexão já agendada — ignorando duplicata");
            return;
        }

        mReconnectAttempts++;
        mReconnectDelay = BACKOFF_DELAYS[Math.min(mBackoffIndex, BACKOFF_DELAYS.length - 1)];
        mBackoffIndex   = Math.min(mBackoffIndex + 1, BACKOFF_DELAYS.length - 1);

        Log.i(TAG, "[INDUSTRIAL] Reconexão #" + mReconnectAttempts
                + " em " + mReconnectDelay + "ms"
                + " (backoff index=" + mBackoffIndex + ")");

        if (mReconnectAttempts >= MAX_RECONNECT_BEFORE_RESET) {
            Log.w(TAG, "[INDUSTRIAL] " + mReconnectAttempts
                    + " reconexões consecutivas — forçando closeGatt() e reiniciando");
            closeGatt();
            mReconnectAttempts = 0;
            mBackoffIndex      = 0;
            mReconnectDelay    = BACKOFF_DELAYS[0];
        }

        mTotalFailures++;
        if (mTotalFailures >= MAX_FAILURES_BEFORE_MAC_RESET && mTargetMac != null) {
            Log.w(TAG, "[MAC] " + mTotalFailures + " falhas consecutivas ao MAC "
                    + mTargetMac + " — limpando MAC e iniciando scan para encontrar novo ESP32");
            mTotalFailures = 0;
            mTargetMac = null;
            getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                    .edit().remove("esp32_mac").apply();
            closeGatt();
            mReconnectRunnable = () -> {
                mReconnectRunnable = null;
                if (!mAutoReconnect) return;
                Log.i(TAG, "[MAC] Iniciando scan após reset de MAC");
                iniciarScanComRetry();
            };
            mMainHandler.postDelayed(mReconnectRunnable, 2_000L);
            return;
        }

        mReconnectRunnable = () -> {
            mReconnectRunnable = null;
            if (!mAutoReconnect) return;
            if (mTargetMac != null) {
                Log.i(TAG, "[INDUSTRIAL] Reconectando → " + mTargetMac);
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mTargetMac);
                conectarGatt(device);
            } else {
                Log.i(TAG, "[INDUSTRIAL] Sem MAC — reiniciando scan");
                iniciarScanComRetry();
            }
        };
        mMainHandler.postDelayed(mReconnectRunnable, mReconnectDelay);
    }

    private void pararReconexao() {
        if (mReconnectRunnable != null) {
            mMainHandler.removeCallbacks(mReconnectRunnable);
            mReconnectRunnable = null;
            Log.d(TAG, "[INDUSTRIAL] Reconexão cancelada");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Scan BLE
    // ═══════════════════════════════════════════════════════════════════════════

    private void iniciarScanComRetry() {
        if (!mAutoReconnect) return;
        if (mTargetMac != null) {
            Log.i(TAG, "[SCAN] MAC disponível durante retry → conectando diretamente");
            agendarConexaoDireta(mTargetMac);
            return;
        }
        iniciarScan();
    }

    private void iniciarScan() {
        if (mScanning || mBleScanner == null) return;
        if (!mBluetoothAdapter.isEnabled()) {
            Log.w(TAG, "[SCAN] Bluetooth desativado — scan cancelado");
            return;
        }

        // CORREÇÃO CRÍTICA Android 12+: verificar BLUETOOTH_SCAN antes de startScan().
        // Sem esta verificação, o serviço lança SecurityException se a permissão
        // ainda não foi concedida (ex: primeira abertura do app).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "[SCAN] BLUETOOTH_SCAN não concedido — scan adiado. "
                        + "Aguardando permissão do usuário em Imei.java");
                // Agenda retry: quando a permissão for concedida, Imei.java inicia
                // o serviço novamente via startBleServiceIfPermitted(). Mas caso
                // o serviço já esteja rodando, tentamos novamente em 5s.
                mMainHandler.postDelayed(() -> {
                    if (mAutoReconnect) iniciarConexao();
                }, 5_000L);
                return;
            }
        }

        synchronized (mMacsValidando) { mMacsValidando.clear(); }
        mScanning = true;
        transitionTo(State.SCANNING);
        Log.i(TAG, "[SCAN] Iniciando scan BLE por CHOPP_*");
        broadcastConnectionStatus("scanning");
        mBleScanner.startScan(mScanCallback);

        mScanStopRunnable = () -> {
            if (mScanning) {
                Log.w(TAG, "[SCAN] Timeout " + SCAN_TIMEOUT_MS / 1000
                        + "s — nenhum CHOPP_ encontrado. Retry em "
                        + SCAN_RETRY_DELAY_MS / 1000 + "s");
                pararScan();
                broadcastConnectionStatus("scan_timeout");
                if (mAutoReconnect && mTargetMac == null) {
                    mMainHandler.postDelayed(() -> {
                        if (mAutoReconnect && mTargetMac == null) {
                            iniciarScanComRetry();
                        } else if (mAutoReconnect && mTargetMac != null) {
                            agendarConexaoDireta(mTargetMac);
                        }
                    }, SCAN_RETRY_DELAY_MS);
                }
            }
        };
        mMainHandler.postDelayed(mScanStopRunnable, SCAN_TIMEOUT_MS);
    }

    private void pararScan() {
        if (!mScanning) return;
        mScanning = false;
        if (mScanStopRunnable != null) {
            mMainHandler.removeCallbacks(mScanStopRunnable);
            mScanStopRunnable = null;
        }
        if (mBleScanner != null) {
            try { mBleScanner.stopScan(mScanCallback); } catch (Exception e) {
                Log.w(TAG, "[SCAN] stopScan() erro: " + e.getMessage());
            }
        }
        Log.i(TAG, "[SCAN] Scan parado");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ScanCallback — filtra CHOPP_* e valida MAC na API
    // ═══════════════════════════════════════════════════════════════════════════

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            if (name == null || name.isEmpty()) return;
            if (!name.startsWith(BLE_NAME_PREFIX)) return;

            String mac = device.getAddress();
            Log.i(TAG, "[SCAN] Dispositivo encontrado: " + name + " | " + mac);

            synchronized (mMacsValidando) {
                if (mMacsValidando.contains(mac)) return;
                mMacsValidando.add(mac);
            }
            validarMacNaApi(device);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "[SCAN] Falhou com código: " + errorCode);
            mScanning = false;
            transitionTo(State.DISCONNECTED);
        }
    };

    /**
     * Valida o MAC BLE na API verify_tap_mac.php.
     */
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
                    String responseBody = response.body() != null
                            ? response.body().string() : "";
                    Log.d(TAG, "[SCAN] verify_tap_mac: HTTP " + response.code()
                            + " | " + responseBody);
                    if (response.isSuccessful() && responseBody.contains("\"valid\":true")) {
                        Log.i(TAG, "[SCAN] MAC válido: " + mac + " — conectando");
                        mMainHandler.post(() -> {
                            pararScan();
                            salvarMac(mac);
                            iniciarBondEConectar(device);
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
    // Bond (pareamento) — PIN 259087
    // ═══════════════════════════════════════════════════════════════════════════

    private void iniciarBondEConectar(BluetoothDevice device) {
        // CORREÇÃO CRÍTICA: getBondState() e createBond() exigem BLUETOOTH_CONNECT no Android 12+
        if (!hasConnectPermission()) {
            Log.w(TAG, "[BOND] BLUETOOTH_CONNECT não concedida — pulando bond, conectando direto");
            agendarConexaoDireta(device.getAddress());
            return;
        }
        int bondState = device.getBondState();
        Log.i(TAG, "[BOND] iniciarBondEConectar() | bond=" + bondStateName(bondState)
                + " | mac=" + device.getAddress());
        switch (bondState) {
            case BluetoothDevice.BOND_BONDED:
                Log.i(TAG, "[BOND] Bond já existe → agendando conexão GATT");
                agendarConexaoDireta(device.getAddress());
                break;
            case BluetoothDevice.BOND_BONDING:
                Log.i(TAG, "[BOND] Bond em andamento → aguardando BOND_STATE_CHANGED");
                iniciarTimeoutBond(device);
                break;
            default: // BOND_NONE
                Log.i(TAG, "[BOND] BOND_NONE → createBond() com PIN 259087");
                boolean ok = device.createBond();
                Log.i(TAG, "[BOND] createBond() → " + (ok ? "INICIADO" : "FALHOU"));
                iniciarTimeoutBond(device);
                break;
        }
    }

    private void iniciarTimeoutBond(BluetoothDevice device) {
        cancelarBondTimeout();
        mBondTimeoutRunnable = () -> {
            Log.e(TAG, "[BOND] Timeout " + BOND_TIMEOUT_MS / 1000
                    + "s — forçando connectGatt() sem bond");
            agendarConexaoDireta(device.getAddress());
        };
        mMainHandler.postDelayed(mBondTimeoutRunnable, BOND_TIMEOUT_MS);
    }

    private void cancelarBondTimeout() {
        if (mBondTimeoutRunnable != null) {
            mMainHandler.removeCallbacks(mBondTimeoutRunnable);
            mBondTimeoutRunnable = null;
        }
    }

    private boolean isBleStackPronto() {
        return mBluetoothGatt != null
                && mWriteCharacteristic != null
                && mNotifyCharacteristic != null;
    }

    private void logBleHandles(String origem) {
        Log.d(TAG, "[BLE] " + origem + " | gatt=" + mBluetoothGatt);
        Log.d(TAG, "[BLE] " + origem + " | writeCharacteristic=" + mWriteCharacteristic);
        Log.d(TAG, "[BLE] " + origem + " | notifyCharacteristic=" + mNotifyCharacteristic);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Receiver: ACTION_PAIRING_REQUEST — injeta PIN 259087 automaticamente
    // ═══════════════════════════════════════════════════════════════════════════

    private final BroadcastReceiver mPairingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) return;
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
            Log.i(TAG, "[PAIRING] ACTION_PAIRING_REQUEST | device="
                    + (device != null ? device.getAddress() : "null")
                    + " | variant=" + variant);
            if (device == null) return;

            // CORREÇÃO CRÍTICA: setPin() e setPairingConfirmation() exigem BLUETOOTH_CONNECT no Android 12+
            if (!hasConnectPermission()) {
                Log.e(TAG, "[PAIRING] BLUETOOTH_CONNECT não concedida — não é possível confirmar pareamento");
                return;
            }

            // PIN do firmware ESP32: 259087
            byte[] pinBytes = "259087".getBytes(StandardCharsets.UTF_8);

            switch (variant) {
                case BluetoothDevice.PAIRING_VARIANT_PIN: {
                    boolean ok = device.setPin(pinBytes);
                    Log.i(TAG, "[PAIRING] PIN_VARIANT → setPin(259087) -> "
                            + (ok ? "ACEITO" : "REJEITADO"));
                    abortBroadcast();
                    break;
                }
                case BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION:
                    device.setPairingConfirmation(true);
                    Log.i(TAG, "[PAIRING] AUTO-CONFIRM -> setPairingConfirmation(true)");
                    abortBroadcast();
                    break;
                case 3: // PAIRING_VARIANT_PASSKEY (NimBLE)
                    try {
                        boolean okPasskey = device.setPin(pinBytes);
                        Log.i(TAG, "[PAIRING] Variante 3 (PASSKEY) → setPin(259087) -> "
                                + (okPasskey ? "ACEITO" : "REJEITADO"));
                        abortBroadcast();
                    } catch (Exception ex) {
                        Log.e(TAG, "[PAIRING] Erro ao confirmar pareamento variante 3: " + ex.getMessage());
                    }
                    break;
                default:
                    Log.w(TAG, "[PAIRING] Variante desconhecida: " + variant
                            + " - tentando setPin como fallback");
                    try {
                        device.setPin(pinBytes);
                        abortBroadcast();
                    } catch (Exception ignored) {}
                    break;
            }
        }
    };

    // ═══════════════════════════════════════════════════════════════════════════
    // Receiver: ACTION_BOND_STATE_CHANGED
    // ═══════════════════════════════════════════════════════════════════════════

    private final BroadcastReceiver mBondStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;
            BluetoothDevice device   = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int newState  = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
            int prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
            Log.i(TAG, "[BOND] STATE_CHANGED: " + bondStateName(prevState)
                    + " → " + bondStateName(newState)
                    + " | mac=" + (device != null ? device.getAddress() : "null"));
            if (device == null) return;

            if (newState == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "[BOND] BOND_BONDED! Salvando MAC e agendando conexão GATT...");
                cancelarBondTimeout();
                salvarMac(device.getAddress());
                agendarConexaoDireta(device.getAddress());

            } else if (newState == BluetoothDevice.BOND_NONE
                    && prevState == BluetoothDevice.BOND_BONDING) {
                Log.e(TAG, "[BOND] Pareamento FALHOU. Verifique PIN 259087 no firmware ESP32.");
                cancelarBondTimeout();
                broadcastConnectionStatus("bond_failed");
                mMainHandler.postDelayed(() -> iniciarConexao(), 3_000L);
            }
        }
    };

    // ═══════════════════════════════════════════════════════════════════════════
    // GATT Callback — coração do serviço BLE
    // ═══════════════════════════════════════════════════════════════════════════

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newConnectionState) {
            String mac = gatt.getDevice().getAddress();
            Log.i(TAG, "[GATT] onConnectionStateChange | status=" + status
                    + " | newState=" + connectionStateName(newConnectionState)
                    + " | mac=" + mac
                    + " | bond=" + bondStateName(gatt.getDevice().getBondState()));

            if (newConnectionState == BluetoothProfile.STATE_CONNECTED) {
                handleGattConnected(gatt, status);
            } else if (newConnectionState == BluetoothProfile.STATE_DISCONNECTED) {
                handleGattDisconnected(gatt, status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "[GATT] onMtuChanged | mtu=" + mtu + " | status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "[GATT] MTU negociado: " + mtu + " bytes — iniciando discoverServices()");
            } else {
                Log.w(TAG, "[GATT] MTU falhou (status=" + status + ") — prosseguindo com discoverServices()");
            }
            boolean ok = gatt.discoverServices();
            Log.i(TAG, "[GATT] discoverServices() → " + (ok ? "INICIADO" : "FALHOU"));
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.i(TAG, "[GATT] onServicesDiscovered | status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[GATT] discoverServices falhou (status=" + status + ") — reconectando");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }

            // Localiza serviço NUS do ESP32
            BluetoothGattService nusService = gatt.getService(NUS_SERVICE_UUID);
            if (nusService == null) {
                Log.e(TAG, "[BLE] NUS SERVICE NOT FOUND (6E400001)");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }
            Log.i(TAG, "[BLE] NUS SERVICE FOUND");

            BluetoothGattCharacteristic rxChar =
                    nusService.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
            BluetoothGattCharacteristic txChar =
                    nusService.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID);

            if (rxChar == null) {
                Log.e(TAG, "[BLE] CRITICAL: NUS RX NOT FOUND (6E400002)");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }
            if (txChar == null) {
                Log.e(TAG, "[BLE] CRITICAL: NUS TX NOT FOUND (6E400003)");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }

            mBluetoothGatt = gatt;
            mWriteCharacteristic = rxChar;
            mNotifyCharacteristic = txChar;
            Log.i(TAG, "[BLE] NUS RX READY (write)");
            Log.i(TAG, "[BLE] NUS TX READY (notify)");
            Log.i(TAG, "[BLE] BLE READY: NUS SERVICE + RX + TX OK");
            logBleHandles("onServicesDiscovered");

            // Habilita notificações no TX
            habilitarNotificacoes(gatt, txChar);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status) {
            Log.i(TAG, "[GATT] onDescriptorWrite | status=" + status
                    + " | descriptor=" + descriptor.getUuid());
            logBleHandles("onDescriptorWrite");
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "[BLE] NOTIFY ENABLED — transicionando para READY");
                // Novo protocolo: sem AUTH — após notificações habilitadas, está READY
                mMainHandler.post(() -> {
                    if (isBleStackPronto()) {
                        transitionTo(State.READY);
                        broadcastConnectionStatus("ready");
                    } else {
                        Log.e(TAG, "[BLE] READY BLOQUEADO — handles BLE ausentes");
                        logBleHandles("onDescriptorWrite_ready");
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
        Log.i(TAG, "[GATT] CONECTADO → " + gatt.getDevice().getAddress()
                + " | status=" + status);
        cancelarTimeoutConexao();
        mBluetoothGatt = gatt;
        mWriteCharacteristic = null;
        mNotifyCharacteristic = null;
        logBleHandles("handleGattConnected");
        pararReconexao();
        transitionTo(State.CONNECTED);
        broadcastConnectionStatus("connected");

        // BALANCED aumenta o supervision timeout para ~20s no Android
        boolean priOk = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
        Log.i(TAG, "[GATT] requestConnectionPriority(BALANCED) → " + (priOk ? "OK" : "FALHOU"));

        boolean mtuOk = gatt.requestMtu(512);
        Log.i(TAG, "[GATT] requestMtu(512) → " + (mtuOk ? "AGUARDANDO onMtuChanged" : "FALHOU — discoverServices direto"));
        if (!mtuOk) {
            mMainHandler.post(() -> {
                boolean ok = gatt.discoverServices();
                Log.i(TAG, "[GATT] discoverServices() (fallback) → " + (ok ? "INICIADO" : "FALHOU"));
            });
        }
    }

    private void handleGattDisconnected(BluetoothGatt gatt, int status) {
        Log.i(TAG, "[GATT] DESCONECTADO | status=" + status
                + " | mac=" + gatt.getDevice().getAddress()
                + " | state_anterior=" + mState.name());

        cancelarTimeoutConexao();
        transitionTo(State.DISCONNECTED);
        broadcastConnectionStatus("disconnected:" + status);

        if (!mAutoReconnect) {
            Log.i(TAG, "[INDUSTRIAL] autoReconnect=false — não reconectando");
            return;
        }

        if (status == STATUS_GATT_ERROR) {
            Log.w(TAG, "[GATT] status=133 (GATT_ERROR) → fechando GATT e recriando");
            closeGatt();
            mMainHandler.postDelayed(() -> reconectarComBackoff(), 1_000L);

        } else if (status == STATUS_AUTH_FAIL) {
            Log.e(TAG, "[GATT] status=0x89 (AUTH_FAIL) → removendo bond e recriando");
            BluetoothDevice device = gatt.getDevice();
            closeGatt();
            removeBond(device);
            mMainHandler.postDelayed(() -> iniciarBondEConectar(device), 2_000L);

        } else if (status == STATUS_CONN_TIMEOUT
                || status == STATUS_CONN_257
                || status == STATUS_CONN_TERMINATE
                || status == STATUS_CONN_FAIL) {
            Log.i(TAG, "[GATT] status=" + status + " → NÃO fechando GATT — reconectando");
            reconectarComBackoff();

        } else {
            Log.w(TAG, "[GATT] status=" + status + " (desconhecido) → fechando GATT");
            closeGatt();
            reconectarComBackoff();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Habilitar notificações (CCCD)
    // ═══════════════════════════════════════════════════════════════════════════

    private void habilitarNotificacoes(BluetoothGatt gatt,
                                        BluetoothGattCharacteristic txChar) {
        mNotifyCharacteristic = txChar;
        logBleHandles("habilitarNotificacoes");
        boolean ok = gatt.setCharacteristicNotification(txChar, true);
        Log.i(TAG, "[GATT] setCharacteristicNotification(TX, true) → " + (ok ? "OK" : "FALHOU"));

        BluetoothGattDescriptor cccd = txChar.getDescriptor(CCCD_UUID);
        if (cccd == null) {
            Log.e(TAG, "[GATT] CCCD descriptor não encontrado — indo para READY sem notificações");
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
        Log.i(TAG, "[GATT] writeDescriptor(CCCD) → " + (writeOk ? "OK" : "FALHOU"));
        if (!writeOk) {
            // Fallback: ir para READY mesmo sem CCCD confirmado
            mMainHandler.post(() -> {
                if (isBleStackPronto()) {
                    transitionTo(State.READY);
                    broadcastConnectionStatus("ready");
                }
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Processamento de respostas BLE do ESP32 (novo protocolo)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Processa todas as mensagens recebidas do ESP32 via notificação BLE.
     *
     * Protocolo do firmware operacional.cpp:
     *   OK       — Comando aceito e enfileirado
     *   ERRO     — Comando com erro
     *   VP:xxx   — Volume parcial durante liberação (mL)
     *   QP:xxx   — Quantidade de pulsos ao final
     *   ML:xxx   — Volume final liberado (sinal de conclusão — válvula fechada)
     *   PL:xxx   — Resposta de leitura de pulsos/litro
     */
    private void processarRespostaBle(String data) {
        // ── OK — comando aceito ──────────────────────────────────────────────
        if ("OK".equalsIgnoreCase(data)) {
            Log.i(TAG, "[BLE] Comando aceito pelo ESP32 (OK)");
            broadcastData(data);
            return;
        }

        // ── ERRO — comando com erro ──────────────────────────────────────────
        if ("ERRO".equalsIgnoreCase(data)) {
            Log.e(TAG, "[BLE] ESP32 reportou ERRO");
            broadcastData(data);
            return;
        }

        // ── VP: — volume parcial durante liberação ───────────────────────────
        if (data.startsWith("VP:")) {
            Log.i(TAG, "[BLE] Volume parcial: " + data);
            broadcastData(data);
            return;
        }

        // ── QP: — quantidade de pulsos ao final ──────────────────────────────
        if (data.startsWith("QP:")) {
            Log.i(TAG, "[BLE] Pulsos: " + data);
            broadcastData(data);
            return;
        }

        // ── ML: — volume final liberado (conclusão) ──────────────────────────
        if (data.startsWith("ML:")) {
            Log.i(TAG, "[BLE] Volume final (conclusão): " + data);
            broadcastData(data);
            return;
        }

        // ── PL: — resposta de leitura de pulsos/litro ────────────────────────
        if (data.startsWith("PL:")) {
            Log.i(TAG, "[BLE] Pulsos/litro: " + data);
            broadcastData(data);
            return;
        }

        // ── Demais dados não reconhecidos ─────────────────────────────────────
        Log.i(TAG, "[BLE] Dado recebido (não reconhecido): \"" + data + "\"");
        broadcastData(data);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Fila de comandos (apenas 1 write por vez)
    // ═══════════════════════════════════════════════════════════════════════════

    private void enqueueCommand(String command) {
        mMainHandler.post(() -> {
            mCommandQueue.add(command);
            Log.d(TAG, "[QUEUE] Enfileirado: \"" + command
                    + "\" | fila=" + mCommandQueue.size());
            drainCommandQueue();
        });
    }

    private void drainCommandQueue() {
        if (mState != State.READY) {
            Log.e(TAG, "[QUEUE] Bloqueado — BLE não pronto (estado=" + mState.name()
                    + ", fila=" + mCommandQueue.size() + ")");
            return;
        }

        if (!isBleStackPronto()) {
            Log.e(TAG, "[QUEUE] Bloqueado — BLE não pronto (handles ausentes, fila="
                    + mCommandQueue.size() + ")");
            logBleHandles("drainCommandQueue");
            return;
        }
        if (mWriteBusy.get()) {
            Log.d(TAG, "[QUEUE] Write em andamento — aguardando onCharacteristicWrite");
            return;
        }
        String next = mCommandQueue.poll();
        if (next == null) return;

        Log.i(TAG, "[QUEUE] Enviando: \"" + next + "\" | restante=" + mCommandQueue.size());
        writeImediato(next);
    }

    /**
     * Escreve diretamente na característica NUS RX.
     */
    private void writeImediato(String data) {
        if (!isBleStackPronto()) {
            Log.e(TAG, "[WRITE] WRITE BLOQUEADO — BLE não inicializado: \"" + data + "\"");
            logBleHandles("writeImediato");
            return;
        }
        // CORREÇÃO CRÍTICA: writeCharacteristic() exige BLUETOOTH_CONNECT no Android 12+
        if (!hasConnectPermission()) {
            Log.e(TAG, "[WRITE] BLUETOOTH_CONNECT não concedida — write bloqueado: \"" + data + "\"");
            mWriteBusy.set(false);
            return;
        }
        mWriteBusy.set(true);
        mWriteCharacteristic.setValue(data.getBytes(StandardCharsets.UTF_8));
        mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        boolean ok = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
        Log.i(TAG, "[TX] " + data + " | ok=" + ok);
        if (!ok) {
            mWriteBusy.set(false);
            Log.e(TAG, "[WRITE] writeCharacteristic() falhou para: \"" + data + "\"");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utilitários GATT
    // ═══════════════════════════════════════════════════════════════════════════

    private void closeGatt() {
        cancelarTimeoutConexao();
        if (mBluetoothGatt != null) {
            Log.i(TAG, "[GATT] closeGatt() → " + mBluetoothGatt.getDevice().getAddress());
            mBluetoothGatt.close();
            mBluetoothGatt = null;
            mWriteCharacteristic = null;
            mNotifyCharacteristic = null;
        }
    }

    public static void removeBond(BluetoothDevice device) {
        if (device == null) return;
        try {
            Method m = device.getClass().getMethod("removeBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
            Log.i(TAG, "[BOND] removeBond() → " + device.getAddress());
        } catch (Exception e) {
            Log.e(TAG, "[BOND] removeBond() erro: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utilitários de log
    // ═══════════════════════════════════════════════════════════════════════════

    private static String bondStateName(int state) {
        switch (state) {
            case BluetoothDevice.BOND_NONE:    return "BOND_NONE";
            case BluetoothDevice.BOND_BONDING: return "BOND_BONDING";
            case BluetoothDevice.BOND_BONDED:  return "BOND_BONDED";
            default:                           return "UNKNOWN(" + state + ")";
        }
    }

    private static String connectionStateName(int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:    return "CONNECTED";
            case BluetoothProfile.STATE_CONNECTING:   return "CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTED: return "DISCONNECTED";
            case BluetoothProfile.STATE_DISCONNECTING:return "DISCONNECTING";
            default:                                  return "UNKNOWN(" + state + ")";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Persistência de MAC
    // ═══════════════════════════════════════════════════════════════════════════

    private void salvarMac(String mac) {
        if (mac == null) return;
        mTargetMac = mac;
        getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .edit().putString("esp32_mac", mac).apply();
        Log.i(TAG, "[INDUSTRIAL] MAC salvo: " + mac);
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
        Log.i(TAG, "[BROADCAST] *** ACTION_WRITE_READY *** — READY para comandos");
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(ACTION_WRITE_READY));
    }

    private void broadcastBleState(State state) {
        Intent i = new Intent(ACTION_BLE_STATE_CHANGED);
        i.putExtra(EXTRA_BLE_STATE, state.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JWT para validação de MAC na API
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

            Log.d(TAG, "[JWT] Token gerado com sucesso");
            return msg + "." + s;
        } catch (Exception e) {
            Log.e(TAG, "[JWT] Erro ao gerar token: " + e.getMessage());
            return "";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Notificação Foreground — Android 12+ / Android 14 FGS
    // ═══════════════════════════════════════════════════════════════════════════

    private void criarNotificacaoForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "Chopp BLE Industrial",
                    NotificationManager.IMPORTANCE_LOW);
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
        Log.i(TAG, "[INDUSTRIAL] Foreground service iniciado (notif_id=" + NOTIF_ID + ")");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API Pública
    // ═══════════════════════════════════════════════════════════════════════════

    public void connect(String mac) {
        if (mac == null || mac.isEmpty()) {
            Log.e(TAG, "[API] connect() — MAC inválido");
            return;
        }
        Log.i(TAG, "[API] connect(" + mac + ")");
        if (!mac.equalsIgnoreCase(mTargetMac)) {
            salvarMac(mac);
        }
        if (mState == State.CONNECTING || mState == State.CONNECTED || mState == State.READY) {
            Log.i(TAG, "[API] connect() — já conectado/conectando (estado=" + mState.name() + ")");
            return;
        }
        pararScan();
        pararReconexao();
        agendarConexaoDireta(mac);
    }

    public void disconnect() {
        Log.i(TAG, "[API] disconnect()");
        mAutoReconnect = false;
        pararReconexao();
        pararScan();
        if (mBluetoothGatt != null) {
            mBluetoothGatt.disconnect();
        }
        transitionTo(State.DISCONNECTED);
    }

    /**
     * Envia um comando ao ESP32 via fila de comandos.
     * Formato esperado: "$ML:100", "$PL:5880", "$TO:5000", "$LB:", etc.
     *
     * @param data string de comando
     * @return true se o comando foi enfileirado
     */
    public boolean write(String data) {
        if (data == null || data.isEmpty()) {
            Log.w(TAG, "[API] write() — dado vazio ignorado");
            return false;
        }
        if (mState != State.READY) {
            Log.e(TAG, "BLOCKED: BLE NOT READY");
            Log.e(TAG, "[API] write() bloqueado — BLE não pronto (estado=" + mState.name() + ")");
            return false;
        }
        if (!isBleStackPronto()) {
            Log.e(TAG, "BLOCKED: BLE NOT READY");
            Log.e(TAG, "[API] write() bloqueado — handles BLE ausentes");
            logBleHandles("write");
            return false;
        }
        if (mState == State.ERROR) {
            Log.e(TAG, "[API] write() — estado ERROR, comando descartado: \"" + data + "\"");
            return false;
        }
        Log.i(TAG, "[API] write(\"" + data + "\") | estado=" + mState.name());
        enqueueCommand(data);
        return true;
    }

    public boolean isReady() {
        return mState == State.READY;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GUARD-BAND
    // ═══════════════════════════════════════════════════════════════════════════

    public long getTimeSinceReady() {
        if (mReadyTimestamp == 0) {
            return -1;
        }
        return System.currentTimeMillis() - mReadyTimestamp;
    }

    public boolean isReadyWithGuardBand() {
        if (!isReady()) {
            return false;
        }
        long timeSinceReady = getTimeSinceReady();
        if (timeSinceReady < 0) {
            return false;
        }
        boolean guardBandExpired = timeSinceReady >= GUARD_BAND_MS;
        if (!guardBandExpired) {
            Log.w(TAG, "[GUARD-BAND] BLOQUEADO — " + (GUARD_BAND_MS - timeSinceReady)
                    + "ms faltando. Time since READY: " + timeSinceReady + "ms");
        }
        return guardBandExpired;
    }

    public long getReadyTimestamp() {
        return mReadyTimestamp;
    }

    public long getGuardBandMs() {
        return GUARD_BAND_MS;
    }

    public State getState() {
        return mState;
    }

    public String getTargetMac() {
        return mTargetMac;
    }

    public void resetMac() {
        Log.i(TAG, "[API] resetMac() — limpando MAC salvo e reiniciando scan");
        mTargetMac = null;
        getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .edit().remove("esp32_mac").apply();
        disconnect();
        mAutoReconnect = true;
        iniciarScanComRetry();
    }

    public void salvarMacExterno(String mac) {
        if (mac == null || mac.isEmpty()) return;
        if (mac.equalsIgnoreCase(mTargetMac)) {
            Log.d(TAG, "[MAC] salvarMacExterno: MAC já é o mesmo (" + mac + ") — sem ação");
            return;
        }
        Log.w(TAG, "[MAC] salvarMacExterno: atualizando MAC " + mTargetMac + " → " + mac);
        salvarMac(mac);
        if (mState == State.CONNECTING || mState == State.CONNECTED || mState == State.READY) {
            Log.w(TAG, "[MAC] Desconectando do MAC antigo para reconectar ao novo: " + mac);
            disconnect();
        }
        mAutoReconnect = true;
        agendarConexaoDireta(mac);
    }

    public int getQueueSize() {
        return mCommandQueue.size();
    }

    public void clearQueue() {
        int size = mCommandQueue.size();
        mCommandQueue.clear();
        mWriteBusy.set(false);
        Log.i(TAG, "[API] clearQueue() — " + size + " comandos descartados");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API de compatibilidade com BluetoothService (drop-in replacement)
    // ═══════════════════════════════════════════════════════════════════════════

    public enum BleState { DISCONNECTED, CONNECTED, READY }

    public BleState getBleState() {
        switch (mState) {
            case READY:
                return BleState.READY;
            case CONNECTED:
            case CONNECTING:
            case SCANNING:
                return BleState.CONNECTED;
            default:
                return BleState.DISCONNECTED;
        }
    }

    public boolean connected() {
        return mBluetoothGatt != null && mState != State.DISCONNECTED;
    }

    public void forceReady() {
        Log.i(TAG, "[COMPAT] forceReady() chamado — estado=" + mState.name());
        logBleHandles("forceReady");
        if (!isBleStackPronto()) {
            Log.e(TAG, "[COMPAT] forceReady BLOQUEADO — BLE não pronto");
            return;
        }
        Log.i(TAG, "[COMPAT] forceReady permitido — BLE pronto");
        if (mState != State.READY) {
            transitionTo(State.READY);
        }
    }

    public void scanLeDevice(boolean enable) {
        mAutoReconnect = true;
        if (!enable) {
            pararScan();
            return;
        }
        if (mTargetMac != null) {
            Log.i(TAG, "[COMPAT] scanLeDevice() — MAC salvo: " + mTargetMac + " → conectando");
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mTargetMac);
            iniciarBondEConectar(device);
        } else {
            Log.i(TAG, "[COMPAT] scanLeDevice() — sem MAC → scan por CHOPP_");
            iniciarScanComRetry();
        }
    }

    public void enableAutoReconnect() {
        mAutoReconnect = true;
        mReconnectAttempts = 0;
        Log.i(TAG, "[COMPAT] enableAutoReconnect()");
    }

    public BluetoothDevice getBoundDevice() {
        return mBluetoothGatt != null ? mBluetoothGatt.getDevice() : null;
    }

    /**
     * Retorna CommandQueueManager (legado) — null nesta implementação.
     */
    public CommandQueueManager getCommandQueue() {
        Log.w(TAG, "[COMPAT] getCommandQueue() chamado — não utilizado no protocolo v4.0 NUS");
        return null;
    }

    /**
     * Retorna CommandQueue v2.3 — null nesta implementação (protocolo simplificado).
     */
    public CommandQueue getCommandQueueV2() {
        Log.w(TAG, "[COMPAT] getCommandQueueV2() chamado — não utilizado no protocolo v4.0 NUS");
        return null;
    }

    /**
     * Enfileira comando de liberação de chopp no novo protocolo.
     * Formato: $ML:<volume_ml>
     *
     * @param volumeMl volume em mL a liberar
     * @param sessionId ignorado no novo protocolo (mantido para compatibilidade de assinatura)
     * @return BleCommand com os dados do comando, ou null se BLE não pronto
     */
    public BleCommand enqueueServeCommand(int volumeMl, String sessionId) {
        if (mState != State.READY || !isBleStackPronto()) {
            Log.e(TAG, "[BLOCK] BLE não está READY");
            logBleHandles("enqueueServeCommand");
            return null;
        }

        String command = "$ML:" + volumeMl;
        Log.i(TAG, "[FLOW] Pagamento aprovado → liberando " + volumeMl + "mL via " + command);

        BleCommand cmd = new BleCommand(BleCommand.Type.ML, sessionId, volumeMl);
        boolean ok = write(command);
        if (!ok) {
            Log.e(TAG, "[FLOW] Falha ao enfileirar comando: " + command);
            return null;
        }

        cmd.state = BleCommand.State.SENT;
        return cmd;
    }

    public boolean isInternetAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            Log.e(TAG, "[NET] isInternetAvailable() erro: " + e.getMessage());
            return false;
        }
    }
}
