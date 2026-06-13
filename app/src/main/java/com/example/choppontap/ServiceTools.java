package com.example.choppontap;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ServiceTools — Tela de ferramentas do técnico.
 *
 * Fluxo de DESATIVAÇÃO da TAP:
 *   1. Chama toggle_tap.php com action=desativar
 *   2. Recebe success=true, status=offline
 *   3. Desconecta o Bluetooth (mBluetoothService.disconnect())
 *   4. Navega para OfflineTap com FLAG_ACTIVITY_CLEAR_TOP + finishAffinity()
 *
 * Fluxo de ATIVAÇÃO da TAP:
 *   1. Chama toggle_tap.php com action=ativar
 *   2. Recebe success=true, status=online
 *   3. Inicia reconexão Bluetooth (mBluetoothService.scanLeDevice(true))
 *   4. Navega para Home com FLAG_ACTIVITY_CLEAR_TOP + finishAffinity()
 *      → Home.onCreate() chama sendRequestCheckSecurity() + bindBluetoothService()
 *        que recarrega bebida, imagem, preço e conecta ao ESP32
 *
 * Sistema de ATUALIZAÇÃO DE APK (v3.3.0 — migrado do AcessoMaster):
 *   - checkAppUpdate(Context)  → GET /api/app_version.php (com fallback para version.json)
 *   - downloadNewVersion(...)  → baixa o APK via DownloadManager
 *   - installApk(File)         → instala via FileProvider (Android 7+)
 *   Não interfere em nenhuma lógica existente.
 *   Acionado apenas pelo botão btnAtualizarApp.
 *
 * NOTA: A lógica de atualização foi centralizada aqui (ServiceTools).
 *       O AcessoMaster contém apenas QR Code e Senha.
 */
public class ServiceTools extends AppCompatActivity {

    private static final String TAG = "SERVICE_TOOLS";

    // ── OTA: URL base da pasta de APKs no servidor ──────────────────────────
    // Estrutura esperada no servidor:
    //   https://www.choppon24h.com.br/apk/version.json  ← fonte primária (JSON)
    //   https://www.choppon24h.com.br/apk/APK01.apk    ← versão base (instalada)
    //   https://www.choppon24h.com.br/apk/APK02.apk    ← versão superior → atualiza
    //   https://www.choppon24h.com.br/apk/             ← fallback: parse do HTML
    //
    // Regra de versão: o número extraído do nome do arquivo (APK01→1, APK02→2,
    // chopponv1→1, chopponv2→2) é comparado com APK_BASE_VERSION (1).
    // Qualquer número maior dispara o download.
    private static final String OTA_BASE_URL      = "https://www.choppon24h.com.br/apk/";
    private static final String OTA_VERSION_JSON  = OTA_BASE_URL + "version.json";
    private static final int    APK_BASE_VERSION  = 1;   // APK01 = versão base instalada

    // ── Nome do arquivo APK salvo no Downloads ────────────────────────────────
    private static final String APK_FILE_NAME = "choppontap-update.apk";

    // ── Card de sistema ────────────────────────────────────────────────────
    private TextView txtInfoImei, txtInfoBluetooth, txtInfoWifi;

    // ── Card BLE (ESP32) ────────────────────────────────────────────────────
    private TextView txtBleMac, txtBleStatus, txtBleNome, txtBleAndroidId;
    private MaterialButton btnCopiarMac, btnCopiarAndroidId;

    // ── Card de leitora ───────────────────────────────────────────────────────
    private TextView txtLeitoraNome, txtLeitoraStatus, txtApiStatus, txtLeitoraMensagem;
    private TextView txtLeitoraBateria, txtLeitoraConexao, txtLeitoraFirmware;
    private LinearLayout layoutLeitoraDetalhes;
    private View viewStatusDot, viewApiDot;
    private ProgressBar progressLeitora;

    // ── Botões ────────────────────────────────────────────────────────────────
    private MaterialButton btnCalibrarPulsos, btnTempoAbertura, btnSairTools;
    private MaterialButton btnAtualizarLeitora, btnToggleTap;
    private MaterialButton btnAtualizarApp;          // NOVO — botão de atualização
    private ProgressBar progressToggle;
    private ProgressBar progressUpdate;              // NOVO — progresso do download

    // ── Estado da TAP ─────────────────────────────────────────────────────────
    private boolean tapAtiva = true;
    private String android_id;
    private boolean fromOffline = false;

    // ── Download em andamento ─────────────────────────────────────────────────
    private long mDownloadId = -1;
    private BroadcastReceiver mDownloadReceiver;

    // ── Bluetooth Service ─────────────────────────────────────────────────────
    private BluetoothServiceIndustrial mBluetoothService;
    private boolean mIsServiceBound = false;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBluetoothService = ((BluetoothServiceIndustrial.LocalBinder) service).getService();
            mIsServiceBound = true;
            Log.i(TAG, "BluetoothService vinculado ao ServiceTools");
            runOnUiThread(() -> {
                boolean conectado = mBluetoothService.getCurrentStatus().equals("ready") || mBluetoothService.getCurrentStatus().equals("connected");
                txtInfoBluetooth.setText("Bluetooth: " + (conectado ? "Conectado ao ESP32" : "Desconectado"));
                // Atualiza o card BLE with the status de conexão real
                atualizarStatusBle(conectado);
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mIsServiceBound = false;
            mBluetoothService = null;
            Log.w(TAG, "BluetoothService desvinculado");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_service_tools);
        // v5.2: Bloqueia o botão Voltar — redireciona para Home sem abrir AcessoMaster.
        setupBackBlock();

        fromOffline = getIntent().getBooleanExtra("from_offline", false);

        // ── Referências ───────────────────────────────────────────────────────
        txtInfoImei           = findViewById(R.id.txtInfoImei);
        txtInfoBluetooth      = findViewById(R.id.txtInfoBluetooth);
        txtInfoWifi           = findViewById(R.id.txtInfoWifi);

        txtLeitoraNome        = findViewById(R.id.txtLeitoraNome);
        txtLeitoraStatus      = findViewById(R.id.txtLeitoraStatus);
        txtApiStatus          = findViewById(R.id.txtApiStatus);
        txtLeitoraMensagem    = findViewById(R.id.txtLeitoraMensagem);
        txtLeitoraBateria     = findViewById(R.id.txtLeitoraBateria);
        txtLeitoraConexao     = findViewById(R.id.txtLeitoraConexao);
        txtLeitoraFirmware    = findViewById(R.id.txtLeitoraFirmware);
        layoutLeitoraDetalhes = findViewById(R.id.layoutLeitoraDetalhes);
        viewStatusDot         = findViewById(R.id.viewStatusDot);
        viewApiDot            = findViewById(R.id.viewApiDot);
        progressLeitora       = findViewById(R.id.progressLeitora);

        btnCalibrarPulsos     = findViewById(R.id.btnCalibrarPulsos);
        btnTempoAbertura      = findViewById(R.id.btnTempoAbertura);
        btnSairTools          = findViewById(R.id.btnSairTools);
        btnAtualizarLeitora   = findViewById(R.id.btnAtualizarLeitora);
        btnToggleTap          = findViewById(R.id.btnToggleTap);
        progressToggle        = findViewById(R.id.progressToggle);

        // NOVO — botão e progresso de atualização do app
        btnAtualizarApp  = findViewById(R.id.btnAtualizarApp);
        progressUpdate   = findViewById(R.id.progressUpdate);

        // ── Card BLE (ESP32) ────────────────────────────────────────────────────
        txtBleMac       = findViewById(R.id.txtBleMac);
        txtBleStatus    = findViewById(R.id.txtBleStatus);
        txtBleNome      = findViewById(R.id.txtBleNome);
        txtBleAndroidId = findViewById(R.id.txtBleAndroidId);
        btnCopiarMac    = findViewById(R.id.btnCopiarMac);
        btnCopiarAndroidId = findViewById(R.id.btnCopiarAndroidId);

        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        // ── Inicialização ────────────────────────────────────────────────────
        loadSystemInfo();
        loadBleInfo();       // ← Card BLE: MAC, status e nome do ESP32
        loadReaderStatus();
        sincronizarEstadoTap();

        // ── Vincula o BluetoothService ────────────────────────────────────────
        bindService(new Intent(this, BluetoothServiceIndustrial.class), mServiceConnection, Context.BIND_AUTO_CREATE);

        // ── Listeners ─────────────────────────────────────────────────────────
        btnCalibrarPulsos.setOnClickListener(v ->
                startActivity(new Intent(this, CalibrarPulsos.class)));

        btnTempoAbertura.setOnClickListener(v ->
                startActivity(new Intent(this, ModificarTimeout.class)));

        btnSairTools.setOnClickListener(v -> finish());

        btnAtualizarLeitora.setOnClickListener(v -> loadReaderStatus());

        btnToggleTap.setOnClickListener(v -> confirmarToggleTap());

        // NOVO — listener do botão de atualização do app
        if (btnAtualizarApp != null) {
            btnAtualizarApp.setOnClickListener(v -> checkAppUpdate(this));
        }
    }

    // =========================================================================
    // SISTEMA OTA DE ATUALIZAÇÃO DE APK — v5.14
    // Consulta https://www.choppon24h.com.br/apk/
    // Fluxo:
    //   1. Tenta version.json (fonte primária)
    //   2. Fallback: parse do HTML da pasta /apk/ para extrair o APK mais recente
    //   3. Compara o número do APK do servidor com APK_BASE_VERSION (1 = APK01)
    //   4. Se servidor > instalado: exibe diálogo de download
    //   5. Se servidor <= instalado: informa que está atualizado
    // =========================================================================

    /**
     * Ponto de entrada público para verificação de atualização OTA.
     * Chamado pelo botão btnAtualizarApp ou por qualquer Activity.
     */
    public static void checkAppUpdate(Context context) {
        Log.i(TAG, "[OTA] Iniciando verificação — " + OTA_VERSION_JSON);

        if (context instanceof ServiceTools) {
            ServiceTools st = (ServiceTools) context;
            if (st.btnAtualizarApp != null) {
                st.btnAtualizarApp.setEnabled(false);
                st.btnAtualizarApp.setText("Verificando...");
            }
            if (st.progressUpdate != null) st.progressUpdate.setVisibility(View.VISIBLE);
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        // Passo 1: tenta version.json
        Request req = new Request.Builder()
                .url(OTA_VERSION_JSON)
                .addHeader("Cache-Control", "no-cache, no-store")
                .build();

        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                Log.w(TAG, "[OTA] version.json inacessível: " + e.getMessage() + " — usando fallback HTML");
                fetchApkListFallback(context, client);
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws IOException {
                try (okhttp3.ResponseBody rb = response.body()) {
                    String body = rb != null ? rb.string() : "";
                    Log.d(TAG, "[OTA] version.json HTTP=" + response.code() + " body=" + body.substring(0, Math.min(200, body.length())));

                    // Se retornou HTML (página de erro/redirect) ou não é JSON válido, usa fallback
                    if (!response.isSuccessful() || !body.trim().startsWith("{")) {
                        Log.w(TAG, "[OTA] version.json não é JSON válido — usando fallback HTML");
                        fetchApkListFallback(context, client);
                        return;
                    }

                    OtaInfo info = parseVersionJson(body);
                    if (info == null || info.apkNumber <= 0) {
                        Log.w(TAG, "[OTA] version.json inválido — usando fallback HTML");
                        fetchApkListFallback(context, client);
                        return;
                    }

                    Log.i(TAG, "[OTA] version.json OK — APK#" + info.apkNumber + " (" + info.apkName + ") base=" + APK_BASE_VERSION);
                    avaliarEExibir(context, info);
                }
            }
        });
    }

    /**
     * Fallback Tier-1: probe direto nos arquivos APK##.apk no servidor.
     *
     * Faz HEAD requests em APK02.apk, APK03.apk ... APK99.apk e retorna
     * o maior número que existir (HTTP 200). Se nenhum existir além da base,
     * cai no Fallback Tier-2 (parse do HTML).
     *
     * Isso é robusto mesmo quando o index.html não menciona o APK mais recente.
     */
    private static void fetchApkListFallback(Context context, OkHttpClient client) {
        Log.i(TAG, "[OTA] Fallback Tier-1: probe direto nos arquivos APK##.apk");

        // Executa em thread de background para não bloquear a UI
        new Thread(() -> {
            int highestFound = APK_BASE_VERSION; // começa da versão base
            String highestName = null;

            // Testa APK02 até APK20 (limite razoável para evitar muitas requisições)
            for (int n = APK_BASE_VERSION + 1; n <= 20; n++) {
                String paddedNum = String.format("%02d", n);  // 2 → "02", 10 → "10"
                String apkName   = "APK" + paddedNum + ".apk";
                String apkUrl    = OTA_BASE_URL + apkName;

                try {
                    Request headReq = new Request.Builder()
                            .url(apkUrl)
                            .head()   // HEAD: verifica existência sem baixar o arquivo
                            .addHeader("Cache-Control", "no-cache")
                            .build();

                    okhttp3.Response resp = client.newCall(headReq).execute();
                    int code = resp.code();
                    resp.close();

                    if (code == 200) {
                        Log.i(TAG, "[OTA] Probe: " + apkName + " existe (HTTP 200)");
                        highestFound = n;
                        highestName  = apkName;
                    } else if (code == 404) {
                        // A partir do primeiro 404 consecutivo, para a busca
                        Log.d(TAG, "[OTA] Probe: " + apkName + " não existe (HTTP 404) — parando");
                        break;
                    } else {
                        Log.w(TAG, "[OTA] Probe: " + apkName + " HTTP " + code + " — ignorando");
                    }
                } catch (IOException e) {
                    Log.w(TAG, "[OTA] Probe: " + apkName + " falhou: " + e.getMessage());
                    break;
                }
            }

            if (highestFound > APK_BASE_VERSION && highestName != null) {
                // Encontrou versão superior via probe
                OtaInfo info = new OtaInfo();
                info.apkNumber = highestFound;
                info.apkName   = highestName;
                info.apkUrl    = OTA_BASE_URL + highestName;
                info.changelog = "";
                Log.i(TAG, "[OTA] Probe concluído: versão mais alta = " + highestName + " (#" + highestFound + ")");
                avaliarEExibir(context, info);
            } else {
                // Nenhum APK superior encontrado via probe — tenta parse do HTML como Tier-2
                Log.i(TAG, "[OTA] Probe: nenhum APK superior encontrado — tentando parse HTML");
                fetchApkHtmlFallback(context, client);
            }
        }).start();
    }

    /**
     * Fallback Tier-2: parse do HTML da página /apk/.
     * Usado apenas se o probe direto não encontrou nenhum APK superior.
     */
    private static void fetchApkHtmlFallback(Context context, OkHttpClient client) {
        Log.i(TAG, "[OTA] Fallback Tier-2: parse HTML de " + OTA_BASE_URL);

        Request req = new Request.Builder()
                .url(OTA_BASE_URL)
                .addHeader("Cache-Control", "no-cache, no-store")
                .build();

        client.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                Log.e(TAG, "[OTA] Fallback Tier-2 falhou: " + e.getMessage());
                notificarErro(context, "Não foi possível verificar atualizações.\nVerifique a conexão Wi-Fi.");
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws IOException {
                try (okhttp3.ResponseBody rb = response.body()) {
                    if (!response.isSuccessful() || rb == null) {
                        notificarErro(context, "Servidor retornou erro " + response.code() + ".");
                        return;
                    }

                    String html = rb.string();
                    Log.d(TAG, "[OTA] HTML recebido (" + html.length() + " chars)");

                    OtaInfo info = extrairApkDoHtml(html);
                    if (info == null || info.apkNumber <= APK_BASE_VERSION) {
                        // Nenhum APK superior encontrado em nenhuma fonte
                        Log.i(TAG, "[OTA] Nenhuma atualização encontrada em nenhuma fonte");
                        OtaInfo atual = new OtaInfo();
                        atual.apkNumber = APK_BASE_VERSION;
                        atual.apkName   = "APK0" + APK_BASE_VERSION + ".apk";
                        atual.apkUrl    = "";
                        avaliarEExibir(context, atual);
                        return;
                    }

                    Log.i(TAG, "[OTA] APK encontrado no HTML: " + info.apkName + " (#" + info.apkNumber + ")");
                    avaliarEExibir(context, info);
                }
            }
        });
    }

    /**
     * Compara o número do APK do servidor com a versão base instalada
     * e exibe o diálogo ou mensagem de versão atual.
     *
     * Regra:
     *   servidor > APK_BASE_VERSION  → nova versão disponível
     *   servidor <= APK_BASE_VERSION → versão já está atual
     */
    private static void avaliarEExibir(Context context, OtaInfo info) {
        if (!(context instanceof AppCompatActivity)) return;
        AppCompatActivity activity = (AppCompatActivity) context;

        activity.runOnUiThread(() -> {
            if (context instanceof ServiceTools) ((ServiceTools) context).resetUpdateButton();

            if (info.apkNumber > APK_BASE_VERSION) {
                // Nova versão disponível
                String titulo = "⬆️ Nova atualização disponível";
                String msg = "Versão disponivel no servidor: " + info.apkName + "\n"
                        + "Versão base instalada: APK0" + APK_BASE_VERSION + "\n\n"
                        + (info.changelog != null && !info.changelog.isEmpty()
                            ? "O que há de novo:\n" + info.changelog + "\n\n" : "")
                        + "Deseja baixar e instalar agora?";

                Log.i(TAG, "[OTA] Atualização disponível: " + info.apkName + " (#" + info.apkNumber + " > " + APK_BASE_VERSION + ")");

                new AlertDialog.Builder(activity)
                        .setTitle(titulo)
                        .setMessage(msg)
                        .setPositiveButton("Baixar e instalar", (dialog, which) -> {
                            if (activity instanceof ServiceTools) {
                                ((ServiceTools) activity).downloadNewVersion(info.apkUrl);
                            }
                        })
                        .setNegativeButton("Agora não", null)
                        .show();

            } else {
                // Versão atual
                String msg = "✅ Versão atual!\n"
                        + "APK instalado: APK0" + APK_BASE_VERSION + "\n"
                        + "Servidor: " + info.apkName + "\n"
                        + "Nenhuma atualização disponível.";
                Log.i(TAG, "[OTA] Versão atual — servidor=" + info.apkNumber + " base=" + APK_BASE_VERSION);

                new AlertDialog.Builder(activity)
                        .setTitle("Versão atual")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    /**
     * Parseia o version.json do servidor.
     * Formato esperado:
     * {
     *   "apkNumber": 2,
     *   "apkName": "APK02.apk",
     *   "apkUrl": "https://www.choppon24h.com.br/apk/APK02.apk",
     *   "changelog": "Descrição das mudanças"
     * }
     * Também aceita o formato legado com "versionCode" e "apkUrl".
     */
    private static OtaInfo parseVersionJson(String json) {
        try {
            int idx = json.indexOf('{');
            if (idx > 0) json = json.substring(idx);

            com.google.gson.JsonObject obj =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();

            OtaInfo info = new OtaInfo();

            // Formato novo: apkNumber + apkName + apkUrl
            if (obj.has("apkNumber")) {
                info.apkNumber  = obj.get("apkNumber").getAsInt();
                info.apkName    = obj.has("apkName")  ? obj.get("apkName").getAsString()  : "APK0" + info.apkNumber + ".apk";
                info.apkUrl     = obj.has("apkUrl")   ? obj.get("apkUrl").getAsString()   : OTA_BASE_URL + info.apkName;
                info.changelog  = obj.has("changelog") ? obj.get("changelog").getAsString() : "";
                return info;
            }

            // Formato legado: versionCode + apkUrl
            if (obj.has("versionCode") && obj.has("apkUrl")) {
                info.apkNumber  = obj.get("versionCode").getAsInt();
                info.apkName    = obj.has("versionName") ? obj.get("versionName").getAsString() : "APK0" + info.apkNumber;
                info.apkUrl     = obj.get("apkUrl").getAsString();
                info.changelog  = obj.has("changelog") ? obj.get("changelog").getAsString() : "";
                return info;
            }

            Log.w(TAG, "[OTA] JSON sem campos reconhecidos");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "[OTA] parseVersionJson erro: " + e.getMessage());
            return null;
        }
    }

    /**
     * Extrai o APK mais recente referenciado no HTML da página /apk/.
     * Suporta padrões:
     *   APK01.apk, APK02.apk, APK10.apk  (padrão principal)
     *   chopponv1.apk, chopponv2.apk      (padrão alternativo)
     *   qualquer .apk referenciado em href ou onclick
     *
     * Retorna o APK com o MAIOR número encontrado.
     */
    private static OtaInfo extrairApkDoHtml(String html) {
        // Regex 1: APK seguido de números (APK01, APK02, APK10...)
        java.util.regex.Pattern p1 = java.util.regex.Pattern.compile(
                "APK(\\d+)\\.apk", java.util.regex.Pattern.CASE_INSENSITIVE);
        // Regex 2: chopponvN.apk
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile(
                "chopponv(\\d+)\\.apk", java.util.regex.Pattern.CASE_INSENSITIVE);
        // Regex 3: qualquer .apk referenciado (href ou src)
        java.util.regex.Pattern p3 = java.util.regex.Pattern.compile(
                "['\"]([^'\"]*\\.apk)['\"]" , java.util.regex.Pattern.CASE_INSENSITIVE);

        int bestNumber = -1;
        String bestName = null;

        // Testa padrão APK##
        java.util.regex.Matcher m1 = p1.matcher(html);
        while (m1.find()) {
            int n = Integer.parseInt(m1.group(1));
            if (n > bestNumber) { bestNumber = n; bestName = m1.group(0); }
        }

        // Testa padrão chopponvN
        java.util.regex.Matcher m2 = p2.matcher(html);
        while (m2.find()) {
            int n = Integer.parseInt(m2.group(1));
            if (n > bestNumber) { bestNumber = n; bestName = m2.group(0); }
        }

        // Fallback genérico: qualquer .apk entre aspas
        if (bestName == null) {
            java.util.regex.Matcher m3 = p3.matcher(html);
            if (m3.find()) {
                String rawName = m3.group(1);
                // Extrai número do nome se possível
                java.util.regex.Matcher numM = java.util.regex.Pattern.compile("(\\d+)").matcher(rawName);
                bestNumber = numM.find() ? Integer.parseInt(numM.group(1)) : 1;
                // Pega apenas o nome do arquivo (sem path)
                bestName = rawName.contains("/") ? rawName.substring(rawName.lastIndexOf('/') + 1) : rawName;
            }
        }

        if (bestName == null) return null;

        OtaInfo info = new OtaInfo();
        info.apkNumber = bestNumber;
        info.apkName   = bestName;
        info.apkUrl    = OTA_BASE_URL + bestName;
        info.changelog = "";
        return info;
    }

    /** Exibe Toast de erro na UI thread. */
    private static void notificarErro(Context context, String mensagem) {
        if (context instanceof AppCompatActivity) {
            ((AppCompatActivity) context).runOnUiThread(() -> {
                if (context instanceof ServiceTools) ((ServiceTools) context).resetUpdateButton();
                Toast.makeText(context, mensagem, Toast.LENGTH_LONG).show();
            });
        }
    }

    /**
     * Baixa o APK usando DownloadManager e registra um BroadcastReceiver
     * para iniciar a instalação quando o download concluir.
     *
     * @param apkUrl URL completa do APK no servidor
     */
    private void downloadNewVersion(String apkUrl) {
        Log.d(TAG, "downloadNewVersion() url=" + apkUrl);

        // Exibe progresso
        if (progressUpdate != null) {
            progressUpdate.setVisibility(View.VISIBLE);
        }
        if (btnAtualizarApp != null) {
            btnAtualizarApp.setEnabled(false);
            btnAtualizarApp.setText("Baixando...");
        }

        Toast.makeText(this, "Iniciando download da atualização...", Toast.LENGTH_SHORT).show();

        // Remove APK anterior se existir
        File apkFile = getApkFile();
        if (apkFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            apkFile.delete();
        }

        // Configura o DownloadManager
        DownloadManager.Request dmRequest = new DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("ChoppON — Atualização")
                .setDescription("Baixando versão mais recente do app...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false);

        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Log.e(TAG, "downloadNewVersion - DownloadManager indisponível");
            resetUpdateButton();
            return;
        }

        mDownloadId = dm.enqueue(dmRequest);
        Log.d(TAG, "downloadNewVersion - download enfileirado id=" + mDownloadId);

        // Registra receiver para capturar conclusão do download
        mDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (completedId != mDownloadId) return;

                Log.d(TAG, "downloadNewVersion - download concluído id=" + completedId);
                unregisterReceiver(mDownloadReceiver);
                mDownloadReceiver = null;

                // Verifica se o download foi bem-sucedido
                DownloadManager.Query query = new DownloadManager.Query()
                        .setFilterById(mDownloadId);
                Cursor cursor = dm.query(query);
                if (cursor != null && cursor.moveToFirst()) {
                    int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int status = (statusIdx >= 0) ? cursor.getInt(statusIdx) : -1;
                    cursor.close();

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        runOnUiThread(() -> {
                            resetUpdateButton();
                            installApk(getApkFile());
                        });
                    } else {
                        Log.e(TAG, "downloadNewVersion - download falhou status=" + status);
                        runOnUiThread(() -> {
                            resetUpdateButton();
                            Toast.makeText(ServiceTools.this,
                                    "Falha no download. Tente novamente.",
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                }
            }
        };

        // DownloadManager é broadcast do sistema → RECEIVER_EXPORTED obrigatório no Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mDownloadReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    RECEIVER_EXPORTED);
        } else {
            registerReceiver(mDownloadReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    /**
     * Inicia a instalação do APK baixado usando FileProvider (Android 7+).
     *
     * @param apkFile Arquivo APK a ser instalado
     */
    private void installApk(File apkFile) {
        Log.d(TAG, "installApk() path=" + apkFile.getAbsolutePath());

        if (!apkFile.exists()) {
            Log.e(TAG, "installApk - arquivo não encontrado: " + apkFile.getAbsolutePath());
            Toast.makeText(this, "Arquivo de atualização não encontrado.", Toast.LENGTH_LONG).show();
            return;
        }

        // Verifica permissão de instalação de fontes desconhecidas (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                Log.w(TAG, "installApk - permissão de instalar APK não concedida, abrindo configurações");
                Toast.makeText(this,
                        "Permita a instalação de apps de fontes desconhecidas nas configurações.",
                        Toast.LENGTH_LONG).show();
                Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:" + getPackageName()));
                startActivity(settingsIntent);
                return;
            }
        }

        Uri apkUri;
        try {
            apkUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    apkFile);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "installApk - FileProvider falhou: " + e.getMessage());
            Toast.makeText(this, "Erro ao preparar instalação do APK.", Toast.LENGTH_LONG).show();
            return;
        }

        Intent installIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Log.d(TAG, "installApk - iniciando instalação via Intent");
        startActivity(installIntent);
    }

    // ── Helpers privados ──────────────────────────────────────────────────────

    /** Retorna o File do APK no diretório Downloads público. */
    private static File getApkFile() {
        return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                APK_FILE_NAME);
    }

    /** Restaura o estado do botão de atualização. */
    private void resetUpdateButton() {
        if (progressUpdate != null) progressUpdate.setVisibility(View.GONE);
        if (btnAtualizarApp != null) {
            btnAtualizarApp.setEnabled(true);
            btnAtualizarApp.setText("Verificar Atualização");
        }
    }

    /** Modelo de dados OTA (versão unificada). */
    private static class OtaInfo {
        int    apkNumber = 0;   // Número do APK: APK01→1, APK02→2, etc.
        String apkName   = "";  // Nome do arquivo: APK02.apk
        String apkUrl    = "";  // URL completa para download
        String changelog = "";  // Descrição das mudanças (opcional)
    }

    // =========================================================================
    // LÓGICA EXISTENTE — não alterada
    // =========================================================================

    // ─────────────────────────────────────────────────────────────────────────
    // Sincroniza o estado atual da TAP via verify_tap.php
    // ─────────────────────────────────────────────────────────────────────────
    private void sincronizarEstadoTap() {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);

        new ApiHelper(this).sendPost(body, "verify_tap.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Falha ao sincronizar estado da TAP: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    String json = rb != null ? rb.string() : "{}";
                    boolean ativa = true;
                    try {
                        String jsonLimpo = json;
                        int idx = json.indexOf('{');
                        if (idx > 0) jsonLimpo = json.substring(idx);

                        com.google.gson.JsonObject obj =
                                com.google.gson.JsonParser.parseString(jsonLimpo).getAsJsonObject();

                        if (obj.has("tap_status")) {
                            ativa = (obj.get("tap_status").getAsInt() == 1);
                        } else if (obj.has("status")) {
                            ativa = (obj.get("status").getAsInt() == 1);
                        }
                        Log.d(TAG, "Estado TAP sincronizado: " + (ativa ? "ATIVA" : "DESATIVADA"));
                    } catch (Exception ignored) {
                        Log.w(TAG, "Não foi possível parsear status da TAP");
                    }

                    final boolean estadoFinal = ativa;
                    runOnUiThread(() -> {
                        tapAtiva = estadoFinal;
                        atualizarBotaoToggle();
                    });
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Atualiza o visual do botão conforme o estado da TAP
    // ─────────────────────────────────────────────────────────────────────────
    private void atualizarBotaoToggle() {
        if (tapAtiva) {
            btnToggleTap.setText("Desativar TAP");
            btnToggleTap.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336")));
            btnToggleTap.setIconResource(android.R.drawable.ic_lock_power_off);
        } else {
            btnToggleTap.setText("Ativar TAP");
            btnToggleTap.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50")));
            btnToggleTap.setIconResource(android.R.drawable.ic_media_play);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Diálogo de confirmação antes de executar o toggle
    // ─────────────────────────────────────────────────────────────────────────
    private void confirmarToggleTap() {
        String acao   = tapAtiva ? "desativar" : "ativar";
        String titulo = tapAtiva ? "Desativar esta TAP?" : "Ativar esta TAP?";
        String msg    = tapAtiva
                ? "A torneira ficará OFFLINE.\nO Bluetooth será desconectado e os clientes serão redirecionados.\nDeseja continuar?"
                : "A torneira voltará a funcionar normalmente.\nO Bluetooth será reconectado automaticamente.\nDeseja ativar?";

        new AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(msg)
                .setPositiveButton("Confirmar", (dialog, which) -> executarToggleTap(acao))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Executa o toggle via API e aplica o fluxo completo de BT + navegação
    // ─────────────────────────────────────────────────────────────────────────
    private void executarToggleTap(String acao) {
        btnToggleTap.setEnabled(false);
        progressToggle.setVisibility(View.VISIBLE);

        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("action", acao);

        new ApiHelper(this).sendPost(body, "toggle_tap.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Falha de rede ao executar toggle_tap: " + e.getMessage());
                runOnUiThread(() -> {
                    btnToggleTap.setEnabled(true);
                    progressToggle.setVisibility(View.GONE);
                    Toast.makeText(ServiceTools.this, "Erro de conexão", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    String json = rb != null ? rb.string() : "{}";
                    boolean sucesso   = false;
                    String novoStatus = "";

                    String jsonLimpo = json;
                    int braceIdx = json.indexOf('{');
                    if (braceIdx > 0) jsonLimpo = json.substring(braceIdx);

                    try {
                        com.google.gson.JsonObject obj =
                                com.google.gson.JsonParser.parseString(jsonLimpo).getAsJsonObject();
                        sucesso    = obj.has("success") && obj.get("success").getAsBoolean();
                        novoStatus = obj.has("status")  ? obj.get("status").getAsString() : "";
                        Log.d(TAG, "toggle_tap resposta: success=" + sucesso + " status=" + novoStatus);
                    } catch (Exception e) {
                        Log.e(TAG, "Erro ao parsear resposta toggle_tap: " + e.getMessage());
                    }

                    final boolean ok          = sucesso;
                    final String  statusFinal = novoStatus;

                    runOnUiThread(() -> {
                        btnToggleTap.setEnabled(true);
                        progressToggle.setVisibility(View.GONE);

                        if (ok) {
                            tapAtiva = "online".equals(statusFinal);
                            atualizarBotaoToggle();

                            if (!tapAtiva) {
                                desconectarBluetooth();
                                Log.i(TAG, "TAP desativada → BT desconectado → navegando para OfflineTap");
                                Intent intent = new Intent(ServiceTools.this, OfflineTap.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finishAffinity();
                            } else {
                                reconectarBluetooth();
                                Log.i(TAG, "TAP ativada → BT reconectando → navegando para Home");
                                Intent intent = new Intent(ServiceTools.this, Home.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finishAffinity();
                            }
                        } else {
                            Toast.makeText(ServiceTools.this,
                                    "Falha ao alterar status da TAP", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Desconecta o Bluetooth do ESP32 (TAP sendo desativada)
    // ─────────────────────────────────────────────────────────────────────────
    private void desconectarBluetooth() {
        if (mIsServiceBound && mBluetoothService != null) {
            Log.i(TAG, "Desconectando Bluetooth (TAP desativada)");
            mBluetoothService.disconnect(true);
        } else {
            Log.w(TAG, "BluetoothService não vinculado — não foi possível desconectar");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inicia reconexão Bluetooth ao ESP32 (TAP sendo ativada)
    // ─────────────────────────────────────────────────────────────────────────
    private void reconectarBluetooth() {
        if (mIsServiceBound && mBluetoothService != null) {
            Log.i(TAG, "Iniciando reconexão Bluetooth (TAP ativada)");
            String mac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                    .getString("esp32_mac", "");
            mBluetoothService.connectWithMac(mac);
        } else {
            Log.w(TAG, "BluetoothService não vinculado — Home fará a reconexão no onCreate()");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Card BLE (ESP32) — MAC e informações do dispositivo
    // ─────────────────────────────────────────────────────────────────────────────
    /**
     * Carrega as informações do dispositivo BLE (ESP32) salvas no SharedPreferences.
     * O MAC é gravado em "tap_config" → "esp32_mac" pelo BluetoothService ao conectar.
     * O nome do dispositivo é o prefixo "CHOPP_" seguido do identificador do ESP32.
     * Exibe o Android ID do tablet para facilitar o cadastro na API web.
     */
    private void loadBleInfo() {
        String mac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .getString("esp32_mac", null);

        if (txtBleMac == null) return; // card não presente no layout

        if (mac != null && !mac.isEmpty()) {
            txtBleMac.setText(mac);
            txtBleNome.setText("Nome esperado: CHOPP_" + mac.replace(":", "").substring(6));
        } else {
            txtBleMac.setText("Não encontrado");
            txtBleNome.setText("Aguardando conexão BLE...");
        }

        // Android ID do tablet (necessário para cadastrar o TAP na API web)
        if (txtBleAndroidId != null) {
            txtBleAndroidId.setText(android_id);
        }

        // Status inicial (será atualizado quando o BluetoothService conectar)
        if (txtBleStatus != null) {
            txtBleStatus.setText("● AGUARDANDO...");
            txtBleStatus.setTextColor(android.graphics.Color.parseColor("#888888"));
        }

        // Botão copiar MAC
        if (btnCopiarMac != null) {
            btnCopiarMac.setOnClickListener(v -> {
                String macAtual = txtBleMac.getText().toString();
                if (!macAtual.isEmpty() && !macAtual.equals("Não encontrado")) {
                    copiarParaClipboard("MAC ESP32", macAtual);
                    Toast.makeText(this, "MAC copiado: " + macAtual, Toast.LENGTH_SHORT).show();
                    Log.i(TAG, "MAC BLE copiado: " + macAtual);
                } else {
                    Toast.makeText(this, "MAC não disponível. Conecte o BLE primeiro.", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Botão copiar Android ID
        if (btnCopiarAndroidId != null) {
            btnCopiarAndroidId.setOnClickListener(v -> {
                copiarParaClipboard("Android ID", android_id);
                Toast.makeText(this, "Android ID copiado!", Toast.LENGTH_SHORT).show();
                Log.i(TAG, "Android ID copiado: " + android_id);
            });
        }
    }

    /**
     * Atualiza o indicador de status BLE no card após o BluetoothService conectar.
     * Chamado pelo onServiceConnected quando o status real do GATT é conhecido.
     */
    private void atualizarStatusBle(boolean conectado) {
        if (txtBleStatus == null) return;
        if (conectado) {
            txtBleStatus.setText("● CONECTADO");
            txtBleStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
            // Atualiza o MAC se ainda não estava salvo (conexão feita antes de abrir ServiceTools)
            if (mBluetoothService != null && (mBluetoothService.getCurrentStatus().equals("ready") || mBluetoothService.getCurrentStatus().equals("connected"))) {
                String mac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                        .getString("esp32_mac", null);
                if (mac != null && txtBleMac != null) {
                    txtBleMac.setText(mac);
                    if (txtBleNome != null)
                        txtBleNome.setText("Nome esperado: CHOPP_" + mac.replace(":", "").substring(6));
                }
            }
        } else {
            txtBleStatus.setText("● DESCONECTADO");
            txtBleStatus.setTextColor(android.graphics.Color.parseColor("#F44336"));
        }
    }

    /** Copia texto para a área de transferência. */
    private void copiarParaClipboard(String label, String texto) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, texto));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Informações do sistema
    // ─────────────────────────────────────────────────────────────────────────────
    private void loadSystemInfo() {
        txtInfoImei.setText("IMEI/ID: " + android_id);
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifiManager.getConnectionInfo();
        String ssid = info.getSSID();
        if (ssid != null && !ssid.equals("<unknown ssid>")) {
            txtInfoWifi.setText("Wi-Fi: Conectado a " + ssid.replace("\"", ""));
        } else {
            txtInfoWifi.setText("Wi-Fi: Desconectado");
        }
        txtInfoBluetooth.setText("Bluetooth: Verificando...");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status da leitora SumUp
    // ─────────────────────────────────────────────────────────────────────────
    private void loadReaderStatus() {
        runOnUiThread(() -> {
            progressLeitora.setVisibility(View.VISIBLE);
            btnAtualizarLeitora.setEnabled(false);
            txtLeitoraNome.setText("Leitora: Consultando...");
        });

        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);

        new ApiHelper(this).sendPost(body, "reader_status.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    progressLeitora.setVisibility(View.GONE);
                    btnAtualizarLeitora.setEnabled(true);
                    txtLeitoraStatus.setText("ERRO");
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    String json = responseBody != null ? responseBody.string() : "{}";
                    ReaderStatusResponse status = parseReaderStatus(json);

                    runOnUiThread(() -> {
                        progressLeitora.setVisibility(View.GONE);
                        btnAtualizarLeitora.setEnabled(true);
                        txtLeitoraNome.setText("Leitora: " + (status.leitora_nome.isEmpty() ? "Não configurada" : status.leitora_nome));
                        txtLeitoraStatus.setText("● " + status.status_leitora.toUpperCase());
                        txtApiStatus.setText(status.api_ativa ? "● ATIVA" : "● INATIVA");

                        if (!status.bateria.isEmpty()) {
                            layoutLeitoraDetalhes.setVisibility(View.VISIBLE);
                            txtLeitoraBateria.setText("Bat: " + status.bateria);
                            txtLeitoraConexao.setText("Rede: " + status.conexao.toUpperCase());
                            txtLeitoraFirmware.setText("FW: " + status.firmware);
                        }
                    });
                }
            }
        });
    }

    private ReaderStatusResponse parseReaderStatus(String json) {
        ReaderStatusResponse r = new ReaderStatusResponse();
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            r.leitora_nome   = obj.has("leitora_nome")   ? obj.get("leitora_nome").getAsString()   : "";
            r.status_leitora = obj.has("status_leitora") ? obj.get("status_leitora").getAsString() : "offline";
            r.api_ativa      = obj.has("api_ativa")      && obj.get("api_ativa").getAsBoolean();
            r.bateria        = obj.has("bateria")  && !obj.get("bateria").isJsonNull()  ? obj.get("bateria").getAsString()  : "";
            r.conexao        = obj.has("conexao")  && !obj.get("conexao").isJsonNull()  ? obj.get("conexao").getAsString()  : "";
            r.firmware       = obj.has("firmware") && !obj.get("firmware").isJsonNull() ? obj.get("firmware").getAsString() : "";
        } catch (Exception e) {
            Log.e(TAG, "Erro ao parsear reader_status: " + e.getMessage());
        }
        return r;
    }

    private static class ReaderStatusResponse {
        String  leitora_nome   = "";
        String  status_leitora = "offline";
        boolean api_ativa      = false;
        String  bateria        = "";
        String  conexao        = "";
        String  firmware       = "";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida — desvincula o serviço e o receiver ao destruir a Activity
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
        // Desregistra o receiver de download se ainda estiver ativo
        if (mDownloadReceiver != null) {
            try {
                unregisterReceiver(mDownloadReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver já foi desregistrado
            }
            mDownloadReceiver = null;
        }
    }
    // v5.2: Impede que o botão Voltar caia na AcessoMaster via back stack.
    private void setupBackBlock() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                android.util.Log.i("SERVICE_TOOLS", "[KIOSK] Botão Voltar bloqueado → Home");
                android.content.Intent intent = new android.content.Intent(ServiceTools.this, Home.class);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
            }
        });
    }

}
