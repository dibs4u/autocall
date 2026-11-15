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
    static final String PLAY_STREAM = "play_stream";         // one of the MODE_ values below
    static final String VOLUME_PCT = "volume_pct";           // loudness of the message, 10-100
    static final String TEST_NUMBER = "test_number";         // last number used for a test call
    static final String MESSAGE_SEC = "message_sec";         // second-phone mode: length of its message
    static final String HANGUP_AFTER_SEC = "hangup_after_sec"; // hang up this long after the message ends

    static final String LIST_NAME = "list_name";             // name of the last imported file

    // How the message is carried to the other person (see Settings for the explanation).
    static final int MODE_SPEAKER_MEDIA = 0;             // call and message on the loudspeaker
    static final int MODE_SPEAKER_CALL = 1;              // same, on the call's volume channel
    static final int MODE_EARPIECE_CALL_SPEAKER_MSG = 2; // call on earpiece, message on loudspeaker
    static final int MODE_SPEAKER_CALL_EARPIECE_MSG = 3; // call on loudspeaker, message on earpiece
    static final int MODE_EARPIECE = 4;                  // call and message on the earpiece
    static final int MODE_WIRED = 5;
    static final int MODE_DIRECT = 7;                    // straight into the call (system-only on most phones)
    static final int MODE_BLUETOOTH = 6;                 // Bluetooth headphones or headset
    static final int MODE_SECOND_PHONE = 8;              // a second phone (AutoCaller Player) plays it
                        // wired earphones or an audio cable
    static final int DEF_VOLUME_PCT = 100;
    static final int DEF_PLAY_STREAM = MODE_SECOND_PHONE;
    static final int DEF_HANGUP_AFTER_SEC = 5;
    static final int DEF_MESSAGE_SEC = 30;

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

    /** The play method chosen in Settings. */
    static int playMode(Context c) {
        int m = getInt(c, PLAY_STREAM, DEF_PLAY_STREAM);
        return m >= 0 && m < Util.PLAY_MODES.length ? m : DEF_PLAY_STREAM;
    }

    /**
     * How long the message on the second phone lasts. Settings value if set, otherwise the
     * length of the message recorded on this phone, otherwise 30 seconds.
     */
    static int messageSec(Context c) {
        int s = getInt(c, MESSAGE_SEC, 0);
        if (s > 0) return s;
        int ms = getInt(c, AUDIO_MS, 0);
        return ms > 0 ? (ms + 999) / 1000 : DEF_MESSAGE_SEC;
    }

    static void putInt(Context c, String key, int value) {
        sp(c).edit().putInt(key, value).apply();
    }

    static void putString(Context c, String key, String value) {
        sp(c).edit().putString(key, value).apply();
    }
}
