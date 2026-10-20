package com.example.omnihear;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class AudioService extends Service {

    private static final String TAG = "OmniHearService";

    public static final String ACTION_UPDATE_UI = "com.example.omnihear.UPDATE_UI";
    public static final String EXTRA_EMERGENCY = "EXTRA_EMERGENCY";
    public static final String EXTRA_LABEL = "EXTRA_LABEL";
    public static final String EXTRA_PROB = "EXTRA_PROB";
    public static final String EXTRA_PCT_BG = "EXTRA_PCT_BG";
    public static final String EXTRA_PCT_HORN = "EXTRA_PCT_HORN";
    public static final String EXTRA_PCT_SIREN = "EXTRA_PCT_SIREN";

    private static final String MODEL_FILE = "custom_safehear.tflite";
    private static final String CHANNEL_ID_SERVICE = "omnihear_service";
    private static final String CHANNEL_ID_ALERTS = "omnihear_alerts";
    private static final int FOREGROUND_NOTIFICATION_ID = 100;
    private static final int ALERT_NOTIFICATION_ID = 101;

    private static final int SAMPLE_RATE = 16000;
    private static final int RECORDING_LENGTH = 15600;

    private AudioRecord audioRecord;
    private Thread recordingThread;
    private Interpreter tflite;
    private volatile boolean isListening = false;

    private short[] audioBuffer;
    private float[] floatInputBuffer;

    private ToneGenerator toneGenerator;
    private AudioManager audioManager;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private long lastAlertTime = 0;

    private final String[] CLASSES = {"Background", "Car Horn", "Siren"};

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        createNotificationChannels();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");

        // Create channels first
        createNotificationChannels();

        // Build notification
        Notification notification = buildForegroundNotification();

        // Start foreground with MICROPHONE type only
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification);
            }
            Log.d(TAG, "startForeground SUCCESS");
        } catch (Exception e) {
            Log.e(TAG, "startForeground FAILED", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Acquire WakeLock
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniHear::Mic");
                wakeLock.acquire();
                Log.d(TAG, "WakeLock acquired");
            }
        }

        // Start listening only once
        if (!isListening) {
            startListening();
        }

        return START_STICKY;
    }

    private Notification buildForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
                .setContentTitle("SafeHear is Active")
                .setContentText("Listening for emergency sounds...")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;

            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID_SERVICE, "Background Listening", NotificationManager.IMPORTANCE_LOW);
            serviceChannel.setDescription("Keeps SafeHear listening in the background");
            serviceChannel.setShowBadge(false);
            nm.createNotificationChannel(serviceChannel);

            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ID_ALERTS, "Emergency Alerts", NotificationManager.IMPORTANCE_HIGH);
            alertChannel.setDescription("Alerts for detected emergency sounds");
            nm.createNotificationChannel(alertChannel);
        }
    }

    private MappedByteBuffer loadModelFile() throws Exception {
        var afd = this.getAssets().openFd(MODEL_FILE);
        var fis = new FileInputStream(afd.getFileDescriptor());
        var fc = fis.getChannel();
        return fc.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength());
    }

    private void startListening() {
        Log.d(TAG, "startListening");

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "No RECORD_AUDIO permission");
            stopSelf();
            return;
        }

        try {
            tflite = new Interpreter(loadModelFile());
            tflite.resizeInput(0, new int[]{RECORDING_LENGTH});
            tflite.allocateTensors();
            Log.d(TAG, "TFLite model loaded");
        } catch (Exception e) {
            Log.e(TAG, "TFLite init error", e);
            stopSelf();
            return;
        }

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2;
        }

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        audioBuffer = new short[RECORDING_LENGTH];
        floatInputBuffer = new float[RECORDING_LENGTH];

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to init");
            stopSelf();
            return;
        }

        audioRecord.startRecording();
        isListening = true;
        Log.d(TAG, "Recording started");

        recordingThread = new Thread(this::inferenceLoop, "InferenceThread");
        recordingThread.start();
    }

    private void inferenceLoop() {
        Log.d(TAG, "Inference loop started");
        while (isListening) {
            try {
                if (audioRecord == null || audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    Thread.sleep(100);
                    continue;
                }

                int readSize = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                if (readSize <= 0) continue;

                // Sliding Window
                if (readSize < floatInputBuffer.length) {
                    System.arraycopy(floatInputBuffer, readSize, floatInputBuffer, 0,
                            floatInputBuffer.length - readSize);
                }

                // Software Gain (5x)
                int startIndex = Math.max(0, floatInputBuffer.length - readSize);
                for (int i = 0; i < readSize; i++) {
                    float scaledVal = (audioBuffer[i] / 32767.0f) * 5.0f;
                    if (scaledVal > 1.0f) scaledVal = 1.0f;
                    if (scaledVal < -1.0f) scaledVal = -1.0f;
                    floatInputBuffer[startIndex + i] = scaledVal;
                }

                float[][] outputBuffer = new float[1][3];
                tflite.run(floatInputBuffer, outputBuffer);

                float probBg = Math.min(1.0f, outputBuffer[0][0]);
                float probHorn = Math.min(1.0f, outputBuffer[0][1]);
                float probSiren = Math.min(1.0f, outputBuffer[0][2]);

                String detectedLabel = "";
                boolean emergency = false;
                float highestProb = 0.3f;

                if (probHorn > highestProb) {
                    detectedLabel = CLASSES[1];
                    highestProb = probHorn;
                    emergency = true;
                }
                if (probSiren > highestProb) {
                    detectedLabel = CLASSES[2];
                    highestProb = probSiren;
                    emergency = true;
                }

                if (emergency) {
                    triggerEarphoneAlert(detectedLabel);
                }

                // Broadcast to UI
                Intent bi = new Intent(ACTION_UPDATE_UI);
                bi.setPackage(getPackageName());
                bi.putExtra(EXTRA_EMERGENCY, emergency);
                bi.putExtra(EXTRA_LABEL, detectedLabel);
                bi.putExtra(EXTRA_PROB, (int) (highestProb * 100));
                bi.putExtra(EXTRA_PCT_BG, (int) (probBg * 100));
                bi.putExtra(EXTRA_PCT_HORN, (int) (probHorn * 100));
                bi.putExtra(EXTRA_PCT_SIREN, (int) (probSiren * 100));
                sendBroadcast(bi);

            } catch (Exception e) {
                Log.e(TAG, "Inference error", e);
            }
        }
    }

    private void triggerEarphoneAlert(String label) {
        long now = System.currentTimeMillis();
        if (now - lastAlertTime < 1500) return;
        lastAlertTime = now;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(f -> {}).build();
            audioManager.requestAudioFocus(req);
        }

        toneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500);

        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(1000);
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Emergency Detected!")
                    .setContentText(label.toUpperCase() + " detected nearby.")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM);
            NotificationManagerCompat.from(this).notify(ALERT_NOTIFICATION_ID, builder.build());
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved - restarting");
        Intent restart = new Intent(getApplicationContext(), AudioService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restart);
        } else {
            startService(restart);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        isListening = false;

        if (recordingThread != null) {
            try { recordingThread.join(1000); } catch (InterruptedException ignored) {}
            recordingThread = null;
        }
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
        if (tflite != null) { tflite.close(); tflite = null; }
        if (toneGenerator != null) { toneGenerator.release(); toneGenerator = null; }
        if (wakeLock != null && wakeLock.isHeld()) { wakeLock.release(); wakeLock = null; }

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
