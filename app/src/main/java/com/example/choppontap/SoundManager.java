package com.example.choppontap;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

/**
 * SoundManager — v5.13
 *
 * Gerencia todos os efeitos sonoros do ChopponTap usando {@link SoundPool}
 * para garantir baixa latência nos feedbacks de UI.
 *
 * Sons disponíveis:
 *  - {@link #playSelectChopp()}      → bip único ao selecionar volume de chopp
 *  - {@link #playSelectPayment()}    → bip duplo ao selecionar forma de pagamento
 *  - {@link #playTimerWarning()}     → 3 bips rápidos quando timer ≤ 20s
 *  - {@link #playPaymentSuccess()}   → som de caixa registradora (pagamento confirmado)
 *  - {@link #playValveTick()}        → bip curto por segundo no countdown de reinício
 *  - {@link #playValveOpen()}        → arpejo ascendente quando válvula disponível
 *
 * Uso:
 * <pre>
 *   // onCreate
 *   SoundManager.init(this);
 *
 *   // ao clicar em chopp
 *   SoundManager.getInstance().playSelectChopp();
 *
 *   // onDestroy
 *   SoundManager.release();
 * </pre>
 *
 * O SoundManager é um singleton de escopo de aplicação.
 * Chame {@link #init(Context)} uma vez em {@code Application.onCreate()} ou
 * na primeira Activity. Chame {@link #release()} apenas ao encerrar o app.
 */
public class SoundManager {

    private static final String TAG = "SoundManager";

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static SoundManager sInstance;

    public static void init(Context context) {
        if (sInstance == null) {
            sInstance = new SoundManager(context.getApplicationContext());
        }
    }

    public static SoundManager getInstance() {
        if (sInstance == null) {
            Log.e(TAG, "SoundManager não inicializado! Chame SoundManager.init(context) primeiro.");
        }
        return sInstance;
    }

    public static void release() {
        if (sInstance != null) {
            sInstance.destroy();
            sInstance = null;
        }
    }

    // ── Instância ─────────────────────────────────────────────────────────────
    private final SoundPool mSoundPool;
    private boolean         mLoaded = false;

    // IDs dos sons carregados no SoundPool
    private int mIdSelectChopp      = 0;
    private int mIdSelectPayment    = 0;
    private int mIdTimerWarning     = 0;
    private int mIdPaymentSuccess   = 0;
    private int mIdValveTick        = 0;
    private int mIdValveOpen        = 0;

    // Controle para não disparar o warning mais de uma vez por ciclo de timer
    private boolean mTimerWarningSent = false;

    private SoundManager(Context context) {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        mSoundPool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(attrs)
                .build();

        mSoundPool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            if (status == 0) {
                Log.d(TAG, "Som carregado: sampleId=" + sampleId);
            } else {
                Log.w(TAG, "Falha ao carregar som: sampleId=" + sampleId + " status=" + status);
            }
        });

        // Carregar todos os recursos
        mIdSelectChopp    = mSoundPool.load(context, R.raw.sound_select_chopp,    1);
        mIdSelectPayment  = mSoundPool.load(context, R.raw.sound_select_payment,  1);
        mIdTimerWarning   = mSoundPool.load(context, R.raw.sound_timer_warning,   1);
        mIdPaymentSuccess = mSoundPool.load(context, R.raw.sound_payment_success, 1);
        mIdValveTick      = mSoundPool.load(context, R.raw.sound_valve_tick,      1);
        mIdValveOpen      = mSoundPool.load(context, R.raw.sound_valve_open,      1);

        mLoaded = true;
        Log.i(TAG, "SoundManager inicializado — 6 sons carregados");
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Bip único ao selecionar o volume de chopp (Home).
     */
    public void playSelectChopp() {
        play(mIdSelectChopp, 0.8f);
    }

    /**
     * Bip duplo ao selecionar a forma de pagamento (PIX / Crédito / Débito).
     */
    public void playSelectPayment() {
        play(mIdSelectPayment, 0.8f);
    }

    /**
     * 3 bips rápidos de alerta quando o timer de pagamento chega em ≤ 20 segundos.
     * Possui proteção interna para não disparar mais de uma vez por ciclo.
     * Chame {@link #resetTimerWarning()} ao iniciar um novo timer.
     */
    public void playTimerWarning() {
        if (!mTimerWarningSent) {
            mTimerWarningSent = true;
            play(mIdTimerWarning, 1.0f);
            Log.i(TAG, "[SOUND] Timer warning disparado");
        }
    }

    /**
     * Reseta o flag de controle do timer warning.
     * Deve ser chamado toda vez que um novo countdown de pagamento é iniciado.
     */
    public void resetTimerWarning() {
        mTimerWarningSent = false;
    }

    /**
     * Som de caixa registradora ao confirmar o pagamento.
     */
    public void playPaymentSuccess() {
        play(mIdPaymentSuccess, 1.0f);
    }

    /**
     * Bip curto de contagem — chamado 1x por segundo no countdown de reinício
     * da válvula (5s antes de liberar).
     */
    public void playValveTick() {
        play(mIdValveTick, 0.7f);
    }

    /**
     * Arpejo ascendente indicando que a válvula está disponível (IN: recebido).
     */
    public void playValveOpen() {
        play(mIdValveOpen, 0.9f);
    }

    // ── Interno ───────────────────────────────────────────────────────────────

    private void play(int soundId, float volume) {
        if (!mLoaded || mSoundPool == null || soundId == 0) {
            Log.w(TAG, "play() ignorado — não carregado ou soundId=0");
            return;
        }
        mSoundPool.play(soundId, volume, volume, 1, 0, 1.0f);
    }

    private void destroy() {
        if (mSoundPool != null) {
            mSoundPool.release();
        }
        Log.i(TAG, "SoundManager liberado");
    }
}
