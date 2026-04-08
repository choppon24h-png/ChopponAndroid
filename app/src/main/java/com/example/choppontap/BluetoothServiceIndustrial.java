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
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BluetoothServiceIndustrial — Conexão Direta NUS com ESP32
 *
 * ESTRATÉGIA DEFINITIVA PARA status=133:
 *
 * O status=133 (GATT_ERROR) ocorre porque o Android tenta conectar via
 * connectGatt() mas o ESP32 não está visível no ar naquele momento.
 * O Android espera ~30s e devolve 133.
 *
 * SOLUÇÃO: Scan-Before-Connect
 *   1. Antes de chamar connectGatt(), faz um scan BLE curto (máx 8s)
 *      filtrando pelo MAC do ESP32.
 *   2. Só chama connectGatt() DEPOIS de confirmar que o ESP32 está
 *      em advertising (visível no ar).
 *   3. Se o scan não encontrar o ESP32 em 8s, aguarda e tenta novamente
 *      com backoff progressivo.
 *
 * Isso elimina o status=133 porque o connectGatt() só é chamado quando
 * o dispositivo já está confirmadamente visível.
 */
@SuppressLint("MissingPermission")
public class BluetoothServiceIndustrial extends Service {

    private static final String TAG = "BLE_INDUSTRIAL";

    // ─── Singleton ───────────────────────────────────────────────────────────
    private static volatile BluetoothServiceIndustrial sInstance;
    public static BluetoothServiceIndustrial getInstance() { return sInstance; }
    public static boolean isRunning() { return sInstance != null; }

    // ─── Estados ─────────────────────────────────────────────────────────────
    public enum State { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, READY, ERROR }
    private volatile State mState = State.DISCONNECTED;

    // ─── UUIDs NUS (Nordic UART Service) ─────────────────────────────────────
    private static final UUID NUS_SERVICE_UUID           = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_RX_CHARACTERISTIC_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_TX_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID CCCD_UUID                  = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ─── Ações de Broadcast ───────────────────────────────────────────────────
    public static final String ACTION_DATA_AVAILABLE    = "com.example.choppontap.ACTION_DATA_AVAILABLE";
    public static final String ACTION_CONNECTION_STATUS = "com.example.choppontap.ACTION_CONNECTION_STATUS";
    public static final String ACTION_BLE_STATE_CHANGED = "com.example.choppontap.ACTION_BLE_STATE_CHANGED";
    public static final String EXTRA_DATA               = "com.example.choppontap.EXTRA_DATA";
    public static final String EXTRA_STATUS             = "com.example.choppontap.EXTRA_STATUS";
    public static final String EXTRA_BLE_STATE          = "com.example.choppontap.EXTRA_BLE_STATE";
    public static final String ACTION_DEVICE_FOUND      = "com.example.choppontap.ACTION_DEVICE_FOUND";
    public static final String EXTRA_DEVICE             = "com.example.choppontap.EXTRA_DEVICE";
    public static final String ACTION_WRITE_READY       = "com.example.choppontap.ACTION_WRITE_READY";

    // ─── Foreground Service ───────────────────────────────────────────────────
    private static final String NOTIF_CHANNEL_ID = "ble_industrial_channel";
    private static final int    NOTIF_ID         = 1001;

    // ─── Parâmetros de Scan e Reconexão ──────────────────────────────────────
    /** Tempo máximo de scan para encontrar o ESP32 antes de desistir e reagendar */
    private static final long SCAN_TIMEOUT_MS = 8_000L;

    /** Delay antes de cada connectGatt() após o scan encontrar o dispositivo */
    private static final long PRE_CONNECT_DELAY_MS = 500L;

    /** Delays de reconexão com backoff progressivo (em ms) */
    private static final long[] RECONNECT_DELAYS_MS = { 2_000, 4_000, 8_000, 15_000, 30_000 };

    // ─── Variáveis de Controle ────────────────────────────────────────────────
    private final AtomicInteger mFailCount = new AtomicInteger(0);
    private volatile boolean mAutoReconnect = true;
    private volatile boolean mScanning = false;

    // ─── Variáveis de Instância ───────────────────────────────────────────────
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBleScanner;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothGattCharacteristic mWriteCharacteristic;
    private Handler mMainHandler;
    private String mTargetMac;
    private String mPendingCommand = null;

    // ─── Binder ───────────────────────────────────────────────────────────────
    public class LocalBinder extends Binder {
        public BluetoothServiceIndustrial getService() {
            return BluetoothServiceIndustrial.this;
        }
    }
    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de Vida do Serviço
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mMainHandler = new Handler(Looper.getMainLooper());
        Log.i(TAG, "[SERVICE] BluetoothServiceIndustrial iniciado (estratégia: scan-before-connect)");
        criarNotificacaoForeground();

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            mBluetoothAdapter = bm.getAdapter();
            if (mBluetoothAdapter != null) {
                mBleScanner = mBluetoothAdapter.getBluetoothLeScanner();
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "[SERVICE] SERVICE DESTROYED");
        mAutoReconnect = false;
        sInstance = null;
        mMainHandler.removeCallbacksAndMessages(null);
        pararScan();
        closeGatt();
        transitionTo(State.DISCONNECTED);
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API Pública
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inicia o processo de conexão com o MAC fornecido.
     * Usa a estratégia scan-before-connect para evitar status=133.
     */
    public void connectWithMac(String mac) {
        if (mac == null || mac.isEmpty() || mBluetoothAdapter == null) {
            Log.e(TAG, "[CONNECT] MAC inválido ou BluetoothAdapter nulo");
            return;
        }

        // Evita reconexão desnecessária se já está conectado ao mesmo MAC
        if ((mState == State.READY || mState == State.CONNECTED) && mac.equalsIgnoreCase(mTargetMac)) {
            Log.i(TAG, "[CONNECT] Já conectado ao MAC " + mac + " — ignorando chamada duplicada");
            return;
        }

        mTargetMac = mac.toUpperCase();
        mAutoReconnect = true;
        mFailCount.set(0);
        Log.i(TAG, "[CONNECT] Iniciando conexão ao MAC: " + mTargetMac);
        iniciarCicloConexao();
    }

    /**
     * Envia o comando de volume para o ESP32.
     * Exemplo: enviarVolume(300) envia "$ML:300" para a característica RX.
     */
    public void enviarVolume(int ml) {
        String comando = "$ML:" + ml;
        if (!write(comando)) {
            Log.e(TAG, "[TX] Falha ao enviar volume — estado=" + mState + " | cmd=" + comando);
        }
    }

    /**
     * Envia um comando genérico para o ESP32.
     * Retorna true se o envio foi aceito pelo stack BLE.
     */
    public boolean write(String command) {
        if (mState != State.READY || mWriteCharacteristic == null || mBluetoothGatt == null) {
            Log.w(TAG, "[TX] write() ignorado — estado=" + mState + " | cmd=" + command);
            return false;
        }

        Log.i(TAG, "[TX] Enviando: " + command);
        byte[] value = command.getBytes(StandardCharsets.UTF_8);
        mWriteCharacteristic.setValue(value);

        // Tenta WRITE_TYPE_NO_RESPONSE primeiro (sem ACK, mais rápido)
        mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        boolean ok = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);

        if (!ok) {
            // Fallback para WRITE_TYPE_DEFAULT (com ACK)
            Log.w(TAG, "[TX] NO_RESPONSE falhou, tentando DEFAULT");
            mWriteCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            ok = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
        }

        Log.d(TAG, "[TX] writeCharacteristic resultado: " + ok);
        return ok;
    }

    /** Envia comando de parada imediata */
    public void pararChopp() { write("$ML:0"); }

    /** Compatibilidade com PagamentoConcluido.java */
    public void enqueueServeCommand(int volumeMl, String checkoutId) {
        Log.i(TAG, "[SERVE] enqueueServeCommand | vol=" + volumeMl + " | estado=" + mState);
        if (isReady()) {
            write("$ML:" + volumeMl);
        } else {
            mPendingCommand = "$ML:" + volumeMl;
            Log.w(TAG, "[SERVE] BLE não pronto — comando pendente: " + mPendingCommand);
        }
    }

    // Getters de estado
    public State getState()    { return mState; }
    public boolean isReady()   { return mState == State.READY; }
    public boolean connected() { return mState == State.CONNECTED || mState == State.READY; }

    /** Desconecta e desabilita a reconexão automática */
    public void disconnect() {
        Log.i(TAG, "[CONNECT] disconnect() chamado — reconexão desabilitada");
        mAutoReconnect = false;
        mMainHandler.removeCallbacksAndMessages(null);
        pararScan();
        closeGatt();
        transitionTo(State.DISCONNECTED);
    }

    // Stubs de compatibilidade com código legado
    public void scanLeDevice(boolean enable) { /* No-op */ }
    public void enableAutoReconnect() {
        mAutoReconnect = true;
        Log.d(TAG, "[CONNECT] Auto-reconnect habilitado");
    }

    public void salvarMacExterno(String mac) {
        if (mac != null && !mac.isEmpty()) {
            mTargetMac = mac.toUpperCase();
            getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                    .edit().putString("esp32_mac", mac).apply();
            Log.i(TAG, "[CONNECT] MAC externo salvo: " + mac);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Estratégia Scan-Before-Connect
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inicia o ciclo completo: scan → connect → discover → ready.
     * Chamado na primeira conexão e em cada reconexão.
     */
    private void iniciarCicloConexao() {
        if (!mAutoReconnect || mTargetMac == null) return;

        pararScan();
        closeGatt();
        transitionTo(State.SCANNING);
        broadcastConnectionStatus("scanning");

        Log.i(TAG, "[SCAN] Iniciando scan para confirmar presença do ESP32: " + mTargetMac);
        iniciarScanParaMac(mTargetMac);
    }

    /**
     * Faz um scan BLE curto filtrando pelo MAC do ESP32.
     * Quando o dispositivo é encontrado, cancela o scan e chama connectGatt().
     * Se o timeout de 8s expirar sem encontrar, agenda nova tentativa com backoff.
     */
    private void iniciarScanParaMac(String mac) {
        if (mBleScanner == null) {
            Log.e(TAG, "[SCAN] BluetoothLeScanner nulo — tentando connectGatt direto");
            // Fallback: tenta conectar direto sem scan
            mMainHandler.postDelayed(() -> executarConnectGatt(), PRE_CONNECT_DELAY_MS);
            return;
        }

        mScanning = true;

        // Filtro por MAC para scan mais eficiente (não escaneia todos os dispositivos)
        ScanFilter filtro = new ScanFilter.Builder()
                .setDeviceAddress(mac)
                .build();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Máxima velocidade de detecção
                .build();

        try {
            mBleScanner.startScan(Collections.singletonList(filtro), settings, mScanCallback);
            Log.i(TAG, "[SCAN] Scan iniciado com filtro MAC=" + mac + " | timeout=" + SCAN_TIMEOUT_MS + "ms");
        } catch (Exception e) {
            Log.e(TAG, "[SCAN] Erro ao iniciar scan: " + e.getMessage());
            mScanning = false;
            // Fallback: tenta conectar direto
            mMainHandler.postDelayed(() -> executarConnectGatt(), PRE_CONNECT_DELAY_MS);
            return;
        }

        // Timeout do scan: se não encontrar em SCAN_TIMEOUT_MS, agenda reconexão
        mMainHandler.postDelayed(() -> {
            if (mScanning) {
                Log.w(TAG, "[SCAN] Timeout! ESP32 não encontrado em " + SCAN_TIMEOUT_MS + "ms — ESP32 pode não estar em advertising");
                pararScan();
                transitionTo(State.DISCONNECTED);
                broadcastConnectionStatus("disconnected:scan_timeout");
                agendarReconexao();
            }
        }, SCAN_TIMEOUT_MS);
    }

    /** Callback do scan BLE */
    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String mac = device.getAddress();
            int rssi = result.getRssi();

            Log.i(TAG, "[SCAN] ESP32 encontrado! MAC=" + mac + " | RSSI=" + rssi + "dBm");

            // Para o scan imediatamente — dispositivo confirmado no ar
            pararScan();

            // Aguarda PRE_CONNECT_DELAY_MS para garantir que o stack BLE
            // processou o resultado do scan antes de chamar connectGatt()
            mMainHandler.postDelayed(() -> executarConnectGatt(), PRE_CONNECT_DELAY_MS);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "[SCAN] Falha no scan: errorCode=" + errorCode);
            mScanning = false;
            mMainHandler.removeCallbacksAndMessages(null);

            // Tenta conectar direto como fallback
            mMainHandler.postDelayed(() -> executarConnectGatt(), PRE_CONNECT_DELAY_MS);
        }
    };

    /** Para o scan BLE se estiver ativo */
    private void pararScan() {
        if (mScanning && mBleScanner != null) {
            try {
                mBleScanner.stopScan(mScanCallback);
                Log.d(TAG, "[SCAN] Scan parado");
            } catch (Exception e) {
                Log.w(TAG, "[SCAN] Erro ao parar scan: " + e.getMessage());
            }
            mScanning = false;
        }
    }

    /**
     * Executa o connectGatt() após o scan confirmar que o ESP32 está visível.
     * Com o dispositivo confirmado em advertising, o status=133 não deve ocorrer.
     */
    private void executarConnectGatt() {
        if (!mAutoReconnect || mTargetMac == null || mBluetoothAdapter == null) return;

        try {
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mTargetMac);
            int tentativa = mFailCount.get() + 1;
            Log.i(TAG, "[CONNECT] connectGatt() | MAC=" + mTargetMac
                    + " | tentativa=" + tentativa
                    + " | autoConnect=false | TRANSPORT_LE");

            transitionTo(State.CONNECTING);
            broadcastConnectionStatus("connecting");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mBluetoothGatt = device.connectGatt(
                        BluetoothServiceIndustrial.this,
                        false,
                        mGattCallback,
                        BluetoothDevice.TRANSPORT_LE);
            } else {
                mBluetoothGatt = device.connectGatt(
                        BluetoothServiceIndustrial.this,
                        false,
                        mGattCallback);
            }

        } catch (Exception e) {
            Log.e(TAG, "[CONNECT] Exceção em connectGatt(): " + e.getMessage());
            transitionTo(State.DISCONNECTED);
            agendarReconexao();
        }
    }

    /**
     * Agenda nova tentativa de conexão com backoff progressivo.
     */
    private void agendarReconexao() {
        if (!mAutoReconnect || mTargetMac == null) return;

        int falhas = mFailCount.incrementAndGet();
        int idx = Math.min(falhas - 1, RECONNECT_DELAYS_MS.length - 1);
        long delay = RECONNECT_DELAYS_MS[idx];

        Log.w(TAG, "[RECONNECT] Falha #" + falhas + " — próxima tentativa (com scan) em " + delay + "ms");

        // Na 3ª falha, tenta limpar o cache GATT
        if (falhas == 3) {
            Log.w(TAG, "[RECONNECT] 3 falhas — tentando refresh do cache GATT");
            refreshGattCache();
        }

        mMainHandler.postDelayed(this::iniciarCicloConexao, delay);
    }

    /**
     * Limpa o cache GATT via reflexão (BluetoothGatt.refresh() é @hide).
     * Resolve casos onde o Android tem cache desatualizado do ESP32.
     */
    private void refreshGattCache() {
        if (mBluetoothGatt == null) return;
        try {
            Method refresh = mBluetoothGatt.getClass().getMethod("refresh");
            boolean result = (boolean) refresh.invoke(mBluetoothGatt);
            Log.i(TAG, "[GATT] refreshGattCache() resultado: " + result);
        } catch (Exception e) {
            Log.w(TAG, "[GATT] refreshGattCache() não disponível: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Callbacks GATT
    // ─────────────────────────────────────────────────────────────────────────

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.i(TAG, "[BLE] onConnectionStateChange | status=" + status
                    + " | newState=" + newState
                    + " | mac=" + (mTargetMac != null ? mTargetMac : "?"));

            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                // ✅ Conexão física estabelecida
                Log.i(TAG, "[BLE] CONECTADO! Resetando contador de falhas. Solicitando MTU...");
                mFailCount.set(0);
                transitionTo(State.CONNECTED);
                broadcastConnectionStatus("connected");
                // Solicita MTU maior para evitar fragmentação de pacotes NUS
                gatt.requestMtu(247);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // Desconexão limpa (solicitada pelo app ou pelo ESP32)
                    Log.i(TAG, "[BLE] Desconexão limpa (status=0).");
                    closeGatt();
                    transitionTo(State.DISCONNECTED);
                    broadcastConnectionStatus("disconnected");
                    if (mAutoReconnect) {
                        Log.i(TAG, "[RECONNECT] Desconexão limpa — reagendando ciclo em 2s");
                        mMainHandler.postDelayed(() -> iniciarCicloConexao(), 2_000);
                    }

                } else if (status == 133) {
                    // GATT_ERROR — não deveria ocorrer com scan-before-connect,
                    // mas tratamos como fallback de segurança
                    Log.w(TAG, "[BLE] GATT_ERROR (133) — ocorreu mesmo após scan. "
                            + "Possível race condition. Reagendando com scan.");
                    closeGatt();
                    transitionTo(State.DISCONNECTED);
                    broadcastConnectionStatus("disconnected:133");
                    agendarReconexao();

                } else if (status == 8) {
                    // GATT_CONN_TIMEOUT — conexão caiu por distância/interferência
                    Log.w(TAG, "[BLE] GATT_CONN_TIMEOUT (8) — conexão perdida. Reconectando...");
                    closeGatt();
                    transitionTo(State.DISCONNECTED);
                    broadcastConnectionStatus("disconnected");
                    if (mAutoReconnect) {
                        mMainHandler.postDelayed(() -> {
                            mFailCount.set(0);
                            iniciarCicloConexao();
                        }, 3_000);
                    }

                } else {
                    Log.w(TAG, "[BLE] Erro GATT: status=" + status + ". Reagendando...");
                    closeGatt();
                    transitionTo(State.ERROR);
                    broadcastConnectionStatus("error:" + status);
                    if (mAutoReconnect) agendarReconexao();
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "[BLE] MTU negociado: " + mtu + " bytes. Iniciando discoverServices()");
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[BLE] Falha ao descobrir serviços: status=" + status);
                agendarReconexao();
                return;
            }

            BluetoothGattService nusService = gatt.getService(NUS_SERVICE_UUID);
            if (nusService == null) {
                Log.e(TAG, "[BLE] Serviço NUS (6E400001) não encontrado! "
                        + "Verifique o firmware do ESP32.");
                refreshGattCache();
                agendarReconexao();
                return;
            }

            mWriteCharacteristic = nusService.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
            BluetoothGattCharacteristic notifyChar = nusService.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID);

            if (mWriteCharacteristic == null || notifyChar == null) {
                Log.e(TAG, "[BLE] Características RX/TX ausentes no serviço NUS!");
                agendarReconexao();
                return;
            }

            Log.i(TAG, "[BLE] Serviço NUS OK. RX e TX encontrados. Habilitando notificações...");
            habilitarNotificacoes(gatt, notifyChar);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "[BLE] Notificações habilitadas! ✅ Estado: READY");
                mMainHandler.post(() -> {
                    transitionTo(State.READY);
                    broadcastConnectionStatus("ready");

                    // Envia comando pendente se houver
                    if (mPendingCommand != null) {
                        Log.i(TAG, "[BLE] Enviando comando pendente: " + mPendingCommand);
                        write(mPendingCommand);
                        mPendingCommand = null;
                    }
                });
            } else {
                Log.e(TAG, "[BLE] Falha ao escrever CCCD: status=" + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] raw = characteristic.getValue();
            if (raw != null && raw.length > 0) {
                String data = new String(raw, StandardCharsets.UTF_8).trim();
                Log.i(TAG, "[RX] Recebido do ESP32: " + data);
                // Formato esperado: "VP:150" (volume parcial) ou "ML:300" (finalizado)
                broadcastData(data);
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Métodos Auxiliares
    // ─────────────────────────────────────────────────────────────────────────

    private void transitionTo(State newState) {
        if (mState == newState) return;
        Log.i(TAG, "=== STATE: " + mState.name() + " → " + newState.name() + " ===");
        mState = newState;
        broadcastBleState(newState);
        if (newState == State.DISCONNECTED || newState == State.ERROR) {
            mWriteCharacteristic = null;
        }
    }

    private void habilitarNotificacoes(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean ok = gatt.writeDescriptor(descriptor);
            Log.d(TAG, "[BLE] writeDescriptor(CCCD) resultado: " + ok);
        } else {
            Log.e(TAG, "[BLE] Descritor CCCD não encontrado na característica TX!");
        }
    }

    private void closeGatt() {
        if (mBluetoothGatt != null) {
            try {
                mBluetoothGatt.disconnect();
                mBluetoothGatt.close();
                Log.d(TAG, "[GATT] closeGatt() — GATT fechado");
            } catch (Exception e) {
                Log.e(TAG, "[GATT] Erro ao fechar GATT: " + e.getMessage());
            }
            mBluetoothGatt = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Foreground Service
    // ─────────────────────────────────────────────────────────────────────────

    private void criarNotificacaoForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID, "Chopp BLE", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Mantém a conexão com a chopeira ativa");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Intent notifIntent = new Intent(this, getClass());
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

    // ─────────────────────────────────────────────────────────────────────────
    // Broadcasts
    // ─────────────────────────────────────────────────────────────────────────

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

    private void broadcastBleState(State state) {
        Intent i = new Intent(ACTION_BLE_STATE_CHANGED);
        i.putExtra(EXTRA_BLE_STATE, state.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }
}
