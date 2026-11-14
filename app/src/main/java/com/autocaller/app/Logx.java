package com.autocaller.app;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small file-backed log so that problems on a real phone can be inspected (Home > Activity log). */
final class Logx {

    private static final String TAG = "AutoCaller";
    private static final long MAX_BYTES = 400 * 1024;
    private static File file;

    private Logx() {
    }

    static void init(Context c) {
        file = new File(c.getFilesDir(), "activity-log.txt");
    }

    static synchronized void log(String msg) {
        Log.i(TAG, msg);
        if (file == null) return;
        try {
            if (file.length() > MAX_BYTES) trim();
            String stamp = new SimpleDateFormat("dd-MM HH:mm:ss", Locale.US).format(new Date());
            FileWriter w = new FileWriter(file, true);
            try {
                w.write(stamp + "  " + msg + "\n");
            } finally {
                w.close();
            }
        } catch (Exception ignored) {
        }
    }

    static void err(String what, Throwable t) {
        log(what + ": " + Log.getStackTraceString(t));
    }

    /** The last part of the log, at most maxBytes long. */
    static synchronized String tail(int maxBytes) {
        if (file == null || !file.exists()) return "";
        try {
            long len = file.length();
            FileInputStream in = new FileInputStream(file);
            try {
                long skip = Math.max(0, len - maxBytes);
                long done = 0;
                while (done < skip) {
                    long n = in.skip(skip - done);
                    if (n <= 0) break;
                    done += n;
                }
                byte[] buf = new byte[(int) (len - done)];
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                return new String(buf, 0, off, StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    static synchronized void clear() {
        if (file != null) file.delete();
    }

    private static void trim() {
        String keep = tail((int) (MAX_BYTES / 2));
        try {
            FileOutputStream out = new FileOutputStream(file, false);
            try {
                out.write(keep.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }
}
