package com.example.choppontap;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.Map;

import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class ApiHelper {
    private static final String TAG = "ApiHelper";

    private final OkHttpClient httpClient;
    private final Context context;

    public ApiHelper(Context context) {
        this.context = context;
        this.httpClient = new OkHttpClient();
    }

    public void sendPost(Map<String, String> body, String endpoint, Callback callback) {
        try {
            String url = ApiConfig.getBaseUrl() + endpoint;
            Log.d(TAG, "[POST] " + url);

            FormBody.Builder bodyBuilder = new FormBody.Builder();
            for (Map.Entry<String, String> entry : body.entrySet()) {
                bodyBuilder.add(entry.getKey(), entry.getValue());
            }

            RequestBody requestBody = bodyBuilder.build();
            Request request = new Request.Builder().url(url).post(requestBody).build();
            httpClient.newCall(request).enqueue(callback);

        } catch (Exception e) {
            Log.e(TAG, "[ERROR] POST: " + e.getMessage());
        }
    }

    public void sendGet(String endpoint, Callback callback) {
        try {
            String url = ApiConfig.getBaseUrl() + endpoint;
            Log.d(TAG, "[GET] " + url);
            Request request = new Request.Builder().url(url).get().build();
            httpClient.newCall(request).enqueue(callback);
        } catch (Exception e) {
            Log.e(TAG, "[ERROR] GET: " + e.getMessage());
        }
    }

    // Métodos legados chamados por outras activities
    public Bitmap getImage(Object tap) {
        Log.d(TAG, "[getImage] Legacy method");
        return null;
    }

    public void warmupServer() {
        Log.d(TAG, "[warmupServer] Legacy method");
    }
}
