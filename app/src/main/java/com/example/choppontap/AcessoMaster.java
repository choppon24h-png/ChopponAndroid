package com.example.choppontap;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * AcessoMaster — Tela de autenticação para acesso ao ServiceTools.
 *
 * FLUXO QR Code (lógica invertida):
 *   1. Android gera token via API (POST /api/request_master_qr.php?action=generate)
 *   2. Android exibe QR Code na tela com o token
 *   3. Admin no ERP (menu perfil → Acesso QR CODE) escaneia o QR Code do tablet
 *   4. Android faz polling a cada 3s (POST /api/request_master_qr.php?action=poll)
 *   5. Ao receber status=approved → abre ServiceTools
 *
 * FLUXO Verificar Atualização (v3.3.0):
 *   1. Toque em "Verificar Atualização"
 *   2. GET /api/app_version.php → compara versionCode com o app instalado
 *   3. Se nova versão disponível → diálogo de confirmação
 *   4. Aceita → DownloadManager baixa o APK
 *   5. Download concluído → Intent de instalação via FileProvider
 *
 * Fluxo Senha:
 *   Digita 6 dígitos → 259087 libera localmente, outros validam via API
 */
public class AcessoMaster extends AppCompatActivity {

    private static final String TAG              = "ACESSO_MASTER";
    private static final int    POLL_INTERVAL_MS = 3000;   // 3 segundos
    private static final int    QR_EXPIRY_MS     = 300000; // 5 minutos
    private static final String APK_FILE_NAME    = "choppontap-update.apk";

    // ── URL do endpoint de versão ─────────────────────────────────────────────
    // Usa o novo endpoint PHP que lê do banco (com fallback para /app/version.json)
    private static final String VERSION_URL =
            ApiConfig.getBaseUrl() + "app_version.php";

    // ── Views ─────────────────────────────────────────────────────────────────
    private MaterialButton   btnAcessoQrCode, btnAcessoSenha, btnVerificarAtualizacao;
    private LinearLayout     layoutQrAcesso;
    private TextInputLayout  layoutInputSenha;
    private TextInputEditText edtSenhaAcesso;
    private ProgressBar      progressQr;
    private ProgressBar      progressUpdate;
    private TextView         txtStatusQr;
    private TextView         txtVersaoAtual;
    private ImageView        imgQrCode;

    // ── Estado QR ─────────────────────────────────────────────────────────────
    private String  android_id;
    private int     mTokenId  = -1;
    private boolean mPolling  = false;
    private boolean mAprovado = false;

    // ── Estado Download ───────────────────────────────────────────────────────
    private long             mDownloadId       = -1;
    private BroadcastReceiver mDownloadReceiver = null;

    private ApiHelper            apiHelper;
    private final Handler        mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor   = Executors.newSingleThreadExecutor();

    // ── Runnable de polling ───────────────────────────────────────────────────
    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mPolling || mAprovado) return;
            verificarStatusToken();
            mainHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_acesso_master);

        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        apiHelper  = new ApiHelper(this);

        // ── Bind views ────────────────────────────────────────────────────────
        btnAcessoQrCode          = findViewById(R.id.btnAcessoQrCode);
        btnAcessoSenha           = findViewById(R.id.btnAcessoSenha);
        btnVerificarAtualizacao  = findViewById(R.id.btnVerificarAtualizacao);
        layoutQrAcesso           = findViewById(R.id.layoutQrAcesso);
        layoutInputSenha         = findViewById(R.id.layoutInputSenha);
        edtSenhaAcesso           = findViewById(R.id.edtSenhaAcesso);
        progressQr               = findViewById(R.id.progressQr);
        progressUpdate           = findViewById(R.id.progressUpdate);
        txtStatusQr              = findViewById(R.id.txtStatusQr);
        txtVersaoAtual           = findViewById(R.id.txtVersaoAtual);
        imgQrCode                = findViewById(R.id.imgQrCode);

        // Exibir versão atual do app
        if (txtVersaoAtual != null) {
            txtVersaoAtual.setText("Versão instalada: " + getCurrentVersionName());
        }

        // ── Listeners ─────────────────────────────────────────────────────────
        btnAcessoQrCode.setOnClickListener(v -> iniciarFluxoQrCode());
        btnAcessoSenha.setOnClickListener(v -> mostrarInputSenha());

        if (btnVerificarAtualizacao != null) {
            btnVerificarAtualizacao.setOnClickListener(v -> verificarAtualizacao());
        }

        // Bloqueia botão Voltar — redireciona para Home
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Log.i(TAG, "[KIOSK] Botão Voltar na AcessoMaster → redirecionando para Home");
                pararPolling();
                Intent intent = new Intent(AcessoMaster.this, Home.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
            }
        });

        edtSenhaAcesso.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(android.text.Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() == 6) {
                    String senha = s.toString();
                    if (senha.equals("259087")) {
                        liberarAcesso("Acesso Master", 1);
                    } else {
                        validarSenhaAPI(senha);
                    }
                }
            }
        });
    }

    // =========================================================================
    // FLUXO QR CODE
    // =========================================================================

    private void iniciarFluxoQrCode() {
        pararPolling();
        mTokenId  = -1;
        mAprovado = false;

        layoutQrAcesso.setVisibility(View.VISIBLE);
        layoutInputSenha.setVisibility(View.GONE);
        imgQrCode.setVisibility(View.GONE);
        progressQr.setVisibility(View.VISIBLE);
        txtStatusQr.setVisibility(View.VISIBLE);
        txtStatusQr.setText("Gerando QR Code...");
        btnAcessoQrCode.setEnabled(false);
        btnAcessoSenha.setEnabled(false);
        if (btnVerificarAtualizacao != null) btnVerificarAtualizacao.setEnabled(false);

        Map<String, String> body = new HashMap<>();
        body.put("action",    "generate");
        body.put("device_id", android_id != null ? android_id : "");

        apiHelper.sendPost(body, "request_master_qr", new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "[QR] Falha ao gerar token: " + e.getMessage());
                runOnUiThread(() -> {
                    progressQr.setVisibility(View.GONE);
                    txtStatusQr.setText("Erro de conexão. Verifique o Wi-Fi.");
                    reativarBotoes();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String rb = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "[QR] HTTP " + response.code() + " | Resposta generate: '" + rb + "'");
                if (rb.trim().isEmpty()) {
                    Log.e(TAG, "[QR] Servidor retornou corpo vazio (HTTP " + response.code() + ").");
                    runOnUiThread(() -> {
                        progressQr.setVisibility(View.GONE);
                        txtStatusQr.setText("Servidor não respondeu. Verifique se request_master_qr.php está instalado.");
                        reativarBotoes();
                    });
                    return;
                }
                try {
                    JSONObject json = new JSONObject(rb);
                    if (json.optBoolean("success", false)) {
                        mTokenId = json.optInt("token_id", -1);
                        String qrData = json.optString("qr_data", "");
                        if (qrData.isEmpty() || mTokenId <= 0) {
                            runOnUiThread(() -> {
                                progressQr.setVisibility(View.GONE);
                                txtStatusQr.setText("Resposta inválida do servidor. Tente novamente.");
                                reativarBotoes();
                            });
                            return;
                        }
                        runOnUiThread(() -> exibirQrCode(qrData));
                    } else {
                        String msg = json.optString("message", "Erro ao gerar QR Code.");
                        runOnUiThread(() -> {
                            progressQr.setVisibility(View.GONE);
                            txtStatusQr.setText(msg);
                            reativarBotoes();
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[QR] Erro ao parsear generate: " + e.getMessage());
                    runOnUiThread(() -> {
                        progressQr.setVisibility(View.GONE);
                        txtStatusQr.setText("Erro ao processar resposta do servidor.");
                        reativarBotoes();
                    });
                }
            }
        });
    }

    private void exibirQrCode(String qrData) {
        progressQr.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                Bitmap bmp = gerarBitmapQr(qrData, 600);
                runOnUiThread(() -> {
                    imgQrCode.setImageBitmap(bmp);
                    imgQrCode.setVisibility(View.VISIBLE);
                    txtStatusQr.setText("Aguardando aprovação...\nAbra o menu de perfil no ERP e escaneie este QR Code.");
                    txtStatusQr.setVisibility(View.VISIBLE);
                    Log.i(TAG, "[QR] QR Code exibido. token_id=" + mTokenId);
                    iniciarPolling();
                    mainHandler.postDelayed(() -> {
                        if (!mAprovado) {
                            pararPolling();
                            txtStatusQr.setText("QR Code expirado. Toque em 'Acesso QR Code' para gerar um novo.");
                            imgQrCode.setVisibility(View.GONE);
                            reativarBotoes();
                        }
                    }, QR_EXPIRY_MS);
                });
            } catch (WriterException e) {
                Log.e(TAG, "[QR] Erro ao gerar bitmap: " + e.getMessage());
                runOnUiThread(() -> {
                    txtStatusQr.setText("Erro ao gerar QR Code. Tente novamente.");
                    reativarBotoes();
                });
            }
        }).start();
    }

    private Bitmap gerarBitmapQr(String content, int size) throws WriterException {
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int x = 0; x < size; x++)
            for (int y = 0; y < size; y++)
                bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
        return bmp;
    }

    private void iniciarPolling() {
        mPolling = true;
        mainHandler.postDelayed(pollingRunnable, POLL_INTERVAL_MS);
    }

    private void pararPolling() {
        mPolling = false;
        mainHandler.removeCallbacks(pollingRunnable);
    }

    private void verificarStatusToken() {
        if (mTokenId <= 0) return;
        Map<String, String> body = new HashMap<>();
        body.put("action",    "poll");
        body.put("token_id",  String.valueOf(mTokenId));
        body.put("device_id", android_id != null ? android_id : "");

        apiHelper.sendPost(body, "request_master_qr", new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "[QR] Polling falhou (rede): " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String rb = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "[QR] Poll resposta: " + rb);
                try {
                    JSONObject json = new JSONObject(rb);
                    String status = json.optString("status", "pending");
                    switch (status) {
                        case "approved":
                            mAprovado = true;
                            pararPolling();
                            String userName = json.optString("user_name", "Técnico");
                            int    userType = json.optInt("user_type", 3);
                            runOnUiThread(() -> liberarAcesso(userName, userType));
                            break;
                        case "rejected":
                            pararPolling();
                            runOnUiThread(() -> {
                                imgQrCode.setVisibility(View.GONE);
                                txtStatusQr.setText("Acesso negado pelo administrador.");
                                reativarBotoes();
                            });
                            break;
                        case "expired":
                            pararPolling();
                            runOnUiThread(() -> {
                                imgQrCode.setVisibility(View.GONE);
                                txtStatusQr.setText("QR Code expirado. Toque em 'Acesso QR Code' para gerar um novo.");
                                reativarBotoes();
                            });
                            break;
                        default:
                            Log.d(TAG, "[QR] Aguardando aprovação...");
                            break;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[QR] Erro ao parsear poll: " + e.getMessage());
                }
            }
        });
    }

    // =========================================================================
    // FLUXO VERIFICAR ATUALIZAÇÃO
    // =========================================================================

    /**
     * Verifica se há nova versão disponível no servidor.
     * Consulta GET /api/app_version.php e compara com o versionCode instalado.
     */
    private void verificarAtualizacao() {
        if (btnVerificarAtualizacao != null) {
            btnVerificarAtualizacao.setEnabled(false);
            btnVerificarAtualizacao.setText("Verificando...");
        }
        if (progressUpdate != null) progressUpdate.setVisibility(View.VISIBLE);

        Log.d(TAG, "[UPDATE] Verificando versão em: " + VERSION_URL);

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(VERSION_URL)
                .addHeader("Cache-Control", "no-cache")
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                Log.e(TAG, "[UPDATE] Falha de rede: " + e.getMessage());
                runOnUiThread(() -> {
                    resetBotaoAtualizacao();
                    Toast.makeText(AcessoMaster.this,
                            "Não foi possível verificar atualizações.\nVerifique a conexão Wi-Fi.",
                            Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws IOException {
                try (okhttp3.ResponseBody rb = response.body()) {
                    if (!response.isSuccessful() || rb == null) {
                        Log.e(TAG, "[UPDATE] Resposta inválida HTTP " + response.code());
                        runOnUiThread(() -> {
                            resetBotaoAtualizacao();
                            Toast.makeText(AcessoMaster.this,
                                    "Servidor retornou erro " + response.code() + ". Tente novamente.",
                                    Toast.LENGTH_LONG).show();
                        });
                        return;
                    }

                    String jsonStr = rb.string();
                    Log.d(TAG, "[UPDATE] version.json: " + jsonStr);

                    JSONObject json = new JSONObject(jsonStr);
                    int    serverVersionCode = json.optInt("versionCode", 0);
                    String serverVersionName = json.optString("versionName", "?");
                    String apkUrl            = json.optString("apkUrl", "");
                    boolean force            = json.optBoolean("force", false);
                    String changelog         = json.optString("changelog", "");

                    int currentVersionCode = getCurrentVersionCode();
                    Log.d(TAG, "[UPDATE] Versão atual: " + currentVersionCode
                            + " | Versão servidor: " + serverVersionCode);

                    runOnUiThread(() -> {
                        resetBotaoAtualizacao();
                        processarResultadoAtualizacao(
                                serverVersionCode, serverVersionName,
                                apkUrl, force, changelog, currentVersionCode);
                    });

                } catch (Exception e) {
                    Log.e(TAG, "[UPDATE] Erro ao parsear JSON: " + e.getMessage());
                    runOnUiThread(() -> {
                        resetBotaoAtualizacao();
                        Toast.makeText(AcessoMaster.this,
                                "Erro ao processar resposta do servidor.",
                                Toast.LENGTH_LONG).show();
                    });
                }
            }
        });
    }

    /**
     * Avalia o resultado da verificação e exibe o diálogo adequado.
     */
    private void processarResultadoAtualizacao(int serverCode, String serverName,
                                                String apkUrl, boolean force,
                                                String changelog, int currentCode) {
        if (serverCode > currentCode) {
            // Nova versão disponível
            String mensagem = "Versão " + serverName + " disponível.\n"
                    + "Versão instalada: " + getCurrentVersionName() + "\n\n"
                    + (changelog != null && !changelog.isEmpty()
                       ? "O que há de novo:\n" + changelog : "");

            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle("Nova atualização disponível")
                    .setMessage(mensagem)
                    .setPositiveButton("Atualizar agora", (dialog, which) -> {
                        if (!apkUrl.isEmpty()) {
                            baixarApk(apkUrl);
                        } else {
                            Toast.makeText(this, "URL do APK não configurada.", Toast.LENGTH_LONG).show();
                        }
                    });

            if (force) {
                // Atualização obrigatória — sem botão cancelar
                builder.setCancelable(false);
            } else {
                builder.setNegativeButton("Agora não", null);
            }

            builder.show();

        } else {
            // App já está atualizado
            Toast.makeText(this,
                    "O aplicativo já está na versão mais recente (" + getCurrentVersionName() + ").",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Baixa o APK usando DownloadManager e registra BroadcastReceiver para instalação.
     */
    private void baixarApk(String apkUrl) {
        Log.d(TAG, "[UPDATE] Iniciando download: " + apkUrl);

        if (btnVerificarAtualizacao != null) {
            btnVerificarAtualizacao.setEnabled(false);
            btnVerificarAtualizacao.setText("Baixando...");
        }
        if (progressUpdate != null) progressUpdate.setVisibility(View.VISIBLE);

        Toast.makeText(this, "Iniciando download da atualização...", Toast.LENGTH_SHORT).show();

        // Remover APK anterior
        File apkFile = getApkFile();
        if (apkFile.exists()) apkFile.delete();

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
            Log.e(TAG, "[UPDATE] DownloadManager indisponível");
            resetBotaoAtualizacao();
            return;
        }

        mDownloadId = dm.enqueue(dmRequest);
        Log.d(TAG, "[UPDATE] Download enfileirado id=" + mDownloadId);

        mDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (completedId != mDownloadId) return;

                Log.d(TAG, "[UPDATE] Download concluído id=" + completedId);
                unregisterReceiver(mDownloadReceiver);
                mDownloadReceiver = null;

                DownloadManager.Query query = new DownloadManager.Query().setFilterById(mDownloadId);
                Cursor cursor = dm.query(query);
                if (cursor != null && cursor.moveToFirst()) {
                    int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int status = (statusIdx >= 0) ? cursor.getInt(statusIdx) : -1;
                    cursor.close();

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        runOnUiThread(() -> {
                            resetBotaoAtualizacao();
                            instalarApk(getApkFile());
                        });
                    } else {
                        Log.e(TAG, "[UPDATE] Download falhou status=" + status);
                        runOnUiThread(() -> {
                            resetBotaoAtualizacao();
                            Toast.makeText(AcessoMaster.this,
                                    "Falha no download. Tente novamente.", Toast.LENGTH_LONG).show();
                        });
                    }
                }
            }
        };

        registerReceiver(mDownloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    /**
     * Instala o APK baixado via FileProvider (Android 7+).
     */
    private void instalarApk(File apkFile) {
        Log.d(TAG, "[UPDATE] Instalando APK: " + apkFile.getAbsolutePath());

        if (!apkFile.exists()) {
            Toast.makeText(this, "Arquivo de atualização não encontrado.", Toast.LENGTH_LONG).show();
            return;
        }

        // Android 8+: verificar permissão de instalar fontes desconhecidas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
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
            Log.e(TAG, "[UPDATE] FileProvider falhou: " + e.getMessage());
            Toast.makeText(this, "Erro ao preparar instalação do APK.", Toast.LENGTH_LONG).show();
            return;
        }

        Intent installIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Log.d(TAG, "[UPDATE] Iniciando instalação via Intent");
        startActivity(installIntent);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private File getApkFile() {
        return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                APK_FILE_NAME);
    }

    private int getCurrentVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pi.getLongVersionCode();
            } else {
                //noinspection deprecation
                return pi.versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private String getCurrentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "desconhecida";
        }
    }

    private void resetBotaoAtualizacao() {
        if (progressUpdate != null) progressUpdate.setVisibility(View.GONE);
        if (btnVerificarAtualizacao != null) {
            btnVerificarAtualizacao.setEnabled(true);
            btnVerificarAtualizacao.setText("Verificar Atualização");
        }
    }

    private void reativarBotoes() {
        btnAcessoQrCode.setEnabled(true);
        btnAcessoSenha.setEnabled(true);
        if (btnVerificarAtualizacao != null) btnVerificarAtualizacao.setEnabled(true);
    }

    // =========================================================================
    // FLUXO SENHA
    // =========================================================================

    private void mostrarInputSenha() {
        pararPolling();
        layoutInputSenha.setVisibility(View.VISIBLE);
        layoutQrAcesso.setVisibility(View.GONE);
        edtSenhaAcesso.requestFocus();
    }

    private void validarSenhaAPI(String senha) {
        Toast.makeText(this, "Validando senha...", Toast.LENGTH_SHORT).show();
        Map<String, String> body = new HashMap<>();
        body.put("senha",     senha);
        body.put("device_id", android_id != null ? android_id : "");
        apiHelper.sendPost(body, "validate_master_qr", new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> Toast.makeText(AcessoMaster.this,
                        "Erro de conexão ao validar senha", Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String rb = response.body() != null ? response.body().string() : "";
                try {
                    JSONObject json = new JSONObject(rb);
                    if (json.optBoolean("success", false)) {
                        runOnUiThread(() -> liberarAcesso(
                                json.optString("user_name", "Técnico"),
                                json.optInt("user_type", 3)));
                    } else {
                        runOnUiThread(() -> Toast.makeText(AcessoMaster.this,
                                "Senha inválida", Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(AcessoMaster.this,
                            "Erro ao validar senha", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    // =========================================================================
    // LIBERAR ACESSO
    // =========================================================================

    private void liberarAcesso(String userName, int userType) {
        Log.i(TAG, "[ACESSO] Liberado para: " + userName);
        Toast.makeText(this, "Acesso Master Liberado — " + userName, Toast.LENGTH_SHORT).show();
        boolean fromOffline = getIntent().getBooleanExtra("from_offline", false);
        Intent intent = new Intent(AcessoMaster.this, ServiceTools.class);
        intent.putExtra("from_offline",     fromOffline);
        intent.putExtra("master_user_name", userName);
        intent.putExtra("master_user_type", userType);
        startActivity(intent);
        finish();
    }

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pararPolling();
        executor.shutdown();
        if (mDownloadReceiver != null) {
            try { unregisterReceiver(mDownloadReceiver); } catch (Exception ignored) {}
            mDownloadReceiver = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Manter polling em background (usuário pode minimizar enquanto admin aprova)
    }
}
