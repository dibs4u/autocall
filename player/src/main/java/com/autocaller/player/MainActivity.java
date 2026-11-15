package com.autocaller.player;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements ListenService.Ui {
    private static final int REQ_PICK = 10, REQ_PERM = 11;

    private SharedPreferences prefs;
    private TextView messageInfo, status, live, logView;
    private ProgressBar meter;
    private Button startBtn, recBtn, playBtn;
    private MediaRecorder recorder;
    private MediaPlayer testPlayer;
    private final StringBuilder logText = new StringBuilder();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("p", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);

        TextView title = text("AutoCaller Player", 22, true);
        root.addView(title);
        root.addView(text("Put this phone next to the calling phone (AutoCaller, method \"Second phone plays "
                + "the message\"). The calling phone keeps the call on its loudspeaker. This phone listens "
                + "for the ringing, then for the person who answers, and then plays your message from its "
                + "own loudspeaker, into the calling phone's microphone.", 14, false));

        root.addView(space());
        root.addView(text("1. Message", 16, true));
        messageInfo = text("", 14, false);
        root.addView(messageInfo);
        Button pick = button("Choose an audio file");
        pick.setOnClickListener(v -> pickFile());
        root.addView(pick);
        recBtn = button("Record a message");
        recBtn.setOnClickListener(v -> toggleRecord());
        root.addView(recBtn);
        playBtn = button("Play message (test)");
        playBtn.setOnClickListener(v -> toggleTestPlay());
        root.addView(playBtn);

        root.addView(space());
        root.addView(text("2. Settings", 16, true));
        addSeek(root, "Voice must be louder than the background by (dB). Lower = more sensitive",
                "margin", 12, 6, 30, "%d dB");
        addSeek(root, "Wait after the voice is heard before playing (ms)",
                "delay", 700, 0, 3000, "%d ms");
        addSeek(root, "Rest after each message before listening again (s)",
                "rest", 8, 3, 30, "%d s");
        CheckBox ring = new CheckBox(this);
        ring.setText("Play only after the call has rung (ignores recorded announcements)");
        ring.setChecked(prefs.getBoolean("needRing", true));
        ring.setOnCheckedChangeListener((v, c) -> { prefs.edit().putBoolean("needRing", c).apply(); apply(); });
        root.addView(ring);
        CheckBox vol = new CheckBox(this);
        vol.setText("Set media volume to maximum while the message plays");
        vol.setChecked(prefs.getBoolean("maxVol", true));
        vol.setOnCheckedChangeListener((v, c) -> prefs.edit().putBoolean("maxVol", c).apply());
        root.addView(vol);

        root.addView(space());
        root.addView(text("3. Listen", 16, true));
        startBtn = button("Start listening");
        startBtn.setOnClickListener(v -> toggleListen());
        root.addView(startBtn);
        status = text("Stopped", 18, true);
        root.addView(status);
        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(60);
        root.addView(meter);
        live = text("", 12, false);
        live.setTypeface(Typeface.MONOSPACE);
        root.addView(live);
        logView = text("", 12, false);
        root.addView(logView);

        setContentView(sv);
        refreshMessageInfo();
    }

    @Override protected void onResume() {
        super.onResume();
        ListenService.ui = this;
        startBtn.setText(ListenService.running ? "Stop listening" : "Start listening");
    }

    @Override protected void onPause() {
        super.onPause();
        if (ListenService.ui == this) ListenService.ui = null;
    }

    // ---------- message ----------

    private File messageFile() { return new File(getFilesDir(), ListenService.MESSAGE_FILE); }

    private void refreshMessageInfo() {
        File f = messageFile();
        if (!f.exists()) { messageInfo.setText("No message yet."); return; }
        String len = "";
        try {
            MediaMetadataRetriever m = new MediaMetadataRetriever();
            m.setDataSource(f.getAbsolutePath());
            long ms = Long.parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            len = String.format(Locale.US, ", %.1f seconds", ms / 1000.0);
            m.release();
        } catch (Exception ignored) { }
        messageInfo.setText("Message ready" + len + ". Enter this length on the calling phone:"
                + " AutoCaller > Settings > Hang-up timer.");
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try (InputStream in = getContentResolver().openInputStream(u);
             OutputStream out = new java.io.FileOutputStream(messageFile())) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            addLog("Message file saved.");
        } catch (Exception e) {
            addLog("Could not read that file: " + e);
        }
        refreshMessageInfo();
    }

    private void toggleRecord() {
        if (recorder != null) { stopRecord(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM);
            return;
        }
        if (ListenService.running) { addLog("Stop listening before recording."); return; }
        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(96000);
            recorder.setOutputFile(messageFile().getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recBtn.setText("Stop recording");
        } catch (Exception e) {
            addLog("Recording did not start: " + e);
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
    }

    private void stopRecord() {
        try { recorder.stop(); } catch (Exception ignored) { }
        try { recorder.release(); } catch (Exception ignored) { }
        recorder = null;
        recBtn.setText("Record a message");
        refreshMessageInfo();
    }

    private void toggleTestPlay() {
        if (testPlayer != null) { stopTestPlay(); return; }
        if (!messageFile().exists()) { addLog("No message yet."); return; }
        try {
            testPlayer = new MediaPlayer();
            testPlayer.setDataSource(messageFile().getAbsolutePath());
            testPlayer.setOnCompletionListener(m -> stopTestPlay());
            testPlayer.prepare();
            testPlayer.start();
            playBtn.setText("Stop playing");
        } catch (Exception e) {
            addLog("Could not play: " + e);
            stopTestPlay();
        }
    }

    private void stopTestPlay() {
        if (testPlayer != null) { try { testPlayer.release(); } catch (Exception ignored) { } }
        testPlayer = null;
        playBtn.setText("Play message (test)");
    }

    // ---------- listening ----------

    private void toggleListen() {
        if (ListenService.running) {
            startService(new Intent(this, ListenService.class).setAction(ListenService.ACTION_STOP));
            startBtn.setText("Start listening");
            return;
        }
        if (!messageFile().exists()) { addLog("Choose or record a message first."); return; }
        if (recorder != null) stopRecord();
        stopTestPlay();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM);
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM);
            }
            addLog("Allow the microphone, then press the button again.");
            return;
        }
        startForegroundService(new Intent(this, ListenService.class).setAction(ListenService.ACTION_START));
        startBtn.setText("Stop listening");
    }

    private void apply() {
        if (ListenService.running) {
            startService(new Intent(this, ListenService.class).setAction(ListenService.ACTION_APPLY));
        }
    }

    // ---------- Ui callbacks (may come from any thread) ----------

    @Override
    public void onStatus(String state, Detector d, int played) {
        final String line = String.format(Locale.US,
                "level %5.1f dB   floor %5.1f dB%ntone %.2f %s   voice %d ms %s%nmessages played: %d",
                d.levelDb, d.floorDb, d.tonality, d.tonal ? "[TONE]" : "      ",
                d.voiceMs, d.voiced ? "[VOICE]" : "       ", played);
        final int lvl = (int) Math.max(0, Math.min(60, d.levelDb + 70));
        runOnUiThread(() -> {
            status.setText(state);
            live.setText(line);
            meter.setProgress(lvl);
            if (!ListenService.running) startBtn.setText("Start listening");
        });
    }

    @Override
    public void onLog(String line) { runOnUiThread(() -> addLog(line)); }

    private void addLog(String s) {
        logText.insert(0, clock.format(new Date()) + "  " + s + "\n");
        if (logText.length() > 4000) logText.setLength(4000);
        logView.setText(logText.toString());
    }

    // ---------- small UI helpers ----------

    private void addSeek(LinearLayout root, String label, String key, int def, int min, int max, String fmt) {
        final TextView t = text("", 14, false);
        int val = prefs.getInt(key, def);
        t.setText(label + ": " + String.format(Locale.US, fmt, val));
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        s.setProgress(val - min);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                int v = p + min;
                t.setText(label + ": " + String.format(Locale.US, fmt, v));
                if (user) { prefs.edit().putInt(key, v).apply(); apply(); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        root.addView(t);
        root.addView(s);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private View space() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(12)));
        return v;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
