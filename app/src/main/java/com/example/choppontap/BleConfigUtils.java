package com.example.choppontap;

import android.util.Log;

/**
 * BleConfigUtils — adaptado para firmware ASOARESBH/ESP32
 *
 * UUIDs: Nordic UART Service (NUS) padrão — 6E400001/2/3
 * Nome BLE: CHOPP_ + 4 primeiros hex do eFuse MAC (= 2 primeiros octetos do wifiMac)
 * Segurança: Just Works — sem PIN, sem bond
 *
 * Regra do nome (espelha geraNomeBle() do firmware):
 *   ESP.getEfuseMac() armazena o MAC WiFi com bytes invertidos.
 *   O firmware pega os 4 primeiros caracteres hex do resultado, em maiúsculas.
 *   Equivalente em Android: pegar os 2 primeiros octetos do wifiMac da API.
 *   Exemplo: wifiMac = "48:F6:EE:23:2A:6C" -> eFuse bytes 0,1 = 48,F6 -> nome = "CHOPP_48F6"
 *
 *   ATENÇÃO: o NRF Connect mostrou "CHOPP_F648" neste dispositivo específico.
 *   Isso ocorre porque o eFuse MAC pode estar invertido dependendo do chip.
 *   A função deriveBleNameFromWifiMac() tenta as duas variantes e usa a validação
 *   por prefix durante o scan — o Android aceita qualquer "CHOPP_XXXX" encontrado
 *   cujo MAC BLE bata com o esperado da API.
 */
public class BleConfigUtils {

    private static final String TAG = "BleConfigUtils";

    // UUIDs — Nordic UART Service (NUS) — firmware operaBLE.h
    public static final String SERVICE_UUID           = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String CHARACTERISTIC_UUID_RX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String CHARACTERISTIC_UUID_TX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";

    // Prefixo BLE — igual a BLE_NAME_PREFIX do firmware config.h
    public static final String BLE_NAME_PREFIX = "CHOPP_";

    // MTU solicitado — conforme ANDROID_BLE_INTEGRACAO.md
    public static final int MTU_REQUESTED = 247;

    // Segurança — Just Works: sem PIN, sem bond
    public static final boolean REQUIRES_BOND = false;
    public static final int BLE_PIN = -1;

    // Timeout de scan (ms)
    public static final long SCAN_TIMEOUT_MS = 8000L;

    /** Retorna o nome BLE esperado: bytes 0+1 do wifiMac em ordem direta. Ex: "48:F6:.." -> "CHOPP_48F6" */
    public static String deriveBleNameFromWifiMac(String wifiMac) {
        if (wifiMac == null || wifiMac.isEmpty()) return BLE_NAME_PREFIX;
        try {
            String[] parts = wifiMac.toUpperCase().split(":");
            if (parts.length < 2) return BLE_NAME_PREFIX;
            return BLE_NAME_PREFIX + parts[0] + parts[1];
        } catch (Exception e) {
            Log.e(TAG, "Erro ao derivar nome BLE: " + e.getMessage());
            return BLE_NAME_PREFIX;
        }
    }

    /** Variante invertida (little-endian eFuse): bytes 1+0. Ex: "48:F6:.." -> "CHOPP_F648" (observado no NRF Connect) */
    public static String deriveBleNameFromWifiMacInverted(String wifiMac) {
        if (wifiMac == null || wifiMac.isEmpty()) return BLE_NAME_PREFIX;
        try {
            String[] parts = wifiMac.toUpperCase().split(":");
            if (parts.length < 2) return BLE_NAME_PREFIX;
            return BLE_NAME_PREFIX + parts[1] + parts[0];
        } catch (Exception e) {
            Log.e(TAG, "Erro ao derivar nome BLE invertido: " + e.getMessage());
            return BLE_NAME_PREFIX;
        }
    }

    /** Retorna true se o nome comecar com CHOPP_ (pertence ao firmware). */
    public static boolean isChoppDevice(String deviceName) {
        return deviceName != null && deviceName.toUpperCase().startsWith(BLE_NAME_PREFIX);
    }

    /** Testa as duas variantes (direta e invertida) do nome derivado do wifiMac. */
    public static boolean matchesBleNameForMac(String foundName, String wifiMac) {
        if (foundName == null || wifiMac == null) return false;
        String upper = foundName.toUpperCase();
        return upper.equals(deriveBleNameFromWifiMac(wifiMac).toUpperCase())
            || upper.equals(deriveBleNameFromWifiMacInverted(wifiMac).toUpperCase());
    }
}
