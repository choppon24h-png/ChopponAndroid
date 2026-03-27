package com.example.choppontap;

import android.content.Context;
import android.util.Log;

import java.util.Map;

import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Helper para requisições HTTP
 * Abstração sobre OkHttpClient
 */
public class ApiHelper {
    private static final String TAG = "ApiHelper";
    private static final String BASE_URL = "http://192.168.1.100/choppon/api/"; // Ajustar conforme seu servidor

    private final OkHttpClient httpClient;
    private final Context context;

    public ApiHelper(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient();
    }

    /**
     * Envia POST request
     */
    public void sendPost(Map<String, String> body, String endpoint, Callback callback) {
        try {
            String url = BASE_URL + endpoint;
            Log.d(TAG, "[POST] " + url);

            // Build form body
            FormBody.Builder bodyBuilder = new FormBody.Builder();
            for (Map.Entry<String, String> entry : body.entrySet()) {
                bodyBuilder.add(entry.getKey(), entry.getValue());
                Log.d(TAG, "  " + entry.getKey() + " = " + entry.getValue());
            }

            RequestBody requestBody = bodyBuilder.build();

            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build();

            httpClient.newCall(request).enqueue(callback);

        } catch (Exception e) {
            Log.e(TAG, "[ERROR] Erro ao enviar POST: " + e.getMessage());
        }
    }

    /**
     * Envia GET request
     */
    public void sendGet(String endpoint, Callback callback) {
        try {
            String url = BASE_URL + endpoint;
            Log.d(TAG, "[GET] " + url);

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            httpClient.newCall(request).enqueue(callback);

        } catch (Exception e) {
            Log.e(TAG, "[ERROR] Erro ao enviar GET: " + e.getMessage());
        }
    }
}
