package com.autocaller.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telecom.Call;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import java.io.File;
import java.util.Calendar;
import java.util.List;

/**
 * The calling run: takes the next number from the list, places the call, waits for it to be
 * answered, plays the voice message on the loudspeaker, hangs up, writes the result to the
 * history, waits, and goes on to the next number.
 *
 * Runs as a foreground service so that it keeps going with the screen off.
 * Everything here happens on the main thread.
 */
public class CampaignService extends Service {

    static final String ACTION_START = "com.autocaller.app.action.START";

    static final int PH_IDLE = 0;
    static final int PH_PLACING = 1;
    static final int PH_RINGING = 2;
    static final int PH_ANSWERED = 3;
    static final int PH_PLAYING = 4;
    static final int PH_GAP = 5;
    static final int PH_WAIT = 6;

    private static final int NOTIF_RUN = 11;
    private static final int NOTIF_DONE = 12;
    private static final long PLACE_TIMEOUT_MS = 25000;
    private static final long FORCE_END_MS = 8000;
    private static final int MAX_FAILURES_IN_A_ROW = 5;

    interface Listener {
        void onCampaignChanged();
    }

    /** One call attempt that is in progress. */
    private static final class Attempt {
        long contactId;
        String name;
        String number;
        String list;
        int attemptNo;
        Call call;
        long dialTime;
        long answerTime;
        boolean ringTimerStarted;
        boolean timedOut;
        boolean skipped;
        boolean stopped;
        boolean finished;
        boolean audioDone;
        int loopsDone;
        int audioMs;
        int audioTotalMs;
        String note = "";
    }

    // ---- state the screens read (main thread only) ------------------------------------------
    static CampaignService instance;
    static Listener listener;
    static boolean running;
    static int phase = PH_IDLE;
    static long phaseSince;
    static long nextDialAt;
    static String curNumber = "";
    static String curName = "";
    static String waitText = "";
    static String lastMessage = "";
    static int sessionCalls;
    static int sessionAnswered;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Db db;
    private PowerManager.WakeLock wakeLock;
    private Attempt cur;
    private MediaPlayer player;
    private boolean stopRequested;
    private String stopReason = "";
    private int failuresInARow;
    private int savedMusicVolume = -1;
    private int savedCallVolume = -1;

    // settings, read once when a run starts
    private long gapMs;
    private long ringMs;
    private long answerDelayMs;
    private long retryWaitMs;
    private int repeat;
    private int retries;
    private int hourFrom;
    private int hourTo;
    private int playStream;
    private int audioMs;
    private String audioPath = "";
    private String simId = "";
    private String simLabel = "";

    // ---- service plumbing -------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        db = Db.get(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            startRun();
        } else if (!running) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (running) {
            running = false;
            lastMessage = "The run was stopped by the phone. Press Start to continue.";
            Logx.log("Service destroyed while a run was active");
        }
        h.removeCallbacksAndMessages(null);
        releasePlayer();
        restoreVolumes();
        releaseWakeLock();
        cur = null;
        phase = PH_IDLE;
        if (instance == this) instance = null;
        notifyUi();
        super.onDestroy();
    }

    // ---- questions other classes ask --------------------------------------------------------

    /** True while a calling-run call is being placed or is in progress. */
    static boolean hasAttempt() {
        CampaignService s = instance;
        return s != null && s.cur != null && !s.cur.finished;
    }

    /** True while a calling-run call has been answered and not yet ended. */
    static boolean isAnswered() {
        CampaignService s = instance;
        return s != null && s.cur != null && !s.cur.finished && s.cur.answerTime > 0;
    }

    /** One line saying what the run is doing right now. */
    static String describe() {
        long now = System.currentTimeMillis();
        switch (phase) {
            case PH_PLACING:
                return "Calling " + curNumber;
            case PH_RINGING:
                return "Ringing " + curNumber + " · " + Util.secs(now - phaseSince) + " s";
            case PH_ANSWERED:
                return "Answered – starting the message";
            case PH_PLAYING:
                return "Playing the message to " + curNumber;
            case PH_GAP:
                return "Next call in " + Util.secs(Math.max(0, nextDialAt - now)) + " s";
            case PH_WAIT:
                return waitText;
            default:
                return lastMessage.length() > 0 ? lastMessage : "Ready";
        }
    }

    // ---- starting and stopping --------------------------------------------------------------

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void startRun() {
        if (running) return;
        gapMs = clamp(Prefs.getInt(this, Prefs.GAP_SEC, Prefs.DEF_GAP_SEC), 2, 600) * 1000L;
        ringMs = clamp(Prefs.getInt(this, Prefs.RING_SEC, Prefs.DEF_RING_SEC), 10, 90) * 1000L;
        answerDelayMs = clamp(Prefs.getInt(this, Prefs.ANSWER_DELAY_MS, Prefs.DEF_ANSWER_DELAY_MS), 0, 10000);
        repeat = clamp(Prefs.getInt(this, Prefs.REPEAT, Prefs.DEF_REPEAT), 1, 5);
        retries = clamp(Prefs.getInt(this, Prefs.RETRIES, Prefs.DEF_RETRIES), 0, 5);
        retryWaitMs = clamp(Prefs.getInt(this, Prefs.RETRY_WAIT_MIN, Prefs.DEF_RETRY_WAIT_MIN), 0, 240) * 60000L;
        hourFrom = clamp(Prefs.getInt(this, Prefs.HOUR_FROM, Prefs.DEF_HOUR_FROM), 0, 24);
        hourTo = clamp(Prefs.getInt(this, Prefs.HOUR_TO, Prefs.DEF_HOUR_TO), 0, 24);
        playStream = Prefs.getInt(this, Prefs.PLAY_STREAM, 0);
        audioPath = Prefs.getString(this, Prefs.AUDIO_PATH, "");
        audioMs = Math.max(0, Prefs.getInt(this, Prefs.AUDIO_MS, 0));
        simId = Prefs.getString(this, Prefs.SIM_ID, "");
        simLabel = Util.simLabelFor(this, simId);

        String problem = null;
        if (!Util.isDefaultDialer(this)) {
            problem = "AutoCaller is not the default phone app.";
        } else if (!Util.has(this, Util.P_CALL)) {
            problem = "The Phone permission is missing.";
        } else if (audioPath.length() == 0 || !new File(audioPath).exists()) {
            problem = "There is no voice message yet.";
        }
        if (problem != null) {
            lastMessage = problem;
            notifyUi();
            stopSelf();
            return;
        }

        running = true;
        stopRequested = false;
        stopReason = "";
        failuresInARow = 0;
        sessionCalls = 0;
        sessionAnswered = 0;
        lastMessage = "";
        cur = null;
        goForeground();
        acquireWakeLock();
        Logx.log("Run started: gap=" + gapMs + "ms ringLimit=" + ringMs + "ms repeat=" + repeat
                + " retries=" + retries + " sim=" + simLabel + " stream=" + playStream
                + " phone=" + Build.MANUFACTURER + " " + Build.MODEL
                + " Android " + Build.VERSION.RELEASE);
        nextDialAt = System.currentTimeMillis();
        setPhase(PH_GAP);
        h.post(dialNext);
    }

    /** Ends the run. A call that is ringing is cancelled and stays in the queue. */
    void requestStop(String reason) {
        if (!running) {
            stopSelf();
            return;
        }
        Logx.log("Stop requested: " + reason);
        stopRequested = true;
        stopReason = reason;
        Attempt a = cur;
        if (a == null) {
            finish(reason);
            return;
        }
        if (a.call != null && !a.finished) {
            if (a.answerTime == 0) a.stopped = true;
            hangUp(a);
        }
        // Otherwise the call is still being set up; attach() or the watchdog will end it.
        notifyUi();
    }

    /** Hangs up the current call and carries on with the next number. */
    void skipCurrent() {
        Attempt a = cur;
        if (a != null && a.call != null && !a.finished) {
            if (a.answerTime == 0) a.skipped = true;
            a.note = "Ended by you";
            hangUp(a);
        }
    }

    private void finish(String message) {
        if (!running) return;
        running = false;
        h.removeCallbacksAndMessages(null);
        cur = null;
        releasePlayer();
        restoreVolumes();
        releaseWakeLock();
        lastMessage = message == null ? "" : message;
        curNumber = "";
        curName = "";
        phase = PH_IDLE;
        phaseSince = System.currentTimeMillis();
        Logx.log("Run ended: " + lastMessage + " (" + sessionCalls + " calls, "
                + sessionAnswered + " answered)");
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        try {
            PendingIntent open = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String counts = sessionCalls + " calls made, " + sessionAnswered + " answered";
            Notification n = new Notification.Builder(this, App.CH_DONE)
                    .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                    .setContentTitle("AutoCaller stopped")
                    .setContentText(counts)
                    .setStyle(new Notification.BigTextStyle().bigText(lastMessage + "\n" + counts))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build();
            getSystemService(NotificationManager.class).notify(NOTIF_DONE, n);
        } catch (Exception e) {
            Logx.err("done notification", e);
        }
        notifyUi();
        stopSelf();
    }

    // ---- dialling ---------------------------------------------------------------------------

    private final Runnable dialNext = new Runnable() {
        @Override
        public void run() {
            doDialNext();
        }
    };

    private boolean withinHours() {
        if (hourFrom == hourTo || (hourFrom <= 0 && hourTo >= 24)) return true;
        int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hourFrom < hourTo) return now >= hourFrom && now < hourTo;
        return now >= hourFrom || now < hourTo; // window that crosses midnight
    }

    private void doDialNext() {
        if (!running || cur != null) return;
        if (stopRequested) {
            finish(stopReason);
            return;
        }
        if (!withinHours()) {
            finish("Outside the calling hours (" + hourFrom + ":00 to " + hourTo
                    + ":00). Press Start again later.");
            return;
        }
        if (!Util.isDefaultDialer(this)) {
            finish("Stopped: AutoCaller is no longer the default phone app.");
            return;
        }
        long now = System.currentTimeMillis();
        if (CallService.anyCall()) {
            // e.g. an incoming call that the user answered: wait until the line is free
            waitText = "Waiting for the current call to end";
            if (phase != PH_WAIT) setPhase(PH_WAIT);
            else notifyUi();
            h.postDelayed(dialNext, 2000);
            return;
        }
        Db.Contact c = db.nextPending();
        if (c == null) {
            c = db.nextRetry();
            if (c == null) {
                finish("Finished: every number in the list has been called.");
                return;
            }
            long ready = c.lastTime + retryWaitMs;
            if (ready > now) {
                waitText = "Waiting until " + Util.clock(ready) + " to retry unanswered numbers";
                nextDialAt = ready;
                if (phase != PH_WAIT) setPhase(PH_WAIT);
                else notifyUi();
                h.postDelayed(dialNext, Math.min(ready - now, 15000L));
                return;
            }
        }
        place(c);
    }

    private void place(Db.Contact c) {
        Attempt a = new Attempt();
        a.contactId = c.id;
        a.name = c.name == null ? "" : c.name;
        a.number = c.number;
        a.list = c.list == null ? "" : c.list;
        a.attemptNo = c.attempts + 1;
        a.dialTime = System.currentTimeMillis();
        a.audioTotalMs = audioMs * repeat;
        cur = a;
        curNumber = a.number;
        curName = a.name;
        sessionCalls++;
        try {
            db.markDialing(c.id, a.dialTime);
        } catch (Exception e) {
            Logx.err("markDialing", e);
        }
        setPhase(PH_PLACING);
        try {
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            Bundle extras = new Bundle();
            extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true);
            PhoneAccountHandle sim = Util.findSim(this, simId);
            if (sim != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, sim);
            Logx.log("Dialling " + a.number + " (attempt " + a.attemptNo + ")");
            tm.placeCall(Uri.fromParts("tel", a.number, null), extras);
            h.postDelayed(placeWatchdog, PLACE_TIMEOUT_MS);
        } catch (Exception e) {
            Logx.err("placeCall", e);
            a.note = "The call could not be placed: " + e.getMessage();
            complete(a, Db.FAILED, null);
        }
    }

    /** Called by CallService for every new call. Returns true if it is the run's own call. */
    static boolean claim(Call call) {
        CampaignService s = instance;
        if (s == null || !running) return false;
        Attempt a = s.cur;
        if (a == null || a.call != null || a.finished) return false;
        if (!CallService.isOutgoing(call)) return false;
        String handle = CallService.numberOf(call);
        if (handle.length() > 0 && !sameNumber(handle, a.number)) return false;
        s.attach(a, call);
        return true;
    }

    /** Called by CallService when a call has gone for good. */
    static void removed(Call call) {
        CampaignService s = instance;
        if (s != null) s.ended(call);
    }

    private static String digits(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') b.append(ch);
        }
        return b.toString();
    }

    private static boolean sameNumber(String x, String y) {
        String dx = digits(x);
        String dy = digits(y);
        int n = Math.min(7, Math.min(dx.length(), dy.length()));
        if (n == 0) return true;
        return dx.substring(dx.length() - n).equals(dy.substring(dy.length() - n));
    }

    private void attach(Attempt a, Call call) {
        a.call = call;
        h.removeCallbacks(placeWatchdog);
        try {
            call.registerCallback(callCallback);
        } catch (Exception e) {
            Logx.err("registerCallback", e);
        }
        if (stopRequested) {
            a.stopped = true;
            hangUp(a);
        }
        onState(call, CallService.stateOf(call));
    }

    private final Call.Callback callCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            onState(call, state);
        }
    };

    private void onState(Call call, int state) {
        Attempt a = cur;
        if (a == null || a.call != call || a.finished) return;
        switch (state) {
            case Call.STATE_SELECT_PHONE_ACCOUNT: {
                // The phone is set to "ask every time": answer for it.
                PhoneAccountHandle sim = Util.findSim(this, simId);
                if (sim == null) {
                    List<PhoneAccountHandle> all = Util.sims(this);
                    if (!all.isEmpty()) sim = all.get(0);
                }
                if (sim != null) {
                    try {
                        call.phoneAccountSelected(sim, false);
                    } catch (Exception e) {
                        Logx.err("phoneAccountSelected", e);
                    }
                } else {
                    a.note = "No SIM could be chosen for the call";
                    hangUp(a);
                }
                break;
            }
            case Call.STATE_CONNECTING:
            case Call.STATE_DIALING:
                if (!a.ringTimerStarted) {
                    a.ringTimerStarted = true;
                    h.postDelayed(ringTimeout, ringMs);
                    setPhase(PH_RINGING);
                }
                break;
            case Call.STATE_ACTIVE:
                if (a.answerTime == 0) {
                    a.answerTime = System.currentTimeMillis();
                    h.removeCallbacks(ringTimeout);
                    sessionAnswered++;
                    Logx.log("Answered: " + a.number + " after "
                            + Util.secs(a.answerTime - a.dialTime) + " s");
                    setPhase(PH_ANSWERED);
                    CallService.forceSpeaker();
                    h.postDelayed(startPlayback, answerDelayMs);
                    h.postDelayed(maxCallWatchdog, answerDelayMs + a.audioTotalMs + 20000L);
                }
                break;
            case Call.STATE_DISCONNECTED:
                ended(call);
                break;
            default:
                break;
        }
    }

    private void hangUp(Attempt a) {
        try {
            if (a.call != null) a.call.disconnect();
        } catch (Exception e) {
            Logx.err("disconnect", e);
        }
        h.removeCallbacks(forceEnd);
        h.postDelayed(forceEnd, FORCE_END_MS);
    }

    private final Runnable placeWatchdog = new Runnable() {
        @Override
        public void run() {
            Attempt a = cur;
            if (a != null && a.call == null && !a.finished) {
                a.note = "The phone did not start the call";
                complete(a, stopRequested ? Db.CANCELLED : Db.FAILED, null);
            }
        }
    };

    private final Runnable ringTimeout = new Runnable() {
        @Override
        public void run() {
            Attempt a = cur;
            if (a != null && !a.finished && a.answerTime == 0 && a.call != null) {
                a.timedOut = true;
                hangUp(a);
            }
        }
    };

    private final Runnable hangUpNow = new Runnable() {
        @Override
        public void run() {
            Attempt a = cur;
            if (a != null && !a.finished && a.call != null) hangUp(a);
        }
    };

    private final Runnable maxCallWatchdog = new Runnable() {
        @Override
        public void run() {
            Attempt a = cur;
            if (a != null && !a.finished && a.call != null) {
                if (a.note.length() == 0) a.note = "Call ended at the time limit";
                hangUp(a);
            }
        }
    };

    private final Runnable forceEnd = new Runnable() {
        @Override
        public void run() {
            Attempt a = cur;
            if (a != null && !a.finished && a.call != null) {
                Logx.log("The call did not report its end; closing it out");
                ended(a.call);
            }
        }
    };

    // ---- playing the message ----------------------------------------------------------------

    private final Runnable startPlayback = new Runnable() {
        @Override
        public void run() {
            final Attempt a = cur;
            if (a == null || a.finished || a.answerTime == 0) return;
            try {
                CallService.forceSpeaker();
                raiseVolumes();
                MediaPlayer mp = new MediaPlayer();
                player = mp;
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(playStream == 1 ? AudioAttributes.USAGE_VOICE_COMMUNICATION
                                : AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                mp.setDataSource(audioPath);
                mp.prepare();
                final int length = Math.max(mp.getDuration(), 0);
                if (length > 0) a.audioTotalMs = length * repeat;
                mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer m) {
                        onMessagePlayedOnce(a, m);
                    }
                });
                mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer m, int what, int extra) {
                        Logx.log("Playback error " + what + "/" + extra);
                        if (a == cur && !a.finished) {
                            a.note = "The message could not be played (error " + what + ")";
                            h.postDelayed(hangUpNow, 300);
                        }
                        return true;
                    }
                });
                mp.start();
                setPhase(PH_PLAYING);
            } catch (Exception e) {
                Logx.err("playback", e);
                a.note = "The message could not be played: " + e.getMessage();
                h.postDelayed(hangUpNow, 500);
            }
        }
    };

    private void onMessagePlayedOnce(Attempt a, MediaPlayer m) {
        if (a != cur || a.finished || m != player) return;
        a.loopsDone++;
        if (a.loopsDone < repeat) {
            try {
                m.seekTo(0);
                m.start();
                return;
            } catch (Exception e) {
                Logx.err("replay", e);
            }
        }
        a.audioDone = true;
        h.postDelayed(hangUpNow, 700);
    }

    /** Stops the player and works out how much of the message the person heard. */
    private void stopPlayer(Attempt a) {
        MediaPlayer mp = player;
        player = null;
        if (mp == null) return;
        try {
            if (a.audioDone) {
                a.audioMs = a.audioTotalMs;
            } else {
                int once = repeat > 0 ? a.audioTotalMs / repeat : a.audioTotalMs;
                int pos = 0;
                try {
                    pos = Math.max(0, mp.getCurrentPosition());
                } catch (Exception ignored) {
                }
                a.audioMs = Math.min(a.loopsDone * once + Math.min(pos, once), a.audioTotalMs);
            }
        } catch (Exception ignored) {
        }
        try {
            mp.stop();
        } catch (Exception ignored) {
        }
        try {
            mp.release();
        } catch (Exception ignored) {
        }
    }

    private void releasePlayer() {
        MediaPlayer mp = player;
        player = null;
        if (mp == null) return;
        try {
            mp.release();
        } catch (Exception ignored) {
        }
    }

    /** The far end hears the message through the loudspeaker, so play it as loud as possible. */
    private void raiseVolumes() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (savedMusicVolume < 0) savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (savedCallVolume < 0) savedCallVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                    am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0);
        } catch (Exception e) {
            Logx.err("raiseVolumes", e);
        }
    }

    private void restoreVolumes() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (savedMusicVolume >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0);
            if (savedCallVolume >= 0) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedCallVolume, 0);
        } catch (Exception e) {
            Logx.err("restoreVolumes", e);
        }
        savedMusicVolume = -1;
        savedCallVolume = -1;
    }

    // ---- when a call ends -------------------------------------------------------------------

    private static String causeName(int code) {
        switch (code) {
            case DisconnectCause.BUSY:
                return "BUSY";
            case DisconnectCause.REJECTED:
                return "REJECTED";
            case DisconnectCause.REMOTE:
                return "ENDED_BY_OTHER_SIDE";
            case DisconnectCause.LOCAL:
                return "ENDED_BY_THIS_PHONE";
            case DisconnectCause.CANCELED:
                return "CANCELLED";
            case DisconnectCause.MISSED:
                return "MISSED";
            case DisconnectCause.ERROR:
                return "NETWORK_ERROR";
            case DisconnectCause.RESTRICTED:
                return "RESTRICTED";
            case DisconnectCause.OTHER:
                return "OTHER";
            default:
                return "UNKNOWN";
        }
    }

    private void ended(Call call) {
        Attempt a = cur;
        if (a == null || a.call != call || a.finished) return;
        int code = -1;
        String cause = "";
        try {
            DisconnectCause dc = call.getDetails().getDisconnectCause();
            if (dc != null) {
                code = dc.getCode();
                cause = causeName(code);
                CharSequence label = dc.getLabel();
                String reason = dc.getReason();
                if (label != null && label.length() > 0) cause = cause + " / " + label;
                if (reason != null && reason.length() > 0) cause = cause + " / " + reason;
            }
        } catch (Exception ignored) {
        }
        long rang = System.currentTimeMillis() - a.dialTime;
        int result;
        if (a.answerTime > 0) {
            result = Db.ANSWERED;
        } else if (a.stopped) {
            result = Db.CANCELLED;
        } else if (a.skipped) {
            result = Db.SKIPPED;
        } else if (a.timedOut) {
            result = Db.NO_ANSWER;
        } else if (code == DisconnectCause.BUSY) {
            result = Db.BUSY;
        } else if (code == DisconnectCause.REJECTED) {
            result = Db.REJECTED;
        } else if (code == DisconnectCause.REMOTE || code == DisconnectCause.MISSED) {
            result = Db.NO_ANSWER;
        } else if (code == DisconnectCause.LOCAL || code == DisconnectCause.CANCELED) {
            result = Db.SKIPPED;
        } else {
            // Network error. If it rang for a long time the network simply gave up waiting.
            result = a.ringTimerStarted && rang >= 25000 ? Db.NO_ANSWER : Db.FAILED;
        }
        complete(a, result, cause);
    }

    private void complete(Attempt a, int result, String cause) {
        if (a.finished) return;
        a.finished = true;
        h.removeCallbacks(placeWatchdog);
        h.removeCallbacks(ringTimeout);
        h.removeCallbacks(startPlayback);
        h.removeCallbacks(hangUpNow);
        h.removeCallbacks(maxCallWatchdog);
        h.removeCallbacks(forceEnd);
        stopPlayer(a);
        if (a.call != null) {
            try {
                a.call.unregisterCallback(callCallback);
            } catch (Exception ignored) {
            }
        }
        long end = System.currentTimeMillis();

        int contactStatus;
        boolean revertAttempt = false;
        if (result == Db.CANCELLED) {
            contactStatus = Db.PENDING; // the run was stopped mid-dial: keep the number queued
            revertAttempt = true;
        } else if ((result == Db.NO_ANSWER || result == Db.BUSY || result == Db.FAILED)
                && a.attemptNo <= retries) {
            contactStatus = Db.RETRY;
        } else {
            contactStatus = result;
        }

        String note = a.note;
        if (cause != null && cause.length() > 0) note = note.length() > 0 ? note + " | " + cause : cause;

        Db.CallRow r = new Db.CallRow();
        r.contactId = a.contactId;
        r.name = a.name;
        r.number = a.number;
        r.list = a.list;
        r.attempt = a.attemptNo;
        r.sim = simLabel;
        r.dialTime = a.dialTime;
        r.answerTime = a.answerTime;
        r.endTime = end;
        r.ringMs = (a.answerTime > 0 ? a.answerTime : end) - a.dialTime;
        r.talkMs = a.answerTime > 0 ? end - a.answerTime : 0;
        r.audioMs = a.audioMs;
        r.audioTotalMs = a.audioTotalMs;
        r.audioDone = a.audioDone;
        r.result = result;
        r.cause = note;
        try {
            db.saveAttempt(r, contactStatus, revertAttempt);
        } catch (Exception e) {
            Logx.err("saveAttempt", e);
        }

        if (result == Db.FAILED) failuresInARow++;
        else if (result != Db.CANCELLED && result != Db.SKIPPED) failuresInARow = 0;
        Logx.log("Result " + a.number + ": " + Util.statusLabel(result)
                + " ring=" + Util.secs(r.ringMs) + "s talk=" + Util.secs(r.talkMs)
                + "s message=" + r.audioMs + "/" + r.audioTotalMs + "ms " + note);

        cur = null;
        if (!running) return;
        if (stopRequested) {
            finish(stopReason);
            return;
        }
        if (failuresInARow >= MAX_FAILURES_IN_A_ROW) {
            finish("Stopped: " + MAX_FAILURES_IN_A_ROW + " calls in a row failed. Check the SIM,"
                    + " balance and network, then press Start.");
            return;
        }
        nextDialAt = end + gapMs;
        setPhase(PH_GAP);
        h.postDelayed(dialNext, gapMs);
    }

    // ---- notification, wake lock, UI updates ------------------------------------------------

    private void setPhase(int p) {
        phase = p;
        phaseSince = System.currentTimeMillis();
        if (running) updateNotification();
        notifyUi();
    }

    private static void notifyUi() {
        Listener l = listener;
        if (l != null) l.onCampaignChanged();
    }

    private Notification buildNotification() {
        int[] cnt;
        try {
            cnt = db.statusCounts();
        } catch (Exception e) {
            cnt = new int[Db.STATUS_COUNT];
        }
        int total = 0;
        for (int v : cnt) total += v;
        int left = cnt[Db.PENDING] + cnt[Db.RETRY];
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getBroadcast(this, 1,
                new Intent(this, ActionReceiver.class).setAction(ActionReceiver.STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, App.CH_RUN)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle("AutoCaller is calling")
                .setContentText(describe() + " · " + (total - left) + " of " + total + " done")
                .setProgress(Math.max(total, 1), total - left, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .build();
    }

    private void goForeground() {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            } else {
                startForeground(NOTIF_RUN, n);
            }
        } catch (Exception e) {
            Logx.err("startForeground", e);
        }
    }

    private void updateNotification() {
        try {
            getSystemService(NotificationManager.class).notify(NOTIF_RUN, buildNotification());
        } catch (Exception e) {
            Logx.err("notification", e);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoCaller:run");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(12L * 60 * 60 * 1000);
        } catch (Exception e) {
            Logx.err("wakeLock", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }
}
