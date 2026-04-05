package com.example.choppontap;

import android.app.Application;
import android.util.Log;

/**
 * Application class para inicialização do app.
 *
 * CORREÇÃO v3.0 — Crash SecurityException BLUETOOTH_SCAN:
 *   CAUSA: Esta classe iniciava BluetoothServiceIndustrial incondicionalmente
 *   em onCreate(), antes de qualquer permissão runtime ser solicitada.
 *   No Android 12+, BLUETOOTH_SCAN é uma permissão de runtime — se o serviço
 *   tentar fazer scan antes da permissão ser concedida, lança SecurityException
 *   e o processo morre. Resultado: tela de permissão aparece, usuário clica
 *   "Permitir", mas o serviço já crashou e nunca reinicia o scan.
 *
 *   SOLUÇÃO: Remover o início do BLE daqui. O BluetoothServiceIndustrial é
 *   iniciado pela Imei.java APÓS as permissões serem concedidas, e pela
 *   Home.java via bindBluetoothService(). Isso garante que o scan só começa
 *   quando BLUETOOTH_SCAN já foi autorizado pelo usuário.
 *
 *   O serviço usa singleton (sInstance) para evitar instâncias duplicadas,
 *   então não há risco de múltiplas instâncias mesmo com o início tardio.
 */
public class ChoppOnApplication extends Application {
    private static final String TAG = "ChoppOn";

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "=================================");
        Log.i(TAG, "[PROCESS] PROCESS STARTED - ChoppOn APP INICIALIZADA");
        Log.i(TAG, "=================================");

        // 1. DETECTAR CRASH OCULTO - UncaughtExceptionHandler global
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "[CRASH] CRASH OCULTO DETECTADO!");
            Log.e(TAG, "[CRASH] Thread: " + thread.getName());
            Log.e(TAG, "[CRASH] Exception: " + throwable.getClass().getName());
            Log.e(TAG, "[CRASH] Message: " + throwable.getMessage());
            Log.e(TAG, "[CRASH] StackTrace:", throwable);
            Log.e(TAG, "[CRASH] PID: " + android.os.Process.myPid());
            Log.e(TAG, "[CRASH] TID: " + android.os.Process.myTid());
            // Não chamar System.exit() — deixa o sistema lidar para que o log seja enviado
        });

        // 2. Valida configuração de API
        try {
            ApiConfig.validate();
            Log.i(TAG, "[CONFIG] Configuracao OK");
        } catch (Exception e) {
            Log.e(TAG, "[CONFIG] ERRO NA CONFIGURACAO: " + e.getMessage());
            throw new RuntimeException("Falha critica na inicializacao", e);
        }

        // NOTA: BluetoothServiceIndustrial NÃO é iniciado aqui.
        // Motivo: No Android 12+, BLUETOOTH_SCAN é permissão de runtime.
        // O serviço é iniciado por Imei.java após permissões concedidas,
        // e por Home.java via bindBluetoothService().
        Log.i(TAG, "[BLE] BluetoothService sera iniciado apos concessao de permissoes (Imei/Home)");

        Log.i(TAG, "[LIFECYCLE] Application.onCreate() concluido");
        Log.i(TAG, "=================================");
    }

    @Override
    public void onTerminate() {
        Log.i(TAG, "[PROCESS] PROCESS ENDED - Application.onTerminate()");
        super.onTerminate();
    }

    @Override
    public void onLowMemory() {
        Log.w(TAG, "[LIFECYCLE] Application.onLowMemory() - memoria baixa!");
        super.onLowMemory();
    }

    @Override
    public void onTrimMemory(int level) {
        Log.i(TAG, "[LIFECYCLE] Application.onTrimMemory() - level: " + level);
        super.onTrimMemory(level);
    }
}
