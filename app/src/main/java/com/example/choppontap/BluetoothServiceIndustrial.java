package com.example.choppontap;

/*
 * ═══════════════════════════════════════════════════════════════════════════════
 * BluetoothServiceIndustrial.java — Serviço BLE Padrão Industrial 24/7
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Versão: 3.0-INDUSTRIAL
 * Protocolo: Nordic UART Service (NUS) sobre BLE
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
 *        │                                                             AUTH_OK
 *        │                                                                    │
 *        └──────────────────── reconexão ◄──────────────────────────── READY ◄┘
 *                                                                         │
 *                                                                    ERROR (fatal)
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * PROTEÇÕES ANTI-BUG ANDROID
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *   status=8   (GATT_CONN_TIMEOUT)     → NÃO fechar GATT → reconectar imediato
 *   status=257 (GATT_CONN_TERMINATE)   → NÃO fechar GATT → reconectar imediato
 *   status=133 (GATT_ERROR)            → FECHAR GATT → recriar GATT
 *   status=0x89 (GATT_AUTH_FAIL)       → FECHAR GATT → remover bond → recriar bond
 *   reconexões >= 3                    → FORÇAR closeGatt() e reiniciar do zero
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * FLUXO COMPLETO
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * CASO 1 — Primeira conexão (sem MAC salvo):
 *   DISCONNECTED → SCANNING → scan filtra CHOPP_* → valida MAC na API
 *   → pararScan → salvarMac → CONNECTING → createBond()
 *   → ACTION_PAIRING_REQUEST → setPin(259087) → BOND_BONDED
 *   → delay 800–1200ms → connectGatt(autoConnect=false, TRANSPORT_LE)
 *   → CONNECTED → requestConnectionPriority(HIGH) → requestMtu(512)
 *   → discoverServices → delay 600ms → AUTH|<HMAC_HEX>:<UPTIME_SEG>|<CMD_ID>|<SESSION_ID>
 *   → AUTH_OK → READY → broadcastWriteReady
 *
 * CASO 2 — Reconexão (MAC salvo):
 *   DISCONNECTED → CONNECTING → delay 800–1200ms
 *   → connectGatt(autoConnect=false, TRANSPORT_LE)
 *   → CONNECTED → MTU → discoverServices → AUTH → READY
 *
 * CASO 3 — Queda durante operação (status=8 ou 257):
 *   READY → DISCONNECTED (GATT preservado) → CONNECTING
 *   → gatt.connect() imediato → CONNECTED → AUTH → READY
 *   → CommandQueue retoma comando ativo
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
import java.util.Date;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import android.util.Base64;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

public class BluetoothServiceIndustrial extends Service {

    // ═══════════════════════════════════════════════════════════════════════════
    // TAG e identificação
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String TAG = "BLE_INDUSTRIAL";

    // FIX: Singleton para evitar múltiplas instâncias
    private static BluetoothServiceIndustrial sInstance = null;

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUISITO 1 — Máquina de estados completa
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Estados possíveis do serviço BLE industrial.
     * Toda transição é feita exclusivamente via {@link #transitionTo(State)}.
     */
    public enum State {
        /** Sem conexão ativa. Nenhum GATT aberto (ou GATT preservado pós-status=8). */
        DISCONNECTED,
        /** Scan BLE ativo procurando dispositivos CHOPP_*. */
        SCANNING,
        /** connectGatt() foi chamado; aguardando STATE_CONNECTED do callback GATT. */
        CONNECTING,
        /** GATT conectado; discoverServices em andamento ou MTU sendo negociado. */
        CONNECTED,
        /** AUTH_OK recebido. Pronto para receber comandos industriais. */
        READY,
        /** Erro fatal (ex.: GATT_AUTH_FAIL repetido). Requer intervenção manual. */
        ERROR
    }

    /** Estado atual — acesso apenas via {@link #transitionTo(State)} e {@link #getState()}. */
    private volatile State mState = State.DISCONNECTED;

    /**
     * Transiciona para um novo estado com log detalhado.
     * Regra: nunca chamar connectGatt se estado for CONNECTING ou CONNECTED.
     * Toda mudança de estado emite broadcast {@link #ACTION_BLE_STATE_CHANGED}.
     *
     * @param newState novo estado desejado
     */
    private void transitionTo(State newState) {
        State old = mState;
        if (old == newState) return; // sem mudança — ignora silenciosamente
        mState = newState;
        Log.i(TAG, "═══ STATE: " + old.name() + " → " + newState.name() + " ═══");
        broadcastBleState(newState);

        // Ações automáticas ao entrar em cada estado
        switch (newState) {
            case DISCONNECTED:
                pararHeartbeat();
                mWriteCharacteristic = null;
                mNotifyCharacteristic = null;
                mWriteBusy.set(false);
                mReadyTimestamp = 0; // Reset guard-band timestamp
                if (mCommandQueueV2 != null) {
                    BleCommand active = mCommandQueueV2.getActiveCommand();
                    if (active != null) {
                        Log.w(TAG, "[RESET] BLE desconectado durante SERVE — mantendo comando ativo");
                        mCommandQueueV2.onBleDisconnected();
                    } else {
                        mCommandQueueV2.onBleDisconnected();
                    }
                }
                break;
            case SCANNING:
                pararHeartbeat();
                break;
            case CONNECTING:
                pararHeartbeat();
                break;
            case CONNECTED:
                // Heartbeat só inicia após READY
                break;
            case READY:
                mReconnectAttempts = 0;
                mReconnectDelay    = BACKOFF_DELAYS[0];
                mAuthRetryCount    = 0;
                // ═══════════════════════════════════════════════════════════════════
                // GUARD-BAND: Registar timestamp do READY para sincronização do app
                // ═══════════════════════════════════════════════════════════════════
                mReadyTimestamp = System.currentTimeMillis();
                Log.i(TAG, "[STATE] READY [timestamp=" + mReadyTimestamp + "]");
                Log.i(TAG, "[GUARD-BAND] READY recebido — APP aguardará "
                        + GUARD_BAND_MS + "ms antes de enviar SERVE");
                Log.i(TAG, "[FLOW] Aguardando pagamento");
                iniciarHeartbeat();
                broadcastWriteReady();
                // Drena fila de comandos pendentes (fila string interna)
                mMainHandler.post(this::drainCommandQueue);
                // Notifica CommandQueue v2.3 que BLE está pronto
                if (mCommandQueueV2 != null) mCommandQueueV2.onBleReady();
                break;
            case ERROR:
                pararHeartbeat();
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
    /**
     * Chave secreta HMAC-SHA256 — DEVE ser idêntica a AUTH_SECRET_KEY no config.h do firmware.
     * Formato do token: HMAC(SESSION_ID + ":" + timestamp_segundos, SECRET_KEY)
     * Formato do comando AUTH: AUTH|<HMAC_HEX>:<TIMESTAMP>|<CMD_ID>|<SESSION_ID>
     */
    private static final String AUTH_SECRET_KEY = "Choppon103614@";
    /** ID do comando de autenticação (incrementar por sessão se necessário). */
    private static final String AUTH_CMD_ID = "1";

    /** Comando de heartbeat enviado a cada {@link #HEARTBEAT_INTERVAL_MS}. */
    private static final String PING_COMMAND = "PING";

    // ═══════════════════════════════════════════════════════════════════════════
    // UUIDs reais do ESP32
    // ═══════════════════════════════════════════════════════════════════════════

    private static final UUID NUS_SERVICE_UUID           = UUID.fromString("7f0a0001-7b6b-4b5f-9d3e-3c7b9f100001");
    /** RX do ESP32 — Android escreve aqui para enviar dados ao ESP32. */
    private static final UUID NUS_RX_CHARACTERISTIC_UUID = UUID.fromString("7f0a0002-7b6b-4b5f-9d3e-3c7b9f100001");
    /** TX do ESP32 — Android recebe notificações aqui. */
    private static final UUID NUS_TX_CHARACTERISTIC_UUID = UUID.fromString("7f0a0003-7b6b-4b5f-9d3e-3c7b9f100001");
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

    /** GATT_CONN_TIMEOUT — NÃO fechar GATT, reconectar imediatamente. */
    private static final int STATUS_CONN_TIMEOUT     = 0x08;  // 8
    /** GATT_CONN_TERMINATE_PEER_USER — NÃO fechar GATT, reconectar. */
    private static final int STATUS_CONN_TERMINATE   = 0x13;  // 19
    /** GATT_CONN_FAIL_ESTABLISH — NÃO fechar GATT, reconectar. */
    private static final int STATUS_CONN_FAIL        = 0x3E;  // 62
    /** Código 257 = 0x101 — NÃO fechar GATT (bug Android stack). */
    private static final int STATUS_CONN_257         = 257;
    /** GATT_ERROR (bug Android stack BLE) — FECHAR GATT e recriar. */
    private static final int STATUS_GATT_ERROR       = 133;
    /** Bond inválido — FECHAR GATT, remover bond e recriar. */
    private static final int STATUS_AUTH_FAIL        = 0x89;  // 137

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUISITO 5 — Backoff exponencial de reconexão
    // ═══════════════════════════════════════════════════════════════════════════

    /** Delays de reconexão: 2s → 4s → 8s → 15s (máx). */
    private static final long[] BACKOFF_DELAYS = { 2_000L, 4_000L, 8_000L, 15_000L };

    /** Número máximo de reconexões antes de forçar closeGatt() e reiniciar do zero. */
    private static final int MAX_RECONNECT_BEFORE_RESET = 3;

    private long mReconnectDelay    = BACKOFF_DELAYS[0];
    private int  mReconnectAttempts = 0;
    private int  mBackoffIndex      = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUISITO 3 — Delay inteligente antes de conectar (800–1200ms)
    // ═══════════════════════════════════════════════════════════════════════════

    private static final long CONNECT_DELAY_MIN_MS = 800L;
    private static final long CONNECT_DELAY_MAX_MS = 1_200L;

    /** Gera delay aleatório entre 800ms e 1200ms para evitar colisão de conexões. */
    private long randomConnectDelay() {
        return CONNECT_DELAY_MIN_MS
                + (long)(Math.random() * (CONNECT_DELAY_MAX_MS - CONNECT_DELAY_MIN_MS));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUISITO 7 — Heartbeat PING/PONG
    // ═══════════════════════════════════════════════════════════════════════════

    private static final boolean ENABLE_HEARTBEAT = false;
    private static final long HEARTBEAT_INTERVAL_MS = 5_000L;  // PING a cada 5s
    private static final long HEARTBEAT_TIMEOUT_MS  = 10_000L; // timeout de resposta 10s

    private Runnable mHeartbeatRunnable = null;
    private Runnable mHeartbeatTimeoutRunnable = null;
    private volatile boolean mWaitingPong = false;

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUISITO 8/9 — Autenticação automática
    // ═══════════════════════════════════════════════════════════════════════════

    private static final long AUTH_DELAY_MS   = 600L;    // delay antes de enviar AUTH industrial
    private static final long AUTH_TIMEOUT_MS = 8_000L;  // mantém bloqueado se AUTH_OK não chegar

    private Runnable mAuthTimeoutRunnable = null;

    // ═══════════════════════════════════════════════════════════════════════════
    // GUARD-BAND — Sincronização ESP32 após READY (nova especificação)
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * Guard-band (janela de prontidão) após READY:
     * APP NÃO deve enviar SERVE até que este tempo tenha passado desde READY.
     * ESP32 firmware atualizado requer sincronização com esta janela.
     * Valor recomendado: 800-1000ms. Usando 900ms para estar no meio da janela.
     */
    private static final long GUARD_BAND_MS = 900L;

    /** Timestamp (ms) quando READY foi recebido do ESP32. Zero = não recebido. */
    private volatile long mReadyTimestamp = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Timeouts gerais
    // ═══════════════════════════════════════════════════════════════════════════

    private static final long BOND_TIMEOUT_MS = 15_000L;
    private static final long SCAN_TIMEOUT_MS = 15_000L;
    private static final long SCAN_RETRY_DELAY_MS = 5_000L;

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
    // REQUISITO 6 — Fila de comandos (apenas 1 write por vez)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Fila FIFO de comandos pendentes. Acesso apenas no main thread. */
    private final Queue<String> mCommandQueue = new ArrayDeque<>();

    /** Flag atômica: true quando há um write em andamento aguardando onCharacteristicWrite. */
    private final AtomicBoolean mWriteBusy = new AtomicBoolean(false);

    // ═══════════════════════════════════════════════════════════════════════════
    // CommandQueue v2.3 — fila tipada de BleCommand (SERVE/AUTH/etc.)
    // ═══════════════════════════════════════════════════════════════════════════

    /** CommandQueue v2.3 — fila tipada para rastreamento completo de comandos SERVE. */
    private CommandQueue mCommandQueueV2 = null;

    /** Número máximo de tentativas de reenvio de AUTH após ERROR:NOT_AUTHENTICATED. */
    private static final int MAX_AUTH_RETRY = 3;
    /** Timeout para sair de CONNECTING quando o stack BLE não responde. */
    private static final long CONNECT_TIMEOUT_MS = 8_000L;
    /** Contador de tentativas de reenvio de AUTH. */
    private int mAuthRetryCount = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Handlers e Runnables
    // ═══════════════════════════════════════════════════════════════════════════

    private final Handler mMainHandler         = new Handler(Looper.getMainLooper());
    private Runnable      mReconnectRunnable   = null;
    private Runnable      mScanStopRunnable    = null;
    private Runnable      mBondTimeoutRunnable = null;
    private Runnable      mConnectTimeoutRunnable = null;

    // ═══════════════════════════════════════════════════════════════════════════
    // MACs em validação (evita revalidar o mesmo MAC no mesmo ciclo de scan)
    // ═══════════════════════════════════════════════════════════════════════════

    private final java.util.Set<String> mMacsValidando = new java.util.HashSet<>();

    // ═══════════════════════════════════════════════════════════════════════════
    // Binder (compatível com BluetoothService legado)
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
    // Notificação Foreground (obrigatório Android 12+ / Android 14 FGS)
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
            // FIX: Verificar se já existe instância
            if (sInstance != null) {
                Log.w(TAG, "[INDUSTRIAL] ⚠️  Serviço BLE já está rodando! Abortando onCreate duplicado.");
                stopSelf(); // Para o serviço duplicado
                return;
            }

            sInstance = this;
            Log.i(TAG, "[SERVICE] 🟢 SERVICE CREATED - BluetoothServiceIndustrial v3.0 SINGLETON iniciado");

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

        // Inicializa CommandQueue v2.3 com callbacks de broadcast
        mCommandQueueV2 = new CommandQueue(
            data -> write(data),
            new CommandQueue.Callback() {
                @Override public void onSend(BleCommand cmd) {
                    Log.i(TAG, "[QUEUE_V2] SEND " + cmd);
                    Log.i(TAG, "[FLOW] SERVE enviado");
                }
                @Override public void onAck(BleCommand cmd) {
                    Log.i(TAG, "[QUEUE_V2] ACK " + cmd);
                    broadcastData("QUEUE:ACK:" + cmd.commandId);
                }
                @Override public void onDone(BleCommand cmd) {
                    Log.i(TAG, "[QUEUE_V2] DONE " + cmd + " | ml_real=" + cmd.mlReal);
                    Log.i(TAG, "[FLOW] DONE recebido — ciclo finalizado");
                    broadcastData("QUEUE:DONE:" + cmd.commandId + ":" + cmd.mlReal);
                }
                @Override public void onError(BleCommand cmd, String reason) {
                    Log.e(TAG, "[QUEUE_V2] ERROR " + cmd + " | reason=" + reason);
                    broadcastData("QUEUE:ERROR:" + cmd.commandId + ":" + reason);
                }
                @Override public void onQueueFull() {
                    Log.e(TAG, "[QUEUE_V2] QUEUE:FULL");
                    broadcastData("QUEUE:FULL");
                }
            }
        );

        Log.i(TAG, "[INDUSTRIAL] Service iniciado. Iniciando conexão BLE...");
        iniciarConexao();
        } catch (Exception e) {
            Log.e(TAG, "[SERVICE] 💥 CRASH no onCreate do Service BLE!");
            Log.e(TAG, "[SERVICE] Exception: " + e.getClass().getName() + " - " + e.getMessage());
            Log.e(TAG, "[SERVICE] StackTrace:", e);
            // Não parar o service - deixar o sistema reiniciar com START_STICKY
            // Mas limpar a instância para permitir recriação
            sInstance = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // reinicia automaticamente se o sistema matar o serviço
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "[SERVICE] 🔴 SERVICE DESTROYED - limpando singleton");
        sInstance = null;
        mAutoReconnect = false;
        pararHeartbeat();
        pararReconexao();
        pararScan();
        cancelarAuthTimeout();
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

    /**
     * Retorna a instância singleton do serviço BLE.
     * @return instância ativa ou null se não estiver rodando
     */
    public static BluetoothServiceIndustrial getInstance() {
        return sInstance;
    }

    /**
     * Verifica se o serviço BLE já está rodando.
     * @return true se o serviço está ativo
     */
    public static boolean isRunning() {
        return sInstance != null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Ponto de entrada: iniciar conexão
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Decide entre reconexão direta (MAC conhecido) ou scan (primeiro uso).
     * Chamado no onCreate() e após cada desconexão quando mAutoReconnect=true.
     */
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
    // REQUISITO 12 — Proteção contra dupla conexão
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifica se é seguro chamar connectGatt.
     * Retorna false (e loga) se estado for CONNECTING ou CONNECTED.
     */
    private boolean podeConectar() {
        State s = mState;
        if (s == State.CONNECTING || s == State.CONNECTED || s == State.READY) {
            Log.w(TAG, "[INDUSTRIAL] podeConectar() → BLOQUEADO (estado=" + s.name() + ")");
            return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUISITO 3 — Delay inteligente antes de conectar
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Agenda conexão direta com delay aleatório de 800–1200ms.
     * Usado após BOND_BONDED e em reconexões.
     */
    private void agendarConexaoDireta(String mac) {
        long delay = randomConnectDelay();
        Log.i(TAG, "[INDUSTRIAL] Agendando conexão em " + delay + "ms → " + mac);
        mMainHandler.postDelayed(() -> {
            if (!podeConectar()) return;
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mac);
            conectarGatt(device);
        }, delay);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUISITO 2 — Conexão BLE correta (autoConnect=false, TRANSPORT_LE)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Conecta via GATT com autoConnect=false e TRANSPORT_LE (obrigatório API >= 23).
     *
     * Regras:
     * - Se GATT existente para o mesmo MAC → gatt.connect() (reconexão rápida)
     * - Se GATT de outro MAC → closeGatt() + novo connectGatt()
     * - Se sem GATT → novo connectGatt()
     *
     * NUNCA chamado diretamente — sempre via {@link #agendarConexaoDireta(String)}
     * ou {@link #reconectarComBackoff()}.
     */
    private void conectarGatt(BluetoothDevice device) {
        // REQUISITO 12 — proteção dupla conexão
        if (!podeConectar()) return;

        transitionTo(State.CONNECTING);
        iniciarTimeoutConexao(device);
        Log.i(TAG, "[GATT] conectarGatt() → " + device.getAddress()
                + " | bond=" + bondStateName(device.getBondState())
                + " | tentativa=" + (mReconnectAttempts + 1));

        // Reutiliza GATT existente para o mesmo MAC (preserva cache de serviços)
        if (mBluetoothGatt != null
                && mBluetoothGatt.getDevice().getAddress()
                        .equalsIgnoreCase(device.getAddress())) {
            Log.i(TAG, "[GATT] GATT existente → gatt.connect() (reconexão rápida)");
            boolean ok = mBluetoothGatt.connect();
            Log.i(TAG, "[GATT] gatt.connect() → " + (ok ? "OK" : "FALHOU — criando novo GATT"));
            if (ok) return;
            // Se falhou, fecha e recria
            closeGatt();
        } else if (mBluetoothGatt != null) {
            Log.i(TAG, "[GATT] GATT de outro MAC — fechando antes de criar novo");
            closeGatt();
        }

        // REQUISITO 2 — autoConnect=false + TRANSPORT_LE
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
    // REQUISITO 5 — Reconexão industrial com backoff exponencial
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Agenda reconexão com backoff exponencial: 2s → 4s → 8s → 15s.
     * Não permite múltiplas reconexões simultâneas.
     * Se reconexões >= 3: força closeGatt() antes de tentar novamente.
     */
    private void reconectarComBackoff() {
        Log.i(TAG, "[RECONNECT] 🔄 Tentativa de reconexão automática iniciada");
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

        // REQUISITO 4 — reconexões >= 3: forçar closeGatt()
        if (mReconnectAttempts >= MAX_RECONNECT_BEFORE_RESET) {
            Log.w(TAG, "[INDUSTRIAL] " + mReconnectAttempts
                    + " reconexões consecutivas — forçando closeGatt() e reiniciando");
            closeGatt();
            mReconnectAttempts = 0;
            mBackoffIndex      = 0;
            mReconnectDelay    = BACKOFF_DELAYS[0];
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

    /** Cancela reconexão agendada. */
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
        synchronized (mMacsValidando) { mMacsValidando.clear(); }
        mScanning = true;
        transitionTo(State.SCANNING);
        Log.i(TAG, "[SCAN] Iniciando scan BLE por CHOPP_*");
        broadcastConnectionStatus("scanning");
        mBleScanner.startScan(mScanCallback);

        // Para o scan após SCAN_TIMEOUT_MS e agenda retry
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
     * Se válido: para scan, salva MAC, inicia bond/conexão.
     * Se inválido: remove da lista e continua scan.
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
    // Bond (pareamento)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifica estado do bond e age de acordo:
     * - BOND_BONDED  → agenda conexão direta com delay
     * - BOND_BONDING → aguarda ACTION_BOND_STATE_CHANGED
     * - BOND_NONE    → createBond() + timeout de segurança
     */
    private void iniciarBondEConectar(BluetoothDevice device) {
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
                Log.i(TAG, "[BOND] BOND_NONE → createBond() com PIN ");
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

            switch (variant) {
                case BluetoothDevice.PAIRING_VARIANT_PIN: {
                    // PIN removido — autenticação é feita via HMAC-SHA256 no nível do protocolo BLE
                    // O pareamento BLE é feito sem PIN (JustWorks)
                    boolean ok = device.setPairingConfirmation(true);
                    Log.i(TAG, "[PAIRING] PIN_VARIANT → setPairingConfirmation(true) -> "
                            + (ok ? "ACEITO" : "REJEITADO"));
                    abortBroadcast();
                    break;
                }
                case BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION:
                    device.setPairingConfirmation(true);
                    Log.i(TAG, "[PAIRING] AUTO-CONFIRM -> setPairingConfirmation(true)");
                    abortBroadcast();
                    break;
                case 3: // PAIRING_VARIANT_PASSKEY (não exposto como constante em todas as APIs)
                    // Variante 3 = PIN numérico de 6 dígitos (NimBLE passkey)
                    // O ESP32 com NimBLE usa esta variante quando configurado como
                    // BLE_SM_IO_CAP_DISP_ONLY ou BLE_SM_IO_CAP_KEYBOARD_ONLY.
                    // Solucao: confirmar automaticamente com setPin.
                    try {
                        // PIN removido — confirmar pareamento automaticamente (JustWorks)
                        boolean okPasskey = device.setPairingConfirmation(true);
                        Log.i(TAG, "[PAIRING] Variante 3 (PASSKEY) → setPairingConfirmation(true) -> "
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
                        // device.setPin(...); // PIN removido conforme solicitacao
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
                // REQUISITO 3 — delay 800–1200ms após BOND_BONDED
                agendarConexaoDireta(device.getAddress());

            } else if (newState == BluetoothDevice.BOND_NONE
                    && prevState == BluetoothDevice.BOND_BONDING) {
                Log.e(TAG, "[BOND] Pareamento FALHOU. Verifique PIN "
                        + " no firmware ESP32.");
                cancelarBondTimeout();
                broadcastConnectionStatus("bond_failed");
                // Tenta reconectar após backoff
                mMainHandler.postDelayed(() -> iniciarConexao(), 3_000L);
            }
        }
    };

    // ═══════════════════════════════════════════════════════════════════════════
    // GATT Callback — coração do serviço BLE
    // ═══════════════════════════════════════════════════════════════════════════

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        // ─── onConnectionStateChange ─────────────────────────────────────────

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

        // ─── onMtuChanged ────────────────────────────────────────────────────

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "[GATT] onMtuChanged | mtu=" + mtu + " | status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "[GATT] MTU negociado: " + mtu + " bytes — iniciando discoverServices()");
            } else {
                Log.w(TAG, "[GATT] MTU falhou (status=" + status + ") — prosseguindo com discoverServices()");
            }
            // Inicia descoberta de serviços independente do resultado do MTU
            boolean ok = gatt.discoverServices();
            Log.i(TAG, "[GATT] discoverServices() → " + (ok ? "INICIADO" : "FALHOU"));
        }

        // ─── onServicesDiscovered ─────────────────────────────────────────────

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.i(TAG, "[GATT] onServicesDiscovered | status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[GATT] discoverServices falhou (status=" + status + ") — reconectando");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }

            // Localiza características custom do ESP32: RX (write) e TX (notify)
            BluetoothGattService nusService = gatt.getService(NUS_SERVICE_UUID);
            if (nusService == null) {
                Log.e(TAG, "[BLE] SERVICE NOT FOUND");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }
            Log.i(TAG, "[BLE] SERVICE FOUND");

            BluetoothGattCharacteristic rxChar =
                    nusService.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
            BluetoothGattCharacteristic txChar =
                    nusService.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID);

            if (rxChar == null) {
                Log.e(TAG, "[BLE] CRITICAL: RX NOT FOUND");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }
            if (txChar == null) {
                Log.e(TAG, "[BLE] CRITICAL: TX NOT FOUND");
                mMainHandler.post(() -> reconectarComBackoff());
                return;
            }

            mBluetoothGatt = gatt;
            mWriteCharacteristic = rxChar;
            mNotifyCharacteristic = txChar;
            Log.i(TAG, "[BLE] RX READY");
            Log.i(TAG, "[BLE] TX READY");
            Log.i(TAG, "[BLE] BLE READY: SERVICE + RX + TX OK");
            logBleHandles("onServicesDiscovered");

            // REQUISITO 11 — Habilita notificações no TX (CCCD)
            habilitarNotificacoes(gatt, txChar);
        }

        // ─── onDescriptorWrite ────────────────────────────────────────────────

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status) {
            Log.i(TAG, "[GATT] onDescriptorWrite | status=" + status
                    + " | descriptor=" + descriptor.getUuid());
            logBleHandles("onDescriptorWrite");
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "[BLE] NOTIFY ENABLED");
                // REQUISITO 8 — Envia AUTH industrial após delay de 600ms
                mMainHandler.postDelayed(() -> enviarAutenticacao(gatt), AUTH_DELAY_MS);
            } else {
                Log.w(TAG, "[GATT] onDescriptorWrite falhou (status=" + status
                        + ") — tentando auth mesmo assim");
                mMainHandler.postDelayed(() -> enviarAutenticacao(gatt), AUTH_DELAY_MS);
            }
        }

        // ─── onCharacteristicWrite ────────────────────────────────────────────

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "[GATT] onCharacteristicWrite | status=" + status);
            // REQUISITO 6 — Libera fila para próximo comando
            mWriteBusy.set(false);
            mMainHandler.post(BluetoothServiceIndustrial.this::drainCommandQueue);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[GATT] Write falhou (status=" + status + ")");
                broadcastData("WRITE_ERROR:" + status);
            }
        }

        // ─── onCharacteristicChanged ──────────────────────────────────────────

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            byte[] raw = characteristic.getValue();
            if (raw == null || raw.length == 0) return;
            String data = new String(raw, StandardCharsets.UTF_8).trim();
            Log.i(TAG, "[RX] " + data);

            // Processa respostas do ESP32
            processarRespostaBle(data);
        }
    };

    // ═══════════════════════════════════════════════════════════════════════════
    // Handlers de conexão/desconexão GATT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Chamado quando GATT conecta com sucesso.
     * REQUISITO 10 — requestConnectionPriority(HIGH) + requestMtu(512).
     */
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

        // v2.3.0 FIX: HIGH (timeout=5000ms) derruba o BLE no ESP32-C3 single-core
        // durante dispensação. BALANCED usa timeout=6000ms e interval=30-50ms,
        // suficiente para manter link estável sem saturar o single-core.
        boolean priOk = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
        Log.i(TAG, "[GATT] requestConnectionPriority(BALANCED) → " + (priOk ? "OK" : "FALHOU"));
        Log.i(TAG, "[GATT] timeout=6000ms para ESP32-C3 single-core");

        // REQUISITO 10 — MTU 512 (necessário para comandos longos)
        boolean mtuOk = gatt.requestMtu(512);
        Log.i(TAG, "[GATT] requestMtu(512) → " + (mtuOk ? "AGUARDANDO onMtuChanged" : "FALHOU — discoverServices direto"));
        if (!mtuOk) {
            // Fallback: inicia discoverServices sem MTU
            mMainHandler.post(() -> {
                boolean ok = gatt.discoverServices();
                Log.i(TAG, "[GATT] discoverServices() (fallback) → " + (ok ? "INICIADO" : "FALHOU"));
            });
        }
    }

    /**
     * Chamado quando GATT desconecta.
     * REQUISITO 4 — Decide se fecha GATT baseado no status.
     */
    private void handleGattDisconnected(BluetoothGatt gatt, int status) {
        Log.i(TAG, "[GATT] DESCONECTADO | status=" + status
                + " | mac=" + gatt.getDevice().getAddress()
                + " | state_anterior=" + mState.name());

        cancelarTimeoutConexao();
        cancelarAuthTimeout();
        pararHeartbeat();
        transitionTo(State.DISCONNECTED);
        broadcastConnectionStatus("disconnected:" + status);

        if (!mAutoReconnect) {
            Log.i(TAG, "[INDUSTRIAL] autoReconnect=false — não reconectando");
            return;
        }

        // REQUISITO 4 — Controle de GATT baseado no status
        if (status == STATUS_GATT_ERROR) {
            // status=133: bug Android stack — FECHAR GATT obrigatório
            Log.w(TAG, "[GATT] status=133 (GATT_ERROR) → fechando GATT e recriando");
            closeGatt();
            mMainHandler.postDelayed(() -> reconectarComBackoff(), 1_000L);

        } else if (status == STATUS_AUTH_FAIL) {
            // status=0x89: bond inválido — FECHAR GATT, remover bond, recriar
            Log.e(TAG, "[GATT] status=0x89 (AUTH_FAIL) → removendo bond e recriando");
            BluetoothDevice device = gatt.getDevice();
            closeGatt();
            removeBond(device);
            mMainHandler.postDelayed(() -> iniciarBondEConectar(device), 2_000L);

        } else if (status == STATUS_CONN_TIMEOUT    // 8
                || status == STATUS_CONN_257         // 257
                || status == STATUS_CONN_TERMINATE   // 19
                || status == STATUS_CONN_FAIL) {     // 62
            // NÃO fechar GATT — reconectar imediatamente preservando cache
            Log.i(TAG, "[GATT] status=" + status + " → NÃO fechando GATT (preservando cache)");
            reconectarComBackoff();

        } else {
            // Status desconhecido — fechar GATT por segurança
            Log.w(TAG, "[GATT] status=" + status + " (desconhecido) → fechando GATT");
            closeGatt();
            reconectarComBackoff();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUISITO 11 — Habilitar notificações (CCCD)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Habilita notificações BLE na característica TX do NUS.
     * Passo 1: setCharacteristicNotification(true)
     * Passo 2: writeDescriptor CCCD com ENABLE_NOTIFICATION_VALUE
     */
    private void habilitarNotificacoes(BluetoothGatt gatt,
                                        BluetoothGattCharacteristic txChar) {
        mNotifyCharacteristic = txChar;
        logBleHandles("habilitarNotificacoes");
        boolean ok = gatt.setCharacteristicNotification(txChar, true);
        Log.i(TAG, "[GATT] setCharacteristicNotification(TX, true) → " + (ok ? "OK" : "FALHOU"));

        BluetoothGattDescriptor cccd = txChar.getDescriptor(CCCD_UUID);
        if (cccd == null) {
            Log.e(TAG, "[GATT] CCCD descriptor não encontrado — enviando auth sem notificações");
            mMainHandler.postDelayed(() -> enviarAutenticacao(gatt), AUTH_DELAY_MS);
            return;
        }

        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        boolean writeOk = gatt.writeDescriptor(cccd);
        Log.i(TAG, "[GATT] writeDescriptor(CCCD) → " + (writeOk ? "OK" : "FALHOU"));
        if (!writeOk) {
            // Fallback: tenta auth mesmo sem CCCD confirmado
            mMainHandler.postDelayed(() -> enviarAutenticacao(gatt), AUTH_DELAY_MS);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUISITO 8/9 — Autenticação automática com timeout de segurança
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Envia AUTH industrial após delay de 600ms.
     * REQUISITO 9 — Se AUTH_OK não chegar em 8s, mantém BLE bloqueado.
     */
    /**
     * Gera o comando AUTH com token HMAC-SHA256 no formato esperado pelo firmware v2.0.
     *
     * Formato enviado: AUTH|<HMAC_HEX>:<TIMESTAMP_SEG>|<CMD_ID>|<SESSION_ID>
     * Onde HMAC = HMAC-SHA256(SESSION_ID + ":" + TIMESTAMP_SEG, AUTH_SECRET_KEY)
     *
     * O firmware valida em auth_validator.cpp:
     *   message = sessionId + ":" + timestampStr
     *   expectedHmac = HMAC-SHA256(message, AUTH_SECRET_KEY)
     */
    private String gerarAuth() {
        // Gerar SESSION_ID único por sessão (8 chars hex aleatório)
        final String sessionId = String.format(Locale.US, "%08X",
                (int)(System.currentTimeMillis() & 0xFFFFFFFFL));
                
        // Regra: timestamp = currentTimeMillis (em segundos)
        final long timestampSeg = System.currentTimeMillis() / 1000L;
        
        // Regra: payload = "$timestamp:$sessionId"
        final String payload = sessionId + ":" + timestampSeg;
        
        // Calcular HMAC-SHA256
        String hmacHex = calcularHmacSha256(payload, AUTH_SECRET_KEY);
        if (hmacHex == null) {
            Log.e(TAG, "[AUTH] FALHA ao calcular HMAC — usando fallback");
            hmacHex = "0000000000000000000000000000000000000000000000000000000000000000";
        }
        
        // Regra: Resultado final: <hex_hmac>:<timestamp>
        final String token = hmacHex + ":" + timestampSeg;
        
        // Regra: Formato final: AUTH|<token>|<cmdId>|<sessionId>
        final String cmd = "AUTH|" + token + "|" + AUTH_CMD_ID + "|" + sessionId;
        
        // Logs obrigatórios
        Log.d("BLE_AUTH", "Payload: " + payload);
        Log.d("BLE_AUTH", "Token gerado: " + token);
        Log.d("BLE_AUTH", "Comando enviado: " + cmd);
        
        return cmd;
    }

    /**
     * Calcula HMAC-SHA256 de uma mensagem com a chave fornecida.
     * Retorna string hexadecimal de 64 caracteres ou null em caso de erro.
     */
    private static String calcularHmacSha256(String message, String key) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            final SecretKeySpec secretKey = new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            final byte[] hmacBytes = mac.doFinal(
                    message.getBytes(StandardCharsets.UTF_8));
            // Converter para hexadecimal lowercase (igual ao firmware: %02x)
            final StringBuilder sb = new StringBuilder(64);
            for (byte b : hmacBytes) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e("BLE_INDUSTRIAL", "[AUTH] Erro HMAC: " + e.getMessage());
            return null;
        }
    }

    private void enviarAutenticacao(BluetoothGatt gatt) {
        if (mState == State.DISCONNECTED || mState == State.SCANNING) {
            Log.w(TAG, "[AUTH] Ignorando envio de auth — estado=" + mState.name());
            return;
        }
        mBluetoothGatt = gatt;
        logBleHandles("enviarAutenticacao");
        if (!isBleStackPronto()) {
            Log.e(TAG, "[AUTH] AUTH BLOQUEADO — BLE não pronto");
            return;
        }
        Log.i(TAG, "[AUTH] SENT");
        writeImediato(gerarAuth());

        // Timeout de segurança: se AUTH_OK não chegar, não promover READY falso.
        cancelarAuthTimeout();
        mAuthTimeoutRunnable = () -> {
            if (mState != State.READY) {
                Log.w(TAG, "[AUTH] Timeout " + AUTH_TIMEOUT_MS / 1000
                        + "s — AUTH_OK não recebido. BLE permanece bloqueado");
                logBleHandles("auth_timeout");
                broadcastConnectionStatus("auth_timeout");
            }
        };
        mMainHandler.postDelayed(mAuthTimeoutRunnable, AUTH_TIMEOUT_MS);
    }

    private void cancelarAuthTimeout() {
        if (mAuthTimeoutRunnable != null) {
            mMainHandler.removeCallbacks(mAuthTimeoutRunnable);
            mAuthTimeoutRunnable = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Processamento de respostas BLE do ESP32
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Processa todas as mensagens recebidas do ESP32 via notificação BLE.
     * Roteamento centralizado: AUTH, PONG, dados de operação.
     */
    private void processarRespostaBle(String data) {
        // ── AUTH_OK — autenticação bem-sucedida ──────────────────────────────
        if (data.startsWith("AUTH_OK|") || data.startsWith("AUTH:OK")) {
            Log.i(TAG, "[AUTH] OK");
            cancelarAuthTimeout();
            if (!isBleStackPronto()) {
                Log.e(TAG, "[AUTH] READY BLOQUEADO — handles BLE ausentes após AUTH");
                logBleHandles("AUTH_OK");
                broadcastData(data);
                return;
            }
            transitionTo(State.READY);
            broadcastConnectionStatus("ready");
            broadcastData(data);
            return;
        }

        // ── AUTH:FAIL — autenticação falhou ──────────────────────────────────
        if (data.startsWith("AUTH_FAIL|") || data.startsWith("AUTH:FAIL")) {
            Log.e(TAG, "[AUTH] ✗ AUTH:FAIL — PIN incorreto ou firmware incompatível");
            broadcastData(data);
            return;
        }

        // ── PONG — resposta ao heartbeat ─────────────────────────────────────
        if (data.equals("PONG") || data.startsWith("PONG")) {
            Log.d(TAG, "[HEARTBEAT] PONG recebido ✓");
            mWaitingPong = false;
            cancelarHeartbeatTimeout();
            broadcastData(data);
            return;
        }

        // ── READY — ESP32 pronto (após STATUS sync) ──────────────────────────
        if (data.equals("READY")) {
            Log.i(TAG, "[BLE] ESP32 reportou READY");
            broadcastData(data);
            return;
        }

        // ── BUSY — ESP32 ocupado ─────────────────────────────────────────────
        if (data.equals("BUSY") || data.startsWith("BUSY")) {
            Log.w(TAG, "[BLE] ESP32 BUSY — aguardando liberação");
            broadcastData(data);
            return;
        }

        // ── Demais dados (ML:ACK, DONE, VP:, QP:, ERROR:, etc.) ─────────────
        // ── ERROR:NOT_AUTHENTICATED — reenviar AUTH automaticamente ─────────
        if ("ERROR:NOT_AUTHENTICATED".equalsIgnoreCase(data)
                || "ERROR:NOTAUTH".equalsIgnoreCase(data)) {
            Log.e(TAG, "[AUTH] ERROR:NOT_AUTHENTICATED | retry=" + mAuthRetryCount + "/" + MAX_AUTH_RETRY);
            broadcastData(data);
            if (mAuthRetryCount < MAX_AUTH_RETRY) {
                mAuthRetryCount++;
                Log.i(TAG, "[AUTH] Reenviando AUTH automaticamente (tentativa " + mAuthRetryCount + ")");
                mMainHandler.postDelayed(() -> writeImediato(gerarAuth()), 500L);
            } else {
                Log.e(TAG, "[AUTH] Máximo de retries AUTH atingido ("
                        + MAX_AUTH_RETRY + ") — reconectando");
                mAuthRetryCount = 0;
                if (mBluetoothGatt != null) mBluetoothGatt.disconnect();
            }
            return;
        }

        if ("ERROR:INVALID_FORMAT".equalsIgnoreCase(data)) {
            Log.e(TAG, "[AUTH] ERROR:INVALID_FORMAT - payload rejeitado pelo ESP32");
            broadcastData(data);
            return;
        }

        // ── ERROR:INVALID_AUTH — token mal-formado (reenviar AUTH) ───────────────
        if (data.startsWith("ERROR:INVALID_AUTH")) {
            Log.e(TAG, "[AUTH] ERROR:INVALID_AUTH | retry=" + mAuthRetryCount + "/" + MAX_AUTH_RETRY);
            broadcastData(data);
            if (mAuthRetryCount < MAX_AUTH_RETRY) {
                mAuthRetryCount++;
                Log.i(TAG, "[AUTH] Reenviando AUTH (INVALID_AUTH) tentativa " + mAuthRetryCount);
                mMainHandler.postDelayed(() -> writeImediato(gerarAuth()), 800L);
            } else {
                Log.e(TAG, "[AUTH] Max retries INVALID_AUTH atingido — reconectando");
                mAuthRetryCount = 0;
                if (mBluetoothGatt != null) mBluetoothGatt.disconnect();
            }
            return;
        }

        // ── ERROR:INVALID_TOKEN — HMAC inválido ou token expirado (reenviar AUTH) ─
        if (data.startsWith("ERROR:INVALID_TOKEN")) {
            Log.e(TAG, "[AUTH] ERROR:INVALID_TOKEN (HMAC inválido ou expirado) | retry=" + mAuthRetryCount);
            broadcastData(data);
            if (mAuthRetryCount < MAX_AUTH_RETRY) {
                mAuthRetryCount++;
                Log.i(TAG, "[AUTH] Reenviando AUTH (INVALID_TOKEN) tentativa " + mAuthRetryCount);
                mMainHandler.postDelayed(() -> writeImediato(gerarAuth()), 800L);
            } else {
                Log.e(TAG, "[AUTH] Max retries INVALID_TOKEN atingido — reconectando");
                mAuthRetryCount = 0;
                if (mBluetoothGatt != null) mBluetoothGatt.disconnect();
            }
            return;
        }
        // ── VALVE:OPEN / VALVE:CLOSED ─────────────────────────────────────────
        if ("VALVE:OPEN".equalsIgnoreCase(data)) {
            Log.i(TAG, "[BLE] Válvula ABERTA");
            broadcastData(data);
            return;
        }
        if ("VALVE:CLOSED".equalsIgnoreCase(data)) {
            Log.i(TAG, "[BLE] Válvula FECHADA");
            broadcastData(data);
            return;
        }

        // ── STATUS:IDLE / STATUS:RUNNING / STATUS:ERROR ───────────────────────
        if ("STATUS:IDLE".equalsIgnoreCase(data)) {
            Log.i(TAG, "[BLE] ESP32 STATUS:IDLE");
            broadcastData(data);
            return;
        }
        if ("STATUS:RUNNING".equalsIgnoreCase(data)) {
            Log.i(TAG, "[BLE] ESP32 STATUS:RUNNING");
            broadcastData(data);
            return;
        }
        if ("STATUS:ERROR".equalsIgnoreCase(data)) {
            Log.e(TAG, "[BLE] ESP32 STATUS:ERROR");
            broadcastData(data);
            return;
        }

        // ── QUEUE:FULL ────────────────────────────────────────────────────────
        if ("QUEUE:FULL".equalsIgnoreCase(data)) {
            Log.e(TAG, "[BLE] QUEUE:FULL — fila do ESP32 cheia");
            broadcastData(data);
            return;
        }

        // ── ERROR:TIMEOUT ─────────────────────────────────────────────────────
        if ("ERROR:TIMEOUT".equalsIgnoreCase(data)) {
            Log.e(TAG, "[BLE] ERROR:TIMEOUT recebido do ESP32");
            broadcastData(data);
            return;
        }

        // ── Demais dados (ACK, DONE, VP:, QP:, ERROR:BUSY, etc.) ─────────────
        // Roteia para CommandQueue v2.3 se houver comando ativo
        if (mCommandQueueV2 != null) {
            BleParser.ParsedMessage msg = BleParser.parse(data);
            if (msg.type == BleParser.MessageType.DONE) {
                Log.i(TAG, "[BLE] SERVE FINALIZADO");
            }
            mCommandQueueV2.onBleResponse(msg);
        }
        Log.i(TAG, "[BLE] Dado recebido: \"" + data + "\"");
        broadcastData(data);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUISITO 7 — Heartbeat PING/PONG
    // ═══════════════════════════════════════════════════════════════════════════

    private Runnable mHeartbeatTimeoutRunnableRef = null;

    /**
     * Inicia heartbeat: envia PING a cada 5s.
     * Se PONG não chegar em 10s: reconecta automaticamente.
     */
    private void iniciarHeartbeat() {
        pararHeartbeat();
        if (!ENABLE_HEARTBEAT) {
            Log.i(TAG, "[HEARTBEAT] Desabilitado - ESP32 nao suporta PING");
            return;
        }
        Log.d(TAG, "[HEARTBEAT] Iniciando heartbeat (interval=" + HEARTBEAT_INTERVAL_MS + "ms)");
        mHeartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (mState != State.READY) {
                    Log.d(TAG, "[HEARTBEAT] Estado não é READY — heartbeat pausado");
                    return;
                }
                if (!isBleStackPronto()) {
                    Log.e(TAG, "[HEARTBEAT] PING bloqueado — BLE não inicializado");
                    logBleHandles("heartbeat");
                    return;
                }
                Log.d(TAG, "[HEARTBEAT] Enviando PING");
                mWaitingPong = true;
                writeImediato(PING_COMMAND);

                // Timeout de resposta: 10s
                mHeartbeatTimeoutRunnableRef = () -> {
                    if (mWaitingPong && mState == State.READY) {
                        Log.e(TAG, "[HEARTBEAT] ✗ PONG não recebido em "
                                + HEARTBEAT_TIMEOUT_MS / 1000 + "s — reconectando");
                        mWaitingPong = false;
                        broadcastConnectionStatus("heartbeat_failed");
                        if (mBluetoothGatt != null) {
                            mBluetoothGatt.disconnect();
                        }
                    }
                };
                mMainHandler.postDelayed(mHeartbeatTimeoutRunnableRef, HEARTBEAT_TIMEOUT_MS);

                // Agenda próximo PING
                mMainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        };
        mMainHandler.postDelayed(mHeartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    private void pararHeartbeat() {
        if (mHeartbeatRunnable != null) {
            mMainHandler.removeCallbacks(mHeartbeatRunnable);
            mHeartbeatRunnable = null;
        }
        cancelarHeartbeatTimeout();
        mWaitingPong = false;
    }

    private void cancelarHeartbeatTimeout() {
        if (mHeartbeatTimeoutRunnableRef != null) {
            mMainHandler.removeCallbacks(mHeartbeatTimeoutRunnableRef);
            mHeartbeatTimeoutRunnableRef = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REQUISITO 6 — Fila de comandos (apenas 1 write por vez)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adiciona comando à fila e tenta enviar imediatamente se livre.
     * Thread-safe: deve ser chamado no main thread.
     */
    private void enqueueCommand(String command) {
        mMainHandler.post(() -> {
            mCommandQueue.add(command);
            Log.d(TAG, "[QUEUE] Enfileirado: \"" + command
                    + "\" | fila=" + mCommandQueue.size());
            drainCommandQueue();
        });
    }

    /**
     * Tenta enviar o próximo comando da fila.
     * Só envia se: estado=READY, não há write em andamento, fila não vazia.
     */
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
     * Uso interno — para comandos externos use {@link #write(String)}.
     */
    private void writeImediato(String data) {
        if (!isBleStackPronto()) {
            Log.e(TAG, "[WRITE] WRITE BLOQUEADO — BLE não inicializado: \"" + data + "\"");
            logBleHandles("writeImediato");
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

    public BleCommand enqueueServeCommand(int volumeMl, String sessionId) {
        if (mState != State.READY || !isBleStackPronto()) {
            Log.e(TAG, "[BLOCK] BLE não está READY");
            logBleHandles("enqueueServeCommand");
            return null;
        }
        if (mCommandQueueV2 == null) {
            Log.e(TAG, "[QUEUE_V2] indisponivel - nao foi possivel enfileirar SERVE");
            return null;
        }

        // AUTH_SESSION_ID removido junto com o PIN — gera um sessionId de fallback aleatório
        String safeSessionId = (sessionId != null && !sessionId.trim().isEmpty())
                ? sessionId.trim()
                : String.format(java.util.Locale.US, "%08X",
                        (int)(System.currentTimeMillis() & 0xFFFFFFFFL));

        BleCommand cmd = mCommandQueueV2.enqueueServe(volumeMl, safeSessionId);
        if (cmd == null) {
            Log.e(TAG, "[QUEUE_V2] enqueueServe() retornou null");
            return null;
        }

        Log.i(TAG, "[FLOW] Pagamento aprovado → liberando SERVE");
        Log.i(TAG, "[QUEUE_V2] currentCommand definido antes do envio -> id="
                + cmd.commandId + " | session=" + cmd.sessionId + " | volume=" + cmd.volumeMl);
        return cmd;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utilitários GATT
    // ═══════════════════════════════════════════════════════════════════════════

    /** Fecha o GATT e limpa referências. */
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

    /** Remove bond via reflexão (API não pública). */
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

    /**
     * Gera token JWT compatível com o servidor PHP (php/includes/jwt.php).
     * Implementação manual — evita JJWT que rejeita chaves < 256 bits.
     */
    private String gerarJwtToken() {
        try {
            long nowSec = System.currentTimeMillis() / 1000L;
            String headerJson  = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
            String jti = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
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
    // REQUISITO 14 — API Pública
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Conecta ao dispositivo ESP32 com o MAC especificado.
     * Se MAC diferente do atual: limpa MAC salvo e reinicia conexão.
     *
     * @param mac endereço MAC BLE do ESP32 (ex.: "AA:BB:CC:DD:EE:FF")
     */
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

    /**
     * Desconecta do ESP32 e desativa reconexão automática.
     */
    public void disconnect() {
        Log.i(TAG, "[API] disconnect()");
        mAutoReconnect = false;
        pararHeartbeat();
        pararReconexao();
        pararScan();
        cancelarAuthTimeout();
        if (mBluetoothGatt != null) {
            mBluetoothGatt.disconnect();
        }
        transitionTo(State.DISCONNECTED);
    }

    /**
     * Envia um comando ao ESP32 via fila de comandos.
     * O comando só é transmitido quando o estado for READY e não houver write em andamento.
     *
     * @param data string de comando (ex.: "$ML:300:CMD123:SES456")
     * @return true se o comando foi enfileirado; false se o serviço não está operacional
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

    /**
     * Retorna true se o serviço está no estado READY (autenticado e pronto para comandos).
     */
    public boolean isReady() {
        return mState == State.READY;
    }

    /**
     * ═══════════════════════════════════════════════════════════════════════════
     * GUARD-BAND — Sincronização com ESP32 (nova especificação)
     * ═══════════════════════════════════════════════════════════════════════════
     */

    /**
     * Retorna o tempo (em ms) depuis que READY foi recebido do ESP32.
     * Útil para logs de diagóstico e verificação de guard-band.
     *
     * @return Tempo em ms desde READY, ou -1 se READY nunca foi ativado.
     */
    public long getTimeSinceReady() {
        if (mReadyTimestamp == 0) {
            return -1; // READY nunca foi recebido
        }
        return System.currentTimeMillis() - mReadyTimestamp;
    }

    /**
     * Retorna true se READY foi recebido E o guard-band (800-1000ms) expirou.
     *
     * APP DEVE usar este método antes de enfileirar SERVE:
     *   ```
     *   if (mBluetoothService.isReadyWithGuardBand()) {
     *       // OK para enviar SERVE
     *   } else {
     *       // BLOQUEAR envio — ainda dentro do guard-band
     *   }
     *   ```
     *
     * Isto evita race conditions onde APP envia SERVE antes que ESP32
     * finalize sua janela de prontidão (ARM Cortex M4 @ 240MHz).
     *
     * @return true se isReady() E passou do guard-band, false caso contrário
     */
    public boolean isReadyWithGuardBand() {
        if (!isReady()) {
            return false; // Não está em READY
        }
        long timeSinceReady = getTimeSinceReady();
        if (timeSinceReady < 0) {
            return false; // READY nunca foi recebido (improvável, mas validar por segurança)
        }
        boolean guardBandExpired = timeSinceReady >= GUARD_BAND_MS;
        if (!guardBandExpired) {
            Log.w(TAG, "[GUARD-BAND] BLOQUEADO — " + (GUARD_BAND_MS - timeSinceReady)
                    + "ms faltando. Time since READY: " + timeSinceReady + "ms");
        }
        return guardBandExpired;
    }

    /**
     * Retorna o timestamp (ms desde epoch) quando READY foi recebido.
     * Zero = READY nunca foi ativado.
     */
    public long getReadyTimestamp() {
        return mReadyTimestamp;
    }

    /**
     * Retorna o guarding-band em ms (ou seja, quanto tempo APP deve aguardar após READY).
     */
    public long getGuardBandMs() {
        return GUARD_BAND_MS;
    }

    /**
     * Retorna o estado atual da máquina de estados BLE industrial.
     */
    public State getState() {
        return mState;
    }

    /**
     * Retorna o MAC do ESP32 atualmente configurado (pode ser null se nunca conectou).
     */
    public String getTargetMac() {
        return mTargetMac;
    }

    /**
     * Limpa o MAC salvo e reinicia o processo de scan.
     * Útil para trocar de dispositivo ESP32.
     */
    public void resetMac() {
        Log.i(TAG, "[API] resetMac() — limpando MAC salvo e reiniciando scan");
        mTargetMac = null;
        getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .edit().remove("esp32_mac").apply();
        disconnect();
        mAutoReconnect = true;
        iniciarScanComRetry();
    }

    /**
     * Retorna o tamanho atual da fila de comandos pendentes.
     */
    public int getQueueSize() {
        return mCommandQueue.size();
    }

    /**
     * Limpa todos os comandos pendentes na fila.
     */
    public void clearQueue() {
        int size = mCommandQueue.size();
        mCommandQueue.clear();
        mWriteBusy.set(false);
        Log.i(TAG, "[API] clearQueue() — " + size + " comandos descartados");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API de compatibilidade com BluetoothService (drop-in replacement)
    // Permite que Home, PagamentoConcluido, ServiceTools, ModificarTimeout e
    // CalibrarPulsos usem BluetoothServiceIndustrial sem alterar suas chamadas.
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Enum de compatibilidade com BluetoothService.BleState.
     * Mapeia os estados internos de BluetoothServiceIndustrial.
     */
    public enum BleState { DISCONNECTED, CONNECTED, READY }

    /**
     * Retorna o estado BLE no formato compatível com BluetoothService.BleState.
     * Usado por PagamentoConcluido.getBleState().
     */
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

    /**
     * Retorna true se o BLE está conectado (não desconectado).
     * Compatível com BluetoothService.connected().
     */
    public boolean connected() {
        return mBluetoothGatt != null && mState != State.DISCONNECTED;
    }

    /**
     * Força o estado para READY — usado por PagamentoConcluido após auth manual.
     * Compatível com BluetoothService.forceReady().
     */
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

    /**
     * Inicia ou para o scan BLE por dispositivos CHOPP_.
     * Compatível com BluetoothService.scanLeDevice(boolean).
     */
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

    /**
     * Habilita reconexão automática.
     * Compatível com BluetoothService.enableAutoReconnect().
     */
    public void enableAutoReconnect() {
        mAutoReconnect = true;
        mReconnectAttempts = 0;
        Log.i(TAG, "[COMPAT] enableAutoReconnect()");
    }

    /**
     * Retorna o dispositivo BLE atualmente conectado.
     * Compatível com BluetoothService.getBoundDevice().
     */
    public BluetoothDevice getBoundDevice() {
        return mBluetoothGatt != null ? mBluetoothGatt.getDevice() : null;
    }

    /**
     * Retorna CommandQueueManager (legado) — null nesta implementação.
     * Use getCommandQueueV2() para a fila tipada v2.3.
     * Compatível com BluetoothService.getCommandQueue().
     */
    public CommandQueueManager getCommandQueue() {
        // BluetoothServiceIndustrial não usa CommandQueueManager legado.
        // Retorna null para forçar uso de getCommandQueueV2().
        Log.w(TAG, "[COMPAT] getCommandQueue() chamado — use getCommandQueueV2()");
        return null;
    }

    /**
     * Retorna a CommandQueue v2.3 para enfileiramento de comandos SERVE.
     * Compatível com BluetoothService.getCommandQueueV2().
     */
    public CommandQueue getCommandQueueV2() {
        return mCommandQueueV2;
    }

    /**
     * Verifica se há conexão com a internet disponível.
     * Usado por Home e PagamentoConcluido para bloquear venda sem rede.
     */
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
