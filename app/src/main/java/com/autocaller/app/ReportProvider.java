package com.autocaller.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets other apps (WhatsApp, Gmail, Drive...) read a report file that the user chose to share.
 * Only files in the app's cache/reports folder can be read, and only by the app the user
 * shared them with.
 */
public class ReportProvider extends ContentProvider {

    static final String AUTHORITY = "com.autocaller.app.reports";
    private static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;

    static Uri uriFor(String fileName) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(fileName));
    }

    /** Folder for shared report files; old ones are removed after a week. */
    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "reports");
        d.mkdirs();
        File[] old = d.listFiles();
        if (old != null) {
            long now = System.currentTimeMillis();
            for (File f : old) {
                if (now - f.lastModified() > KEEP_MS) f.delete();
            }
        }
        return d;
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0 || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("bad name");
        }
        Context c = getContext();
        if (c == null) throw new FileNotFoundException("no context");
        File f = new File(new File(c.getCacheDir(), "reports"), name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] values = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) values[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) values[i] = f.length();
            else values[i] = null;
        }
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(values);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        return name != null && name.endsWith(".csv") ? "text/csv" : "text/plain";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
