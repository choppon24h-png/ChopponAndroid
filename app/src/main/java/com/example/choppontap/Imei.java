package com.example.choppontap;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.provider.Settings.Secure;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Imei - Tela de Vínculo de Dispositivo
 *
 * CORREÇÕES v3.0 — Crash SecurityException BLUETOOTH_SCAN:
 *
 *   CAUSA RAIZ (analisada no log):
 *     ChoppOnApplication iniciava BluetoothServiceIndustrial antes das permissões
 *     de runtime (BLUETOOTH_SCAN/BLUETOOTH_CONNECT) serem solicitadas. O serviço
 *     tentava fazer scan, lançava SecurityException e o processo morria.
 *     Resultado: usuário via o diálogo de permissão, clicava "Permitir", mas o
 *     serviço já havia crashado e nunca reiniciava o scan. ESP32 com LED azul,
 *     tablet mostrando "Desconectado".
 *
 *   SOLUÇÕES APLICADAS:
 *     1. ChoppOnApplication NÃO mais inicia o BLE Service.
 *     2. checkPermissionsAndRequest() agora verifica se TODAS as permissões já
 *        foram concedidas. Se sim, inicia o BLE Service imediatamente.
 *        Se não, solicita as permissões.
 *     3. onRequestPermissionsResult() inicia o BLE Service após concessão e
 *        depois dispara a requisição de API.
 *     4. onResume() verifica se o serviço está rodando e o inicia se necessário,
 *        garantindo que após retornar do diálogo de permissão o scan seja feito.
 *     5. startBleServiceIfPermitted() centraliza a lógica de início do serviço
 *        com verificação de permissão, evitando duplicação.
 */
public class Imei extends AppCompatActivity {

    private static final String TAG = "IMEI_CHECK";
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final int MAX_RETRY_ATTEMPTS = 5;

    String android_id;
    private int retryAttempt = 0;
    private View rootView;
    private TextView txtStatus;
    private Button btnUpdate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_imei);
        setupFullscreen();

        rootView  = findViewById(R.id.main);
        txtStatus = findViewById(R.id.txtStatus);
        btnUpdate = findViewById(R.id.btnUpdate);

        if (rootView == null) {
            Log.e(TAG, "View raiz (R.id.main) não encontrada!");
        }

        android_id = Secure.getString(this.getContentResolver(), Secure.ANDROID_ID);

        if (btnUpdate != null) {
            btnUpdate.setOnClickListener(v -> {
                retryAttempt = 0;
                warmupAndSendRequest();
            });
        } else {
            Log.e(TAG, "Botão btnUpdate não encontrado!");
        }

        TextView txtTap = findViewById(R.id.txtTap);
        if (txtTap != null) {
            txtTap.setText(android_id);
        } else {
            Log.e(TAG, "TextView txtTap não encontrado!");
        }

        // Verifica e solicita permissões BLE. Se já concedidas, inicia o serviço.
        checkPermissionsAndRequest();
    }

    private void setupFullscreen() {
        WindowInsetsControllerCompat wic =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        wic.hide(WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        retryAttempt = 0;

        // Garante que o serviço BLE está rodando após retornar do diálogo de permissão.
        // Se o usuário acabou de conceder a permissão e voltou para esta tela,
        // o serviço precisa ser iniciado agora.
        startBleServiceIfPermitted();

        warmupAndSendRequest();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BLE Service — início seguro após permissões
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inicia o BluetoothServiceIndustrial SOMENTE se as permissões BLE necessárias
     * já foram concedidas. Caso contrário, não faz nada (o serviço será iniciado
     * após onRequestPermissionsResult).
     *
     * Esta é a única forma segura de iniciar o serviço no Android 12+:
     * verificar BLUETOOTH_SCAN antes de qualquer operação BLE.
     */
    private void startBleServiceIfPermitted() {
        // Verifica se as permissões necessárias foram concedidas
        if (!hasBlePermissions()) {
            Log.w(TAG, "[BLE] Permissões BLE ainda não concedidas — serviço não iniciado");
            return;
        }

        // Evita iniciar o serviço se já está rodando
        if (BluetoothServiceIndustrial.isRunning()) {
            Log.i(TAG, "[BLE] BluetoothService já está rodando — sem necessidade de reiniciar");
            return;
        }

        Log.i(TAG, "[BLE] Permissões OK — iniciando BluetoothServiceIndustrial");
        Intent serviceIntent = new Intent(this, BluetoothServiceIndustrial.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    /**
     * Verifica se todas as permissões BLE necessárias foram concedidas.
     */
    private boolean hasBlePermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Warm-up + envio
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executa o warm-up do servidor em thread separada e, ao concluir,
     * dispara a requisição principal na UI thread.
     */
    private void warmupAndSendRequest() {
        updateStatusText("Conectando ao servidor...");
        new Thread(() -> {
            new ApiHelper(this).warmupServer();
            runOnUiThread(this::sendRequestWithRetry);
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Retry
    // ─────────────────────────────────────────────────────────────────────────

    private long getRetryDelay(int attemptNumber) {
        switch (attemptNumber) {
            case 1:  return 1000;
            case 2:  return 3000;
            case 3:  return 5000;
            case 4:  return 8000;
            default: return 3000;
        }
    }

    private void sendRequestWithRetry() {
        if (!isNetworkAvailable()) {
            Log.e(TAG, "Sem conexão de internet");
            showMessage("Sem conexão de internet. Verifique sua rede.", Snackbar.LENGTH_LONG);
            updateStatusText("Sem conexão de internet");
            return;
        }

        retryAttempt++;
        Log.i(TAG, "Tentativa " + retryAttempt + " de " + MAX_RETRY_ATTEMPTS);
        updateStatusText("Tentativa " + retryAttempt + " de " + MAX_RETRY_ATTEMPTS + "...");
        sendRequest();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Requisição principal
    // ─────────────────────────────────────────────────────────────────────────

    public void sendRequest() {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);

        Log.d(TAG, "Buscando TAP na API com ID: " + android_id);

        new ApiHelper(this).sendPost(body, "verify_tap.php", new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Erro de rede: " + e.getMessage());

                if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                    long delay = getRetryDelay(retryAttempt);
                    Log.i(TAG, "Agendando retry em " + delay + "ms...");
                    runOnUiThread(() -> {
                        showMessage("Tentativa " + retryAttempt + " falhou. Retentando em "
                                + (delay / 1000) + "s...", Snackbar.LENGTH_SHORT);
                        updateStatusText("Retentando em " + (delay / 1000) + "s...");
                    });
                    new Handler(Looper.getMainLooper()).postDelayed(
                            Imei.this::sendRequestWithRetry, delay);
                } else {
                    Log.e(TAG, "Falha após " + MAX_RETRY_ATTEMPTS + " tentativas");
                    runOnUiThread(() -> {
                        updateStatusText("Falha ao conectar. Toque em Tentar Novamente.");
                        showMessageWithRetry("Erro ao conectar após " +
                                MAX_RETRY_ATTEMPTS + " tentativas. Verifique sua conexão.");
                    });
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                Log.i(TAG, "Resposta da API: HTTP " + response.code());

                final String jsonResponse;
                final int httpCode = response.code();
                try (ResponseBody responseBody = response.body()) {
                    if (responseBody == null) {
                        Log.e(TAG, "Corpo da resposta nulo (HTTP " + httpCode + ")");
                        runOnUiThread(() ->
                                showMessage("Resposta vazia do servidor (HTTP " + httpCode + ")",
                                        Snackbar.LENGTH_LONG));
                        return;
                    }

                    jsonResponse = responseBody.string();
                    Log.d(TAG, "JSON recebido (HTTP " + httpCode + "): " + jsonResponse);

                    if (!response.isSuccessful()) {
                        Log.e(TAG, "Resposta não-sucesso: HTTP " + httpCode);

                        if (httpCode == 404) {
                            String errMsg = "Dispositivo não cadastrado no servidor.";
                            try {
                                int braceIdx = jsonResponse.indexOf('{');
                                String cleanJson = braceIdx >= 0 ? jsonResponse.substring(braceIdx) : jsonResponse;
                                org.json.JSONObject errJson = new org.json.JSONObject(cleanJson);
                                if (errJson.has("error")) {
                                    errMsg = errJson.getString("error");
                                } else if (errJson.has("message")) {
                                    errMsg = errJson.getString("message");
                                }
                            } catch (Exception ignored) {}

                            final String finalMsg = errMsg;
                            Log.w(TAG, "HTTP 404 — TAP não encontrada: " + finalMsg);
                            runOnUiThread(() -> {
                                updateStatusText("Dispositivo não cadastrado.");
                                showMessageWithRetry("" + finalMsg +
                                        "\nVerifique se este tablet está vinculado no painel.");
                            });
                        } else if (httpCode == 401) {
                            Log.e(TAG, "HTTP 401 — Token JWT inválido ou expirado");
                            runOnUiThread(() -> {
                                updateStatusText("Erro de autenticação.");
                                showMessage("Erro de autenticação (401). Verifique a chave da API.",
                                        Snackbar.LENGTH_LONG);
                            });
                        } else {
                            Log.e(TAG, "HTTP " + httpCode + " — erro inesperado");
                            if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                                long delay = getRetryDelay(retryAttempt);
                                runOnUiThread(() -> updateStatusText("Retentando em " + (delay/1000) + "s..."));
                                new Handler(Looper.getMainLooper()).postDelayed(
                                        Imei.this::sendRequestWithRetry, delay);
                            } else {
                                runOnUiThread(() ->
                                        showMessageWithRetry("Erro HTTP " + httpCode +
                                                ". Toque em Tentar Novamente."));
                            }
                        }
                        return;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Erro ao ler resposta da API.", e);
                    runOnUiThread(() ->
                            showMessage("Erro ao ler resposta do servidor", Snackbar.LENGTH_LONG));
                    return;
                }

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        Log.w(TAG, "Activity destruída. Abortando navegação.");
                        return;
                    }
                    try {
                        Log.i(TAG, "Processando JSON da API...");

                        // Remove possíveis caracteres antes do JSON (BOM, whitespace)
                        String cleanJson = jsonResponse;
                        int braceIdx = cleanJson.indexOf('{');
                        if (braceIdx > 0) {
                            Log.w(TAG, braceIdx + " caractere(s) antes do JSON removidos");
                            cleanJson = cleanJson.substring(braceIdx);
                        }

                        Tap tap = new Gson().fromJson(cleanJson, Tap.class);

                        if (tap != null && tap.bebida != null && !tap.bebida.isEmpty()) {
                            Log.i(TAG, "TAP validada. bebida=" + tap.bebida
                                    + " mac=" + tap.esp32_mac
                                    + " status=" + tap.tap_status);
                            showMessage("Conectado com sucesso!", Snackbar.LENGTH_SHORT);
                            updateStatusText("Conectado!");

                            if (tap.esp32_mac != null && !tap.esp32_mac.isEmpty()) {
                                saveMacLocally(tap.esp32_mac);
                            }

                            // Verifica se a TAP está desativada
                            if (tap.tap_status != null && tap.tap_status == 0) {
                                Log.w(TAG, "tap_status=0 → TAP OFFLINE → OfflineTap");
                                Intent offlineIntent = new Intent(
                                        getApplicationContext(), OfflineTap.class);
                                offlineIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(offlineIntent);
                                finish();
                                return;
                            }

                            // CORREÇÃO: Garante que o BLE Service está rodando ANTES de
                            // navegar para Home. Sem isso, Home.bindBluetoothService()
                            // tentaria fazer bind em um serviço que ainda não existe.
                            startBleServiceIfPermitted();

                            Intent it = new Intent(getApplicationContext(), Home.class);
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            it.putExtra("bebida", tap.bebida);
                            it.putExtra("preco",
                                    (tap.preco != null) ? tap.preco.floatValue() : 0.0f);
                            it.putExtra("imagem", tap.image);
                            it.putExtra("cartao", (tap.cartao != null) && tap.cartao);

                            Log.d(TAG, "Navegando para Home...");
                            startActivity(it);
                            finish();
                        } else {
                            Log.w(TAG, "Dispositivo não configurado na API.");
                            updateStatusText("Dispositivo não configurado no servidor.");
                            showMessage("Dispositivo não configurado no servidor.",
                                    Snackbar.LENGTH_LONG);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "ERRO FATAL no processamento ou navegação.", t);
                        showMessage("Erro ao processar resposta do servidor.",
                                Snackbar.LENGTH_LONG);
                    }
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilitários
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao verificar conectividade", e);
            return false;
        }
    }

    private void updateStatusText(String message) {
        if (txtStatus != null) {
            runOnUiThread(() -> txtStatus.setText(message));
        }
        Log.d(TAG, "[STATUS] " + message);
    }

    private void showMessage(String message, int duration) {
        if (rootView != null) {
            Snackbar.make(rootView, message, duration).show();
        } else {
            Log.w(TAG, "rootView não disponível. Mensagem: " + message);
        }
    }

    private void showMessageWithRetry(String message) {
        if (rootView != null) {
            Snackbar.make(rootView, message, Snackbar.LENGTH_INDEFINITE)
                    .setAction("Tentar Novamente", v -> {
                        retryAttempt = 0;
                        warmupAndSendRequest();
                    })
                    .show();
        } else {
            Log.w(TAG, "rootView não disponível. Mensagem: " + message);
        }
    }

    private void saveMacLocally(String mac) {
        SharedPreferences prefs = getSharedPreferences("tap_config", Context.MODE_PRIVATE);
        prefs.edit().putString("esp32_mac", mac).apply();
        Log.i(TAG, "MAC " + mac + " salvo localmente.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissões BLE — Android 12+
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifica se as permissões BLE necessárias foram concedidas.
     * Se sim, inicia o serviço BLE imediatamente.
     * Se não, solicita as permissões ao usuário.
     *
     * FLUXO CORRETO Android 12+:
     *   1. checkPermissionsAndRequest() → solicita permissões se necessário
     *   2. Usuário clica "Permitir" no diálogo do sistema
     *   3. onRequestPermissionsResult() → inicia BLE Service + dispara API
     *
     * Se as permissões já foram concedidas anteriormente:
     *   1. checkPermissionsAndRequest() → inicia BLE Service diretamente
     *   2. warmupAndSendRequest() é chamado em onResume() normalmente
     */
    private void checkPermissionsAndRequest() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            Log.i(TAG, "[PERM] Solicitando " + permissionsNeeded.size() + " permissão(ões) BLE...");
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            // Todas as permissões já concedidas — inicia o serviço imediatamente
            Log.i(TAG, "[PERM] Todas as permissões BLE já concedidas — iniciando serviço");
            startBleServiceIfPermitted();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Log.i(TAG, "[PERM] Todas as permissões BLE concedidas pelo usuário");
                // Inicia o serviço BLE agora que as permissões foram concedidas
                startBleServiceIfPermitted();
                // Dispara a requisição de API
                sendRequestWithRetry();
            } else {
                Log.w(TAG, "[PERM] Uma ou mais permissões BLE negadas pelo usuário");
                showMessage("Permissão Bluetooth necessária para o funcionamento do app.",
                        Snackbar.LENGTH_LONG);
                updateStatusText("Permissão Bluetooth necessária.");
            }
        }
    }
}
