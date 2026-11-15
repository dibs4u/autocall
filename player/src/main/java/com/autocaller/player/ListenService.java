package com.autocaller.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.File;

/**
 * Foreground service: listens through the microphone, and when the Detector hears a
 * voice after the ringing, plays the message through the loudspeaker.
 */
public class ListenService extends Service {
    public static final String ACTION_START = "com.autocaller.player.START";
    public static final String ACTION_STOP = "com.autocaller.player.STOP";
    public static final String ACTION_APPLY = "com.autocaller.player.APPLY";
    public static final String MESSAGE_FILE = "message.audio";

    public interface Ui {
        void onStatus(String state, Detector d, int played);
        void onLog(String line);
    }

    public static volatile Ui ui;
    public static volatile boolean running;

    private static final String CHANNEL = "player";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Detector det = new Detector();
    private volatile boolean stop;
    private Thread worker;
    private MediaPlayer mp;
    private PowerManager.WakeLock wake;
    private int played;
    private int savedVol = -1;
    private String state = "";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_APPLY.equals(a)) {
            applyPrefs();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        running = true;
        stop = false;
        played = 0;
        startForegroundNow();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoCallerPlayer:listen");
        wake.acquire();
        applyPrefs();
        det.reset();
        log("Listening started. Start the calls on the other phone.");
        startLoop();
        return START_NOT_STICKY;
    }

    private void applyPrefs() {
        SharedPreferences p = getSharedPreferences("p", MODE_PRIVATE);
        det.marginDb = p.getInt("margin", 12);
        det.requireRingback = p.getBoolean("needRing", true);
    }

    private int prefDelay() { return getSharedPreferences("p", MODE_PRIVATE).getInt("delay", 700); }
    private int prefRest() { return getSharedPreferences("p", MODE_PRIVATE).getInt("rest", 8); }
    private boolean prefMaxVol() { return getSharedPreferences("p", MODE_PRIVATE).getBoolean("maxVol", true); }

    private void startForegroundNow() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Listening",
                NotificationManager.IMPORTANCE_LOW));
        Intent stopIntent = new Intent(this, ListenService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("AutoCaller Player is listening")
                .setContentText("Plays the message when someone answers")
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }
    }

    private void startLoop() {
        if (stop) return;
        worker = new Thread(this::listenLoop, "listen");
        worker.start();
    }

    private void listenLoop() {
        AudioRecord r = null;
        try {
            int min = AudioRecord.getMinBufferSize(Detector.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Detector.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, 32768));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                log("The microphone could not be opened. Is another app using it?");
                main.post(this::shutdown);
                return;
            }
            r.startRecording();
            setState("Waiting for the call to ring");
            short[] f = new short[Detector.FRAME];
            int have = 0;
            long lastUi = 0;
            while (!stop) {
                int n = r.read(f, have, f.length - have);
                if (n < 0) {
                    log("Microphone read error " + n);
                    main.post(this::shutdown);
                    return;
                }
                have += n;
                if (have < f.length) continue;
                have = 0;
                int e = det.process(f, f.length);
                if (e == Detector.RINGBACK) {
                    log("Ringing heard.");
                    setState("Ringing heard, waiting for a voice");
                }
                long now = System.currentTimeMillis();
                if (now - lastUi > 150) { lastUi = now; push(); }
                if (e == Detector.TRIGGER) {
                    int delay = prefDelay();
                    log("Voice heard. Playing the message in " + delay + " ms.");
                    setState("Voice heard, playing the message");
                    main.postDelayed(this::playMessage, delay);
                    return;
                }
            }
        } catch (Exception ex) {
            log("Listening error: " + ex);
            main.post(this::shutdown);
        } finally {
            if (r != null) {
                try { r.stop(); } catch (Exception ignored) { }
                r.release();
            }
        }
    }

    private void playMessage() {
        if (stop) return;
        File f = new File(getFilesDir(), MESSAGE_FILE);
        if (!f.exists()) {
            log("There is no message yet. Choose or record one first.");
            shutdown();
            return;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (prefMaxVol()) {
                savedVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC,
                        am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            }
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(m -> finishPlay());
            mp.setOnErrorListener((m, what, extra) -> {
                log("Playback error " + what + "/" + extra);
                finishPlay();
                return true;
            });
            mp.prepare();
            mp.start();
            setState("Playing the message");
        } catch (Exception ex) {
            log("Could not play the message: " + ex);
            finishPlay();
        }
    }

    private void finishPlay() {
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
        if (savedVol >= 0) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0);
            savedVol = -1;
        }
        played++;
        log("Message played (" + played + " so far). Resting " + prefRest() + " s.");
        setState("Resting before the next call");
        main.postDelayed(() -> {
            if (stop) return;
            det.rearm();
            startLoop();
        }, prefRest() * 1000L);
    }

    private void shutdown() {
        stop = true;
        running = false;
        main.removeCallbacksAndMessages(null);
        if (mp != null) {
            try { mp.release(); } catch (Exception ignored) { }
            mp = null;
        }
        if (savedVol >= 0) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0);
            savedVol = -1;
        }
        if (wake != null && wake.isHeld()) wake.release();
        setState("Stopped");
        log("Listening stopped. Messages played: " + played);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stop = true;
        running = false;
        super.onDestroy();
    }

    private void setState(String s) { state = s; push(); }

    private void push() {
        Ui u = ui;
        if (u != null) u.onStatus(state, det, played);
    }

    private void log(String s) {
        Ui u = ui;
        if (u != null) u.onLog(s);
    }
}
