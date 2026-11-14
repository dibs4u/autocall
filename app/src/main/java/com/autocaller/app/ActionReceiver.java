package com.autocaller.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telecom.Call;
import android.telecom.VideoProfile;

/** Handles the buttons on AutoCaller's notifications. */
public class ActionReceiver extends BroadcastReceiver {

    static final String STOP = "com.autocaller.app.action.STOP_RUN";
    static final String ANSWER = "com.autocaller.app.action.ANSWER";
    static final String DECLINE = "com.autocaller.app.action.DECLINE";
    static final String HANGUP = "com.autocaller.app.action.HANGUP";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a == null) return;
        try {
            if (STOP.equals(a)) {
                CampaignService s = CampaignService.instance;
                if (s != null) s.requestStop("Stopped from the notification.");
                return;
            }
            Call c = CallService.primary();
            if (c == null) return;
            if (ANSWER.equals(a)) c.answer(VideoProfile.STATE_AUDIO_ONLY);
            else if (DECLINE.equals(a)) c.reject(false, null);
            else if (HANGUP.equals(a)) c.disconnect();
        } catch (Exception e) {
            Logx.err("notification action " + a, e);
        }
    }
}
