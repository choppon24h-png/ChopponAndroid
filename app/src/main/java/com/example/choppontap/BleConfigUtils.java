package com.example.choppontap;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.UUID;

public final class BleConfigUtils {
    private static final String PREF_NAME = "tap_config";

    public static final String KEY_BLE_MAC = "ble_mac";
    public static final String KEY_WIFI_MAC = "wifi_mac";
    public static final String KEY_BLE_NAME_EXPECTED = "ble_name_expected";
    public static final String KEY_BLE_NAME_API = "ble_name_api";
    public static final String KEY_PAIRING_PIN = "pairing_pin";
    public static final String KEY_SERVICE_UUID = "ble_service_uuid";
    public static final String KEY_RX_UUID = "ble_rx_uuid";
    public static final String KEY_TX_UUID = "ble_tx_uuid";
    public static final String KEY_MTU = "ble_mtu";
    public static final String KEY_AUTO_CONNECT = "ble_auto_connect";

    public static final String DEFAULT_PAIRING_PIN = "259087";
    public static final String DEFAULT_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String DEFAULT_RX_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String DEFAULT_TX_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final int DEFAULT_MTU = 247;

    private BleConfigUtils() {}

    public static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null) {
                String trimmed = v.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    public static String normalizeMac(String raw) {
        if (raw == null) return null;
        String clean = raw.trim().toUpperCase(Locale.US).replace('-', ':');
        return clean.isEmpty() ? null : clean;
    }

    /**
     * Deriva o nome BLE esperado a partir do wifiMac retornado pela API.
     *
     * Regra do firmware (ESP32):
     *   bleName = "CHOPP_" + 4 primeiros caracteres HEX do ESP.getEfuseMac()
     *   Exemplo: wifiMac = "DC:B4:D9:99:B8:E2" → hex = "DCB4D999B8E2" → "CHOPP_DCB4"
     *
     * ATENÇÃO: usa os 4 primeiros hex do MAC (bytes 0 e 1 = DC e B4),
     * NÃO os últimos. O ESP32 usa getEfuseMac() que retorna o MAC base
     * na mesma ordem que o wifiMac da API.
     */
    public static String deriveBleNameFromWifiMac(String wifiMac) {
        String mac = normalizeMac(wifiMac);
        if (mac == null) return null;

        // Remove os ':' e pega os 4 primeiros caracteres HEX (2 bytes)
        String hex = mac.replace(":", "");
        if (hex.length() < 4) return null;

        // Exemplo: "DCB4D999B8E2" → "CHOPP_DCB4"
        return "CHOPP_" + hex.substring(0, 4).toUpperCase(Locale.US);
    }

    public static boolean isValidUuid(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        try {
            UUID.fromString(raw.trim());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void persistFromTap(Context context, Tap tap) {
        if (context == null || tap == null) return;

        String wifiMac = normalizeMac(firstNonBlank(tap.wifiMac, tap.wifi_mac));
        String bleNameApi = firstNonBlank(tap.bleName, tap.ble_name);
        String bleNameDerived = deriveBleNameFromWifiMac(wifiMac);
        String bleNameExpected = firstNonBlank(bleNameDerived, bleNameApi);

        String bleMac = normalizeMac(firstNonBlank(tap.bleMac, tap.ble_mac, tap.esp32_mac));

        String pairingPin = firstNonBlank(tap.pairingPin, tap.pairing_pin, DEFAULT_PAIRING_PIN);

        String serviceUuid = firstNonBlank(tap.serviceUuid, tap.service_uuid, DEFAULT_SERVICE_UUID);
        if (!isValidUuid(serviceUuid)) serviceUuid = DEFAULT_SERVICE_UUID;

        String rxUuid = firstNonBlank(tap.rxUuid, tap.rx_uuid, DEFAULT_RX_UUID);
        if (!isValidUuid(rxUuid)) rxUuid = DEFAULT_RX_UUID;

        String txUuid = firstNonBlank(tap.txUuid, tap.tx_uuid, DEFAULT_TX_UUID);
        if (!isValidUuid(txUuid)) txUuid = DEFAULT_TX_UUID;

        int mtu = (tap.mtu != null && tap.mtu > 0) ? tap.mtu : DEFAULT_MTU;
        boolean autoConnect = firstNonBlank(
                tap.autoConnect != null ? String.valueOf(tap.autoConnect) : null,
                tap.auto_connect != null ? String.valueOf(tap.auto_connect) : null
        ) != null && (Boolean.TRUE.equals(tap.autoConnect) || Boolean.TRUE.equals(tap.auto_connect));

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        if (bleMac != null) {
            editor.putString(KEY_BLE_MAC, bleMac);
            editor.putString("esp32_mac", bleMac);
        }
        if (wifiMac != null) editor.putString(KEY_WIFI_MAC, wifiMac);
        if (bleNameExpected != null) editor.putString(KEY_BLE_NAME_EXPECTED, bleNameExpected.toUpperCase(Locale.US));
        if (bleNameApi != null) editor.putString(KEY_BLE_NAME_API, bleNameApi.toUpperCase(Locale.US));

        editor.putString(KEY_PAIRING_PIN, pairingPin);
        editor.putString(KEY_SERVICE_UUID, serviceUuid.toUpperCase(Locale.US));
        editor.putString(KEY_RX_UUID, rxUuid.toUpperCase(Locale.US));
        editor.putString(KEY_TX_UUID, txUuid.toUpperCase(Locale.US));
        editor.putInt(KEY_MTU, mtu);
        editor.putBoolean(KEY_AUTO_CONNECT, autoConnect);
        editor.apply();
    }
}
