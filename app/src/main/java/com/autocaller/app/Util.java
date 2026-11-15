package com.autocaller.app;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class Util {

    static final String P_CALL = "android.permission.CALL_PHONE";
    static final String P_STATE = "android.permission.READ_PHONE_STATE";
    static final String P_MIC = "android.permission.RECORD_AUDIO";
    static final String P_NOTIF = "android.permission.POST_NOTIFICATIONS";

    private Util() {
    }

    static boolean has(Context c, String permission) {
        return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** True when this app is the phone's default phone (dialer) app. */
    static boolean isDefaultDialer(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = c.getSystemService(RoleManager.class);
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    return rm.isRoleHeld(RoleManager.ROLE_DIALER);
                }
            }
            TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            return tm != null && c.getPackageName().equals(tm.getDefaultDialerPackage());
        } catch (Exception e) {
            return false;
        }
    }

    /** The SIMs (phone accounts) that can place calls. Empty if the permission is missing. */
    static List<PhoneAccountHandle> sims(Context c) {
        ArrayList<PhoneAccountHandle> out = new ArrayList<>();
        try {
            TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null && has(c, P_STATE)) {
                List<PhoneAccountHandle> l = tm.getCallCapablePhoneAccounts();
                if (l != null) out.addAll(l);
            }
        } catch (Exception e) {
            Logx.err("sims", e);
        }
        return out;
    }

    static String simLabel(Context c, PhoneAccountHandle h, int index) {
        String label = "SIM " + (index + 1);
        try {
            TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            PhoneAccount pa = tm == null ? null : tm.getPhoneAccount(h);
            CharSequence name = pa == null ? null : pa.getLabel();
            if (name != null && name.length() > 0) label = label + " – " + name;
        } catch (Exception ignored) {
        }
        return label;
    }

    static PhoneAccountHandle findSim(Context c, String id) {
        if (id == null || id.length() == 0) return null;
        for (PhoneAccountHandle h : sims(c)) {
            if (id.equals(h.getId())) return h;
        }
        return null;
    }

    static String simLabelFor(Context c, String id) {
        if (id == null || id.length() == 0) return "Phone default";
        List<PhoneAccountHandle> l = sims(c);
        for (int i = 0; i < l.size(); i++) {
            if (id.equals(l.get(i).getId())) return simLabel(c, l.get(i), i);
        }
        return "Phone default";
    }

    // ---- how the message is played -----------------------------------------------------------

    /** Labels for the Prefs.MODE_ values, in the same order. */
    static final String[] PLAY_MODES = {
            "Loudspeaker, media volume",
            "Loudspeaker, call volume",
            "Call on earpiece, message on loudspeaker",
            "Call on loudspeaker, message on earpiece",
            "Earpiece only",
            "Wired earphones or audio cable",
            "Bluetooth headphones",
            "Direct into the call (experimental)",
            "Second phone plays the message (AutoCaller Player)"};

    static String playModeLabel(int mode) {
        return mode >= 0 && mode < PLAY_MODES.length ? PLAY_MODES[mode] : PLAY_MODES[0];
    }

    /** True when this phone only places the call and another phone plays the message. */
    static boolean secondPhone(int mode) {
        return mode == Prefs.MODE_SECOND_PHONE;
    }

    /** True for the methods that keep the call itself on the loudspeaker. */
    static boolean callOnSpeaker(int mode) {
        return mode == Prefs.MODE_SPEAKER_MEDIA || mode == Prefs.MODE_SPEAKER_CALL
                || mode == Prefs.MODE_SPEAKER_CALL_EARPIECE_MSG || mode == Prefs.MODE_SECOND_PHONE;
    }

    /** The phone's own output of the given AudioDeviceInfo type, or null if it has none. */
    static AudioDeviceInfo outputDevice(Context c, int type) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.getType() == type) return d;
            }
        } catch (Exception e) {
            Logx.err("outputDevice", e);
        }
        return null;
    }

    static String deviceName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return "earpiece";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "loudspeaker";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "wired headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "wired headphones";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "bluetooth (call)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "bluetooth (music)";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "usb audio";
            case AudioDeviceInfo.TYPE_TELEPHONY:
                return "telephony";
            default:
                return "type " + type;
        }
    }

    /** True if wired or USB earphones (or an audio cable) are plugged in. */
    static boolean wiredAudioConnected(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_USB_DEVICE
                        || t == AudioDeviceInfo.TYPE_LINE_ANALOG) {
                    return true;
                }
            }
        } catch (Exception e) {
            Logx.err("wiredAudioConnected", e);
        }
        return false;
    }

    // ---- formatting ---------------------------------------------------------------------

    private static String fmt(String pattern, long t) {
        return new SimpleDateFormat(pattern, Locale.ENGLISH).format(new Date(t));
    }

    static String clock(long t) {
        return t <= 0 ? "-" : fmt("HH:mm:ss", t);
    }

    static String dateTime(long t) {
        return t <= 0 ? "-" : fmt("dd MMM yyyy HH:mm:ss", t);
    }

    static String day(long t) {
        return t <= 0 ? "-" : fmt("dd MMM yyyy", t);
    }

    static String stamp(long t) {
        return fmt("yyyyMMdd_HHmm", t);
    }

    static long secs(long ms) {
        return ms <= 0 ? 0 : (ms + 500) / 1000;
    }

    /** 75000 -> "1:15", 3723000 -> "1:02:03". */
    static String dur(long ms) {
        long s = secs(ms);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0 ? h + ":" + two(m) + ":" + two(sec) : m + ":" + two(sec);
    }

    static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    static String statusLabel(int status) {
        switch (status) {
            case Db.PENDING:
                return "Pending";
            case Db.RETRY:
                return "Will retry";
            case Db.ANSWERED:
                return "Answered";
            case Db.NO_ANSWER:
                return "Not answered";
            case Db.BUSY:
                return "Busy";
            case Db.REJECTED:
                return "Rejected";
            case Db.FAILED:
                return "Failed";
            case Db.SKIPPED:
                return "Skipped";
            case Db.CANCELLED:
                return "Cancelled";
            default:
                return "-";
        }
    }

    static int statusColor(int status) {
        switch (status) {
            case Db.ANSWERED:
                return Ui.PRIMARY;
            case Db.NO_ANSWER:
            case Db.BUSY:
                return Ui.AMBER;
            case Db.REJECTED:
            case Db.FAILED:
                return Ui.RED;
            case Db.RETRY:
                return Ui.BLUE;
            default:
                return Ui.MUTED;
        }
    }
}
