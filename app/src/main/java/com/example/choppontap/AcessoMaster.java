package com.example.choppontap;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import android.content.Intent;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * AcessoMaster — Tela de autenticação para acesso ao ServiceTools.
 *
 * Esta tela contém APENAS dois métodos de autenticação:
 *
 * 1. FLUXO QR Code (lógica invertida):
 *    a. Android gera token via API (POST /api/request_master_qr.php?action=generate)
 *    b. Android exibe QR Code na tela com o token
 *    c. Admin no ERP (menu perfil → Acesso QR CODE) escaneia o QR Code do tablet
 *    d. Android faz polling a cada 3s (POST /api/request_master_qr.php?action=poll)
 *    e. Ao receber status=approved → abre ServiceTools
 *
 * 2. FLUXO Senha:
 *    Digita 6 dígitos → 259087 libera localmente, outros validam via API
 *
 * NOTA: "Verificar Atualização" foi migrado para ServiceTools.java.
 *       Acesse: ServiceTools → botão "Verificar Atualização".
 */
public class AcessoMaster extends AppCompatActivity {

    private static final String TAG              = "ACESSO_MASTER";
    private static final int    POLL_INTERVAL_MS = 3000;   // 3 segundos
    private static final int    QR_EXPIRY_MS     = 300000; // 5 minutos

    // ── Views ─────────────────────────────────────────────────────────────────
    private MaterialButton    btnAcessoQrCode, btnAcessoSenha;
    private LinearLayout      layoutQrAcesso;
    private TextInputLayout   layoutInputSenha;
    private TextInputEditText edtSenhaAcesso;
    private ProgressBar       progressQr;
    private TextView          txtStatusQr;
    private ImageView         imgQrCode;

    // ── Estado QR ─────────────────────────────────────────────────────────────
    private String  android_id;
    private int     mTokenId  = -1;
    private boolean mPolling  = false;
    private boolean mAprovado = false;

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
        btnAcessoQrCode  = findViewById(R.id.btnAcessoQrCode);
        btnAcessoSenha   = findViewById(R.id.btnAcessoSenha);
        layoutQrAcesso   = findViewById(R.id.layoutQrAcesso);
        layoutInputSenha = findViewById(R.id.layoutInputSenha);
        edtSenhaAcesso   = findViewById(R.id.edtSenhaAcesso);
        progressQr       = findViewById(R.id.progressQr);
        txtStatusQr      = findViewById(R.id.txtStatusQr);
        imgQrCode        = findViewById(R.id.imgQrCode);

        // ── Listeners ─────────────────────────────────────────────────────────
        btnAcessoQrCode.setOnClickListener(v -> iniciarFluxoQrCode());
        btnAcessoSenha.setOnClickListener(v -> mostrarInputSenha());

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

        // Listener do campo de senha — dispara ao completar 6 dígitos
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

                    // Expiração automática após QR_EXPIRY_MS
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
    // LIBERAR ACESSO → abre ServiceTools
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
    // HELPERS
    // =========================================================================

    private void reativarBotoes() {
        btnAcessoQrCode.setEnabled(true);
        btnAcessoSenha.setEnabled(true);
    }

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pararPolling();
        executor.shutdown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Mantém polling em background enquanto admin aprova no ERP
    }
}
