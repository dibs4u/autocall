package com.autocaller.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

import java.util.ArrayList;

/**
 * Android binds this service whenever the phone has a call, because AutoCaller is the default
 * phone app. Calls placed by a calling run are handed to CampaignService; every other call
 * (incoming, or dialled by hand) gets the normal call screen (InCallActivity).
 */
public class CallService extends InCallService {

    interface Listener {
        void onCallsChanged();
    }

    static final int NOTIF_CALL = 21;

    static CallService instance;
    static Listener listener;

    /** Calls shown on the call screen: everything except calls made by a calling run. */
    private final ArrayList<Call> uiCalls = new ArrayList<>();

    private final Call.Callback uiCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            changed();
        }

        @Override
        public void onDetailsChanged(Call call, Call.Details details) {
            changed();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        try {
            getSystemService(NotificationManager.class).cancel(NOTIF_CALL);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        int state = stateOf(call);
        Logx.log("Call added: state=" + state + " number=" + numberOf(call));

        if (CampaignService.claim(call)) return;

        if (CampaignService.hasAttempt() && state == Call.STATE_RINGING) {
            // A calling-run call is in progress: don't let an incoming call break into it.
            Logx.log("Incoming call rejected because a calling-run call is in progress");
            try {
                call.reject(false, null);
            } catch (Exception e) {
                Logx.err("reject", e);
            }
            return;
        }

        uiCalls.add(call);
        try {
            call.registerCallback(uiCallback);
        } catch (Exception e) {
            Logx.err("registerCallback", e);
        }
        showCallScreen();
        changed();
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        Logx.log("Call removed: number=" + numberOf(call));
        CampaignService.removed(call);
        if (uiCalls.remove(call)) {
            try {
                call.unregisterCallback(uiCallback);
            } catch (Exception ignored) {
            }
        }
        changed();
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState audioState) {
        super.onCallAudioStateChanged(audioState);
        // The message is carried to the other side by the loudspeaker, so keep it on.
        if (CampaignService.isAnswered()) forceSpeaker();
        Listener l = listener;
        if (l != null) l.onCallsChanged();
    }

    private void changed() {
        updateNotification();
        Listener l = listener;
        if (l != null) l.onCallsChanged();
    }

    private void showCallScreen() {
        try {
            Intent i = new Intent(this, InCallActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            startActivity(i);
        } catch (Exception e) {
            Logx.err("showCallScreen", e);
        }
    }

    private PendingIntent action(String action, int requestCode) {
        Intent i = new Intent(this, ActionReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            Call c = primary();
            if (c == null) {
                nm.cancel(NOTIF_CALL);
                return;
            }
            int state = stateOf(c);
            boolean ringing = state == Call.STATE_RINGING;
            PendingIntent open = PendingIntent.getActivity(this, 20,
                    new Intent(this, InCallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = new Notification.Builder(this, App.CH_CALLS)
                    .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                    .setContentTitle(ringing ? "Incoming call" : "Call in progress")
                    .setContentText(displayName(c))
                    .setCategory(Notification.CATEGORY_CALL)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(open);
            if (ringing) {
                b.setFullScreenIntent(open, true);
                b.addAction(0, "Decline", action(ActionReceiver.DECLINE, 21));
                b.addAction(0, "Answer", action(ActionReceiver.ANSWER, 22));
            } else {
                b.addAction(0, "Hang up", action(ActionReceiver.HANGUP, 23));
            }
            nm.notify(NOTIF_CALL, b.build());
        } catch (Exception e) {
            Logx.err("call notification", e);
        }
    }

    // ---- helpers used by the screens and by CampaignService ---------------------------------

    @SuppressWarnings("deprecation")
    static int stateOf(Call c) {
        try {
            return c.getState();
        } catch (Exception e) {
            return Call.STATE_NEW;
        }
    }

    static boolean isOutgoing(Call c) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int d = c.getDetails().getCallDirection();
                if (d == Call.Details.DIRECTION_OUTGOING) return true;
                if (d == Call.Details.DIRECTION_INCOMING) return false;
            }
        } catch (Exception ignored) {
        }
        int s = stateOf(c);
        return s == Call.STATE_CONNECTING || s == Call.STATE_DIALING
                || s == Call.STATE_SELECT_PHONE_ACCOUNT || s == Call.STATE_NEW;
    }

    static String numberOf(Call c) {
        try {
            Uri h = c.getDetails().getHandle();
            String n = h == null ? null : h.getSchemeSpecificPart();
            return n == null ? "" : n;
        } catch (Exception e) {
            return "";
        }
    }

    static String displayName(Call c) {
        String name = null;
        try {
            if (Build.VERSION.SDK_INT >= 30) name = c.getDetails().getContactDisplayName();
            if (name == null || name.length() == 0) name = c.getDetails().getCallerDisplayName();
        } catch (Exception ignored) {
        }
        if (name == null || name.length() == 0) name = numberOf(c);
        return name.length() == 0 ? "Unknown number" : name;
    }

    /** True while the phone has any call at all (ours or not). */
    static boolean anyCall() {
        CallService s = instance;
        if (s == null) return false;
        try {
            return !s.getCalls().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** The call the call screen should show: ringing first, then active, dialling, on hold. */
    static Call primary() {
        CallService s = instance;
        if (s == null) return null;
        Call best = null;
        int bestRank = -1;
        for (Call c : s.uiCalls) {
            int st = stateOf(c);
            int rank;
            if (st == Call.STATE_RINGING) rank = 5;
            else if (st == Call.STATE_ACTIVE) rank = 4;
            else if (st == Call.STATE_DIALING || st == Call.STATE_CONNECTING
                    || st == Call.STATE_SELECT_PHONE_ACCOUNT) rank = 3;
            else if (st == Call.STATE_HOLDING) rank = 2;
            else if (st == Call.STATE_DISCONNECTED) rank = 0;
            else rank = 1;
            if (rank > bestRank) {
                best = c;
                bestRank = rank;
            }
        }
        return best;
    }

    /** Switches the call to the loudspeaker and makes sure the microphone is not muted. */
    static void forceSpeaker() {
        CallService s = instance;
        if (s == null) return;
        try {
            CallAudioState st = s.getCallAudioState();
            if (st != null && st.isMuted()) s.setMuted(false);
            if (st == null || st.getRoute() != CallAudioState.ROUTE_SPEAKER) {
                s.setAudioRoute(CallAudioState.ROUTE_SPEAKER);
            }
        } catch (Exception e) {
            Logx.err("forceSpeaker", e);
        }
    }
}
