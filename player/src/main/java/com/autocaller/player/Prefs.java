package com.autocaller.player;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings and the current message, kept in SharedPreferences. */
final class Prefs {
    static final String MESSAGE_FILE = "message.audio";

    static final String MARGIN_DB = "margin";        // voice must be this much above background
    static final String MIN_VOICE_MS = "min_voice";  // voice must last this long
    static final String DELAY_MS = "delay";          // wait after the voice before playing
    static final String REST_SEC = "rest";           // pause after a message before listening
    static final String NEED_RING = "needRing";      // play only after the ringing was heard
    static final String MAX_VOL = "maxVol";          // media volume to maximum while playing
    static final String AUDIO_MS = "audio_ms";
    static final String AUDIO_LABEL = "audio_label";
    static final String AUDIO_TIME = "audio_time";

    static final int DEF_MARGIN_DB = 12;
    static final int DEF_MIN_VOICE_MS = 280;
    static final int DEF_DELAY_MS = 700;
    static final int DEF_REST_SEC = 8;

    private Prefs() {
    }

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("p", Context.MODE_PRIVATE);
    }

    static int getInt(Context c, String k, int def) {
        return sp(c).getInt(k, def);
    }

    static boolean getBool(Context c, String k, boolean def) {
        return sp(c).getBoolean(k, def);
    }
}
