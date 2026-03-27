package com.example.choppontap.api;

import android.content.Context;
import android.util.Log;

import com.example.choppontap.ApiHelper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * Gerenciador de API para envio de dados de liberação
 * Garante que cada requisição seja enviada APENAS UMA VEZ
 */
public class ApiManager {
    private static final String TAG = "ApiManager";
    private static ApiManager instance;
    private static final Object lock = new Object();

    private final Context context;
    private final ApiHelper apiHelper;

    /**
     * Singleton
     */
    public static ApiManager getInstance(Context context) {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new ApiManager(context);
                }
            }
        }
        return instance;
    }

    private ApiManager(Context context) {
        this.context = context;
        this.apiHelper = new ApiHelper(context);
    }

    /**
     * Interface de callback
     */
    public interface ApiCallback {
        void onSuccess(String response);
        void onFailure(String error);
    }

    /**
     * Envia liberação completa para API
     * Chamado APENAS quando receber resposta "ML" do ESP32
     */
    public void sendOrderCompletion(Integer mlReleased, String checkoutId, String androidId, ApiCallback callback) {
        Log.d(TAG, "[API] Enviando conclusão do pedido: " + mlReleased + "ml");

        Map<String, String> body = new HashMap<>();
        body.put("android_id", androidId);
        body.put("qtd_ml", String.valueOf(mlReleased));
        body.put("checkout_id", checkoutId);

        apiHelper.sendPost(body, "liberacao.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                String error = "Erro de rede: " + e.getMessage();
                Log.e(TAG, "[API] " + error);
                if (callback != null) {
                    callback.onFailure(error);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        String body = response.body().string();
                        Log.d(TAG, "[API] Sucesso: " + body);
                        if (callback != null) {
                            callback.onSuccess(body);
                        }
                    } else {
                        String error = "Erro HTTP: " + response.code();
                        Log.e(TAG, "[API] " + error);
                        if (callback != null) {
                            callback.onFailure(error);
                        }
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * Envia dados de volume parcial (contínuo)
     */
    public void sendPartialVolume(Integer mlVolume, String androidId, ApiCallback callback) {
        Log.d(TAG, "[API] Enviando volume parcial: " + mlVolume + "ml");

        Map<String, String> body = new HashMap<>();
        body.put("android_id", androidId);
        body.put("qtd_ml", String.valueOf(mlVolume));

        apiHelper.sendPost(body, "liquido_liberado.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                String error = "Erro de rede: " + e.getMessage();
                Log.e(TAG, "[API] " + error);
                if (callback != null) {
                    callback.onFailure(error);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "[API] Volume parcial enviado com sucesso");
                        if (callback != null) {
                            callback.onSuccess("OK");
                        }
                    } else {
                        String error = "Erro HTTP: " + response.code();
                        Log.e(TAG, "[API] " + error);
                        if (callback != null) {
                            callback.onFailure(error);
                        }
                    }
                } finally {
                    response.close();
                }
            }
        });
    }
}
