package com.example.choppontap.controller;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;

import com.example.choppontap.api.ApiManager;
import com.example.choppontap.ble.BleConnectionManager;
import com.example.choppontap.ble.BleState;

/**
 * Orquestrador de operações BLE + API
 * Coordena o fluxo completo:
 * 1. Enviar comando BLE
 * 2. Receber resposta BLE
 * 3. Enviar para API
 * 4. Processar próximo comando
 *
 * GARANTE: Cada operação acontece EXATAMENTE UMA VEZ
 */
public class ChopperController {
    private static final String TAG = "ChopperController";

    private final Context context;
    private final BleConnectionManager bleManager;
    private final ApiManager apiManager;

    // Dados da operação atual
    private String currentCheckoutId;
    private String currentAndroidId;
    private Integer currentMlRequested;
    private Integer currentMlReceived = 0;
    private boolean apiRequestSent = false; // GUARD contra duplicação

    // Listener para UI
    public interface ChopperListener {
        void onConnecting();
        void onConnected();
        void onDisconnected();
        void onCommandSending(String command);
        void onCommandSent(String command);
        void onPartialVolume(Integer ml);
        void onPulseCount(Integer count);
        void onOrderComplete(Integer mlTotal);
        void onError(String error);
    }

    private ChopperListener listener;

    public ChopperController(Context context) {
        this.context = context;
        this.bleManager = BleConnectionManager.getInstance(context);
        this.apiManager = ApiManager.getInstance(context);

        setupBleListeners();
    }

    public void setListener(ChopperListener listener) {
        this.listener = listener;
    }

    /**
     * Conecta ao ESP32
     */
    public void connect(BluetoothDevice device) {
        Log.d(TAG, "[CONNECT] Conectando ao esp32...");
        if (listener != null) {
            listener.onConnecting();
        }

        bleManager.connectToDevice(device);
    }

    /**
     * Desconecta do ESP32
     */
    public void disconnect() {
        Log.d(TAG, "[DISCONNECT] Desconectando...");
        bleManager.disconnectDevice();
    }

    /**
     * Libera líquido (fluxo principal com API)
     */
    public void liberarLiquido(Integer mlRequested, String checkoutId, String androidId) {
        Log.d(TAG, "[LIBERAR] Pedido: " + mlRequested + "ml, checkout=" + checkoutId);

        if (!bleManager.isConnectedDevice()) {
            String error = "Dispositivo não conectado";
            Log.e(TAG, error);
            if (listener != null) {
                listener.onError(error);
            }
            return;
        }

        // Reset de estado
        this.currentCheckoutId = checkoutId;
        this.currentAndroidId = androidId;
        this.currentMlRequested = mlRequested;
        this.currentMlReceived = 0;
        this.apiRequestSent = false; // *** RESET GUARD ***

        String command = "SERVE|" + mlRequested + "|DUMMY|DUMMY";
        bleManager.sendCommand(command);

        if (listener != null) {
            listener.onCommandSending(command);
        }
    }

    /**
     * Liberação contínua (sem requisição de API, apenas envio de volume)
     */
    public void liberarContinuo() {
        Log.d(TAG, "[CONTINUO] Iniciando liberação contínua");

        if (!bleManager.isConnectedDevice()) {
            String error = "Dispositivo não conectado";
            Log.e(TAG, error);
            if (listener != null) {
                listener.onError(error);
            }
            return;
        }

        this.currentAndroidId = null; // Sem checkout
        this.currentCheckoutId = null;
        this.apiRequestSent = false;

        bleManager.sendCommand("$LB:");
    }

    /**
     * Query de pulsos
     */
    public void getPulseCount() {
        Log.d(TAG, "[PULSOS] Consultando...");

        if (!bleManager.isConnectedDevice()) {
            if (listener != null) {
                listener.onError("Dispositivo não conectado");
            }
            return;
        }

        bleManager.sendCommand("$PL:0");
    }

    /**
     * Set de pulsos por litro
     */
    public void setPulseCount(Integer pulsesPerLiter) {
        Log.d(TAG, "[PULSOS] Configurando para " + pulsesPerLiter + " pulsos/litro");

        if (!bleManager.isConnectedDevice()) {
            if (listener != null) {
                listener.onError("Dispositivo não conectado");
            }
            return;
        }

        bleManager.sendCommand("$PL:" + pulsesPerLiter);
    }

    /**
     * Setup dos listeners do BLE
     */
    private void setupBleListeners() {
        bleManager.setConnListener(new BleConnectionManager.ConnectionListener() {
            @Override
            public void onConnected() {
                Log.d(TAG, "[BLE] Conectado com sucesso");
                if (listener != null) {
                    listener.onConnected();
                }
            }

            @Override
            public void onDisconnected() {
                Log.d(TAG, "[BLE] Desconectado");
                resetOperationState();
                if (listener != null) {
                    listener.onDisconnected();
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "[BLE] Erro: " + error);
                resetOperationState();
                if (listener != null) {
                    listener.onError(error);
                }
            }
        });

        bleManager.setCmdListener(new BleConnectionManager.CommandListener() {
            @Override
            public void onCommandSent(String command) {
                Log.d(TAG, "[BLE] Comando enviado: " + command);
                if (listener != null) {
                    listener.onCommandSent(command);
                }
            }

            @Override
            public void onResponseReceived(String response) {
                Log.d(TAG, "[BLE] Resposta recebida: " + response);
                handleBleResponse(response);
            }

            @Override
            public void onCommandError(String error) {
                Log.e(TAG, "[BLE] Erro: " + error);
                resetOperationState();
                if (listener != null) {
                    listener.onError(error);
                }
            }
        });
    }

    /**
     * Processa resposta BLE e coordena API
     * *** CRÍTICO: Chamado apenas uma vez por resposta ***
     */
    private void handleBleResponse(String response) {
        try {
            // VP: Volume Parcial
            if (response.contains("VP:")) {
                try {
                    String vpStr = response.replace("VP:", "").trim();
                    if (!vpStr.isEmpty()) {
                        Integer mlParcial = Integer.parseInt(vpStr);
                        currentMlReceived = mlParcial;
                        Log.d(TAG, "[RESPONSE] VP = " + mlParcial + "ml");

                        if (listener != null) {
                            listener.onPartialVolume(mlParcial);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[PARSE] Erro ao fazer parse de VP: " + e.getMessage());
                }
            }

            // QP: Pulse Count (Contagem de Pulsos)
            if (response.contains("QP:")) {
                try {
                    String qpStr = response.replace("QP:", "").replace("\n", "").trim();
                    if (!qpStr.isEmpty()) {
                        Integer pulseCount = Integer.parseInt(qpStr);
                        Log.d(TAG, "[RESPONSE] QP = " + pulseCount);

                        if (listener != null) {
                            listener.onPulseCount(pulseCount);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[PARSE] Erro ao fazer parse de QP: " + e.getMessage());
                }
            }

            // ML: Operação completa - ENVIA PARA API APENAS UMA VEZ
            if (response.contains("ML:")) {
                Log.d(TAG, "[RESPONSE] ML recebido - OPERAÇÃO COMPLETA");

                // *** GUARD contra chamada duplicada de API ***
                if (!apiRequestSent && currentCheckoutId != null) {
                    apiRequestSent = true; // Marcar como enviado ANTES de fazer requisição

                    Log.d(TAG, "[API] Enviando para backend...");
                    apiManager.sendOrderCompletion(
                            currentMlReceived,
                            currentCheckoutId,
                            currentAndroidId,
                            new ApiManager.ApiCallback() {
                                @Override
                                public void onSuccess(String response) {
                                    Log.d(TAG, "[API] Sucesso!");
                                    if (listener != null) {
                                        listener.onOrderComplete(currentMlReceived);
                                    }
                                    resetOperationState();
                                }

                                @Override
                                public void onFailure(String error) {
                                    Log.e(TAG, "[API] Erro: " + error);
                                    if (listener != null) {
                                        listener.onError("Erro ao enviar para API: " + error);
                                    }
                                }
                            }
                    );
                } else if (!apiRequestSent) {
                    // ML sem checkout (liberação contínua)
                    Log.d(TAG, "[RESPONSE] ML contínuo - sem API");
                    if (listener != null) {
                        listener.onOrderComplete(currentMlReceived);
                    }
                } else {
                    Log.w(TAG, "[API] Já foi enviado para API, ignorando duplicata");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "[ERROR] Erro ao processar resposta: " + e.getMessage());
            if (listener != null) {
                listener.onError("Erro ao processar resposta: " + e.getMessage());
            }
        }
    }

    /**
     * Reset de estado da operação
     */
    private void resetOperationState() {
        currentCheckoutId = null;
        currentAndroidId = null;
        currentMlRequested = null;
        currentMlReceived = 0;
        apiRequestSent = false;
        Log.d(TAG, "[STATE] Operação resetada");
    }

    // Getters

    public BleState getState() {
        return bleManager.getState();
    }

    public boolean isConnected() {
        return bleManager.isConnectedDevice();
    }
}
