package com.autocaller.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
 * The screen reads the static fields below a few times a second.
 */
public class ListenService extends Service {
    static final String ACTION_START = "com.autocaller.player.START";
    static final String ACTION_STOP = "com.autocaller.player.STOP";
    static final String ACTION_APPLY = "com.autocaller.player.APPLY";

    static final int ST_STOPPED = 0, ST_WAIT_RING = 1, ST_WAIT_VOICE = 2, ST_PLAYING = 3, ST_REST = 4;

    // ---- read by the screen
    static volatile boolean running;
    static volatile int state = ST_STOPPED;
    static volatile long stateSince;
    static volatile long restUntil;
    static volatile int played;
    static volatile String lastMessage = "";
    static final Detector det = new Detector();

    private static final String CHANNEL = "player";
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stop;
    private MediaPlayer mp;
    private PowerManager.WakeLock wake;
    private int savedVol = -1;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Plog.init(this);
        String a = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            shutdown("Stopped by you.");
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
        lastMessage = "";
        startForegroundNow();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoCallerPlayer:listen");
        wake.acquire();
        applyPrefs();
        det.reset();
        Plog.add("Listening started: sensitivity " + det.marginDb + " dB, voice "
                + det.minVoiceMs + " ms, ringing check " + (det.requireRingback ? "on" : "off")
                + ", phone " + Build.MANUFACTURER + " " + Build.MODEL
                + " Android " + Build.VERSION.RELEASE);
        startLoop();
        return START_NOT_STICKY;
    }

    private void applyPrefs() {
        det.marginDb = Prefs.getInt(this, Prefs.MARGIN_DB, Prefs.DEF_MARGIN_DB);
        det.minVoiceMs = Prefs.getInt(this, Prefs.MIN_VOICE_MS, Prefs.DEF_MIN_VOICE_MS);
        det.requireRingback = Prefs.getBool(this, Prefs.NEED_RING, true);
    }

    private void startForegroundNow() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Listening",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, ListenService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent openPi = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class),
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

    private void setState(int s) {
        state = s;
        stateSince = System.currentTimeMillis();
    }

    private void startLoop() {
        if (stop) return;
        new Thread(this::listenLoop, "listen").start();
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
                Plog.add("The microphone could not be opened.");
                main.post(() -> shutdown("The microphone could not be opened. Is another app using it?"));
                return;
            }
            r.startRecording();
            setState(det.ringbackSeen() ? ST_WAIT_VOICE : ST_WAIT_RING);
            short[] f = new short[Detector.FRAME];
            int have = 0;
            while (!stop) {
                int n = r.read(f, have, f.length - have);
                if (n < 0) {
                    Plog.add("Microphone read error " + n);
                    main.post(() -> shutdown("The microphone stopped working."));
                    return;
                }
                have += n;
                if (have < f.length) continue;
                have = 0;
                int e = det.process(f, f.length);
                if (e == Detector.RINGBACK) {
                    Plog.add("Ringing heard.");
                    setState(ST_WAIT_VOICE);
                }
                if (e == Detector.TRIGGER) {
                    int delay = Prefs.getInt(this, Prefs.DELAY_MS, Prefs.DEF_DELAY_MS);
                    Plog.add("Voice heard. Playing the message in " + delay + " ms.");
                    setState(ST_PLAYING);
                    main.postDelayed(this::playMessage, delay);
                    return;
                }
            }
        } catch (Exception ex) {
            Plog.add("Listening error: " + ex);
            main.post(() -> shutdown("Listening error: " + ex.getMessage()));
        } finally {
            if (r != null) {
                try {
                    r.stop();
                } catch (Exception ignored) {
                }
                r.release();
            }
        }
    }

    private void playMessage() {
        if (stop) return;
        File f = new File(getFilesDir(), Prefs.MESSAGE_FILE);
        if (!f.exists()) {
            shutdown("There is no message yet. Choose or record one first.");
            return;
        }
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (Prefs.getBool(this, Prefs.MAX_VOL, true)) {
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
            mp.setOnCompletionListener(m -> finishPlay(true));
            mp.setOnErrorListener((m, what, extra) -> {
                Plog.add("Playback error " + what + "/" + extra);
                finishPlay(false);
                return true;
            });
            mp.prepare();
            mp.start();
            setState(ST_PLAYING);
        } catch (Exception ex) {
            Plog.add("Could not play the message: " + ex);
            finishPlay(false);
        }
    }

    private void releasePlayer() {
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
        if (savedVol >= 0) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0);
            savedVol = -1;
        }
    }

    private void finishPlay(boolean ok) {
        releasePlayer();
        if (ok) played++;
        int rest = Prefs.getInt(this, Prefs.REST_SEC, Prefs.DEF_REST_SEC);
        Plog.add((ok ? "Message played (" + played + " so far)." : "Message not played.")
                + " Resting " + rest + " s.");
        restUntil = System.currentTimeMillis() + rest * 1000L;
        setState(ST_REST);
        main.postDelayed(() -> {
            if (stop) return;
            det.rearm();
            startLoop();
        }, rest * 1000L);
    }

    private void shutdown(String why) {
        if (!running && stop) return;
        stop = true;
        running = false;
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        if (wake != null && wake.isHeld()) wake.release();
        setState(ST_STOPPED);
        lastMessage = why + " Messages played: " + played + ".";
        Plog.add("Listening stopped: " + why + " Messages played: " + played);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (running) {
            stop = true;
            running = false;
            releasePlayer();
            if (wake != null && wake.isHeld()) wake.release();
            setState(ST_STOPPED);
            lastMessage = "Listening was stopped by the phone. Press Start listening again.";
            Plog.add("Service destroyed while listening");
        }
        super.onDestroy();
    }
}
