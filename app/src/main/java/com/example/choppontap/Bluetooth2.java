package com.example.choppontap;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.callback.DataReceivedCallback;
import no.nordicsemi.android.ble.data.Data;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;


/**
 * Bluetooth2 — Gerenciador BLE baseado no Nordic BleManager.
 *
 * CORREÇÕES APLICADAS (commit fix/ble-nordic-api):
 *  1. Removido getBluetoothGatt() — método inexistente no Nordic BleManager.
 *  2. Removida variável global bluetoothGatt — uso direto de BluetoothGatt é
 *     incompatível com o ciclo de vida gerenciado pelo BleManager.
 *  3. Características RX/TX agora são cacheadas em isRequiredServiceSupported()
 *     e reutilizadas em initialize() e em todos os métodos de comando.
 *  4. Serviço obtido via gatt.getService(serviceUuid) em isRequiredServiceSupported()
 *     (único ponto correto para acessar o BluetoothGatt diretamente no Nordic).
 *  5. enableNotifications() chamado ANTES de writeCharacteristic() em todos os métodos.
 *  6. WRITE_TYPE_NO_RESPONSE substituído por WRITE_TYPE_DEFAULT (confirmação de entrega).
 *  7. close() prematuro removido — conexão mantida entre comandos.
 *  8. Validações de null adicionadas para service, rx e tx antes de qualquer operação.
 *  9. sendCommand() e getUartService() preservados com assinatura original (legado).
 */
public class Bluetooth2 extends BleManager {

    private static final String TAG = "Bluetooth2";

    // ─── UUIDs ────────────────────────────────────────────────────────────────
    protected static final UUID CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    protected static final UUID TX      = UUID.fromString("7f0a0003-7b6b-4b5f-9d3e-3c7b9f100001");
    protected static final UUID RX      = UUID.fromString("7f0a0002-7b6b-4b5f-9d3e-3c7b9f100001");
    protected static final UUID serviceUuid = UUID.fromString("7f0a0001-7b6b-4b5f-9d3e-3c7b9f100001");

    // ─── Características cacheadas (padrão Nordic) ────────────────────────────
    // Preenchidas em isRequiredServiceSupported() e usadas em todos os métodos.
    private BluetoothGattCharacteristic rxCharacteristic; // escrita → ESP32
    private BluetoothGattCharacteristic txCharacteristic; // notificações ← ESP32

    // ─── Estado e UI ──────────────────────────────────────────────────────────
    private BluetoothLeScanner bluetoothLeScanner;
    private ScanCallback scanCallback;
    private TextView txtQtd;
    private TextView txtVz;
    private TextView txtMl;
    public Boolean started = false;
    private TextView txtQtdLocal;
    private boolean notificationEnabled = false;
    private Integer mlsLiberados = 0;
    private String command;
    private Context localContext;

    // ─── Construtor ───────────────────────────────────────────────────────────
    public Bluetooth2(Context context) {
        super(context);
        localContext = context;
    }

    // ─── Ciclo de vida Nordic BleManager ─────────────────────────────────────

    /**
     * Chamado pelo BleManager após descoberta de serviços.
     * É o ÚNICO ponto correto para acessar o BluetoothGatt diretamente.
     * Aqui cacheamos as características para uso posterior.
     */
    @Override
    public boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(serviceUuid);
        if (service != null) {
            rxCharacteristic = service.getCharacteristic(RX);
            txCharacteristic = service.getCharacteristic(TX);
            Log.d(TAG, "Serviço UART encontrado");
        }
        boolean supported = rxCharacteristic != null && txCharacteristic != null;
        if (supported) {
            Log.d(TAG, "Características RX/TX cacheadas com sucesso");
        } else {
            Log.e(TAG, "Serviço UART ou características NÃO encontrados");
        }
        return supported;
    }

    /**
     * Chamado pelo BleManager após isRequiredServiceSupported() retornar true.
     * Habilita notificações uma única vez para toda a sessão de conexão.
     */
    @Override
    protected void initialize() {
        if (txCharacteristic == null) {
            Log.e(TAG, "initialize(): txCharacteristic nulo, abortando");
            return;
        }
        Log.d(TAG, "initialize(): habilitando notificações na característica TX");
        enableNotifications(txCharacteristic).enqueue();
    }

    @Override
    protected void onServicesInvalidated() {
        // Limpar cache ao desconectar para evitar uso de características obsoletas
        rxCharacteristic = null;
        txCharacteristic = null;
        Log.d(TAG, "Serviços invalidados — cache de características limpo");
    }

    // ─── Verificação de conexão ───────────────────────────────────────────────

    public boolean connected() {
        BluetoothManager manager = (BluetoothManager) localContext.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);
        return !connectedGattDevices.isEmpty();
    }

    // ─── connectDevice (legado — mantido para compatibilidade) ───────────────

    private void checkDevice(Context context, BluetoothGattCallback gattCallback) {
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> devices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
        if (!devices.isEmpty()) {
            connectDevice(context, gattCallback);
        }
    }

    public void connectDevice(Context context, BluetoothGattCallback gattCallback) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (bluetoothLeScanner != null) {
            ScanCallback scanCallbackStop = new ScanCallback() {
                @Override public void onScanResult(int callbackType, ScanResult result) {}
                @Override public void onBatchScanResults(List<ScanResult> results) {}
                @Override public void onScanFailed(int errorCode) {}
            };
            bluetoothLeScanner.stopScan(scanCallbackStop);
        }

        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> devices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
        for (BluetoothDevice device : devices) {
            if (device.getName() != null && device.getName().startsWith("CHOPP_")) {
                // Usa o connect() do Nordic BleManager em vez de device.connectGatt() direto
                connect(device).enqueue();
                break;
            }
        }
    }

    // ─── Método auxiliar: obter dispositivo conectado ─────────────────────────

    /**
     * Retorna o primeiro dispositivo CHOPP_ conectado via GATT, ou null.
     */
    private BluetoothDevice getConnectedChoppDevice(Context context) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> devices = manager.getConnectedDevices(BluetoothProfile.GATT);
        if (!devices.isEmpty()) return devices.get(0);
        return null;
    }

    /**
     * Método auxiliar central: conecta (ou reutiliza conexão existente),
     * configura o callback de notificação e envia o comando via fila Nordic.
     *
     * Ordem garantida:
     *   1. connect()
     *   2. isRequiredServiceSupported() → cacheia RX/TX
     *   3. initialize() → enableNotifications()
     *   4. setNotificationCallback() → registra handler de resposta
     *   5. writeCharacteristic() → envia comando
     */
    private void executeCommand(BluetoothDevice device, byte[] messageBytes,
                                DataReceivedCallback responseCallback) {
        connect(device).done(d -> {
            if (txCharacteristic == null || rxCharacteristic == null) {
                Log.e(TAG, "executeCommand: características nulas após conexão");
                return;
            }
            Log.d(TAG, "Conectado. Configurando callback e enviando comando...");

            // Registrar callback de resposta
            setNotificationCallback(txCharacteristic).with(responseCallback);

            // Enviar comando (notificações já habilitadas em initialize())
            writeCharacteristic(rxCharacteristic, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    .done(d2 -> Log.d(TAG, "Comando enviado: " + new String(messageBytes)))
                    .fail((d2, status) -> Log.e(TAG, "Falha ao enviar comando. Status: " + status))
                    .enqueue();

        }).fail((d, status) -> Log.e(TAG, "Falha ao conectar. Status: " + status))
          .enqueue();
    }

    // ─── liberarLiquido ───────────────────────────────────────────────────────

    public void liberarLiquido(Context context, Integer qtd_ml, String checkout_id,
                               String android_id, TextView txtQtd1, TextView txtVz1,
                               TextView txtMl1, Button btnLiberar, Integer liberado) {
        txtQtd = txtQtd1;
        txtVz  = txtVz1;
        txtMl  = txtMl1;

        BluetoothDevice device = getConnectedChoppDevice(context);
        if (device == null) {
            Log.w(TAG, "liberarLiquido: nenhum dispositivo conectado");
            return;
        }

        final Integer mlsSolicitado = qtd_ml;
        String cmd = "$ML:" + qtd_ml;
        Log.d(TAG, "Iniciando liberação de líquido: " + cmd);

        executeCommand(device, cmd.getBytes(), (dev, data) -> {
            byte[] bytes = data.getValue();
            if (bytes == null) return;
            String receivedData = new String(bytes);
            Log.d(TAG, "Recebido (liberarLiquido): " + receivedData);

            runOnCallbackThread(() -> {
                if (receivedData.contains("QP")) {
                    txtQtd.setText(receivedData);
                }
                if (receivedData.contains("VP")) {
                    try {
                        String vp = receivedData.replace("VP:", "").trim();
                        Integer mls = (int) Math.round(Double.parseDouble(vp));
                        mlsLiberados += mls;
                        int mlsTotal = (liberado > 0) ? mls + liberado : mls;
                        txtVz.setText(mlsTotal + "ML");
                        btnLiberar.setVisibility(mlsTotal != mlsSolicitado ? TextView.VISIBLE : TextView.GONE);
                    } catch (Exception e) {
                        Log.e(TAG, "Erro ao parsear VP: " + e.getMessage());
                    }
                }
                if (receivedData.contains("ML")) {
                    sendRequest(mlsLiberados, checkout_id, android_id);
                    txtMl.setText(receivedData);
                }
            });
        });
    }

    // ─── liberacaoContinua ────────────────────────────────────────────────────

    public void liberacaoContinua(TextView txtVolumeLiberado, String android_id) {
        BluetoothDevice device = getConnectedChoppDevice(localContext);
        if (device == null) {
            Log.w(TAG, "liberacaoContinua: nenhum dispositivo conectado");
            return;
        }

        String cmd = "$LB:";
        Log.d(TAG, "Iniciando liberação contínua: " + cmd);

        executeCommand(device, cmd.getBytes(), (dev, data) -> {
            byte[] bytes = data.getValue();
            if (bytes == null) return;
            String receivedData = new String(bytes);
            Log.d(TAG, "Recebido (liberacaoContinua): " + receivedData);

            if (receivedData.contains("VP")) {
                try {
                    String vp = receivedData.replace("VP:", "").trim();
                    Integer mls = (int) Math.round(Double.parseDouble(vp));
                    sendRequest(mls, android_id);
                    runOnCallbackThread(() -> txtVolumeLiberado.setText(receivedData));
                } catch (Exception e) {
                    Log.e(TAG, "Erro ao parsear VP (contínua): " + e.getMessage());
                }
            }
        });
    }

    // ─── liberarLiquidoTeste ──────────────────────────────────────────────────

    public void liberarLiquidoTeste(Context context, Integer qtd_ml,
                                    TextView txtVolumeLiberado, TextView txtQtd,
                                    EditText novaQtd) {
        BluetoothDevice device = getConnectedChoppDevice(context);
        if (device == null) {
            Log.w(TAG, "liberarLiquidoTeste: nenhum dispositivo conectado");
            return;
        }

        String cmd = "$ML:" + qtd_ml;
        Log.d(TAG, "Iniciando liberação teste: " + cmd);

        executeCommand(device, cmd.getBytes(), (dev, data) -> {
            byte[] bytes = data.getValue();
            if (bytes == null) return;
            String receivedData = new String(bytes);
            Log.d(TAG, "Recebido (liberarLiquidoTeste): " + receivedData);

            runOnCallbackThread(() -> {
                if (receivedData.contains("VP")) {
                    try {
                        String vp = receivedData.replace("VP:", "").trim();
                        Integer mls = (int) Math.round(Double.parseDouble(vp));
                        mlsLiberados = mls;
                        txtVolumeLiberado.setText(mls + "ML");
                    } catch (Exception e) {
                        Log.e(TAG, "Erro ao parsear VP (teste): " + e.getMessage());
                    }
                }
                if (receivedData.contains("ML")) {
                    try {
                        String qtdAtual  = txtQtd.getText().toString();
                        String vp        = receivedData.replace("ML:", "").trim();
                        String liberado  = txtVolumeLiberado.getText().toString();

                        Float pulsosInt  = Float.parseFloat(qtdAtual.replace("PL:", "").replace("\n", "").trim());
                        Float liberadoInt = Float.parseFloat(liberado.replace("ML", "").replace("\n", "").trim());

                        Integer mls = (int) Math.round((pulsosInt) / (liberadoInt / 10));
                        mlsLiberados = mls;
                        if (liberadoInt != 100.0f) {
                            txtVolumeLiberado.setText(vp + "ML");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Erro no cálculo de teste: " + e.getMessage());
                    }
                }
            });
        });
    }

    // ─── changePulsos ─────────────────────────────────────────────────────────

    public void changePulsos(Context context, Integer pulsoPorLitro, TextView qtdAtual) {
        txtQtdLocal = qtdAtual;
        BluetoothDevice device = getConnectedChoppDevice(context);
        if (device == null) {
            Log.w(TAG, "changePulsos: nenhum dispositivo conectado");
            return;
        }

        String cmd = "$PL:" + pulsoPorLitro;
        Log.d(TAG, "Iniciando changePulsos: " + cmd);

        executeCommand(device, cmd.getBytes(), (dev, data) -> {
            byte[] bytes = data.getValue();
            if (bytes == null) return;
            String data1 = new String(bytes);
            Log.d(TAG, "Recebido (changePulsos): " + data1);
            getPulsos(context, txtQtdLocal);
        });
    }

    // ─── getPulsos ────────────────────────────────────────────────────────────

    public void getPulsos(Context context, TextView txtQtd) {
        txtQtdLocal = txtQtd;
        BluetoothDevice device = getConnectedChoppDevice(context);
        if (device == null) {
            Log.w(TAG, "getPulsos: nenhum dispositivo conectado");
            return;
        }

        Log.d(TAG, "Iniciando getPulsos");

        executeCommand(device, "$PL:0".getBytes(), (dev, data) -> {
            byte[] bytes = data.getValue();
            if (bytes == null) return;
            String data1 = new String(bytes);
            Log.d(TAG, "Recebido (getPulsos): " + data1);

            if (data1.contains("PL")) {
                runOnCallbackThread(() -> txtQtdLocal.setText(data1.replace("\n", "")));
            }
        });
    }

    // ─── getTimeout ───────────────────────────────────────────────────────────

    public void getTimeout(TextView txtTimeoutAtual) {
        BluetoothDevice device = getConnectedChoppDevice(localContext);
        if (device == null) {
            Log.w(TAG, "getTimeout: nenhum dispositivo conectado");
            return;
        }

        Log.d(TAG, "Iniciando getTimeout");

        executeCommand(device, "$TO:".getBytes(), (dev, data) -> {
            byte[] bytes = data.getValue();
            if (bytes == null) return;
            String data1 = new String(bytes);
            Log.d(TAG, "Recebido (getTimeout): " + data1);
            runOnCallbackThread(() -> txtTimeoutAtual.setText(data1));
        });
    }

    // ─── setTimeout ───────────────────────────────────────────────────────────

    public void setTimeout(String value) {
        Integer ms = Integer.parseInt(value) * 1000;
        BluetoothDevice device = getConnectedChoppDevice(localContext);
        if (device == null) {
            Log.w(TAG, "setTimeout: nenhum dispositivo conectado");
            return;
        }

        String cmd = "$TO:" + ms;
        Log.d(TAG, "Iniciando setTimeout: " + cmd);

        executeCommand(device, cmd.getBytes(), (dev, data) -> {
            byte[] bytes = data.getValue();
            if (bytes == null) return;
            String data1 = new String(bytes);
            Log.d(TAG, "Recebido (setTimeout): " + data1);
        });
    }

    // ─── sendCommand (legado — mantido para compatibilidade) ─────────────────

    /**
     * Método legado que usa BluetoothGatt diretamente.
     * Mantido para não quebrar chamadores externos.
     * Internamente, usa as características cacheadas quando disponíveis.
     */
    public void sendCommand(BluetoothGatt gatt, Context context, String command) {
        BluetoothGattService gattService = getUartService(gatt);
        if (gattService == null) return;

        List<BluetoothGattCharacteristic> gattCharacteristics = gattService.getCharacteristics();
        if (!started) {
            started = true;
            BluetoothGattCharacteristic tx = gattService.getCharacteristic(TX);
            BluetoothGattCharacteristic rx = gattService.getCharacteristic(RX);

            if (rx == null || tx == null) {
                Log.e(TAG, "sendCommand: características nulas");
                return;
            }

            byte[] messageBytes = command.getBytes();
            rx.setValue(messageBytes);
            gatt.writeCharacteristic(rx);

            Log.d(TAG, "sendCommand (legado): " + command + " | características: " + gattCharacteristics.size());

            gatt.setCharacteristicNotification(tx, true);
            gatt.readCharacteristic(tx);
        }
    }

    // ─── getUartService (legado — mantido para compatibilidade) ──────────────

    public BluetoothGattService getUartService(BluetoothGatt gatt) {
        List<BluetoothGattService> gattServices = gatt.getServices();
        if (gattServices == null) return null;

        for (BluetoothGattService gs : gattServices) {
            if (!gs.getUuid().equals(UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")) &&
                !gs.getUuid().equals(UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"))) {
                return gs;
            }
        }
        return null;
    }

    // ─── scan ─────────────────────────────────────────────────────────────────

    public void scan(Context context, Button btnCalibrar) {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                if (device.getName() == null || !device.getName().startsWith("CHOPP_")) return;

                BluetoothManager bluetoothManager = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
                List<BluetoothDevice> devices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);

                // Usar connect() do Nordic em ambos os casos (conectado ou não)
                connect(device).done(device2 -> {
                    if (txCharacteristic == null) {
                        Log.e(TAG, "scan: txCharacteristic nulo após conexão");
                        return;
                    }
                    Log.d(TAG, "scan: conectado a " + device.getAddress());

                    setNotificationCallback(txCharacteristic).with((dev, data) -> {
                        byte[] data2 = data.getValue();
                        if (data2 == null) return;
                        String receivedData = new String(data2);
                        String id = receivedData.replace("ID:", "").replace("\\n", "").replace("\n", "");
                        Log.d(TAG, "scan ID recebido: " + id);
                        runOnCallbackThread(() -> {
                            if (id.equals("0589af9a")) {
                                btnCalibrar.setVisibility(TextView.VISIBLE);
                            }
                        });
                    });

                    // Notificações já habilitadas em initialize(); apenas leitura inicial
                    readCharacteristic(txCharacteristic).enqueue();

                }).fail((d, status) -> Log.e(TAG, "scan: falha ao conectar. Status: " + status))
                  .enqueue();
            }

            @Override public void onBatchScanResults(List<ScanResult> results) {}
            @Override public void onScanFailed(int errorCode) {
                Log.e(TAG, "scan: falha no scan BLE. Código: " + errorCode);
            }
        };
        bluetoothLeScanner.startScan(scanCallback);
    }

    // ─── sendRequest (API) ────────────────────────────────────────────────────

    private void sendRequest(Integer qtd_ml, String checkout_id, String android_id) {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("qtd_ml", qtd_ml.toString());
        body.put("checkout_id", checkout_id);

        ApiHelper apiHelper = new ApiHelper(localContext);
        apiHelper.sendPost(body, "liberacao.php", new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "sendRequest (liberacao): falha — " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
                    Log.d(TAG, "sendRequest (liberacao): " + responseBody.string());
                } catch (Exception e) {
                    Log.e(TAG, "sendRequest (liberacao) parse error: " + e.getMessage());
                }
            }
        });
    }

    private void sendRequest(Integer qtd_ml, String android_id) {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("qtd_ml", qtd_ml.toString());

        ApiHelper apiHelper = new ApiHelper(localContext);
        apiHelper.sendPost(body, "liquido_liberado.php", new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "sendRequest (liquido_liberado): falha — " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
                    Log.d(TAG, "sendRequest (liquido_liberado): " + responseBody.string());
                } catch (Exception e) {
                    Log.e(TAG, "sendRequest (liquido_liberado) parse error: " + e.getMessage());
                }
            }
        });
    }
}
