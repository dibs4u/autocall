package com.autocaller.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** The number list (contacts) and the history of every call attempt (calls). */
final class Db extends SQLiteOpenHelper {

    // status of a number in the list, and result of a single call attempt
    static final int PENDING = 0;
    static final int RETRY = 1;
    static final int ANSWERED = 2;
    static final int NO_ANSWER = 3;
    static final int BUSY = 4;
    static final int REJECTED = 5;
    static final int FAILED = 6;
    static final int SKIPPED = 7;
    static final int CANCELLED = 8;
    static final int STATUS_COUNT = 9;

    static final class Contact {
        long id;
        String name;
        String number;
        int status;
        int attempts;
        long lastTime;
        String list;
    }

    static final class CallRow {
        long id;
        long contactId;
        String name;
        String number;
        String list;
        String sim;
        String cause;
        int attempt;
        int result;
        long dialTime;
        long answerTime;
        long endTime;
        long ringMs;
        long talkMs;
        int audioMs;
        int audioTotalMs;
        boolean audioDone;
    }

    private static final String CONTACT_COLS = "_id,name,number,status,attempts,last_time,list_name";

    private static Db instance;

    static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "autocaller.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE contacts (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT,"
                + " number TEXT NOT NULL, status INTEGER NOT NULL DEFAULT 0,"
                + " attempts INTEGER NOT NULL DEFAULT 0, last_time INTEGER NOT NULL DEFAULT 0,"
                + " list_name TEXT)");
        d.execSQL("CREATE TABLE calls (_id INTEGER PRIMARY KEY AUTOINCREMENT, contact_id INTEGER,"
                + " name TEXT, number TEXT, list_name TEXT, attempt INTEGER, sim TEXT,"
                + " dial_time INTEGER, answer_time INTEGER, end_time INTEGER, ring_ms INTEGER,"
                + " talk_ms INTEGER, audio_ms INTEGER, audio_total_ms INTEGER, audio_done INTEGER,"
                + " result INTEGER, cause TEXT)");
        d.execSQL("CREATE INDEX idx_contacts_status ON contacts(status)");
        d.execSQL("CREATE INDEX idx_calls_time ON calls(dial_time)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int oldVersion, int newVersion) {
    }

    // ---- number list --------------------------------------------------------------------

    private static Contact readContact(Cursor c) {
        Contact k = new Contact();
        k.id = c.getLong(0);
        k.name = c.getString(1);
        k.number = c.getString(2);
        k.status = c.getInt(3);
        k.attempts = c.getInt(4);
        k.lastTime = c.getLong(5);
        k.list = c.getString(6);
        return k;
    }

    private Contact one(String where, String order) {
        Cursor c = getReadableDatabase().rawQuery("SELECT " + CONTACT_COLS + " FROM contacts WHERE "
                + where + " ORDER BY " + order + " LIMIT 1", null);
        try {
            return c.moveToFirst() ? readContact(c) : null;
        } finally {
            c.close();
        }
    }

    /** Next number that has not been tried yet, in list order. */
    Contact nextPending() {
        return one("status=" + PENDING, "_id ASC");
    }

    /** Next number waiting for another attempt; the one tried longest ago comes first. */
    Contact nextRetry() {
        return one("status=" + RETRY, "last_time ASC, _id ASC");
    }

    List<Contact> contacts() {
        ArrayList<Contact> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT " + CONTACT_COLS
                + " FROM contacts ORDER BY _id ASC", null);
        try {
            while (c.moveToNext()) out.add(readContact(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** How many numbers are in each status; index = status constant. */
    int[] statusCounts() {
        int[] out = new int[STATUS_COUNT];
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT status, COUNT(*) FROM contacts GROUP BY status", null);
        try {
            while (c.moveToNext()) {
                int s = c.getInt(0);
                if (s >= 0 && s < STATUS_COUNT) out[s] = c.getInt(1);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Adds {name, number} rows, skipping numbers that are already in the list. */
    int addContacts(List<String[]> rows, String list, boolean replace) {
        SQLiteDatabase d = getWritableDatabase();
        int added = 0;
        d.beginTransaction();
        try {
            if (replace) d.delete("contacts", null, null);
            HashSet<String> seen = new HashSet<>();
            if (!replace) {
                Cursor c = d.rawQuery("SELECT number FROM contacts", null);
                try {
                    while (c.moveToNext()) seen.add(Csv.key(c.getString(0)));
                } finally {
                    c.close();
                }
            }
            ContentValues v = new ContentValues();
            for (String[] r : rows) {
                if (!seen.add(Csv.key(r[1]))) continue;
                v.clear();
                v.put("name", r[0]);
                v.put("number", r[1]);
                v.put("list_name", list);
                d.insert("contacts", null, v);
                added++;
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
        return added;
    }

    void clearContacts() {
        getWritableDatabase().delete("contacts", null, null);
    }

    void resetAll() {
        getWritableDatabase().execSQL("UPDATE contacts SET status=0, attempts=0, last_time=0");
    }

    /** Puts every number that was tried but not answered back in the queue. */
    void requeueUnanswered() {
        getWritableDatabase().execSQL("UPDATE contacts SET status=0, attempts=0 WHERE status IN ("
                + RETRY + "," + NO_ANSWER + "," + BUSY + "," + REJECTED + "," + FAILED + ","
                + SKIPPED + "," + CANCELLED + ")");
    }

    void deleteContact(long id) {
        getWritableDatabase().delete("contacts", "_id=?", new String[]{String.valueOf(id)});
    }

    void setPending(long id) {
        getWritableDatabase().execSQL("UPDATE contacts SET status=0, attempts=0 WHERE _id=?",
                new Object[]{id});
    }

    void markDialing(long id, long time) {
        getWritableDatabase().execSQL(
                "UPDATE contacts SET attempts=attempts+1, last_time=? WHERE _id=?",
                new Object[]{time, id});
    }

    // ---- call history -------------------------------------------------------------------

    /** Stores one finished call attempt and updates the number's status in the list. */
    void saveAttempt(CallRow r, int contactStatus, boolean revertAttempt) {
        SQLiteDatabase d = getWritableDatabase();
        d.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("contact_id", r.contactId);
            v.put("name", r.name);
            v.put("number", r.number);
            v.put("list_name", r.list);
            v.put("attempt", r.attempt);
            v.put("sim", r.sim);
            v.put("dial_time", r.dialTime);
            v.put("answer_time", r.answerTime);
            v.put("end_time", r.endTime);
            v.put("ring_ms", r.ringMs);
            v.put("talk_ms", r.talkMs);
            v.put("audio_ms", r.audioMs);
            v.put("audio_total_ms", r.audioTotalMs);
            v.put("audio_done", r.audioDone ? 1 : 0);
            v.put("result", r.result);
            v.put("cause", r.cause);
            d.insert("calls", null, v);
            if (revertAttempt) {
                d.execSQL("UPDATE contacts SET status=?, attempts=MAX(attempts-1,0) WHERE _id=?",
                        new Object[]{contactStatus, r.contactId});
            } else {
                d.execSQL("UPDATE contacts SET status=? WHERE _id=?",
                        new Object[]{contactStatus, r.contactId});
            }
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** All call attempts dialled in [from, to), oldest first. */
    List<CallRow> calls(long from, long to) {
        ArrayList<CallRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT _id,contact_id,name,number,list_name,"
                        + "attempt,sim,dial_time,answer_time,end_time,ring_ms,talk_ms,audio_ms,"
                        + "audio_total_ms,audio_done,result,cause FROM calls"
                        + " WHERE dial_time>=? AND dial_time<? ORDER BY dial_time ASC, _id ASC",
                new String[]{String.valueOf(from), String.valueOf(to)});
        try {
            while (c.moveToNext()) {
                CallRow r = new CallRow();
                r.id = c.getLong(0);
                r.contactId = c.getLong(1);
                r.name = c.getString(2);
                r.number = c.getString(3);
                r.list = c.getString(4);
                r.attempt = c.getInt(5);
                r.sim = c.getString(6);
                r.dialTime = c.getLong(7);
                r.answerTime = c.getLong(8);
                r.endTime = c.getLong(9);
                r.ringMs = c.getLong(10);
                r.talkMs = c.getLong(11);
                r.audioMs = c.getInt(12);
                r.audioTotalMs = c.getInt(13);
                r.audioDone = c.getInt(14) != 0;
                r.result = c.getInt(15);
                r.cause = c.getString(16);
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    void clearCalls() {
        getWritableDatabase().delete("calls", null, null);
    }
}
