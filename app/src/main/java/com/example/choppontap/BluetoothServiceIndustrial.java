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

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serviço BLE NUS Simplificado — Conexão Direta com ESP32
 *
 * Focado exclusivamente na função "Abrir Válvula → Monitorar Volume → Fechar Válvula".
 *
 * MECANISMO DE RECONEXÃO (status=133 / GATT_ERROR):
 * O Android retorna status=133 quando o ESP32 não responde ao pacote de conexão BLE
 * dentro do timeout de ~30 segundos. Isso ocorre porque:
 *   1. O ESP32 ainda não está em modo advertising após boot/reset
 *   2. O cache GATT do Android está corrompido com dados de sessão anterior
 *   3. O stack BLE do Android ficou em estado inconsistente
 *
 * A solução implementada usa três camadas:
 *   - Camada 1: Backoff progressivo (1s → 2s → 4s → 8s → 16s → 30s)
 *   - Camada 2: Refresh do cache GATT via reflexão após 3 falhas consecutivas
 *   - Camada 3: Delay de 600ms antes de cada connectGatt() para dar tempo ao stack BLE
 */
@SuppressLint("MissingPermission")
public class BluetoothServiceIndustrial extends Service {

    private static final String TAG = "BLE_INDUSTRIAL";

    // ─── Singleton ───────────────────────────────────────────────────────────
    private static volatile BluetoothServiceIndustrial sInstance;
    public static BluetoothServiceIndustrial getInstance() { return sInstance; }
    public static boolean isRunning() { return sInstance != null; }

    // ─── Estados ─────────────────────────────────────────────────────────────
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, READY, ERROR }
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

    // ─── Reconexão com Backoff Progressivo ───────────────────────────────────
    /**
     * Delays de reconexão em milissegundos.
     * Após cada falha com status=133, o próximo delay aumenta progressivamente.
     * Após esgotar todos os delays, mantém o último valor (30s) indefinidamente.
     */
    private static final long[] RECONNECT_DELAYS_MS = { 1_000, 2_000, 4_000, 8_000, 16_000, 30_000 };

    /**
     * Número de falhas consecutivas com status=133.
     * Resetado para 0 quando a conexão é bem-sucedida.
     */
    private final AtomicInteger mFailCount = new AtomicInteger(0);

    /**
     * Indica se a reconexão automática está ativa.
     * Setado como false apenas quando disconnect() é chamado explicitamente.
     */
    private volatile boolean mAutoReconnect = true;

    /**
     * Delay antes de cada connectGatt() para dar tempo ao stack BLE do Android
     * de liberar recursos da tentativa anterior. Sem esse delay, o status=133
     * ocorre imediatamente na tentativa seguinte.
     */
    private static final long PRE_CONNECT_DELAY_MS = 600L;

    // ─── Variáveis de Instância ───────────────────────────────────────────────
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothGattCharacteristic mWriteCharacteristic;
    private Handler mMainHandler;
    private String mTargetMac;

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
        Log.i(TAG, "[SERVICE] BluetoothServiceIndustrial iniciado (com reconexão robusta)");
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
        mAutoReconnect = false;
        sInstance = null;
        mMainHandler.removeCallbacksAndMessages(null);
        closeGatt();
        transitionTo(State.DISCONNECTED);
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API Pública
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Conecta diretamente ao MAC fornecido, sem scan.
     * Habilita a reconexão automática com backoff progressivo.
     */
    public void connectWithMac(String mac) {
        if (mac == null || mac.isEmpty() || mBluetoothAdapter == null) {
            Log.e(TAG, "[CONNECT] MAC inválido ou BluetoothAdapter nulo");
            return;
        }

        mTargetMac = mac;
        mAutoReconnect = true;
        mFailCount.set(0);
        Log.i(TAG, "[CONNECT] Conectando diretamente ao MAC: " + mac);
        iniciarConexao();
    }

    /**
     * Envia o comando de volume para o ESP32.
     * Exemplo: enviarVolume(300) envia "$ML:300" para a característica RX.
     */
    public void enviarVolume(int ml) {
        String comando = "$ML:" + ml;
        if (!write(comando)) {
            Log.e(TAG, "[TX] Falha ao enviar volume — BLE não está READY. Comando: " + comando);
        }
    }

    /**
     * Envia um comando genérico para o ESP32.
     * Retorna true se o envio foi aceito pelo stack BLE.
     */
    public boolean write(String command) {
        if (mState != State.READY || mWriteCharacteristic == null || mBluetoothGatt == null) {
            Log.w(TAG, "[TX] write() ignorado — estado=" + mState + " | comando=" + command);
            return false;
        }

        Log.i(TAG, "[TX] Enviando: " + command);
        byte[] value = command.getBytes(StandardCharsets.UTF_8);
        mWriteCharacteristic.setValue(value);

        // Tenta WRITE_TYPE_NO_RESPONSE primeiro (mais rápido, sem ACK)
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

    /**
     * Envia comando de parada imediata (cancela a tiragem em andamento).
     */
    public void pararChopp() {
        write("$ML:0");
    }

    /**
     * Compatibilidade com PagamentoConcluido.java.
     * Enfileira o comando $ML:volume. Se não estiver READY, armazena como pendente.
     */
    public void enqueueServeCommand(int volumeMl, String checkoutId) {
        Log.i(TAG, "[SERVE] enqueueServeCommand | vol=" + volumeMl + " | estado=" + mState);
        if (isReady()) {
            write("$ML:" + volumeMl);
        } else {
            Log.w(TAG, "[SERVE] BLE não pronto — comando pendente: $ML:" + volumeMl);
            mPendingCommand = "$ML:" + volumeMl;
        }
    }

    // Comando pendente para envio quando BLE atingir READY
    private String mPendingCommand = null;

    // Getters de estado
    public State getState()    { return mState; }
    public boolean isReady()   { return mState == State.READY; }
    public boolean connected() { return mState == State.CONNECTED || mState == State.READY; }

    /**
     * Desconecta e desabilita a reconexão automática.
     */
    public void disconnect() {
        Log.i(TAG, "[CONNECT] disconnect() chamado — reconexão desabilitada");
        mAutoReconnect = false;
        mMainHandler.removeCallbacksAndMessages(null);
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
            mTargetMac = mac;
            getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                    .edit().putString("esp32_mac", mac).apply();
            Log.i(TAG, "[CONNECT] MAC externo salvo: " + mac);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lógica de Conexão e Reconexão
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inicia a conexão com um delay de PRE_CONNECT_DELAY_MS para garantir que
     * o stack BLE do Android liberou recursos da tentativa anterior.
     */
    private void iniciarConexao() {
        if (!mAutoReconnect || mTargetMac == null || mBluetoothAdapter == null) return;

        transitionTo(State.CONNECTING);
        broadcastConnectionStatus("connecting");

        mMainHandler.postDelayed(() -> {
            if (!mAutoReconnect) return;

            try {
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mTargetMac);
                Log.i(TAG, "[CONNECT] connectGatt() | MAC=" + mTargetMac
                        + " | tentativa=" + (mFailCount.get() + 1)
                        + " | autoConnect=false | TRANSPORT_LE");

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
                agendarReconexao();
            }
        }, PRE_CONNECT_DELAY_MS);
    }

    /**
     * Agenda a próxima tentativa de reconexão com backoff progressivo.
     *
     * Tabela de delays:
     *   Falha 1 →  1s
     *   Falha 2 →  2s
     *   Falha 3 →  4s  (+ refresh do cache GATT)
     *   Falha 4 →  8s
     *   Falha 5 → 16s
     *   Falha 6+ → 30s
     */
    private void agendarReconexao() {
        if (!mAutoReconnect || mTargetMac == null) return;

        int falhas = mFailCount.incrementAndGet();
        int idx = Math.min(falhas - 1, RECONNECT_DELAYS_MS.length - 1);
        long delay = RECONNECT_DELAYS_MS[idx];

        Log.w(TAG, "[RECONNECT] Falha #" + falhas + " — próxima tentativa em " + delay + "ms");

        // A partir da 3ª falha consecutiva, tenta limpar o cache GATT
        // O cache corrompido é uma das causas mais comuns do status=133
        if (falhas == 3) {
            Log.w(TAG, "[RECONNECT] 3 falhas consecutivas — tentando refresh do cache GATT");
            refreshGattCache();
        }

        mMainHandler.postDelayed(this::iniciarConexao, delay);
    }

    /**
     * Limpa o cache GATT do Android via reflexão.
     *
     * O Android mantém um cache dos serviços GATT descobertos anteriormente.
     * Se o ESP32 reiniciou ou mudou seus serviços, o cache fica desatualizado
     * e causa falhas de conexão (status=133 ou serviços não encontrados).
     *
     * BluetoothGatt.refresh() é um método oculto (@hide) não exposto na API pública,
     * por isso é acessado via reflexão. Funciona em Android 4.3+ (API 18+).
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
                // ✅ Conexão estabelecida com sucesso
                Log.i(TAG, "[BLE] CONECTADO com sucesso! Resetando contador de falhas.");
                mFailCount.set(0);
                transitionTo(State.CONNECTED);
                broadcastConnectionStatus("connected");

                // Solicita MTU maior para evitar fragmentação de pacotes NUS
                gatt.requestMtu(247);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // ❌ Desconectado — analisa o status para decidir a ação

                if (status == 0) {
                    // Desconexão limpa (solicitada pelo app ou pelo ESP32)
                    Log.i(TAG, "[BLE] Desconexão limpa (status=0).");
                    closeGatt();
                    transitionTo(State.DISCONNECTED);
                    broadcastConnectionStatus("disconnected");

                    // Reconecta automaticamente se não foi um disconnect() explícito
                    if (mAutoReconnect && mTargetMac != null) {
                        Log.i(TAG, "[RECONNECT] Desconexão limpa — reagendando conexão em 2s");
                        mMainHandler.postDelayed(() -> iniciarConexao(), 2_000);
                    }

                } else if (status == 133) {
                    // GATT_ERROR (133) — timeout de conexão ou stack BLE inconsistente
                    // Este é o erro mais comum ao conectar com ESP32
                    Log.w(TAG, "[BLE] GATT_ERROR (133) — ESP32 não respondeu ao pacote de conexão.");
                    Log.w(TAG, "[BLE] Causas prováveis: ESP32 não está em advertising, "
                            + "já tem conexão ativa, ou cache GATT corrompido.");
                    closeGatt();
                    transitionTo(State.DISCONNECTED);
                    broadcastConnectionStatus("disconnected:133");

                    // Agenda reconexão com backoff progressivo
                    agendarReconexao();

                } else if (status == 8) {
                    // GATT_CONN_TIMEOUT — timeout de supervisão (conexão caiu por distância)
                    Log.w(TAG, "[BLE] GATT_CONN_TIMEOUT (8) — conexão perdida por timeout de supervisão.");
                    closeGatt();
                    transitionTo(State.DISCONNECTED);
                    broadcastConnectionStatus("disconnected");

                    if (mAutoReconnect) {
                        Log.i(TAG, "[RECONNECT] Timeout de supervisão — reconectando em 3s");
                        mMainHandler.postDelayed(() -> {
                            mFailCount.set(0); // Timeout não é falha de cache, reseta contador
                            iniciarConexao();
                        }, 3_000);
                    }

                } else {
                    // Outros erros GATT
                    Log.w(TAG, "[BLE] Erro GATT desconhecido: status=" + status);
                    closeGatt();
                    transitionTo(State.ERROR);
                    broadcastConnectionStatus("error:" + status);

                    if (mAutoReconnect) {
                        agendarReconexao();
                    }
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
                        + "Verifique se o ESP32 está com o firmware NUS correto.");
                // Tenta refresh do cache e reconecta — pode ser cache desatualizado
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

            Log.i(TAG, "[BLE] Serviço NUS encontrado. Habilitando notificações no TX...");
            habilitarNotificacoes(gatt, notifyChar);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "[BLE] Notificações habilitadas! Estado: READY");
                mMainHandler.post(() -> {
                    transitionTo(State.READY);
                    broadcastConnectionStatus("ready");

                    // Envia comando pendente se houver (ex: $ML:300 que chegou antes do READY)
                    if (mPendingCommand != null) {
                        Log.i(TAG, "[BLE] Enviando comando pendente: " + mPendingCommand);
                        write(mPendingCommand);
                        mPendingCommand = null;
                    }
                });
            } else {
                Log.e(TAG, "[BLE] Falha ao escrever descritor CCCD: status=" + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] raw = characteristic.getValue();
            if (raw != null && raw.length > 0) {
                String data = new String(raw, StandardCharsets.UTF_8).trim();
                Log.i(TAG, "[RX] Recebido do ESP32: " + data);

                // Repassa para a Activity via broadcast
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
