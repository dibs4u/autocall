package com.autocaller.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings and the current voice message, kept in SharedPreferences. */
final class Prefs {

    private static final String FILE = "autocaller";

    // voice message
    static final String AUDIO_PATH = "audio_path";
    static final String AUDIO_MS = "audio_ms";
    static final String AUDIO_TIME = "audio_time";
    static final String AUDIO_LABEL = "audio_label";

    // calling behaviour
    static final String GAP_SEC = "gap_sec";                 // pause between two calls
    static final String RING_SEC = "ring_sec";               // give up if not answered after
    static final String ANSWER_DELAY_MS = "answer_delay_ms"; // wait after answer before playing
    static final String REPEAT = "repeat";                   // how many times to play the message
    static final String RETRIES = "retries";                 // extra attempts for unanswered numbers
    static final String RETRY_WAIT_MIN = "retry_wait_min";   // minimum wait before a retry
    static final String HOUR_FROM = "hour_from";             // calling hours, 0-24
    static final String HOUR_TO = "hour_to";
    static final String SIM_ID = "sim_id";                   // "" = phone default
    static final String PLAY_STREAM = "play_stream";         // 0 = media, 1 = voice-call stream

    static final String LIST_NAME = "list_name";             // name of the last imported file

    static final int DEF_GAP_SEC = 5;
    static final int DEF_RING_SEC = 35;
    static final int DEF_ANSWER_DELAY_MS = 1200;
    static final int DEF_REPEAT = 1;
    static final int DEF_RETRIES = 0;
    static final int DEF_RETRY_WAIT_MIN = 5;
    static final int DEF_HOUR_FROM = 9;
    static final int DEF_HOUR_TO = 21;

    private Prefs() {
    }

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static int getInt(Context c, String key, int def) {
        return sp(c).getInt(key, def);
    }

    static long getLong(Context c, String key, long def) {
        return sp(c).getLong(key, def);
    }

    static String getString(Context c, String key, String def) {
        String v = sp(c).getString(key, def);
        return v == null ? def : v;
    }

    static void putInt(Context c, String key, int value) {
        sp(c).edit().putInt(key, value).apply();
    }

    static void putString(Context c, String key, String value) {
        sp(c).edit().putString(key, value).apply();
    }
}
