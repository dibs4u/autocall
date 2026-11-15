package com.autocaller.player;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Detection and playback settings. Same layout as AutoCaller's Settings screen. */
public class SettingsActivity extends Activity {

    private EditText margin, minVoice, delay, rest;
    private CheckBox needRing, maxVol;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.Screen s = Ui.screen(this, "Settings", true, true);

        // ---- detection
        LinearLayout det = Ui.card(this);
        det.addView(Ui.text(this, "When the person answers", 16, Ui.INK, true));
        det.addView(Ui.text(this, "The message starts when a voice is heard from the calling"
                + " phone's loudspeaker after it has rung.", 13, Ui.MUTED, false), Ui.below(this, 4));
        margin = field(det, "Sensitivity: voice must be louder than the background by (6 to 30 dB)",
                Prefs.getInt(this, Prefs.MARGIN_DB, Prefs.DEF_MARGIN_DB));
        det.addView(Ui.text(this, "Lower = more sensitive. Use 8 to 10 if “hello” is missed,"
                + " 15 to 20 if room noise starts the message.", 13, Ui.MUTED, false), Ui.below(this, 4));
        minVoice = field(det, "Voice must last at least (150 to 1500 milliseconds)",
                Prefs.getInt(this, Prefs.MIN_VOICE_MS, Prefs.DEF_MIN_VOICE_MS));
        needRing = check(det, "Play only after the call has rung",
                Prefs.getBool(this, Prefs.NEED_RING, true));
        det.addView(Ui.text(this, "Keeps network announcements such as “the number is not"
                + " reachable” from starting the message. Turn off for numbers with a caller"
                + " tune instead of ringing.", 13, Ui.MUTED, false), Ui.below(this, 4));
        s.body.addView(det);

        // ---- timing
        LinearLayout timing = Ui.card(this);
        timing.addView(Ui.text(this, "Timing", 16, Ui.INK, true));
        delay = field(timing, "Wait after the voice is heard before the message starts"
                + " (0 to 3000 milliseconds)", Prefs.getInt(this, Prefs.DELAY_MS, Prefs.DEF_DELAY_MS));
        rest = field(timing, "Pause after the message before listening again (3 to 60 seconds)",
                Prefs.getInt(this, Prefs.REST_SEC, Prefs.DEF_REST_SEC));
        timing.addView(Ui.text(this, "Keep the pause shorter than the time the calling phone"
                + " needs to hang up and dial the next number (its hang-up timer plus its pause"
                + " between calls).", 13, Ui.MUTED, false), Ui.below(this, 4));
        s.body.addView(timing);

        // ---- sound
        LinearLayout sound = Ui.card(this);
        sound.addView(Ui.text(this, "Sound", 16, Ui.INK, true));
        maxVol = check(sound, "Set the media volume to maximum while the message plays",
                Prefs.getBool(this, Prefs.MAX_VOL, true));
        s.body.addView(sound);

        Button save = Ui.primary(this, "Save");
        s.body.addView(save, Ui.lp(Ui.MATCH, Ui.WRAP));
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

    private CheckBox check(LinearLayout parent, String label, boolean value) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(Ui.INK);
        c.setTextSize(14);
        c.setButtonTintList(ColorStateList.valueOf(Ui.PRIMARY));
        c.setChecked(value);
        parent.addView(c, Ui.below(this, 10));
        return c;
    }

    private static int read(EditText e, int def, int lo, int hi) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return Math.max(lo, Math.min(hi, v));
        } catch (Exception ex) {
            return def;
        }
    }

    private void save() {
        Prefs.sp(this).edit()
                .putInt(Prefs.MARGIN_DB, read(margin, Prefs.DEF_MARGIN_DB, 6, 30))
                .putInt(Prefs.MIN_VOICE_MS, read(minVoice, Prefs.DEF_MIN_VOICE_MS, 150, 1500))
                .putInt(Prefs.DELAY_MS, read(delay, Prefs.DEF_DELAY_MS, 0, 3000))
                .putInt(Prefs.REST_SEC, read(rest, Prefs.DEF_REST_SEC, 3, 60))
                .putBoolean(Prefs.NEED_RING, needRing.isChecked())
                .putBoolean(Prefs.MAX_VOL, maxVol.isChecked())
                .apply();
        if (ListenService.running) {
            startService(new Intent(this, ListenService.class).setAction(ListenService.ACTION_APPLY));
        }
        Ui.toast(this, "Settings saved.");
        finish();
    }
}
