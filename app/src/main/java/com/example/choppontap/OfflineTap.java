package com.example.choppontap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * OfflineTap — Tela exibida quando a TAP está desativada.
 *
 * Funcionalidades:
 *  - Exibe logo Chopp-On, ícone de torneira offline e instrução de próxima TAP.
 *  - Easter egg: 5 cliques no logo → abre AcessoMaster para reativar a TAP manualmente.
 *  - Animação pulsante no ícone de torneira.
 *  - Bloqueio do botão Voltar (TAP não pode ser reativada pelo cliente).
 *
 * NOVO — Polling automático de reativação:
 *  - Ao entrar nesta tela, inicia o TapStatusPollingService com status=0 (offline).
 *  - O serviço faz polling a cada 10s em /api/tap_status_poll.php.
 *  - Quando o ERP ativa a TAP (status=1), o broadcast é recebido aqui e o app
 *    navega automaticamente para Home, reiniciando todo o fluxo BLE.
 */
public class OfflineTap extends AppCompatActivity {

    private static final String TAG = "OFFLINE_TAP";

    private ImageView logoOffline;
    private ImageView icTapOffline;
    private TextView  txtSecretHint;
    private TextView  txtStatusPoll; // Indicador visual de polling (opcional)

    // Easter egg: contador de cliques no logo
    private int    secretClickCount = 0;
    private final Handler handler   = new Handler();

    // ── Receiver do TapStatusPollingService ──────────────────────────────────
    /**
     * Recebe broadcasts do TapStatusPollingService.
     * Quando o ERP reativa a TAP (status=1), navega automaticamente para Home.
     */
    private final BroadcastReceiver mTapStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!TapStatusPollingService.ACTION_TAP_STATUS_CHANGED.equals(intent.getAction())) return;

            int status = intent.getIntExtra(TapStatusPollingService.EXTRA_STATUS, 0);
            Log.i(TAG, "[POLL] Status da TAP recebido: " + status);

            if (status == 1) {
                // TAP REATIVADA pelo ERP → navega para Home com os dados atualizados
                Log.i(TAG, "[POLL] TAP REATIVADA pelo ERP → navegando para Home");
                Toast.makeText(OfflineTap.this, "TAP reativada! Iniciando sistema...", Toast.LENGTH_SHORT).show();

                String bebida   = intent.getStringExtra(TapStatusPollingService.EXTRA_BEBIDA);
                float  preco    = intent.getFloatExtra(TapStatusPollingService.EXTRA_PRECO, 0f);
                String image    = intent.getStringExtra(TapStatusPollingService.EXTRA_IMAGE);
                boolean cartao  = intent.getBooleanExtra(TapStatusPollingService.EXTRA_CARTAO, false);
                String mac      = intent.getStringExtra(TapStatusPollingService.EXTRA_ESP32_MAC);

                // Salva MAC nas SharedPreferences para o BluetoothService reconectar
                if (mac != null && !mac.isEmpty()) {
                    getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                            .edit()
                            .putString("esp32_mac", mac)
                            .apply();
                    Log.d(TAG, "[POLL] MAC salvo: " + mac);
                }

                // Salva preferência de cartão
                new Sqlite(getApplicationContext()).tapCartao(cartao);

                // Navega para Home passando os dados da TAP diretamente (sem nova chamada à API)
                Intent homeIntent = new Intent(OfflineTap.this, Home.class);
                homeIntent.putExtra("bebida", bebida);
                homeIntent.putExtra("preco",  preco);
                homeIntent.putExtra("imagem", image);
                homeIntent.putExtra("cartao", cartao);
                homeIntent.addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                );
                startActivity(homeIntent);
                finish();
            }
            // Se status=0, TAP continua offline — não faz nada
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_tap);

        Log.d(TAG, "OfflineTap iniciada — TAP em modo OFFLINE");

        setupFullscreen();
        setupViews();
        setupAnimations();
        setupEasterEgg();
        blockBackButton();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Reinicia a animação se necessário (ex: ao voltar do AcessoMaster sem reativar)
        setupAnimations();

        // Reseta o contador secreto ao voltar para a tela
        secretClickCount = 0;
        if (txtSecretHint != null) {
            txtSecretHint.setVisibility(android.view.View.INVISIBLE);
            txtSecretHint.setText("");
        }

        // Registra o receiver do polling
        IntentFilter filter = new IntentFilter(TapStatusPollingService.ACTION_TAP_STATUS_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mTapStatusReceiver, filter);

        // Inicia (ou mantém) o polling com status=0 (offline) para detectar reativação
        TapStatusPollingService.start(this, 0);
        Log.i(TAG, "[POLL] TapStatusPollingService iniciado/confirmado com status=0 (offline)");

        // Atualiza indicador visual de polling
        if (txtStatusPoll != null) {
            txtStatusPoll.setVisibility(android.view.View.VISIBLE);
            txtStatusPoll.setText("Aguardando reativação pelo sistema...");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Desregistra o receiver (o serviço continua rodando em background)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mTapStatusReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (icTapOffline != null) icTapOffline.clearAnimation();
        // Não para o polling aqui — ele pode ser necessário se a Activity for recriada
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Configuração de tela cheia (igual ao Home)
    // ─────────────────────────────────────────────────────────────────────────

    private void setupFullscreen() {
        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(
                getWindow(), getWindow().getDecorView());
        wic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Referências de View
    // ─────────────────────────────────────────────────────────────────────────

    private void setupViews() {
        logoOffline   = findViewById(R.id.logoOffline);
        icTapOffline  = findViewById(R.id.icTapOffline);
        txtSecretHint = findViewById(R.id.txtSecretHint);
        // txtStatusPoll é opcional — adicionar ao layout se quiser feedback visual
        txtStatusPoll = findViewById(R.id.txtStatusPoll);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animação pulsante no ícone da torneira
    // ─────────────────────────────────────────────────────────────────────────

    private void setupAnimations() {
        try {
            Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse_scale);
            icTapOffline.startAnimation(pulse);
            Log.d(TAG, "Animação pulsante iniciada no ícone da torneira");
        } catch (Exception e) {
            Log.w(TAG, "Não foi possível carregar animação: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Easter Egg: 5 cliques no logo → AcessoMaster → ServiceTools (reativar manual)
    // ─────────────────────────────────────────────────────────────────────────

    private void setupEasterEgg() {
        logoOffline.setOnClickListener(v -> {
            secretClickCount++;
            Log.d(TAG, "Clique no logo: " + secretClickCount + "/5");

            // Feedback visual sutil (hint invisível ao cliente casual)
            if (secretClickCount >= 2 && secretClickCount < 5) {
                txtSecretHint.setVisibility(android.view.View.VISIBLE);
                txtSecretHint.setText("···".substring(0, Math.min(secretClickCount - 1, 3)));
            }

            if (secretClickCount >= 5) {
                // Acesso liberado!
                secretClickCount = 0;
                txtSecretHint.setVisibility(android.view.View.INVISIBLE);
                txtSecretHint.setText("");

                Log.i(TAG, "Easter egg ativado! Abrindo AcessoMaster...");

                // Pequena animação de feedback antes de abrir
                logoOffline.animate()
                        .scaleX(1.15f).scaleY(1.15f)
                        .setDuration(120)
                        .withEndAction(() -> {
                            logoOffline.animate()
                                    .scaleX(1.0f).scaleY(1.0f)
                                    .setDuration(120)
                                    .withEndAction(() -> {
                                        Intent intent = new Intent(OfflineTap.this, AcessoMaster.class);
                                        // Flag extra para indicar que veio da tela Offline
                                        // (AcessoMaster usará isso para voltar ao Home após reativar)
                                        intent.putExtra("from_offline", true);
                                        startActivity(intent);
                                    })
                                    .start();
                        })
                        .start();
                return;
            }

            // Reset do contador após 2.5 segundos de inatividade
            handler.removeCallbacksAndMessages("secret_reset");
            handler.postAtTime(() -> {
                if (secretClickCount > 0) {
                    Log.d(TAG, "Contador secreto resetado por inatividade");
                    secretClickCount = 0;
                    txtSecretHint.setVisibility(android.view.View.INVISIBLE);
                    txtSecretHint.setText("");
                }
            }, "secret_reset", android.os.SystemClock.uptimeMillis() + 2500);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bloquear o botão Voltar (cliente não pode sair da tela OFFLINE)
    // ─────────────────────────────────────────────────────────────────────────

    private void blockBackButton() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Não faz nada — a tela OFFLINE é um bloqueio intencional
                Log.d(TAG, "Botão Voltar bloqueado na tela OFFLINE");
            }
        });
    }
}
