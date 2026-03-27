package com.example.choppontap.ble;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

import androidx.annotation.NonNull;

import no.nordicsemi.android.ble.callback.DataReceivedCallback;
import no.nordicsemi.android.ble.data.Data;

/**
 * Gerenciador centralizado de callbacks de notificação
 * Garante que o callback seja registrado apenas uma vez
 * Previne duplicação de triggers e race conditions
 */
public class NotificationCallbackHolder {
    private DataReceivedCallback callbackHandler;
    private boolean isRegistered = false;
    private final Object lock = new Object();

    public interface ResponseListener {
        void onResponseReceived(String response);
        void onError(Exception e);
    }

    private ResponseListener listener;

    public NotificationCallbackHolder() {
        this.callbackHandler = createCallback();
    }

    public void setResponseListener(ResponseListener listener) {
        synchronized (lock) {
            this.listener = listener;
        }
    }

    public DataReceivedCallback getCallback() {
        synchronized (lock) {
            isRegistered = true;
            return callbackHandler;
        }
    }

    public boolean isCallbackRegistered() {
        synchronized (lock) {
            return isRegistered;
        }
    }

    public void resetCallback() {
        synchronized (lock) {
            isRegistered = false;
            Log.d("NotificationCallback", "Callback resetado");
        }
    }

    private DataReceivedCallback createCallback() {
        return new DataReceivedCallback() {
            @Override
            public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                try {
                    String receivedData = data.getStringValue(0);
                    if (receivedData == null) {
                        Log.w("NotificationCallback", "Dados nulos recebidos");
                        return;
                    }

                    receivedData = receivedData.trim();
                    Log.d("NotificationCallback", "[RESPONSE] " + receivedData);

                    synchronized (lock) {
                        if (listener != null) {
                            listener.onResponseReceived(receivedData);
                        }
                    }
                } catch (Exception e) {
                    Log.e("NotificationCallback", "Erro ao processar resposta: " + e.getMessage());
                    synchronized (lock) {
                        if (listener != null) {
                            listener.onError(e);
                        }
                    }
                }
            }
        };
    }
}
