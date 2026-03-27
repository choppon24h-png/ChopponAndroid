package com.example.choppontap;

import android.app.Application;
import android.util.Log;

/**
 * Application class para inicialização do app
 * Valida configurações críticas na startup
 */
public class ChoppOnApplication extends Application {
    private static final String TAG = "ChoppOn";

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "=================================");
        Log.i(TAG, "🚀 ChoppOn APP INICIALIZADA");
        Log.i(TAG, "=================================");

        // Valida configuração de API
        try {
            ApiConfig.validate();
            Log.i(TAG, "✅ Configuração OK");
        } catch (Exception e) {
            Log.e(TAG, "❌ ERRO NA CONFIGURAÇÃO: " + e.getMessage());
            throw new RuntimeException("Falha crítica na inicialização", e);
        }

        Log.i(TAG, "=================================");
    }
}
