package com.autocaller.player;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Activity log, kept in a small file so it survives the app being closed. */
final class Plog {
    private static File file;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("dd MMM HH:mm:ss", Locale.US);

    private Plog() {
    }

    static synchronized void init(Context c) {
        if (file == null) file = new File(c.getApplicationContext().getFilesDir(), "activity.log");
    }

    static synchronized void add(String line) {
        android.util.Log.i("AutoCallerPlayer", line);
        if (file == null) return;
        try {
            if (file.length() > 200_000) {
                String keep = tail(100_000);
                try (FileOutputStream o = new FileOutputStream(file, false)) {
                    o.write(keep.getBytes("UTF-8"));
                }
            }
            try (FileOutputStream o = new FileOutputStream(file, true)) {
                o.write((FMT.format(new Date()) + "  " + line + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    static synchronized String tail(int maxBytes) {
        if (file == null || !file.exists()) return "";
        try (RandomAccessFile r = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, r.length() - maxBytes);
            byte[] b = new byte[(int) (r.length() - start)];
            r.seek(start);
            r.readFully(b);
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    static synchronized void clear() {
        if (file != null) file.delete();
    }
}
