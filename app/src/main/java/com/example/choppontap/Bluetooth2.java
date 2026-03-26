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
import android.bluetooth.BluetoothSocket;
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
import androidx.annotation.Nullable;
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


public class Bluetooth2 extends BleManager {
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private BluetoothDevice esp32Device;
    private static final String ESP32_MAC_ADDRESS = "94:54:C5:E8:72:12"; // Replace with your ESP32's MAC
    private static final UUID SPP_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"); // Standard SPP UUID
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private ScanCallback scanCallback;
    private TextView txtQtd;
    private TextView txtVz;
    private TextView txtMl;
    public Boolean started = false;
    private TextView txtQtdLocal;
    protected static final UUID CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    protected static final UUID TX = UUID.fromString("7f0a0003-7b6b-4b5f-9d3e-3c7b9f100001");
    protected static final UUID RX = UUID.fromString("7f0a0002-7b6b-4b5f-9d3e-3c7b9f100001");
    protected static final UUID serviceUuid = UUID.fromString("7f0a0001-7b6b-4b5f-9d3e-3c7b9f100001");

    private boolean notificationEnabled = false;
    private Integer mlsLiberados = 0;
    private String command;
    private BluetoothGattService gattService;
    private Context localContext;

    private BluetoothGattCharacteristic rxChar, txChar;

    public Bluetooth2(Context context)
    {
        super(context);
        localContext = context;
    }

    @Override
    protected void initialize() {
        // Inicialização do BleManager
    }

    @Override
    public boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt) {
        BluetoothGattService service = gatt.getService(serviceUuid);
        if (service != null) {
            rxChar = service.getCharacteristic(RX);
            txChar = service.getCharacteristic(TX);
            
            boolean writeRequest = rxChar != null && (rxChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
            boolean notify = txChar != null && (txChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;

            Log.d("Bluetooth2", "Serviço UART encontrado: " + (rxChar != null && txChar != null));
            return rxChar != null && txChar != null;
        }
        Log.e("Bluetooth2", "Serviço UART NÃO encontrado");
        return false;
    }

    @Override
    protected void onDeviceDisconnected() {
        rxChar = null;
        txChar = null;
    }


    public boolean connected() {
        return isConnected();
    }


    private void checkDevice(Context context, BluetoothGattCallback gattCallback) {
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> devices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);

        if(!devices.isEmpty()) {
            connectDevice(context,gattCallback);
        }

    }
    public void connectDevice(Context context, BluetoothGattCallback gattCallback )
    {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if(bluetoothLeScanner != null)
        {
            ScanCallback scanCallbackStop = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {}
                @Override
                public void onBatchScanResults(List<ScanResult> results) {}
                @Override
                public void onScanFailed(int errorCode) {}
            };
            bluetoothLeScanner.stopScan(scanCallbackStop);
        }
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> devices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
        for(BluetoothDevice device : devices){
            if(!isConnected() && device.getName() != null && device.getName().startsWith("CHOPP_")){
                connect(device).retry(3, 100).enqueue();
            }
        }

    }

    public void liberarLiquido(Context context, Integer qtd_ml , String checkout_id, String android_id, TextView txtQtd1, TextView txtVz1, TextView txtMl1, Button btnLiberar,Integer liberado)
    {
        txtQtd = txtQtd1;
        txtVz = txtVz1;
        txtMl = txtMl1;

        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);

        if (!connectedGattDevices.isEmpty()) {
            final Integer mlsSolicitado = qtd_ml;
            String command = "$ML:" + qtd_ml.toString();
            byte[] messageBytes = command.getBytes();
            
            BluetoothDevice device = connectedGattDevices.get(0);
            Log.d("Bluetooth2", "Iniciando liberação de líquido: " + command);
            
            connect(device).done(d -> {
                Log.d("Bluetooth2", "Conectado com sucesso");
                
                if (txChar == null || rxChar == null) {
                    Log.e("Bluetooth2", "Características não encontradas");
                    return;
                }

                setNotificationCallback(txChar).with(new DataReceivedCallback() {
                    @Override
                    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                        String receivedData = data.getStringValue(0);
                        if (receivedData == null) return;
                        Log.d("Bluetooth2", "Recebido: " + receivedData);
                        
                        runOnCallbackThread(() -> {
                            if(receivedData.contains("QP")){
                                txtQtd.setText(receivedData);
                            }
                            if(receivedData.contains("VP")){
                                try {
                                    String vp = receivedData.replace("VP:","").trim();
                                    Double mlsFloat = Double.valueOf(vp);
                                    Integer mls = (int) Math.round(mlsFloat);
                                    mlsLiberados += mls;
                                    if(liberado > 0) mls += liberado;
                                    txtVz.setText(mls.toString()+"ML");
                                    if(!mls.equals(mlsSolicitado)){
                                        btnLiberar.setVisibility(TextView.VISIBLE);
                                    }else{
                                        btnLiberar.setVisibility(TextView.GONE);
                                    }
                                } catch (Exception e) {
                                    Log.e("Bluetooth2", "Erro VP: " + e.getMessage());
                                }
                            }
                            if(receivedData.contains("ML")){
                                sendRequest(mlsLiberados,checkout_id,android_id);
                                txtMl.setText(receivedData);
                            }
                        });
                    }
                });

                enableNotifications(txChar).done(d2 -> {
                    writeCharacteristic(rxChar, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue();
                }).enqueue();

            }).enqueue();
        }
    }


    public void liberacaoContinua(TextView txtVolumeLiberado,String android_id)
    {
        BluetoothManager manager = (BluetoothManager) localContext.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);

        if(!connectedGattDevices.isEmpty()) {
            String command = "$LB:";
            byte[] messageBytes = command.getBytes();
            connect(connectedGattDevices.get(0)).done(device -> {
                if (rxChar == null || txChar == null) return;

                setNotificationCallback(txChar).with(new DataReceivedCallback() {
                    @Override
                    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                        String receivedData = data.getStringValue(0);
                        if (receivedData == null) return;
                        Log.d("LB",receivedData);
                        try {
                            String vp = receivedData.replace("VP:","").trim();
                            Double mlsFloat = Double.valueOf(vp);
                            Integer mls = (int) Math.round(mlsFloat);
                            sendRequest(mls,android_id);
                            runOnCallbackThread(() -> txtVolumeLiberado.setText(receivedData));
                            if(receivedData.contains("ML")) disconnect().enqueue();
                        } catch (Exception e) {}
                    }
                });

                enableNotifications(txChar).done(d2 -> {
                    writeCharacteristic(rxChar, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).enqueue();
                }).enqueue();
            }).enqueue();
        }
    }

    public void liberarLiquidoTeste(Context context, Integer qtd_ml, TextView txtVolumeLiberado, TextView txtQtd, EditText novaQtd)
    {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);

        if (!connectedGattDevices.isEmpty()) {
            String command = "$ML:" + qtd_ml.toString();
            byte[] messageBytes = command.getBytes();
            
            connect(connectedGattDevices.get(0)).done(d -> {
                if (txChar == null || rxChar == null) return;

                setNotificationCallback(txChar).with(new DataReceivedCallback() {
                    @Override
                    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                        String receivedData = data.getStringValue(0);
                        if (receivedData == null) return;
                        
                        runOnCallbackThread(() -> {
                            if(receivedData.contains("VP")){
                                try {
                                    String vp = receivedData.replace("VP:","").trim();
                                    Double mlsFloat = Double.valueOf(vp);
                                    Integer mls = (int) Math.round(mlsFloat);
                                    mlsLiberados = mls;
                                    txtVolumeLiberado.setText(mls.toString()+"ML");
                                } catch (Exception e) {}
                            }
                            if(receivedData.contains("ML")){
                                try {
                                    String qtdAtual = txtQtd.getText().toString();
                                    String vp = receivedData.replace("ML:","").trim();
                                    String liberado = txtVolumeLiberado.getText().toString();

                                    String pulsos = qtdAtual.replace("PL:","").trim();
                                    Float pulsosInt = Float.parseFloat(pulsos.replace("\n",""));

                                    String liberadoF = liberado.replace("ML","").trim();
                                    Float liberadoInt = Float.parseFloat(liberadoF.replace("\n",""));

                                    Float mlsFloat = ((pulsosInt)/(liberadoInt/10));
                                    Integer mls = (int) Math.round(mlsFloat);

                                    mlsLiberados = mls;
                                    if(liberadoInt != 100.0) {
                                        txtVolumeLiberado.setText(vp+"ML");
                                    }
                                } catch (Exception e) {}
                            }
                        });
                    }
                });

                enableNotifications(txChar).done(d2 -> {
                    writeCharacteristic(rxChar, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue();
                }).enqueue();

            }).enqueue();
        }
    }

    public void changePulsos(Context context, Integer pulsoPorLitro,TextView qtdAtual){
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);
        txtQtdLocal = qtdAtual;
        if (!connectedGattDevices.isEmpty()) {
            String command = "$PL:" + pulsoPorLitro.toString();
            byte[] messageBytes = command.getBytes();
            
            connect(connectedGattDevices.get(0)).done(d -> {
                if (txChar == null || rxChar == null) return;

                setNotificationCallback(txChar).with(new DataReceivedCallback() {
                    @Override
                    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                        getPulsos(context, txtQtdLocal);
                    }
                });

                enableNotifications(txChar).done(d2 -> {
                    writeCharacteristic(rxChar, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue();
                }).enqueue();
            }).enqueue();
        }
    }

    public void getPulsos(Context context, TextView txtQtd){
        txtQtdLocal = txtQtd;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);

        if (!connectedGattDevices.isEmpty()) {
            byte[] messageBytes = "$PL:0".getBytes();
            connect(connectedGattDevices.get(0)).done(d -> {
                if (txChar == null || rxChar == null) return;

                setNotificationCallback(txChar).with(new DataReceivedCallback() {
                    @Override
                    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                        String data1 = data.getStringValue(0);
                        if(data1 != null && data1.contains("PL")){
                            runOnCallbackThread(() -> txtQtdLocal.setText(data1.replace("\n","")));
                        }
                    }
                });

                enableNotifications(txChar).done(d2 -> {
                    writeCharacteristic(rxChar, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue();
                }).enqueue();
            }).enqueue();
        }
    }

    public void getTimeout(TextView txtTimeoutAtual){
        BluetoothManager manager = (BluetoothManager) localContext.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);

        if (!connectedGattDevices.isEmpty()) {
            byte[] messageBytes = "$TO:".getBytes();
            connect(connectedGattDevices.get(0)).done(d -> {
                if (txChar == null || rxChar == null) return;

                setNotificationCallback(txChar).with(new DataReceivedCallback() {
                    @Override
                    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                        String data1 = data.getStringValue(0);
                        if (data1 != null) runOnCallbackThread(() -> txtTimeoutAtual.setText(data1));
                    }
                });

                enableNotifications(txChar).done(d2 -> {
                    writeCharacteristic(rxChar, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue();
                }).enqueue();
            }).enqueue();
        }
    }

    public void setTimeout(String value){
        Integer ms = Integer.parseInt(value)*1000;
        BluetoothManager manager = (BluetoothManager) localContext.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);

        if (!connectedGattDevices.isEmpty()) {
            String commando = "$TO:" + ms.toString();
            byte[] messageBytes = commando.getBytes();
            
            connect(connectedGattDevices.get(0)).done(d -> {
                if (txChar == null || rxChar == null) return;
                enableNotifications(txChar).done(d2 -> {
                    writeCharacteristic(rxChar, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue();
                }).enqueue();
            }).enqueue();
        }
    }

    // Métodos legados mantidos para compatibilidade, mas ajustados para BleManager
    public void sendCommand(BluetoothGatt gatt,Context context,String command) {
        if (rxChar != null) {
            writeCharacteristic(rxChar, command.getBytes(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue();
        }
    }

    public BluetoothGattService getUartService(BluetoothGatt gatt) {
        return gatt.getService(serviceUuid);
    }

    public void scan(Context context,Button btnCalibrar){
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
                
                if(device.getName() == null || !device.getName().startsWith("CHOPP_")) return;

                if(!isConnected()) {
                    connect(device).done(device2 -> {
                        if (txChar == null) return;
                        setNotificationCallback(txChar).with((device3, data) -> {
                            String receivedData = data.getStringValue(0);
                            if (receivedData == null) return;
                            String id = receivedData.replace("ID:","").replace("\n","");
                            if(id.equals("0589af9a")) {
                                runOnCallbackThread(() -> btnCalibrar.setVisibility(TextView.VISIBLE));
                            }
                        });
                        enableNotifications(txChar).enqueue();
                    }).enqueue();
                }
            }
        };
        bluetoothLeScanner.startScan(scanCallback);
    }

    private void sendRequest(Integer qtd_ml,String checkout_id, String android_id) {
        Map<String,String> body = new HashMap<>();
        body.put("android_id",android_id);
        body.put("qtd_ml",qtd_ml.toString());
        body.put("checkout_id",checkout_id);
        ApiHelper apiHelper = new ApiHelper(localContext);
        apiHelper.sendPost(body,"liberacao.php", new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException {
                response.close();
            }
        });
    }

    private void sendRequest(Integer qtd_ml, String android_id) {
        Map<String,String> body = new HashMap<>();
        body.put("android_id",android_id);
        body.put("qtd_ml",qtd_ml.toString());
        ApiHelper apiHelper = new ApiHelper(localContext);
        apiHelper.sendPost(body,"liquido_liberado.php", new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException {
                response.close();
            }
        });
    }
}
