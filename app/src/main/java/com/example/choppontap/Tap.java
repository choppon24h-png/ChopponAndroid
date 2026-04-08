package com.example.choppontap;

public class Tap {
    public String image;
    public String bebida;
    public String volume;
    public Float preco;
    public Boolean cartao;
    public String esp32_mac;  // MAC address do ESP32 vinculado
    public String wifiMac;
    public String wifi_mac;
    public String bleName;
    public String ble_name;
    public String bleMac;
    public String ble_mac;
    public String pairingPin;
    public String pairing_pin;
    public String serviceUuid;
    public String service_uuid;
    public String rxUuid;
    public String rx_uuid;
    public String txUuid;
    public String tx_uuid;
    public Integer mtu;
    public Boolean autoConnect;
    public Boolean auto_connect;
    public Integer tap_status; // FIX: 1=ativa, 0=desativada (OFFLINE) - retornado pelo verify_tap.php v1.1

    public String toString(){
        return image;
    }
}
