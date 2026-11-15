package com.autocaller.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Home screen: setup checks, the message, and Start / Stop listening. Same look as AutoCaller. */
public class MainActivity extends Activity {
    static final String VERSION = "1.1";
    private static final int REQ_PICK = 10, REQ_PERM = 11;

    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout setupRows;
    private TextView audioInfo, audioHint, runStatus, runDetail, ringChip, voiceChip, levels;
    private ProgressBar meter;
    private Button chooseBtn, recordBtn, playBtn, startBtn, stopBtn;
    private MediaRecorder recorder;
    private long recordStart;
    private MediaPlayer preview;
    private boolean askedOnce;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshRun();
            if (recorder != null) {
                audioInfo.setText("Recording… " + clock(System.currentTimeMillis() - recordStart)
                        + "  Speak clearly, then press Stop.");
            }
            h.postDelayed(this, ListenService.running ? 150 : 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Plog.init(this);
        Ui.Screen s = Ui.screen(this, "AutoCaller Player", false, true);
        Ui.barAction(s, "Help", v -> showHelp());
        LinearLayout body = s.body;

        // ---- setup checks
        LinearLayout setup = Ui.card(this);
        setup.addView(Ui.text(this, "Setup", 16, Ui.INK, true));
        setupRows = Ui.col(this);
        setup.addView(setupRows);
        body.addView(setup);

        // ---- 1. message
        LinearLayout message = Ui.card(this);
        message.addView(Ui.text(this, "1 · Voice message", 16, Ui.INK, true));
        audioInfo = Ui.text(this, "", 14, Ui.MUTED, false);
        message.addView(audioInfo, Ui.below(this, 4));
        audioHint = Ui.text(this, "", 13, Ui.INK, false);
        audioHint.setBackground(Ui.shape(Ui.GREEN_BG, Ui.dp(this, 10), 0, 0));
        int hp = Ui.dp(this, 10);
        audioHint.setPadding(hp, hp, hp, hp);
        message.addView(audioHint, Ui.below(this, 10));
        LinearLayout mb = Ui.row(this);
        chooseBtn = Ui.primary(this, "Choose file");
        recordBtn = Ui.secondary(this, "Record");
        playBtn = Ui.secondary(this, "Play");
        mb.addView(chooseBtn, Ui.share(this, 0));
        mb.addView(recordBtn, Ui.share(this, 8));
        mb.addView(playBtn, Ui.share(this, 8));
        message.addView(mb, Ui.below(this, 12));
        body.addView(message);

        // ---- 2. listening
        LinearLayout run = Ui.card(this);
        run.addView(Ui.text(this, "2 · Listening", 16, Ui.INK, true));
        runStatus = Ui.text(this, "", 17, Ui.INK, true);
        run.addView(runStatus, Ui.below(this, 6));
        runDetail = Ui.text(this, "", 14, Ui.MUTED, false);
        run.addView(runDetail, Ui.below(this, 2));

        LinearLayout chips = Ui.row(this);
        ringChip = chip("Ringing");
        voiceChip = chip("Voice");
        chips.addView(ringChip, Ui.lp(Ui.WRAP, Ui.dp(this, 30)));
        LinearLayout.LayoutParams cp = Ui.lp(Ui.WRAP, Ui.dp(this, 30));
        cp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        chips.addView(voiceChip, cp);
        run.addView(chips, Ui.below(this, 12));

        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(70);
        meter.setProgressTintList(ColorStateList.valueOf(Ui.PRIMARY));
        run.addView(meter, Ui.below(this, 10));
        levels = Ui.text(this, "", 12, Ui.MUTED, false);
        run.addView(levels, Ui.below(this, 2));

        LinearLayout rb = Ui.row(this);
        startBtn = Ui.primary(this, "Start listening");
        stopBtn = Ui.danger(this, "Stop");
        rb.addView(startBtn, Ui.share(this, 0));
        rb.addView(stopBtn, Ui.share(this, 8));
        run.addView(rb, Ui.below(this, 12));
        body.addView(run);

        // ---- everything else
        LinearLayout more = Ui.row(this);
        Button settingsBtn = Ui.outline(this, "Settings");
        Button logBtn = Ui.outline(this, "Activity log");
        more.addView(settingsBtn, Ui.share(this, 0));
        more.addView(logBtn, Ui.share(this, 8));
        body.addView(more, Ui.lp(Ui.MATCH, Ui.WRAP));
        body.addView(Ui.text(this, "AutoCaller Player version " + VERSION, 12, Ui.MUTED, false),
                Ui.below(this, 14));

        chooseBtn.setOnClickListener(v -> {
            if (busy()) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            startActivityForResult(i, REQ_PICK);
        });
        recordBtn.setOnClickListener(v -> toggleRecord());
        playBtn.setOnClickListener(v -> togglePreview());
        startBtn.setOnClickListener(v -> startListening());
        stopBtn.setOnClickListener(v -> startService(new Intent(this, ListenService.class)
                .setAction(ListenService.ACTION_STOP)));
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        logBtn.setOnClickListener(v -> showLog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSetup();
        refreshMessage();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
        if (recorder != null) stopRecord();
        stopPreview();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        refreshSetup();
    }

    // ---- setup card ---------------------------------------------------------------------

    private void refreshSetup() {
        setupRows.removeAllViews();
        String[] missing = missingPermissions();
        if (missing.length == 0) {
            setupRow(true, "Permissions allowed", null, null);
        } else {
            setupRow(false, "Allow: " + describe(missing), "Allow", v -> askPermissions());
        }
        if (batteryUnrestricted()) {
            setupRow(true, "Allowed to keep running with the screen off", null, null);
        } else {
            setupRow(false, "Let AutoCaller Player run without battery limits (recommended for"
                    + " long lists)", "Allow", v -> askBattery());
        }
    }

    private void setupRow(boolean ok, String text, String action, View.OnClickListener onClick) {
        LinearLayout r = Ui.row(this);
        TextView mark = Ui.text(this, ok ? "✓" : "!", 14, ok ? Ui.PRIMARY : Ui.AMBER, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Ui.shape(ok ? Ui.GREEN_BG : Ui.AMBER_BG, Ui.dp(this, 12), 0, 0));
        r.addView(mark, Ui.lp(Ui.dp(this, 24), Ui.dp(this, 24)));
        TextView t = Ui.text(this, text, 14, Ui.INK, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        lp.setMargins(Ui.dp(this, 10), 0, Ui.dp(this, 8), 0);
        r.addView(t, lp);
        if (action != null) {
            Button b = Ui.secondary(this, action);
            b.setMinHeight(Ui.dp(this, 36));
            b.setMinimumHeight(Ui.dp(this, 36));
            b.setOnClickListener(onClick);
            r.addView(b, Ui.lp(Ui.WRAP, Ui.WRAP));
        }
        setupRows.addView(r, Ui.below(this, 10));
    }

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private String[] missingPermissions() {
        ArrayList<String> l = new ArrayList<>();
        if (!has(Manifest.permission.RECORD_AUDIO)) l.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            l.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return l.toArray(new String[0]);
    }

    private static String describe(String[] permissions) {
        StringBuilder sb = new StringBuilder();
        for (String p : permissions) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(Manifest.permission.RECORD_AUDIO.equals(p)
                    ? "Microphone (to hear the call and record)"
                    : "Notifications (to show that it is listening)");
        }
        return sb.toString();
    }

    private void askPermissions() {
        String[] missing = missingPermissions();
        if (missing.length == 0) return;
        boolean blocked = askedOnce;
        if (blocked) {
            for (String p : missing) {
                if (shouldShowRequestPermissionRationale(p)) {
                    blocked = false;
                    break;
                }
            }
        }
        if (blocked) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                Ui.toast(this, "Open Permissions and allow Microphone and Notifications.");
            } catch (Exception ignored) {
            }
        } else {
            askedOnce = true;
            requestPermissions(missing, REQ_PERM);
        }
    }

    private boolean batteryUnrestricted() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return true;
        }
    }

    private void askBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Ui.toast(this, "Open the phone's Settings > Apps > AutoCaller Player > Battery and"
                    + " choose Unrestricted.");
        }
    }

    // ---- message ------------------------------------------------------------------------

    private File messageFile() {
        return new File(getFilesDir(), Prefs.MESSAGE_FILE);
    }

    private boolean busy() {
        if (ListenService.running) {
            Ui.toast(this, "Stop listening first.");
            return true;
        }
        if (recorder != null) {
            Ui.toast(this, "Stop the recording first.");
            return true;
        }
        return false;
    }

    private void refreshMessage() {
        File f = messageFile();
        boolean has = f.exists() && f.length() > 0;
        if (has) {
            int ms = Prefs.getInt(this, Prefs.AUDIO_MS, 0);
            if (ms <= 0) {
                ms = durationOf(f);
                Prefs.sp(this).edit().putInt(Prefs.AUDIO_MS, ms).apply();
            }
            String label = Prefs.sp(this).getString(Prefs.AUDIO_LABEL, "message");
            long saved = Prefs.sp(this).getLong(Prefs.AUDIO_TIME, f.lastModified());
            audioInfo.setText("Ready · " + clock(ms) + " long · " + label + " · saved "
                    + new SimpleDateFormat("dd MMM, HH:mm", Locale.US).format(new Date(saved)));
            audioHint.setText("On the calling phone open AutoCaller > Settings > Hang-up timer"
                    + " and set “Length of the message on the second phone” to "
                    + ((ms + 999) / 1000) + " seconds.");
            audioHint.setVisibility(View.VISIBLE);
        } else {
            audioInfo.setText("No message yet. Choose an audio file or record what people should"
                    + " hear when they pick up.");
            audioHint.setVisibility(View.GONE);
        }
        Ui.enable(playBtn, has && recorder == null);
    }

    private static int durationOf(File f) {
        try {
            MediaMetadataRetriever m = new MediaMetadataRetriever();
            m.setDataSource(f.getAbsolutePath());
            String d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            m.release();
            return d == null ? 0 : Integer.parseInt(d);
        } catch (Exception e) {
            return 0;
        }
    }

    private void saved(String label) {
        File f = messageFile();
        Prefs.sp(this).edit()
                .putInt(Prefs.AUDIO_MS, durationOf(f))
                .putString(Prefs.AUDIO_LABEL, label)
                .putLong(Prefs.AUDIO_TIME, System.currentTimeMillis())
                .apply();
        Plog.add("New message saved: " + label + ", " + clock(durationOf(f)));
        refreshMessage();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        String name = "audio file";
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
        } catch (Exception ignored) {
        }
        try (InputStream in = getContentResolver().openInputStream(u);
             OutputStream out = new FileOutputStream(messageFile())) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            Ui.info(this, "File not used", "That file could not be read: " + e.getMessage());
            return;
        }
        if (durationOf(messageFile()) <= 0) {
            Ui.info(this, "Check the file", "The phone could not read the length of this file."
                    + " It may not play. Try an MP3, M4A or WAV file.");
        }
        saved(name);
        Ui.toast(this, "Saved. This file will be played on every answered call.");
    }

    private void toggleRecord() {
        if (recorder != null) {
            stopRecord();
            return;
        }
        if (ListenService.running) {
            Ui.toast(this, "Stop listening first.");
            return;
        }
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            Ui.info(this, "One more step", "Allow the Microphone first (Setup, at the top).");
            return;
        }
        stopPreview();
        File tmp = new File(getFilesDir(), "recording.tmp");
        try {
            MediaRecorder r = new MediaRecorder();
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioChannels(1);
            r.setAudioSamplingRate(44100);
            r.setAudioEncodingBitRate(96000);
            r.setOutputFile(tmp.getAbsolutePath());
            r.prepare();
            r.start();
            recorder = r;
            recordStart = System.currentTimeMillis();
            setButton(recordBtn, "Stop", Ui.RED, Ui.WHITE);
            Ui.enable(chooseBtn, false);
            Ui.enable(playBtn, false);
        } catch (Exception e) {
            Ui.toast(this, "Recording did not start: " + e.getMessage());
            recorder = null;
        }
    }

    private void stopRecord() {
        MediaRecorder r = recorder;
        recorder = null;
        boolean ok = true;
        try {
            r.stop();
        } catch (Exception e) {
            ok = false;
        }
        try {
            r.release();
        } catch (Exception ignored) {
        }
        setButton(recordBtn, "Record", Ui.SOFT, Ui.INK);
        Ui.enable(chooseBtn, true);
        File tmp = new File(getFilesDir(), "recording.tmp");
        if (ok && tmp.length() > 0 && tmp.renameTo(messageFile())) {
            saved("recorded in the app");
            Ui.toast(this, "Saved. This message will be played on every answered call.");
        } else {
            tmp.delete();
            Ui.toast(this, "The recording was too short. Try again.");
            refreshMessage();
        }
    }

    private void togglePreview() {
        if (preview != null) {
            stopPreview();
            return;
        }
        if (busy() || !messageFile().exists()) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            preview = mp;
            mp.setDataSource(messageFile().getAbsolutePath());
            mp.prepare();
            mp.setOnCompletionListener(m -> stopPreview());
            mp.start();
            playBtn.setText("Stop");
        } catch (Exception e) {
            stopPreview();
            Ui.toast(this, "The message could not be played.");
        }
    }

    private void stopPreview() {
        MediaPlayer mp = preview;
        preview = null;
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception ignored) {
            }
        }
        if (playBtn != null) playBtn.setText("Play");
    }

    // ---- listening ----------------------------------------------------------------------

    private void startListening() {
        if (ListenService.running) return;
        if (recorder != null) stopRecord();
        stopPreview();
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            Ui.info(this, "One more step", "Allow the Microphone first (Setup, at the top).");
            return;
        }
        if (!messageFile().exists()) {
            Ui.info(this, "No voice message", "Choose or record the message people should hear"
                    + " first.");
            return;
        }
        try {
            startForegroundService(new Intent(this, ListenService.class)
                    .setAction(ListenService.ACTION_START));
        } catch (Exception e) {
            Ui.toast(this, "Could not start: " + e.getMessage());
        }
    }

    private void refreshRun() {
        boolean running = ListenService.running;
        Detector d = ListenService.det;
        long now = System.currentTimeMillis();
        if (running) {
            switch (ListenService.state) {
                case ListenService.ST_WAIT_RING:
                    runStatus.setText("Waiting for the call to ring");
                    break;
                case ListenService.ST_WAIT_VOICE:
                    runStatus.setText("Ringing heard · waiting for “hello”");
                    break;
                case ListenService.ST_PLAYING:
                    runStatus.setText("Answered · playing the message");
                    break;
                case ListenService.ST_REST:
                    runStatus.setText("Next call in "
                            + Math.max(0, (ListenService.restUntil - now + 999) / 1000) + " s");
                    break;
                default:
                    runStatus.setText("Starting…");
            }
            runDetail.setText("This session: " + ListenService.played + " messages played");
            boolean live = ListenService.state == ListenService.ST_WAIT_RING
                    || ListenService.state == ListenService.ST_WAIT_VOICE;
            chipOn(ringChip, d.ringbackSeen() || (live && d.tonal), live && d.tonal);
            chipOn(voiceChip, live && d.voiced, false);
            meter.setProgress(live ? (int) Math.max(0, Math.min(70, d.levelDb + 75)) : 0);
            levels.setText(live ? String.format(Locale.US, "Sound %.0f dB · background %.0f dB ·"
                    + " needs %.0f dB", d.levelDb, d.floorDb, d.floorDb + d.marginDb) : "");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            runStatus.setText(ListenService.lastMessage.length() > 0
                    ? ListenService.lastMessage : "Ready to start");
            runDetail.setText("Press Start listening here first, then Start on the calling"
                    + " phone.");
            chipOn(ringChip, false, false);
            chipOn(voiceChip, false, false);
            meter.setProgress(0);
            levels.setText("");
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        Ui.enable(startBtn, !running);
        Ui.enable(stopBtn, running);
        Ui.enable(recordBtn, !running);
        if (recorder == null) Ui.enable(chooseBtn, !running);
    }

    // ---- small helpers ------------------------------------------------------------------

    private TextView chip(String label) {
        TextView t = Ui.text(this, label, 13, Ui.MUTED, true);
        t.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 12);
        t.setPadding(p, 0, p, 0);
        t.setBackground(Ui.shape(Ui.SOFT, Ui.dp(this, 15), 0, 0));
        return t;
    }

    /** on = green; now = also bold border, meaning "being heard right now". */
    private void chipOn(TextView t, boolean on, boolean now) {
        t.setTextColor(on ? Ui.PRIMARY : Ui.MUTED);
        t.setBackground(Ui.shape(on ? Ui.GREEN_BG : Ui.SOFT, Ui.dp(this, 15),
                Ui.PRIMARY, now ? Ui.dp(this, 2) : 0));
    }

    private void setButton(Button b, String label, int bg, int fg) {
        b.setText(label);
        b.setTextColor(fg);
        b.setBackground(Ui.pressable(this, bg, 10));
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s / 60 + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    private void showHelp() {
        Ui.info(this, "How AutoCaller Player works",
                "This app runs on a second phone. It plays the message for AutoCaller, which"
                        + " runs on the phone that makes the calls.\n\n"
                        + "1. On the calling phone choose Settings > How the message reaches the"
                        + " other person > Second phone plays the message.\n"
                        + "2. Here, choose or record the message. Copy its length into the"
                        + " calling phone: Settings > Hang-up timer.\n"
                        + "3. Lay the two phones side by side: this phone's loudspeaker next to"
                        + " the calling phone's microphone. Use a quiet room.\n"
                        + "4. Press Start listening here, then Start on the calling phone.\n\n"
                        + "For each call this phone waits for the ringing, then for the person's"
                        + " “hello”, and then plays the message from its loudspeaker. The"
                        + " calling phone's microphone carries it into the call.\n\n"
                        + "If it does not start\n"
                        + "• “Ringing” never lights up: the calling phone is not on the"
                        + " loudspeaker, or it is too quiet. Raise its call volume.\n"
                        + "• “Voice” lights up but nothing plays: the voice is too short. Lower"
                        + " “Voice must last at least” in Settings.\n"
                        + "• “Voice” does not light up: lower the sensitivity value in"
                        + " Settings.\n"
                        + "• Numbers with a caller tune instead of ringing: turn off “Play"
                        + " only after the call has rung” in Settings.\n\n"
                        + "Voicemail greetings count as a person. If nobody speaks after"
                        + " answering, no message is played.");
    }

    private void showLog() {
        final String log = Plog.tail(30000);
        TextView t = Ui.text(this, log.length() == 0 ? "Nothing logged yet." : log, 11, Ui.INK, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        new AlertDialog.Builder(this)
                .setTitle("Activity log")
                .setView(sv)
                .setPositiveButton("Share", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_SUBJECT, "AutoCaller Player activity log");
                    i.putExtra(Intent.EXTRA_TEXT, "AutoCaller Player " + VERSION + " on "
                            + Build.MANUFACTURER + " " + Build.MODEL + "\n\n" + log);
                    startActivity(Intent.createChooser(i, "Share the log"));
                })
                .setNeutralButton("Clear", (d, w) -> Plog.clear())
                .setNegativeButton("Close", null)
                .show();
    }
}
