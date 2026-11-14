package com.autocaller.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Record the voice message (or pick an audio file) that is played when a call is answered. */
public class RecordActivity extends Activity {

    private static final int REQ_MIC = 41;
    private static final int REQ_FILE = 42;
    private static final long MAX_AUDIO_BYTES = 50L * 1024 * 1024;

    private TextView currentInfo;
    private TextView timer;
    private ProgressBar level;
    private Button recordBtn;
    private Button playTakeBtn;
    private Button useBtn;
    private Button playCurrentBtn;

    private MediaRecorder recorder;
    private MediaPlayer player;
    private int playing; // 0 = nothing, 1 = the new recording, 2 = the saved message
    private File take;
    private boolean recording;
    private boolean hasTake;
    private int takeMs;
    private long recordStart;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            timer.setText(Util.dur(System.currentTimeMillis() - recordStart));
            try {
                level.setProgress(Math.min(100, recorder.getMaxAmplitude() / 200));
            } catch (Exception ignored) {
            }
            h.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.Screen s = Ui.screen(this, "Voice message", true, true);

        LinearLayout current = Ui.card(this);
        current.addView(Ui.text(this, "Message in use", 16, Ui.INK, true));
        currentInfo = Ui.text(this, "", 14, Ui.MUTED, false);
        current.addView(currentInfo, Ui.below(this, 4));
        playCurrentBtn = Ui.secondary(this, "Play");
        current.addView(playCurrentBtn, Ui.below(this, 12));
        s.body.addView(current);

        LinearLayout rec = Ui.card(this);
        rec.addView(Ui.text(this, "Record a new message", 16, Ui.INK, true));
        timer = Ui.text(this, "0:00", 44, Ui.INK, true);
        timer.setGravity(Gravity.CENTER);
        rec.addView(timer, Ui.below(this, 8));
        level = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        level.setMax(100);
        level.setProgressTintList(ColorStateList.valueOf(Ui.RED));
        rec.addView(level, Ui.below(this, 4));
        recordBtn = Ui.danger(this, "Start recording");
        rec.addView(recordBtn, Ui.below(this, 12));
        LinearLayout takeRow = Ui.row(this);
        playTakeBtn = Ui.secondary(this, "Listen");
        useBtn = Ui.primary(this, "Use this recording");
        takeRow.addView(playTakeBtn, Ui.share(this, 0));
        takeRow.addView(useBtn, Ui.share(this, 8));
        rec.addView(takeRow, Ui.below(this, 8));
        rec.addView(Ui.text(this, "Record in a quiet room and speak clearly, a little louder"
                + " than usual. Start with a short greeting: people need a second to bring the"
                + " phone to their ear. 20 to 40 seconds works best.", 13, Ui.MUTED, false),
                Ui.below(this, 12));
        s.body.addView(rec);

        LinearLayout file = Ui.card(this);
        file.addView(Ui.text(this, "Or use an audio file", 16, Ui.INK, true));
        file.addView(Ui.text(this, "An MP3, M4A or WAV file that is already on this phone.",
                14, Ui.MUTED, false), Ui.below(this, 4));
        Button pickBtn = Ui.secondary(this, "Choose audio file");
        file.addView(pickBtn, Ui.below(this, 12));
        s.body.addView(file);

        recordBtn.setOnClickListener(v -> toggleRecording());
        playTakeBtn.setOnClickListener(v -> {
            if (playing == 1) stopPlayback();
            else if (hasTake && take != null) play(take.getAbsolutePath(), 1);
        });
        playCurrentBtn.setOnClickListener(v -> {
            if (playing == 2) stopPlayback();
            else play(Prefs.getString(this, Prefs.AUDIO_PATH, ""), 2);
        });
        useBtn.setOnClickListener(v -> useTake());
        pickBtn.setOnClickListener(v -> pickFile());
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (recording) stopRecording();
        stopPlayback();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MIC) {
            if (Util.has(this, Util.P_MIC)) toggleRecording();
            else Ui.toast(this, "The Microphone permission is needed to record.");
        }
    }

    private void render() {
        String path = Prefs.getString(this, Prefs.AUDIO_PATH, "");
        boolean saved = path.length() > 0 && new File(path).exists();
        if (saved) {
            currentInfo.setText(Util.dur(Prefs.getInt(this, Prefs.AUDIO_MS, 0)) + " long · "
                    + Prefs.getString(this, Prefs.AUDIO_LABEL, "message") + " · saved "
                    + Util.dateTime(Prefs.getLong(this, Prefs.AUDIO_TIME, 0)));
        } else {
            currentInfo.setText("Nothing saved yet.");
        }
        Ui.enable(playCurrentBtn, saved && !recording);
        playCurrentBtn.setText(playing == 2 ? "Stop" : "Play");

        recordBtn.setText(recording ? "Stop recording" : (hasTake ? "Record again" : "Start recording"));
        Ui.enable(playTakeBtn, hasTake && !recording);
        playTakeBtn.setText(playing == 1 ? "Stop" : "Listen");
        Ui.enable(useBtn, hasTake && !recording);
        if (!recording) timer.setText(hasTake ? Util.dur(takeMs) : "0:00");
    }

    // ---- recording ----------------------------------------------------------------------

    private void toggleRecording() {
        if (recording) {
            stopRecording();
            return;
        }
        if (CampaignService.running) {
            Ui.toast(this, "Stop the calling first.");
            return;
        }
        if (!Util.has(this, Util.P_MIC)) {
            requestPermissions(new String[]{Util.P_MIC}, REQ_MIC);
            return;
        }
        stopPlayback();
        take = new File(getFilesDir(), "take.m4a");
        if (take.exists()) take.delete();
        hasTake = false;
        try {
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setOutputFile(take.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recordStart = System.currentTimeMillis();
            h.post(tick);
        } catch (Exception e) {
            Logx.err("record start", e);
            releaseRecorder();
            Ui.info(this, "Recording did not start", "The microphone could not be used: "
                    + e.getMessage());
        }
        render();
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        h.removeCallbacks(tick);
        long length = System.currentTimeMillis() - recordStart;
        boolean ok = true;
        try {
            recorder.stop();
        } catch (Exception e) {
            ok = false;
        }
        releaseRecorder();
        level.setProgress(0);
        if (!ok || take == null || !take.exists() || take.length() < 1000 || length < 800) {
            hasTake = false;
            Ui.toast(this, "That was too short. Please record again.");
        } else {
            hasTake = true;
            takeMs = durationOf(take.getAbsolutePath(), (int) length);
        }
        render();
    }

    private void releaseRecorder() {
        MediaRecorder r = recorder;
        recorder = null;
        if (r != null) {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void useTake() {
        if (!hasTake || take == null) return;
        if (CampaignService.running) {
            Ui.toast(this, "Stop the calling first.");
            return;
        }
        stopPlayback();
        File dest = new File(getFilesDir(), "message_" + System.currentTimeMillis() + ".m4a");
        try {
            if (!take.renameTo(dest)) {
                copy(new FileInputStream(take), dest);
                take.delete();
            }
        } catch (Exception e) {
            Logx.err("save recording", e);
            Ui.toast(this, "The recording could not be saved.");
            return;
        }
        setMessage(dest, takeMs, "recorded in the app");
        hasTake = false;
        render();
        Ui.toast(this, "Saved. This message will be played on every answered call.");
    }

    // ---- audio file ---------------------------------------------------------------------

    private void pickFile() {
        if (CampaignService.running) {
            Ui.toast(this, "Stop the calling first.");
            return;
        }
        if (recording) stopRecording();
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            startActivityForResult(Intent.createChooser(i, "Choose an audio file"), REQ_FILE);
        } catch (Exception e) {
            Ui.toast(this, "No file picker is available on this phone.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        final File dest = new File(getFilesDir(), "message_" + System.currentTimeMillis() + ".audio");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                int ms = 0;
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("the file could not be opened");
                    copy(in, dest);
                    ms = durationOf(dest.getAbsolutePath(), 0);
                    if (ms <= 0) error = "That file could not be played as audio.";
                } catch (Exception e) {
                    Logx.err("pick audio", e);
                    error = "The file could not be used: " + e.getMessage();
                }
                final String err = error;
                final int length = ms;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (err != null) {
                            dest.delete();
                            if (!isFinishing()) Ui.info(RecordActivity.this, "Audio file", err);
                            return;
                        }
                        setMessage(dest, length, "audio file");
                        if (!isFinishing()) {
                            render();
                            Ui.toast(RecordActivity.this, "Saved. This file will be played on"
                                    + " every answered call.");
                        }
                    }
                });
            }
        }).start();
    }

    // ---- helpers ------------------------------------------------------------------------

    /** Makes the given file the message in use and removes the previous one. */
    private void setMessage(File f, int ms, String label) {
        String old = Prefs.getString(this, Prefs.AUDIO_PATH, "");
        Prefs.sp(this).edit()
                .putString(Prefs.AUDIO_PATH, f.getAbsolutePath())
                .putInt(Prefs.AUDIO_MS, ms)
                .putLong(Prefs.AUDIO_TIME, System.currentTimeMillis())
                .putString(Prefs.AUDIO_LABEL, label)
                .apply();
        if (old.length() > 0 && !old.equals(f.getAbsolutePath())) new File(old).delete();
    }

    private static void copy(InputStream in, File dest) throws IOException {
        try {
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[32768];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > MAX_AUDIO_BYTES) throw new IOException("the file is too large");
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    /** Length of an audio file in milliseconds, or the fallback if it cannot be read. */
    static int durationOf(String path, int fallback) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setDataSource(path);
            mp.prepare();
            int d = mp.getDuration();
            return d > 0 ? d : fallback;
        } catch (Exception e) {
            return fallback;
        } finally {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void play(String path, int which) {
        stopPlayback();
        if (path == null || path.length() == 0 || recording) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setDataSource(path);
            mp.prepare();
            mp.setOnCompletionListener(m -> stopPlayback());
            mp.start();
            playing = which;
        } catch (Exception e) {
            Logx.err("play", e);
            stopPlayback();
            Ui.toast(this, "It could not be played.");
        }
        render();
    }

    private void stopPlayback() {
        MediaPlayer mp = player;
        player = null;
        playing = 0;
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
        }
        if (recordBtn != null) render();
    }
}
