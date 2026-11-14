package com.autocaller.app;

import android.app.Activity;
import android.os.Bundle;
import android.telecom.PhoneAccountHandle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.List;

/** Timing, retries, calling hours, SIM and playback options. */
public class SettingsActivity extends Activity {

    private static final String[] STREAMS = {
            "Loudspeaker, media volume (recommended)",
            "Loudspeaker, call volume"};

    private EditText gap;
    private EditText ring;
    private EditText delay;
    private EditText repeat;
    private EditText retries;
    private EditText retryWait;
    private EditText hourFrom;
    private EditText hourTo;
    private Button simBtn;
    private Button streamBtn;
    private String simId = "";
    private int stream;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.Screen s = Ui.screen(this, "Settings", true, true);

        LinearLayout timing = Ui.card(this);
        timing.addView(Ui.text(this, "Timing", 16, Ui.INK, true));
        gap = field(timing, "Pause between two calls (seconds)",
                Prefs.getInt(this, Prefs.GAP_SEC, Prefs.DEF_GAP_SEC));
        ring = field(timing, "Give up if not answered after (seconds)",
                Prefs.getInt(this, Prefs.RING_SEC, Prefs.DEF_RING_SEC));
        delay = field(timing, "Wait after the answer before the message starts (milliseconds)",
                Prefs.getInt(this, Prefs.ANSWER_DELAY_MS, Prefs.DEF_ANSWER_DELAY_MS));
        repeat = field(timing, "Play the message this many times",
                Prefs.getInt(this, Prefs.REPEAT, Prefs.DEF_REPEAT));
        s.body.addView(timing);

        LinearLayout retry = Ui.card(this);
        retry.addView(Ui.text(this, "Unanswered numbers", 16, Ui.INK, true));
        retries = field(retry, "Try again this many times (0 = do not retry)",
                Prefs.getInt(this, Prefs.RETRIES, Prefs.DEF_RETRIES));
        retryWait = field(retry, "Wait before trying a number again (minutes)",
                Prefs.getInt(this, Prefs.RETRY_WAIT_MIN, Prefs.DEF_RETRY_WAIT_MIN));
        s.body.addView(retry);

        LinearLayout hours = Ui.card(this);
        hours.addView(Ui.text(this, "Calling hours", 16, Ui.INK, true));
        hours.addView(Ui.text(this, "Calls are only started between these hours (24-hour clock)."
                + " Use 0 and 24 for no limit.", 13, Ui.MUTED, false), Ui.below(this, 4));
        hourFrom = field(hours, "From hour (0 to 24)",
                Prefs.getInt(this, Prefs.HOUR_FROM, Prefs.DEF_HOUR_FROM));
        hourTo = field(hours, "Until hour (0 to 24)",
                Prefs.getInt(this, Prefs.HOUR_TO, Prefs.DEF_HOUR_TO));
        s.body.addView(hours);

        LinearLayout phone = Ui.card(this);
        phone.addView(Ui.text(this, "Phone", 16, Ui.INK, true));
        phone.addView(Ui.text(this, "SIM used for the calls", 13, Ui.MUTED, false),
                Ui.below(this, 10));
        simId = Prefs.getString(this, Prefs.SIM_ID, "");
        simBtn = Ui.secondary(this, Util.simLabelFor(this, simId));
        phone.addView(simBtn, Ui.below(this, 4));
        phone.addView(Ui.text(this, "How the message is played", 13, Ui.MUTED, false),
                Ui.below(this, 12));
        stream = Prefs.getInt(this, Prefs.PLAY_STREAM, 0) == 1 ? 1 : 0;
        streamBtn = Ui.secondary(this, STREAMS[stream]);
        phone.addView(streamBtn, Ui.below(this, 4));
        phone.addView(Ui.text(this, "If people cannot hear the message clearly on your phone,"
                + " try the other option.", 13, Ui.MUTED, false), Ui.below(this, 6));
        s.body.addView(phone);

        Button save = Ui.primary(this, "Save");
        s.body.addView(save, Ui.lp(Ui.MATCH, Ui.WRAP));

        simBtn.setOnClickListener(v -> chooseSim());
        streamBtn.setOnClickListener(v -> Ui.choose(this, "How the message is played", STREAMS,
                stream, index -> {
                    stream = index;
                    streamBtn.setText(STREAMS[index]);
                }));
        save.setOnClickListener(v -> save());
    }

    private EditText field(LinearLayout parent, String label, int value) {
        parent.addView(Ui.text(this, label, 13, Ui.MUTED, false), Ui.below(this, 10));
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        e.setText(String.valueOf(value));
        parent.addView(e, Ui.lp(Ui.MATCH, Ui.WRAP));
        return e;
    }

    private static int read(EditText e, int def, int lo, int hi) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return Math.max(lo, Math.min(hi, v));
        } catch (Exception ex) {
            return def;
        }
    }

    private void chooseSim() {
        final List<PhoneAccountHandle> sims = Util.sims(this);
        if (sims.isEmpty()) {
            Ui.info(this, "SIM", "The SIMs could not be read. Allow the Phone permission on the"
                    + " home screen first. Until then the phone's default SIM is used.");
            return;
        }
        final String[] labels = new String[sims.size() + 1];
        labels[0] = "Phone default";
        int checked = 0;
        for (int i = 0; i < sims.size(); i++) {
            labels[i + 1] = Util.simLabel(this, sims.get(i), i);
            String id = sims.get(i).getId();
            if (id != null && id.equals(simId)) checked = i + 1;
        }
        Ui.choose(this, "SIM used for the calls", labels, checked, index -> {
            String id = index == 0 ? "" : sims.get(index - 1).getId();
            simId = id == null ? "" : id;
            simBtn.setText(labels[index]);
        });
    }

    private void save() {
        Prefs.sp(this).edit()
                .putInt(Prefs.GAP_SEC, read(gap, Prefs.DEF_GAP_SEC, 2, 600))
                .putInt(Prefs.RING_SEC, read(ring, Prefs.DEF_RING_SEC, 10, 90))
                .putInt(Prefs.ANSWER_DELAY_MS, read(delay, Prefs.DEF_ANSWER_DELAY_MS, 0, 10000))
                .putInt(Prefs.REPEAT, read(repeat, Prefs.DEF_REPEAT, 1, 5))
                .putInt(Prefs.RETRIES, read(retries, Prefs.DEF_RETRIES, 0, 5))
                .putInt(Prefs.RETRY_WAIT_MIN, read(retryWait, Prefs.DEF_RETRY_WAIT_MIN, 0, 240))
                .putInt(Prefs.HOUR_FROM, read(hourFrom, Prefs.DEF_HOUR_FROM, 0, 24))
                .putInt(Prefs.HOUR_TO, read(hourTo, Prefs.DEF_HOUR_TO, 0, 24))
                .putString(Prefs.SIM_ID, simId)
                .putInt(Prefs.PLAY_STREAM, stream)
                .apply();
        Ui.toast(this, "Settings saved.");
        finish();
    }
}
