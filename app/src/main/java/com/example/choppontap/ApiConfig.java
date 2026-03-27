package com.example.choppontap;

import android.os.Build;
import android.util.Log;

/**
 * Configuração centralizada de API
 * Gerencia BASE_URL e validações de produção
 */
public class ApiConfig {
    private static final String TAG = "ApiConfig";

    // ✅ CONFIGURAÇÃO OFICIAL - DOMÍNIO CORRETO
    private static final String PRODUCTION_URL = "https://ochoppoficial.com.br/api/";

    // Para desenvolvimento (comentado, não usar normalmente)
    // private static final String DEVELOPMENT_URL = "http://192.168.1.100/choppon/api/";

    // Flag para determinar modo
    private static final boolean IS_DEBUG = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        || "eng".equals(Build.TYPE);

    /**
     * Obtém a URL base configurada
     * @return BASE_URL válida com HTTPS em produção
     */
    public static String getBaseUrl() {
        String url = PRODUCTION_URL;

        Log.d(TAG, "=== API CONFIG ===");
        Log.d(TAG, "[MODE] " + (IS_DEBUG ? "DEBUG" : "PRODUCTION"));
        Log.d(TAG, "[URL] " + url);
        Log.d(TAG, "[HTTPS] " + url.startsWith("https://"));
        Log.d(TAG, "==================");

        // Validação: Em produção, NUNCA usar IP ou HTTP
        if (!IS_DEBUG && !url.startsWith("https://")) {
            throw new RuntimeException(
                "🔴 ERRO CRÍTICO: URL em produção deve usar HTTPS!\n" +
                "URL: " + url + "\n" +
                "Esta é uma proteção de segurança. Não ignore."
            );
        }

        // Validação: Não permitir IP em qualquer modo com PRODUÇÃO
        if (!IS_DEBUG && (url.contains("192.168.") || url.contains("localhost") || url.contains("127.0.0.1"))) {
            throw new RuntimeException(
                "🔴 ERRO CRÍTICO: IP detectado em produção!\n" +
                "URL: " + url + "\n" +
                "Use domínio: https://ochoppoficial.com.br/api/"
            );
        }

        return url;
    }

    /**
     * Endpoint para liberar líquido
     */
    public static String getLiberar() {
        return getBaseUrl() + "liberar.php";
    }

    /**
     * Endpoint para validar TAP
     */
    public static String getVerifyTap() {
        return getBaseUrl() + "verify_tap_mac.php";
    }

    /**
     * Endpoint para verificar versão
     */
    public static String getVersion() {
        return getBaseUrl() + "version.json";
    }

    /**
     * Loga a URL antes de fazer requisição
     */
    public static void logRequest(String endpoint) {
        Log.d(TAG, "[API_REQUEST] " + getBaseUrl() + endpoint);
    }

    /**
     * Força inicialização de validações
     * Chame isso no onCreate da Application
     */
    public static void validate() {
        try {
            getBaseUrl(); // Vai validar e logar
            Log.i(TAG, "✅ Configuração de API validada com sucesso");
        } catch (RuntimeException e) {
            Log.e(TAG, "❌ ERRO DE CONFIGURAÇÃO: " + e.getMessage());
            throw e;
        }
    }
}
