package com.example.choppontap;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.choppontap.controller.ChopperController;

/**
 * Exemplo de Activity usando a nova arquitetura BLE
 * Demonstra o fluxo correto:
 * 1. Conectar ao ESP32
 * 2. Enviar comando
 * 3. Receber resposta
 * 4. Enviar para API (apenas uma vez)
 */
public class ChopperActivity extends AppCompatActivity {
    private static final String TAG = "ChopperActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private ChopperController controller;
    private BluetoothAdapter bluetoothAdapter;

    // UI Views
    private TextView statusText;
    private TextView volumeText;
    private Button connectButton;
    private Button liberarButton;
    private EditText mlInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chopper);

        // Inicializa UI
        initializeUI();

        // Inicializa controller BLE
        controller = new ChopperController(this);
        controller.setListener(new ChopperController.ChopperListener() {
            @Override
            public void onConnecting() {
                updateStatus("Conectando...");
            }

            @Override
            public void onConnected() {
                updateStatus("✓ Conectado");
                connectButton.setText("Desconectar");
                connectButton.setEnabled(true);
            }

            @Override
            public void onDisconnected() {
                updateStatus("✗ Desconectado");
                connectButton.setText("Conectar");
                connectButton.setEnabled(true);
            }

            @Override
            public void onCommandSending(String command) {
                updateStatus("Enviando: " + command);
            }

            @Override
            public void onCommandSent(String command) {
                updateStatus("Aguardando resposta...");
            }

            @Override
            public void onPartialVolume(Integer ml) {
                volumeText.setText("Volume: " + ml + "ml");
            }

            @Override
            public void onPulseCount(Integer count) {
                updateStatus("Pulsos: " + count);
            }

            @Override
            public void onOrderComplete(Integer mlTotal) {
                updateStatus("✓ Completo: " + mlTotal + "ml");
                Toast.makeText(ChopperActivity.this, "Pedido enviado para API!", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                updateStatus("✗ Erro: " + error);
                Toast.makeText(ChopperActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });

        // Request permissions
        requestBluetoothPermissions();

        // Setup Bluetooth
        setupBluetooth();
    }

    private void initializeUI() {
        statusText = findViewById(R.id.statusText);
        volumeText = findViewById(R.id.volumeText);
        connectButton = findViewById(R.id.connectButton);
        liberarButton = findViewById(R.id.liberarButton);
        mlInput = findViewById(R.id.mlInput);

        connectButton.setOnClickListener(v -> handleConnectClick());
        liberarButton.setOnClickListener(v -> handleLiberarClick());
    }

    private void setupBluetooth() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = manager.getAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth não suportado", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void handleConnectClick() {
        if (controller.isConnected()) {
            controller.disconnect();
            connectButton.setEnabled(false);
        } else {
            findAndConnectDevice();
        }
    }

    private void handleLiberarClick() {
        if (!controller.isConnected()) {
            Toast.makeText(this, "Não conectado", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String mlStr = mlInput.getText().toString();
            if (mlStr.isEmpty()) {
                Toast.makeText(this, "Digite quantidade em ml", Toast.LENGTH_SHORT).show();
                return;
            }

            Integer ml = Integer.parseInt(mlStr);
            String checkoutId = "checkout_" + System.currentTimeMillis();
            String androidId = "android_test";

            // *** FLUXO CORRETO ***
            controller.liberarLiquido(ml, checkoutId, androidId);

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Valor inválido", Toast.LENGTH_SHORT).show();
        }
    }

    private void findAndConnectDevice() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Sem permissão de BLE scan", Toast.LENGTH_SHORT).show();
            return;
        }

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        for (BluetoothDevice device : manager.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)) {
            if (device.getName() != null && device.getName().startsWith("CHOPP_")) {
                updateStatus("Encontrado: " + device.getName());
                controller.connect(device);
                return;
            }
        }

        Toast.makeText(this, "ESP32 não encontrado", Toast.LENGTH_SHORT).show();
    }

    private void updateStatus(String status) {
        runOnUiThread(() -> {
            statusText.setText("[" + java.time.LocalTime.now() + "] " + status);
        });
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                    },
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (controller != null && controller.isConnected()) {
            controller.disconnect();
        }
    }
}
