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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BluetoothServiceIndustrial v4.0 - Conexão direta por MAC
 *
 * ESTRATÉGIA DE CONEXÃO (3 camadas, em ordem de prioridade):
 *
 *   Camada 1 — CONEXÃO DIRETA (sem scan):
 *     BluetoothAdapter.getRemoteDevice(mac) + connectGatt()
 *     Tempo esperado: ~1-2s
 *     Usada: sempre que o MAC é conhecido (primeira tentativa e reconexões)
 *
 *   Camada 2 — SCAN DIRECIONADO (fallback após 2 falhas diretas):
 *     Scan com ScanFilter por MAC exato + nome CHOPP_XXXX
 *     Tempo esperado: ~3-8s
 *     Usada: quando a conexão direta falha (ex: ESP32 reiniciou, cache GATT inválido)
 *
 *   Camada 3 — SCAN PERMISSIVO (fallback após 4 falhas):
 *     Scan sem filtro, aceita qualquer dispositivo com prefixo "CHOPP_"
 *     Tempo esperado: ~5-15s
 *     Usada: quando o MAC do ESP32 mudou (ex: troca de hardware)
 *
 * Protocolo: Nordic UART Service (NUS) - Just Works (sem PIN, sem bond)
 * UUIDs: 6E400001/2/3-B5A3-F393-E0A9-E50E24DCCA9E
 *
 * Broadcasts emitidos:
 *   BLE_STATUS_ACTION  com extra "status": scanning / connected / ready / disconnected:<motivo>
 *   BLE_DATA_ACTION    com extra "data":   string recebida do ESP32
 */
@SuppressLint("MissingPermission")
public class BluetoothServiceIndustrial extends Service {

    // -------------------------------------------------------------------------
    // Constantes públicas
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
    // UUIDs (Nordic UART Service)
    // -------------------------------------------------------------------------
    private static final UUID UUID_SERVICE = UUID.fromString(BleConfigUtils.SERVICE_UUID);
    private static final UUID UUID_RX      = UUID.fromString(BleConfigUtils.CHARACTERISTIC_UUID_RX);
    private static final UUID UUID_TX      = UUID.fromString(BleConfigUtils.CHARACTERISTIC_UUID_TX);
    private static final UUID UUID_CCCD    = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    // -------------------------------------------------------------------------
    // Estado interno
    // -------------------------------------------------------------------------
    private enum State { IDLE, CONNECTING_DIRECT, SCANNING, CONNECTING_GATT, CONNECTED, READY }
    private volatile State mState = State.IDLE;

    private BluetoothAdapter            mAdapter;
    private BluetoothLeScanner          mScanner;
    private BluetoothGatt               mGatt;
    private BluetoothGattCharacteristic mRxChar;
    private BluetoothGattCharacteristic mTxChar;

    private String mTargetMac;
    private String mTargetWifiMac;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // Reconexão com backoff exponencial
    // MAX_RECONNECT = Integer.MAX_VALUE: reconecta indefinidamente após queda de energia da ESP32
    // sem precisar reiniciar o tablet.
    private int    mReconnectCount = 0;
    private static final int    MAX_RECONNECT = Integer.MAX_VALUE;
    // Backoff: direto(1s), direto(2s), scan(4s), scan(5s), permissivo(5s)...
    // Limitado a 5s máximo para garantir reconexão rápida após queda de energia.
    private static final long[] BACKOFF_MS    = {1000, 2000, 4000, 5000, 5000, 5000, 5000, 5000, 5000, 5000};

    // Timeout para conexão direta (sem scan) — se o GATT não conectar em 5s, vai para scan
    private static final long DIRECT_CONNECT_TIMEOUT_MS = 5000L;
    private final Runnable mDirectConnectTimeoutRunnable = this::onDirectConnectTimeout;

    // Timeout para scan
    private final Runnable mScanTimeoutRunnable = this::onScanTimeout;

    // PING keepalive (a cada 5s em estado READY)
    private static final long PING_INTERVAL_MS = 5000L;
    private final Runnable mPingRunnable        = this::sendPing;

    // Flag para suspender PING durante dispensação de chopp
    private volatile boolean mDispensandoChopp = false;

    // ── SEGURANÇA v5.5: Lock de ciclo ativo no nível do serviço BLE ──────────
    // Impede que múltiplos $ML: sejam enviados ao ESP32 enquanto um ciclo
    // de dispensação está em andamento. Resetado quando:
    //   - FN: é recebido pelo PagamentoConcluido (via resetDispenseCycle())
    //   - disconnect() é chamado
    //   - watchdog dispara no PagamentoConcluido
    private volatile boolean mCycleActive = false;

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
            Log.i(TAG, "[SERVICE] BluetoothServiceIndustrial v4.0 DIRETO iniciado");
            Log.i(TAG, "[SERVICE] Protocolo: Nordic UART Service (NUS)");
            Log.i(TAG, "[SERVICE] Estrategia: MAC direto → scan direcionado → scan permissivo");
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
    // API pública
    // =========================================================================

    /**
     * Inicia conexão BLE usando MAC direto como estratégia primária.
     * Scan é usado apenas como fallback após falhas na conexão direta.
     *
     * @param mac     MAC BLE do dispositivo (ex: "48:F6:EE:23:2A:6C")
     * @param wifiMac MAC WiFi — usado para derivar o nome BLE esperado no scan de fallback.
     *                Se null, usa mac como referência.
     */
    public void connectWithMac(String mac, String wifiMac) {
        mTargetMac     = mac;
        mTargetWifiMac = wifiMac != null ? wifiMac : mac;
        Log.i(TAG, "[CONNECT] connectWithMac(" + mac + ")");
        Log.i(TAG, "[CONNECT] Estrategia primaria: CONEXAO DIRETA por MAC (sem scan)");
        mReconnectCount = 0;
        startDirectConnect();
    }

    /** Sobrecarga de compatibilidade — quando só há um MAC (BLE = WiFi). */
    public void connectWithMac(String mac) {
        connectWithMac(mac, mac);
    }

    /** Envia um comando para o ESP32 via característica RX. */
    public boolean sendCommand(String command) {
        if (mState != State.READY && mState != State.CONNECTED) {
            Log.w(TAG, "[CMD] Ignorado (estado=" + mState + "): " + command);
            return false;
        }
        if (mRxChar == null || mGatt == null) {
            Log.w(TAG, "[CMD] RxChar ou GATT nulo");
            return false;
        }
        // ── SEGURANÇA v5.5: Bloqueia $ML: duplicado se ciclo já ativo ────────────────────
        // $TO:0 (kill-switch de emergência) SEMPRE passa, mesmo com ciclo ativo.
        if (command.startsWith(BleCommand.CMD_ML) && !command.equals(BleCommand.CMD_ML + ":0")) {
            if (mCycleActive) {
                Log.e(TAG, "[SEGURANCA-BLE] sendCommand BLOQUEADO — mCycleActive=true. "
                        + "Rejeitando " + command + " (ciclo já em andamento no ESP32)");
                return false;
            }
            // Marca ciclo como ativo ao enviar $ML:
            mCycleActive = true;
            Log.i(TAG, "[SEGURANCA-BLE] Ciclo ativado — mCycleActive=true");
        }
        // Suspende PING durante dispensação ($ML, $LB, $RS)
        if (command.startsWith(BleCommand.CMD_ML) && !command.equals(BleCommand.CMD_ML + "0")) {
            pausarPing();
        } else if (command.equals(BleCommand.CMD_LB)) {
            pausarPing();
        } else if (command.equals(BleCommand.CMD_RS)) {
            pausarPing();
        }
        byte[] bytes = (command + "\n").getBytes(StandardCharsets.UTF_8);
        boolean ok;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int result = mGatt.writeCharacteristic(
                    mRxChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            ok = (result == BluetoothGatt.GATT_SUCCESS);
        } else {
            mRxChar.setValue(bytes);
            mRxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            ok = mGatt.writeCharacteristic(mRxChar);
        }
        Log.d(TAG, "[CMD] Enviado=" + ok + " cmd=" + command.trim());
        return ok;
    }

    /** Suspende o keepalive PING durante a dispensação de chopp. */
    public void pausarPing() {
        if (!mDispensandoChopp) {
            mDispensandoChopp = true;
            mHandler.removeCallbacks(mPingRunnable);
            Log.i(TAG, "[PING] PING suspenso durante dispensação de chopp");
        }
    }

    /** Retoma o keepalive PING após o fim do ciclo de dispensação. */
    public void retomarPing() {
        if (mDispensandoChopp) {
            mDispensandoChopp = false;
            if (mState == State.READY) {
                mHandler.removeCallbacks(mPingRunnable);
                mHandler.postDelayed(mPingRunnable, PING_INTERVAL_MS);
                Log.i(TAG, "[PING] PING retomado após fim da dispensação");
            }
        }
    }

    /**
     * SEGURANÇA v5.5: Reseta o lock de ciclo ativo no nível do serviço BLE.
     * Chamado pelo PagamentoConcluido quando:
     *   - FN: é recebido (ciclo encerrado pelo ESP32)
     *   - Watchdog dispara (timeout sem fluxo)
     *   - Erro de envio (sendCommand falhou)
     * Permite que o próximo $ML: seja aceito normalmente.
     */
    public void resetDispenseCycle() {
        if (mCycleActive) {
            mCycleActive = false;
            Log.i(TAG, "[SEGURANCA-BLE] Ciclo resetado — mCycleActive=false");
        }
    }

    /** Desconecta e para de reconectar. */
    public void disconnect(boolean stopReconnect) {
        if (stopReconnect) {
            mReconnectCount = MAX_RECONNECT;
            mHandler.removeCallbacks(mPingRunnable);
            mHandler.removeCallbacks(mScanTimeoutRunnable);
            mHandler.removeCallbacks(mDirectConnectTimeoutRunnable);
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
        // v5.5: Reseta o lock de ciclo ao desconectar para não bloquear a reconexão
        mCycleActive = false;
    }

    public String getCurrentStatus() {
        return mState.name().toLowerCase();
    }

    // =========================================================================
    // CAMADA 1: Conexão direta por MAC (sem scan) — estratégia primária
    // =========================================================================

    /**
     * Tenta conectar diretamente ao ESP32 usando o MAC conhecido.
     * BluetoothAdapter.getRemoteDevice(mac) retorna um BluetoothDevice sem precisar
     * fazer scan — o Android usa o cache interno de dispositivos BLE já vistos.
     * Tempo típico de conexão: 1-2 segundos.
     */
    private void startDirectConnect() {
        if (mAdapter == null || !mAdapter.isEnabled()) {
            scheduleReconnect("adapter_off");
            return;
        }
        if (mTargetMac == null || mTargetMac.isEmpty()) {
            Log.e(TAG, "[DIRECT] MAC alvo nulo — impossivel conectar diretamente");
            scheduleReconnect("mac_null");
            return;
        }

        try {
            Log.i(TAG, "[DIRECT] Tentando conexao direta ao MAC: " + mTargetMac
                    + " (tentativa " + (mReconnectCount + 1) + ")");
            mState = State.CONNECTING_DIRECT;
            broadcastStatus(STATUS_SCANNING); // UI mostra "conectando..."

            BluetoothDevice device = mAdapter.getRemoteDevice(mTargetMac);

            // Fecha GATT residual de sessão anterior
            if (mGatt != null) {
                try { mGatt.close(); } catch (Exception ignored) {}
                mGatt = null;
            }

            // Timeout de segurança: se o GATT não conectar em 5s, vai para scan
            mHandler.postDelayed(mDirectConnectTimeoutRunnable, DIRECT_CONNECT_TIMEOUT_MS);

            mGatt = device.connectGatt(this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
            Log.d(TAG, "[DIRECT] connectGatt() chamado — aguardando onConnectionStateChange...");

        } catch (IllegalArgumentException e) {
            Log.e(TAG, "[DIRECT] MAC invalido: " + mTargetMac + " — " + e.getMessage());
            scheduleReconnect("invalid_mac");
        } catch (Exception e) {
            Log.e(TAG, "[DIRECT] Excecao ao conectar diretamente: " + e.getMessage());
            scheduleReconnect("direct_exception");
        }
    }

    private void onDirectConnectTimeout() {
        if (mState != State.CONNECTING_DIRECT) return;
        Log.w(TAG, "[DIRECT] Timeout de " + DIRECT_CONNECT_TIMEOUT_MS + "ms — ESP32 nao respondeu");
        Log.w(TAG, "[DIRECT] Fechando GATT e escalando para scan direcionado");
        if (mGatt != null) {
            try { mGatt.close(); } catch (Exception ignored) {}
            mGatt = null;
        }
        mState = State.IDLE;
        scheduleReconnect("direct_timeout");
    }

    // =========================================================================
    // CAMADA 2 e 3: Scan BLE — fallback quando conexão direta falha
    // =========================================================================

    /**
     * Inicia scan BLE como fallback.
     * - Camada 2 (mReconnectCount < 4): scan com filtro por MAC + nome CHOPP_XXXX
     * - Camada 3 (mReconnectCount >= 4): scan permissivo, aceita qualquer CHOPP_
     */
    private void startScanCycle() {
        stopScan();
        if (mAdapter == null || !mAdapter.isEnabled()) return;

        mScanner = mAdapter.getBluetoothLeScanner();
        if (mScanner == null) {
            Log.e(TAG, "[SCAN] BluetoothLeScanner nulo");
            scheduleReconnect("scanner_null");
            return;
        }

        boolean permissive = mReconnectCount >= 4;

        if (!permissive) {
            Log.i(TAG, "[SCAN] Fallback camada 2: scan direcionado por MAC=" + mTargetMac);
        } else {
            Log.w(TAG, "[SCAN] Fallback camada 3: scan permissivo (prefixo CHOPP_) apos "
                    + mReconnectCount + " falhas");
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        List<ScanFilter> filters = new ArrayList<>();

        // Camada 2: adiciona filtro por MAC para scan mais rápido e preciso
        if (!permissive && mTargetMac != null && !mTargetMac.isEmpty()) {
            try {
                ScanFilter macFilter = new ScanFilter.Builder()
                        .setDeviceAddress(mTargetMac)
                        .build();
                filters.add(macFilter);
                Log.d(TAG, "[SCAN] Filtro por MAC adicionado: " + mTargetMac);
            } catch (Exception e) {
                Log.w(TAG, "[SCAN] Nao foi possivel adicionar filtro por MAC: " + e.getMessage());
                // Continua sem filtro — scan vai funcionar, só mais lento
            }
        }

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
        Log.w(TAG, "[SCAN] Timeout — nenhum dispositivo encontrado");
        stopScan();
        mState = State.IDLE;
        scheduleReconnect("scan_timeout");
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            ScanRecord     record  = result.getScanRecord();
            String deviceName = (record != null) ? record.getDeviceName() : null;
            if (deviceName == null) deviceName = device.getName();

            // Só processa dispositivos CHOPP_
            if (!BleConfigUtils.isChoppDevice(deviceName)) return;

            Log.d(TAG, "[SCAN] Encontrado: " + deviceName + " | MAC=" + device.getAddress());

            boolean macMatch    = device.getAddress().equalsIgnoreCase(mTargetMac);
            boolean nameMatch   = BleConfigUtils.matchesBleNameForMac(deviceName, mTargetWifiMac);
            boolean permissive  = mReconnectCount >= 4;

            if (macMatch || nameMatch || permissive) {
                // Guard: evita múltiplas conexões GATT
                if (mState != State.SCANNING) return;
                mState = State.CONNECTING_GATT;

                if (!macMatch && !nameMatch) {
                    Log.w(TAG, "[SCAN] Aceitando " + deviceName
                            + " por scan permissivo apos " + mReconnectCount + " falhas");
                } else {
                    Log.i(TAG, "[SCAN] Dispositivo alvo encontrado: " + deviceName
                            + " | MAC=" + device.getAddress());
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
    // Conexão GATT — compartilhada entre conexão direta e scan
    // =========================================================================

    private void connectGatt(BluetoothDevice device) {
        Log.i(TAG, "[GATT] Conectando a " + device.getAddress());
        if (mGatt != null) {
            try { mGatt.close(); } catch (Exception ignored) {}
            mGatt = null;
        }
        mState = State.CONNECTING_GATT;
        mGatt = device.connectGatt(this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                // Cancela o timeout de conexão direta — conexão bem-sucedida
                mHandler.removeCallbacks(mDirectConnectTimeoutRunnable);

                Log.i(TAG, "[GATT] Conectado (via "
                        + (mState == State.CONNECTING_DIRECT ? "MAC direto" : "scan") + ")");
                mState = State.CONNECTED;
                broadcastStatus(STATUS_CONNECTED);
                gatt.requestMtu(BleConfigUtils.MTU_REQUESTED);

            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                // Cancela timeout de conexão direta se ainda estiver pendente
                mHandler.removeCallbacks(mDirectConnectTimeoutRunnable);

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

            gatt.setCharacteristicNotification(mTxChar, true);
            BluetoothGattDescriptor descriptor = mTxChar.getDescriptor(UUID_CCCD);
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                }
                Log.d(TAG, "[GATT] Habilitando notificacoes TX (API=" + Build.VERSION.SDK_INT + ")");
            } else {
                Log.w(TAG, "[GATT] Descriptor 0x2902 nao encontrado - prosseguindo sem ele");
                onReady();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.i(TAG, "[GATT] onDescriptorWrite status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onReady();
            } else {
                Log.e(TAG, "[GATT] Falha ao escrever descriptor: " + status);
                gatt.disconnect();
            }
        }

        // Android 12 e anteriores
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            processarNotificacaoBLE(characteristic.getValue());
        }

        // Android 13+ (API 33+)
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            processarNotificacaoBLE(value);
        }

        private void processarNotificacaoBLE(byte[] data) {
            if (data == null) return;
            String msg = new String(data, StandardCharsets.UTF_8).trim();
            Log.d(TAG, "[RX] Recebido: " + msg);
            BleCommand.Response r = BleCommand.parse(msg);
            if (r.isPong()) {
                Log.d(TAG, "[PING] PONG recebido - sessao valida");
            }
            // ── SEGURANÇA v5.9: reset automático do mCycleActive ao receber FN: ──────
            // Garante que o próximo $ML: não seja bloqueado mesmo que o PagamentoConcluido
            // tenha sido destruído/recriado e não tenha chamado resetDispenseCycle().
            // Sem isso: nova instância de PagamentoConcluido envia $ML: mas é bloqueada
            // por mCycleActive=true do ciclo anterior → app trava em "Continuar servindo".
            if ("FN:".equals(msg) || "FN".equals(msg)) {
                if (mCycleActive) {
                    mCycleActive = false;
                    Log.i(TAG, "[SEGURANCA-BLE] FN: recebido — mCycleActive resetado automaticamente");
                }
                retomarPing();
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
        if (mDispensandoChopp) {
            Log.d(TAG, "[PING] PING suprimido — dispensação em andamento");
            return;
        }
        sendCommand(BleCommand.buildPing());
        mHandler.postDelayed(mPingRunnable, PING_INTERVAL_MS);
    }

    // =========================================================================
    // Reconexão com backoff exponencial e escalonamento de estratégia
    // =========================================================================

    private void scheduleReconnect(String reason) {
        // MAX_RECONNECT = Integer.MAX_VALUE: nunca desiste — reconecta indefinidamente
        // após queda de energia da ESP32 sem precisar reiniciar o tablet.

        long delay = BACKOFF_MS[Math.min(mReconnectCount, BACKOFF_MS.length - 1)];
        mReconnectCount++;

        // Determina qual estratégia será usada na próxima tentativa
        String estrategia;
        if (mReconnectCount <= 2) {
            estrategia = "MAC direto";
        } else if (mReconnectCount <= 4) {
            estrategia = "scan direcionado";
        } else {
            estrategia = "scan permissivo";
        }

        Log.w(TAG, "[RECONNECT] Falha #" + mReconnectCount
                + " (" + reason + ") — proxima tentativa [" + estrategia + "] em " + delay + "ms");

        if (mReconnectCount == 3) {
            Log.w(TAG, "[RECONNECT] Escalando para scan direcionado por MAC");
            refreshGattCache();
        } else if (mReconnectCount == 5) {
            Log.w(TAG, "[RECONNECT] Escalando para scan permissivo (qualquer CHOPP_)");
        }

        broadcastStatus(STATUS_DISCONNECTED + ":" + reason);
        mHandler.postDelayed(() -> {
            if (mState == State.IDLE) {
                if (mReconnectCount <= 2) {
                    startDirectConnect();
                } else {
                    startScanCycle();
                }
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

    private void broadcastStatus(String status) {
        Log.d(TAG, "[STATUS] " + status);
        Intent i = new Intent(BLE_STATUS_ACTION);
        i.putExtra("status", status);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void broadcastData(String data) {
        Intent i = new Intent(BLE_DATA_ACTION);
        i.putExtra("data", data);
        i.setPackage(getPackageName());
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
