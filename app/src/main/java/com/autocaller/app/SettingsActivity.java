package com.autocaller.app;

import android.app.Activity;
import android.os.Bundle;
import android.telecom.PhoneAccountHandle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.List;

/** How the message is played, timing, retries, calling hours and SIM. */
public class SettingsActivity extends Activity {

    private EditText gap;
    private EditText ring;
    private EditText delay;
    private EditText repeat;
    private EditText retries;
    private EditText retryWait;
    private EditText hourFrom;
    private EditText hourTo;
    private EditText volume;
    private EditText messageSec;
    private EditText hangUpAfter;
    private Button simBtn;
    private Button modeBtn;
    private String simId = "";
    private int mode;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.Screen s = Ui.screen(this, "Settings", true, true);

        // ---- how the message reaches the other person
        LinearLayout sound = Ui.card(this);
        sound.addView(Ui.text(this, "How the message reaches the other person", 16, Ui.INK, true));
        mode = Math.max(0, Math.min(Util.PLAY_MODES.length - 1,
                Prefs.playMode(this)));
        modeBtn = Ui.secondary(this, Util.playModeLabel(mode));
        sound.addView(modeBtn, Ui.below(this, 10));
        sound.addView(Ui.text(this, "• Second phone plays the message (recommended): this phone"
                + " only places the calls, on the loudspeaker. A second phone running AutoCaller"
                + " Player lies next to it, hears the ringing and then the person's \u201chello\u201d,"
                + " and plays the message from its own loudspeaker. This phone's microphone"
                + " carries it into the call. Because the sound comes from another phone, this"
                + " phone does not remove it as echo. No message is needed on this phone.",
                13, Ui.MUTED, false), Ui.below(this, 10));
        sound.addView(Ui.text(this, "Android does not let an app put sound straight into a phone"
                + " call, so the message has to travel through a microphone. Many phones remove"
                + " the sound of their own loudspeaker from a call as echo: the message then"
                + " plays loudly, but the other person hears nothing. Which method works depends"
                + " on the phone, so try them with “Test call” on the home screen.\n\n"
                + "• Loudspeaker (media or call volume): the call and the message both use"
                + " the loudspeaker.\n\n"
                + "• Call on earpiece, message on loudspeaker, and the other way round:"
                + " the message comes from a different speaker than the call, so the phone does"
                + " not treat it as echo. Not every phone allows this.\n\n"
                + "• Earpiece only: quiet, but some phones do not filter it out.\n\n"
                + "• Wired earphones: plug in earphones and keep one earbud pressed against"
                + " the microphone: the one on the earphone cable, or the phone's own microphone"
                + " if the earphones have none. A little tape holds it in place. This works on"
                + " almost every phone.\n\n"
                + "\u2022 Bluetooth headphones: connect them first. The call and the message"
                + " go to the headphones; hold the microphone side of the headphones against"
                + " one of its speakers. Many headphones filter their own sound, so test it.", 13, Ui.MUTED, false), Ui.below(this, 10));
        volume = field(sound, "Loudness of the message, or of the call with a second phone"
                        + " (10 to 100 percent)",
                Prefs.getInt(this, Prefs.VOLUME_PCT, Prefs.DEF_VOLUME_PCT));
        sound.addView(Ui.text(this, "Keep 100 for the loudspeaker and earpiece. With earphones"
                + " on the microphone, 60 to 80 usually sounds clearer.", 13, Ui.MUTED, false),
                Ui.below(this, 4));
        s.body.addView(sound);

        // ---- timing
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

        // ---- when to hang up
        LinearLayout end = Ui.card(this);
        end.addView(Ui.text(this, "Hang-up timer", 16, Ui.INK, true));
        messageSec = field(end, "Length of the message on the second phone (seconds)",
                Prefs.messageSec(this));
        end.addView(Ui.text(this, "Used only with \u201cSecond phone plays the message\u201d. Enter"
                + " the length of the recording on the second phone. Its screen shows it.",
                13, Ui.MUTED, false), Ui.below(this, 4));
        hangUpAfter = field(end, "Hang up this many seconds after the message ends (0 to 120)",
                Prefs.getInt(this, Prefs.HANGUP_AFTER_SEC, Prefs.DEF_HANGUP_AFTER_SEC));
        end.addView(Ui.text(this, "With a second phone the call is ended at: answer + message"
                + " length + this value. The second phone starts about 1 to 2 seconds after the"
                + " person says \u201chello\u201d, so keep at least 4 seconds. When this phone"
                + " plays the message itself, the call ends this long after the message ends.",
                13, Ui.MUTED, false), Ui.below(this, 4));
        s.body.addView(end);

        // ---- retries
        LinearLayout retry = Ui.card(this);
        retry.addView(Ui.text(this, "Unanswered numbers", 16, Ui.INK, true));
        retries = field(retry, "Try again this many times (0 = do not retry)",
                Prefs.getInt(this, Prefs.RETRIES, Prefs.DEF_RETRIES));
        retryWait = field(retry, "Wait before trying a number again (minutes)",
                Prefs.getInt(this, Prefs.RETRY_WAIT_MIN, Prefs.DEF_RETRY_WAIT_MIN));
        s.body.addView(retry);

        // ---- calling hours
        LinearLayout hours = Ui.card(this);
        hours.addView(Ui.text(this, "Calling hours", 16, Ui.INK, true));
        hours.addView(Ui.text(this, "Calls are only started between these hours (24-hour clock)."
                + " Use 0 and 24 for no limit.", 13, Ui.MUTED, false), Ui.below(this, 4));
        hourFrom = field(hours, "From hour (0 to 24)",
                Prefs.getInt(this, Prefs.HOUR_FROM, Prefs.DEF_HOUR_FROM));
        hourTo = field(hours, "Until hour (0 to 24)",
                Prefs.getInt(this, Prefs.HOUR_TO, Prefs.DEF_HOUR_TO));
        s.body.addView(hours);

        // ---- SIM
        LinearLayout phone = Ui.card(this);
        phone.addView(Ui.text(this, "SIM used for the calls", 16, Ui.INK, true));
        simId = Prefs.getString(this, Prefs.SIM_ID, "");
        simBtn = Ui.secondary(this, Util.simLabelFor(this, simId));
        phone.addView(simBtn, Ui.below(this, 10));
        s.body.addView(phone);

        Button save = Ui.primary(this, "Save");
        s.body.addView(save, Ui.lp(Ui.MATCH, Ui.WRAP));

        modeBtn.setOnClickListener(v -> Ui.choose(this, "How the message is played",
                Util.PLAY_MODES, mode, index -> {
                    mode = index;
                    modeBtn.setText(Util.playModeLabel(index));
                }));
        simBtn.setOnClickListener(v -> chooseSim());
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
                .putInt(Prefs.PLAY_STREAM, mode)
                .putInt(Prefs.VOLUME_PCT, read(volume, Prefs.DEF_VOLUME_PCT, 10, 100))
                .putInt(Prefs.GAP_SEC, read(gap, Prefs.DEF_GAP_SEC, 2, 600))
                .putInt(Prefs.RING_SEC, read(ring, Prefs.DEF_RING_SEC, 10, 90))
                .putInt(Prefs.ANSWER_DELAY_MS, read(delay, Prefs.DEF_ANSWER_DELAY_MS, 0, 10000))
                .putInt(Prefs.REPEAT, read(repeat, Prefs.DEF_REPEAT, 1, 5))
                .putInt(Prefs.MESSAGE_SEC, read(messageSec, Prefs.DEF_MESSAGE_SEC, 1, 600))
                .putInt(Prefs.HANGUP_AFTER_SEC, read(hangUpAfter, Prefs.DEF_HANGUP_AFTER_SEC, 0, 120))
                .putInt(Prefs.RETRIES, read(retries, Prefs.DEF_RETRIES, 0, 5))
                .putInt(Prefs.RETRY_WAIT_MIN, read(retryWait, Prefs.DEF_RETRY_WAIT_MIN, 0, 240))
                .putInt(Prefs.HOUR_FROM, read(hourFrom, Prefs.DEF_HOUR_FROM, 0, 24))
                .putInt(Prefs.HOUR_TO, read(hourTo, Prefs.DEF_HOUR_TO, 0, 24))
                .putString(Prefs.SIM_ID, simId)
                .apply();
        Ui.toast(this, "Settings saved.");
        finish();
    }
}
