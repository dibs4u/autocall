package com.autocaller.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;

/** Home screen: setup checks, the number list, the voice message, and Start / Stop. */
public class MainActivity extends Activity implements CampaignService.Listener {

    private static final int REQ_ROLE = 1;
    private static final int REQ_PERMISSIONS = 2;

    private LinearLayout setupRows;
    private TextView numbersInfo;
    private TextView audioInfo;
    private TextView runStatus;
    private TextView runDetail;
    private ProgressBar progress;
    private Button startBtn;
    private Button stopBtn;
    private Button skipBtn;
    private Button playBtn;
    private MediaPlayer preview;
    private boolean askedPermissionsOnce;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshRun();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.Screen s = Ui.screen(this, "AutoCaller", false, true);
        Ui.barAction(s, "Help", v -> showHelp());
        LinearLayout body = s.body;

        // ---- setup checks
        LinearLayout setup = Ui.card(this);
        setup.addView(Ui.text(this, "Setup", 16, Ui.INK, true));
        setupRows = Ui.col(this);
        setup.addView(setupRows);
        body.addView(setup);

        // ---- 1. numbers
        LinearLayout numbers = Ui.card(this);
        numbers.addView(Ui.text(this, "1 · Numbers", 16, Ui.INK, true));
        numbersInfo = Ui.text(this, "", 14, Ui.MUTED, false);
        numbers.addView(numbersInfo, Ui.below(this, 4));
        LinearLayout numberButtons = Ui.row(this);
        Button importBtn = Ui.primary(this, "Import CSV");
        Button listBtn = Ui.secondary(this, "View / edit list");
        numberButtons.addView(importBtn, Ui.share(this, 0));
        numberButtons.addView(listBtn, Ui.share(this, 8));
        numbers.addView(numberButtons, Ui.below(this, 12));
        body.addView(numbers);

        // ---- 2. voice message
        LinearLayout message = Ui.card(this);
        message.addView(Ui.text(this, "2 · Voice message", 16, Ui.INK, true));
        audioInfo = Ui.text(this, "", 14, Ui.MUTED, false);
        message.addView(audioInfo, Ui.below(this, 4));
        LinearLayout messageButtons = Ui.row(this);
        Button recordBtn = Ui.primary(this, "Record / change");
        playBtn = Ui.secondary(this, "Play");
        messageButtons.addView(recordBtn, Ui.share(this, 0));
        messageButtons.addView(playBtn, Ui.share(this, 8));
        message.addView(messageButtons, Ui.below(this, 12));
        body.addView(message);

        // ---- 3. calling
        LinearLayout run = Ui.card(this);
        run.addView(Ui.text(this, "3 · Calling", 16, Ui.INK, true));
        runStatus = Ui.text(this, "", 17, Ui.INK, true);
        run.addView(runStatus, Ui.below(this, 6));
        runDetail = Ui.text(this, "", 14, Ui.MUTED, false);
        run.addView(runDetail, Ui.below(this, 2));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(Ui.PRIMARY));
        run.addView(progress, Ui.below(this, 10));
        LinearLayout runButtons = Ui.row(this);
        startBtn = Ui.primary(this, "Start");
        skipBtn = Ui.secondary(this, "Skip call");
        stopBtn = Ui.danger(this, "Stop");
        runButtons.addView(startBtn, Ui.share(this, 0));
        runButtons.addView(skipBtn, Ui.share(this, 8));
        runButtons.addView(stopBtn, Ui.share(this, 8));
        run.addView(runButtons, Ui.below(this, 12));
        body.addView(run);

        // ---- everything else
        LinearLayout more = Ui.row(this);
        Button reportsBtn = Ui.outline(this, "Reports");
        Button settingsBtn = Ui.outline(this, "Settings");
        more.addView(reportsBtn, Ui.share(this, 0));
        more.addView(settingsBtn, Ui.share(this, 8));
        body.addView(more, Ui.lp(Ui.MATCH, Ui.WRAP));
        LinearLayout more2 = Ui.row(this);
        Button logBtn = Ui.outline(this, "Activity log");
        Button dialBtn = Ui.outline(this, "Dial pad");
        more2.addView(logBtn, Ui.share(this, 0));
        more2.addView(dialBtn, Ui.share(this, 8));
        body.addView(more2, Ui.below(this, 8));

        importBtn.setOnClickListener(v -> {
            if (CampaignService.running) {
                Ui.toast(this, "Stop the calling first.");
                return;
            }
            startActivity(new Intent(this, ContactsActivity.class)
                    .putExtra(ContactsActivity.EXTRA_IMPORT, true));
        });
        listBtn.setOnClickListener(v -> startActivity(new Intent(this, ContactsActivity.class)));
        recordBtn.setOnClickListener(v -> {
            if (CampaignService.running) {
                Ui.toast(this, "Stop the calling first.");
                return;
            }
            stopPreview();
            startActivity(new Intent(this, RecordActivity.class));
        });
        playBtn.setOnClickListener(v -> togglePreview());
        startBtn.setOnClickListener(v -> startRun());
        stopBtn.setOnClickListener(v -> {
            CampaignService svc = CampaignService.instance;
            if (svc != null) svc.requestStop("Stopped by you.");
        });
        skipBtn.setOnClickListener(v -> {
            CampaignService svc = CampaignService.instance;
            if (svc != null) svc.skipCurrent();
        });
        reportsBtn.setOnClickListener(v -> startActivity(new Intent(this, ReportsActivity.class)));
        settingsBtn.setOnClickListener(v -> {
            if (CampaignService.running) {
                Ui.toast(this, "Settings can be changed when calling is stopped.");
                return;
            }
            startActivity(new Intent(this, SettingsActivity.class));
        });
        logBtn.setOnClickListener(v -> showLog());
        dialBtn.setOnClickListener(v -> startActivity(new Intent(this, DialerActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        CampaignService.listener = this;
        refreshSetup();
        refreshData();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (CampaignService.listener == this) CampaignService.listener = null;
        h.removeCallbacks(tick);
        stopPreview();
    }

    @Override
    public void onCampaignChanged() {
        refreshRun();
        refreshData();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        refreshSetup();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshSetup();
    }

    // ---- setup card ---------------------------------------------------------------------

    private void refreshSetup() {
        setupRows.removeAllViews();
        if (Util.isDefaultDialer(this)) {
            setupRow(true, "AutoCaller is the default phone app", "Change", v -> openDefaultApps());
        } else {
            setupRow(false, "Make AutoCaller the default phone app. Android only tells the"
                    + " default phone app when a call is answered.", "Set", v -> requestDialerRole());
        }
        String[] missing = missingPermissions();
        if (missing.length == 0) {
            setupRow(true, "Permissions allowed", null, null);
        } else {
            setupRow(false, "Allow: " + describe(missing), "Allow", v -> askPermissions());
        }
        if (batteryUnrestricted()) {
            setupRow(true, "Allowed to keep running with the screen off", null, null);
        } else {
            setupRow(false, "Let AutoCaller run without battery limits (recommended for long"
                    + " lists)", "Allow", v -> askBattery());
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

    private String[] missingPermissions() {
        ArrayList<String> l = new ArrayList<>();
        for (String p : new String[]{Util.P_CALL, Util.P_STATE, Util.P_MIC}) {
            if (!Util.has(this, p)) l.add(p);
        }
        if (Build.VERSION.SDK_INT >= 33 && !Util.has(this, Util.P_NOTIF)) l.add(Util.P_NOTIF);
        return l.toArray(new String[0]);
    }

    private static String describe(String[] permissions) {
        boolean phone = false, mic = false, notif = false;
        for (String p : permissions) {
            if (Util.P_CALL.equals(p) || Util.P_STATE.equals(p)) phone = true;
            else if (Util.P_MIC.equals(p)) mic = true;
            else if (Util.P_NOTIF.equals(p)) notif = true;
        }
        StringBuilder sb = new StringBuilder();
        if (phone) sb.append("Phone (to place the calls)");
        if (mic) sb.append(sb.length() > 0 ? ", " : "").append("Microphone (to record the message)");
        if (notif) sb.append(sb.length() > 0 ? ", " : "").append("Notifications (to show progress)");
        return sb.toString();
    }

    private void askPermissions() {
        String[] missing = missingPermissions();
        if (missing.length == 0) return;
        // After one refusal Android may stop showing the question; then open the app's settings.
        boolean blocked = askedPermissionsOnce;
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
                Ui.toast(this, "Open Permissions and allow Phone and Microphone.");
            } catch (Exception e) {
                Logx.err("app settings", e);
            }
        } else {
            askedPermissionsOnce = true;
            requestPermissions(missing, REQ_PERMISSIONS);
        }
    }

    private void requestDialerRole() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_ROLE);
                    return;
                }
            }
            Intent i = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
            i.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
            startActivityForResult(i, REQ_ROLE);
        } catch (Exception e) {
            Logx.err("dialer role", e);
            openDefaultApps();
        }
    }

    private void openDefaultApps() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
            Ui.toast(this, "Choose “Phone app” to change the default phone app.");
        } catch (Exception e) {
            Logx.err("default apps", e);
            Ui.toast(this, "Open the phone's Settings > Apps > Default apps > Phone app.");
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
            Logx.err("battery", e);
            Ui.toast(this, "Open the phone's Settings > Apps > AutoCaller > Battery and choose"
                    + " Unrestricted.");
        }
    }

    // ---- numbers and message ------------------------------------------------------------

    private void refreshData() {
        int[] cnt = Db.get(this).statusCounts();
        int total = 0;
        for (int v : cnt) total += v;
        int toCall = cnt[Db.PENDING] + cnt[Db.RETRY];
        int answered = cnt[Db.ANSWERED];
        int notReached = total - toCall - answered;
        if (total == 0) {
            numbersInfo.setText("No numbers yet. Import a CSV file with one phone number per row"
                    + " (a name column is optional).");
        } else {
            numbersInfo.setText(total + " numbers · " + toCall + " to call · " + answered
                    + " answered · " + notReached + " not reached");
        }
        progress.setProgress(total == 0 ? 0 : (int) ((total - toCall) * 1000L / total));

        String path = Prefs.getString(this, Prefs.AUDIO_PATH, "");
        boolean has = path.length() > 0 && new File(path).exists();
        if (has) {
            audioInfo.setText("Ready · " + Util.dur(Prefs.getInt(this, Prefs.AUDIO_MS, 0))
                    + " long · " + Prefs.getString(this, Prefs.AUDIO_LABEL, "message")
                    + " · saved " + Util.dateTime(Prefs.getLong(this, Prefs.AUDIO_TIME, 0)));
        } else {
            audioInfo.setText("No message yet. Record what people should hear when they pick up.");
        }
        Ui.enable(playBtn, has);
    }

    private void togglePreview() {
        if (preview != null) {
            stopPreview();
            return;
        }
        if (CampaignService.running) {
            Ui.toast(this, "Not while calling is in progress.");
            return;
        }
        String path = Prefs.getString(this, Prefs.AUDIO_PATH, "");
        if (path.length() == 0) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            preview = mp;
            mp.setDataSource(path);
            mp.prepare();
            mp.setOnCompletionListener(m -> stopPreview());
            mp.start();
            playBtn.setText("Stop");
        } catch (Exception e) {
            Logx.err("preview", e);
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

    // ---- calling ------------------------------------------------------------------------

    private void refreshRun() {
        boolean running = CampaignService.running;
        if (running) {
            runStatus.setText(CampaignService.describe());
            String who = "";
            if (CampaignService.hasAttempt() && CampaignService.curNumber.length() > 0) {
                who = (CampaignService.curName.length() > 0 ? CampaignService.curName + " · " : "")
                        + CampaignService.curNumber + "\n";
            }
            runDetail.setText(who + "This run: " + CampaignService.sessionCalls + " calls, "
                    + CampaignService.sessionAnswered + " answered");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            runStatus.setText(CampaignService.lastMessage.length() > 0
                    ? CampaignService.lastMessage : "Ready to start");
            runDetail.setText("Keep the phone in a quiet place with its speaker and microphone"
                    + " uncovered while it is calling.");
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        Ui.enable(startBtn, !running);
        Ui.enable(stopBtn, running);
        Ui.enable(skipBtn, running && CampaignService.hasAttempt());
    }

    private void startRun() {
        if (CampaignService.running) return;
        if (!Util.isDefaultDialer(this)) {
            Ui.info(this, "One more step",
                    "First make AutoCaller the default phone app (Setup, at the top).");
            return;
        }
        if (!Util.has(this, Util.P_CALL)) {
            Ui.info(this, "One more step", "First allow the Phone permission (Setup, at the top).");
            return;
        }
        String path = Prefs.getString(this, Prefs.AUDIO_PATH, "");
        if (path.length() == 0 || !new File(path).exists()) {
            Ui.info(this, "No voice message", "Record the message people should hear first.");
            return;
        }
        int[] cnt = Db.get(this).statusCounts();
        int toCall = cnt[Db.PENDING] + cnt[Db.RETRY];
        if (toCall == 0) {
            Ui.info(this, "Nothing to call", "No numbers are waiting. Import a list, or open the"
                    + " list and choose “Call unanswered numbers again”.");
            return;
        }
        Ui.confirm(this, "Start calling?", toCall + " numbers will be called one after another"
                        + " from this phone's SIM. When someone answers, your message plays on"
                        + " the loudspeaker and the call then ends.\n\nPut the phone down in a"
                        + " quiet place and do not cover it.",
                "Start", () -> {
                    stopPreview();
                    try {
                        startService(new Intent(this, CampaignService.class)
                                .setAction(CampaignService.ACTION_START));
                    } catch (Exception e) {
                        Logx.err("start service", e);
                        Ui.toast(this, "Could not start: " + e.getMessage());
                    }
                });
    }

    // ---- help and log -------------------------------------------------------------------

    private void showHelp() {
        Ui.info(this, "How AutoCaller works",
                "1. Make AutoCaller the default phone app (Setup). Android only tells the default"
                        + " phone app the moment a call is answered.\n"
                        + "2. Import your numbers from a CSV file: one phone number per row; a"
                        + " name column is optional.\n"
                        + "3. Record the message people should hear.\n"
                        + "4. Press Start. Each number is called in turn. When the person"
                        + " answers, the message is played and the call ends. Every call is"
                        + " written to Reports.\n\n"
                        + "Good to know\n"
                        + "• Android does not let any app put sound straight into a phone"
                        + " call. The message is played from the loudspeaker and travels through"
                        + " the phone's own microphone, so keep the phone in a quiet room, lying"
                        + " face up, with nothing covering it.\n"
                        + "• Try it on two or three of your own numbers first to hear how it"
                        + " sounds from your phone.\n"
                        + "• While AutoCaller is the default phone app, ordinary calls open"
                        + " in AutoCaller's simple call screen. Switch back whenever you like:"
                        + " Setup > Change.\n"
                        + "• Call only people who expect to hear from you. In India, TRAI"
                        + " rules do not allow promotional calls from an ordinary SIM, and"
                        + " operators disconnect numbers that get reported.");
    }

    private void showLog() {
        final String log = Logx.tail(30000);
        TextView t = Ui.text(this, log.length() == 0 ? "Nothing logged yet." : log, 11, Ui.INK, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this)
                .setTitle("Activity log")
                .setView(sv)
                .setPositiveButton("Share", (d, w) -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_SUBJECT, "AutoCaller activity log");
                        i.putExtra(Intent.EXTRA_TEXT, Build.MANUFACTURER + " " + Build.MODEL
                                + ", Android " + Build.VERSION.RELEASE + "\n\n" + log);
                        startActivity(Intent.createChooser(i, "Share log"));
                    } catch (Exception e) {
                        Logx.err("share log", e);
                    }
                })
                .setNeutralButton("Clear", (d, w) -> Logx.clear())
                .setNegativeButton("Close", null)
                .show();
    }
}
