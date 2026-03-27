package com.example.choppontap;

import android.app.Application;
import android.content.Intent;
import android.os.Build;
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
        Log.i(TAG, "[PROCESS] 🚀 PROCESS STARTED - ChoppOn APP INICIALIZADA");
        Log.i(TAG, "=================================");

        // 1. DETECTAR CRASH OCULTO - UncaughtExceptionHandler global
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                Log.e(TAG, "[CRASH] 💥 CRASH OCULTO DETECTADO!");
                Log.e(TAG, "[CRASH] Thread: " + thread.getName());
                Log.e(TAG, "[CRASH] Exception: " + throwable.getClass().getName());
                Log.e(TAG, "[CRASH] Message: " + throwable.getMessage());
                Log.e(TAG, "[CRASH] StackTrace:", throwable);

                // Log adicional para análise
                Log.e(TAG, "[CRASH] System info - PID: " + android.os.Process.myPid());
                Log.e(TAG, "[CRASH] System info - TID: " + android.os.Process.myTid());

                // Não chamar System.exit() - deixar o sistema lidar
                // Isso permite que o log seja enviado antes do crash
            }
        });

        // 2. MONITORAR CICLO DE VIDA DA APPLICATION
        Log.i(TAG, "[LIFECYCLE] Application.onCreate() iniciado");

        // Valida configuração de API
        try {
            ApiConfig.validate();
            Log.i(TAG, "✅ Configuração OK");
        } catch (Exception e) {
            Log.e(TAG, "❌ ERRO NA CONFIGURAÇÃO: " + e.getMessage());
            throw new RuntimeException("Falha crítica na inicialização", e);
        }

        // FIX: Iniciar BLE Service apenas uma vez no Application
        Log.i(TAG, "[BLE] Iniciando BluetoothServiceIndustrial no Application...");
        Intent serviceIntent = new Intent(this, BluetoothServiceIndustrial.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        Log.i(TAG, "[LIFECYCLE] Application.onCreate() concluído");
        Log.i(TAG, "=================================");
    }

    @Override
    public void onTerminate() {
        Log.i(TAG, "[PROCESS] 💀 PROCESS ENDED - Application.onTerminate()");
        super.onTerminate();
    }

    @Override
    public void onLowMemory() {
        Log.w(TAG, "[LIFECYCLE] ⚠️ Application.onLowMemory() - memória baixa!");
        super.onLowMemory();
    }

    @Override
    public void onTrimMemory(int level) {
        Log.i(TAG, "[LIFECYCLE] Application.onTrimMemory() - level: " + level);
        super.onTrimMemory(level);
    }
}
