package com.example.choppontap;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings.Secure;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.snackbar.Snackbar;

import okhttp3.OkHttpClient;


public class ModificarTimeout extends AppCompatActivity {

    private final OkHttpClient client = new OkHttpClient();
    String android_id;
    private Handler handler = new Handler();
    private BluetoothServiceIndustrial mBluetoothService;
    private boolean mIsServiceBound = false;
    TextView txtTimeoutAtual;
    Button btnSalvarTimeout;
    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothServiceIndustrial.BLE_STATUS_ACTION.equals(action)) {
                String status = intent.getStringExtra("status");
                if(status.equals("Not found")  || status.startsWith("disconnected")){
                    btnSalvarTimeout.setEnabled(false);
                    btnSalvarTimeout.setTextColor(Color.GRAY);
                    View contextView = (View) findViewById(R.id.mainCalibrar);
                    if (contextView != null) {
                        Snackbar snackbar = Snackbar.make(contextView, "Não foi possível comunicar com a TAP", Snackbar.LENGTH_INDEFINITE)
                                .setAction("Repetir", new View.OnClickListener() {
                                    @Override
                                    public void onClick(View view) {
                                        try {
                                            String mac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                                                    .getString("esp32_mac", "");
                                            if (mac != null && !mac.isEmpty()) mBluetoothService.connectWithMac(mac);
                                        } catch (Exception ex) {
                                            throw new RuntimeException(ex);
                                        }
                                    }
                                });
                        snackbar.show();
                    }
                }
                else if(status.equals("connected") || status.equals("ready")){
                    btnSalvarTimeout.setEnabled(true);
                    btnSalvarTimeout.setTextColor(Color.WHITE);
                    mBluetoothService.sendCommand("$TO:0");
                }

                // mStatusTextView.setText(status); // Ex: "Conectado"
            } else if (BluetoothServiceIndustrial.BLE_DATA_ACTION.equals(action)) {
                String receivedData = intent.getStringExtra("data");
                if (receivedData != null) txtTimeoutAtual.setText(receivedData);
            }
            else if (BluetoothServiceIndustrial.BLE_DATA_ACTION.equals(action)) {
                String device = intent.getStringExtra("device");
                // Nova ação de descoberta de dispositivo (opcional)


            }

        }
    };


    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothServiceIndustrial.LocalBinder binder = (BluetoothServiceIndustrial.LocalBinder) service;
            mBluetoothService = binder.getService();
            mIsServiceBound = true;
            String status = mBluetoothService.getCurrentStatus();
            if(status.equals("ready") || status.equals("connected")){
                mBluetoothService.sendCommand("$TO:0");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mIsServiceBound = false;
        }
    };

    @Override
    protected void onDestroy(){
        super.onDestroy();
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }
    @Override
    protected void onPause() {
        super.onPause();
        // REMOVER REGISTRO NO ONPAUSE PARA EVITAR DUPLICAÇÃO E VAZAMENTOS
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mServiceUpdateReceiver);
    }
    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothServiceIndustrial.BLE_STATUS_ACTION);
        filter.addAction(BluetoothServiceIndustrial.BLE_DATA_ACTION);
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceUpdateReceiver, filter);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);


        setContentView(R.layout.modificar_timeout);
        String android_id = Secure.getString(this.getContentResolver(), Secure.ANDROID_ID);

        WindowInsetsControllerCompat windowInsetsController =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());

        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());

        windowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
        txtTimeoutAtual = findViewById(R.id.txtTimeoutAtual);

        btnSalvarTimeout = findViewById(R.id.btnSalvarTimeout);
        EditText edtNovoTimeout = findViewById(R.id.edtNovoTimeout);
        Intent serviceIntent = new Intent(this, BluetoothServiceIndustrial.class);
        //startService(serviceIntent);
        // Liga-se ao serviço
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        btnSalvarTimeout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    String novaQtd = edtNovoTimeout.getText().toString();
                   mBluetoothService.sendCommand("$TO:"+novaQtd);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

    }
}