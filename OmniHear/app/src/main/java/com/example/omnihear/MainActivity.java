package com.example.omnihear;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 1337;

    private Button btnToggleListen;
    private TextView tvStatus;
    private boolean isListening = false;

    private BroadcastReceiver uiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean isEmergency = intent.getBooleanExtra(AudioService.EXTRA_EMERGENCY, false);
            String label = intent.getStringExtra(AudioService.EXTRA_LABEL);
            int prob = intent.getIntExtra(AudioService.EXTRA_PROB, 0);
            int pctBg = intent.getIntExtra(AudioService.EXTRA_PCT_BG, 0);
            int pctHorn = intent.getIntExtra(AudioService.EXTRA_PCT_HORN, 0);
            int pctSiren = intent.getIntExtra(AudioService.EXTRA_PCT_SIREN, 0);

            if (isEmergency) {
                tvStatus.setText("EMERGENCY DETECTED!\n" + label.toUpperCase() +
                        "\n(Confidence: " + prob + "%)\n\n" +
                        "Bg: " + pctBg + "% | Horn: " + pctHorn + "% | Siren: " + pctSiren + "%");
                tvStatus.setBackgroundColor(Color.parseColor("#D32F2F"));
            } else {
                tvStatus.setText("Status: Listening for Emergencies...\n(Background Noise)\n\n" +
                        "Bg: " + pctBg + "% | Horn: " + pctHorn + "% | Siren: " + pctSiren + "%");
                tvStatus.setBackgroundColor(Color.parseColor("#333333"));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnToggleListen = findViewById(R.id.btnToggleListen);
        tvStatus = findViewById(R.id.tvStatus);

        requestNecessaryPermissions();
        requestBatteryExemption();

        btnToggleListen.setOnClickListener(v -> {
            if (!isListening) {
                startListening();
            } else {
                stopListening();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiReceiver, new IntentFilter(AudioService.ACTION_UPDATE_UI),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(uiReceiver, new IntentFilter(AudioService.ACTION_UPDATE_UI));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(uiReceiver); } catch (Exception ignored) {}
    }

    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    private void requestNecessaryPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS
                }, REQUEST_PERMISSIONS);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
            }
        }
    }

    private void startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNecessaryPermissions();
            return;
        }

        Intent serviceIntent = new Intent(this, AudioService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        isListening = true;
        btnToggleListen.setText("STOP LISTENING");
        btnToggleListen.setBackgroundColor(Color.parseColor("#F44336"));
        tvStatus.setText("Status: Listening for Emergencies...");
        tvStatus.setBackgroundColor(Color.parseColor("#333333"));
    }

    private void stopListening() {
        Intent serviceIntent = new Intent(this, AudioService.class);
        stopService(serviceIntent);

        isListening = false;
        btnToggleListen.setText("START LISTENING");
        btnToggleListen.setBackgroundColor(Color.parseColor("#4CAF50"));
        tvStatus.setText("Status: Inactive");
        tvStatus.setBackgroundColor(Color.parseColor("#333333"));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "All permissions required for SafeHear.", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Do NOT stop the service — let it run in the background!
    }
}