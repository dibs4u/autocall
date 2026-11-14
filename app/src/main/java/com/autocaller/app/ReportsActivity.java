package com.autocaller.app;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reports for a chosen period, in three views:
 * Summary (totals and an hour-by-hour table), By number, and By time (every call attempt).
 * Each view can be saved or shared as a CSV file.
 */
public class ReportsActivity extends Activity {

    private static final int REQ_SAVE = 61;
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final String[] PERIODS = {"Today", "Yesterday", "Last 7 days", "This month",
            "All time", "Pick a date…"};
    private static final String[] TABS = {"Summary", "By number", "By time"};
    private static final String[] FILE_KIND = {"summary", "by_number", "by_time"};

    /** One row on screen. */
    private static final class Line {
        final String title;
        final String sub;
        final String right;
        final int rightColor;
        final boolean header;

        Line(String title, String sub, String right, int rightColor, boolean header) {
            this.title = title;
            this.sub = sub;
            this.right = right;
            this.rightColor = rightColor;
            this.header = header;
        }
    }

    /** Everything that happened with one phone number in the period. */
    private static final class NumberRow {
        String number = "";
        String name = "";
        int attempts;
        int result;
        long lastDial;
        long answerTime;
        long talkMs;
        int audioMs;
        int audioTotalMs;
        boolean audioDone;
        boolean answered;
    }

    /** Totals for one hour of one day. */
    private static final class HourRow {
        long start;
        int calls;
        int answered;
        long talkMs;
    }

    private int period;
    private long from;
    private long to;
    private String periodLabel = PERIODS[0];
    private int tab;

    private Button periodBtn;
    private final Button[] tabBtns = new Button[TABS.length];
    private TextView headline;
    private LineAdapter adapter;
    private final ArrayList<Line> lines = new ArrayList<>();
    private List<Db.CallRow> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.Screen s = Ui.screen(this, "Reports", true, false);
        Ui.barAction(s, "Clear", v -> Ui.confirm(this, "Delete the call history?",
                "Every call record in Reports is deleted. The number list is not changed.",
                "Delete", () -> {
                    Db.get(this).clearCalls();
                    load();
                }));

        periodBtn = Ui.outline(this, "");
        s.body.addView(periodBtn, Ui.lp(Ui.MATCH, Ui.WRAP));

        LinearLayout tabRow = Ui.row(this);
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            tabBtns[i] = Ui.outline(this, TABS[i]);
            tabBtns[i].setOnClickListener(v -> {
                tab = index;
                rebuild();
            });
            tabRow.addView(tabBtns[i], Ui.share(this, i == 0 ? 0 : 8));
        }
        s.body.addView(tabRow, Ui.below(this, 8));

        headline = Ui.text(this, "", 14, Ui.INK, true);
        s.body.addView(headline, Ui.below(this, 12));

        ListView list = new ListView(this);
        list.setBackground(Ui.shape(Ui.WHITE, Ui.dp(this, 14), Ui.LINE, Ui.dp(this, 1)));
        list.setClipToOutline(true);
        adapter = new LineAdapter();
        list.setAdapter(adapter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f);
        lp.setMargins(0, Ui.dp(this, 8), 0, 0);
        s.body.addView(list, lp);

        LinearLayout actions = Ui.row(this);
        Button saveBtn = Ui.primary(this, "Save as CSV");
        Button shareBtn = Ui.outline(this, "Share");
        actions.addView(saveBtn, Ui.share(this, 0));
        actions.addView(shareBtn, Ui.share(this, 8));
        s.body.addView(actions, Ui.below(this, 10));

        periodBtn.setOnClickListener(v -> Ui.choose(this, "Period", PERIODS, period, index -> {
            if (index == PERIODS.length - 1) {
                pickDate();
            } else {
                applyPeriod(index, 0);
                load();
            }
        }));
        saveBtn.setOnClickListener(v -> save());
        shareBtn.setOnClickListener(v -> share());

        if (b != null) {
            period = b.getInt("period", 0);
            from = b.getLong("from", 0);
            to = b.getLong("to", 0);
            tab = Math.max(0, Math.min(TABS.length - 1, b.getInt("tab", 0)));
            String label = b.getString("label");
            periodLabel = label == null ? PERIODS[0] : label;
            if (to <= from) applyPeriod(0, 0);
        } else {
            applyPeriod(0, 0);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("period", period);
        out.putLong("from", from);
        out.putLong("to", to);
        out.putInt("tab", tab);
        out.putString("label", periodLabel);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // ---- period -------------------------------------------------------------------------

    private void applyPeriod(int p, long pickedStart) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long today = c.getTimeInMillis();
        period = p;
        periodLabel = PERIODS[p];
        switch (p) {
            case 0:
                from = today;
                to = today + DAY;
                break;
            case 1:
                from = today - DAY;
                to = today;
                break;
            case 2:
                from = today - 6 * DAY;
                to = today + DAY;
                break;
            case 3:
                c.set(Calendar.DAY_OF_MONTH, 1);
                from = c.getTimeInMillis();
                to = today + DAY;
                break;
            case 4:
                from = 0;
                to = Long.MAX_VALUE;
                break;
            default:
                from = pickedStart;
                to = pickedStart + DAY;
                periodLabel = Util.day(pickedStart);
                break;
        }
    }

    private void pickDate() {
        Calendar c = Calendar.getInstance();
        new DatePickerDialog(this, (view, year, month, day) -> {
            Calendar p = Calendar.getInstance();
            p.set(year, month, day, 0, 0, 0);
            p.set(Calendar.MILLISECOND, 0);
            applyPeriod(PERIODS.length - 1, p.getTimeInMillis());
            load();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void load() {
        rows = Db.get(this).calls(from, to);
        periodBtn.setText("Period: " + periodLabel);
        rebuild();
    }

    // ---- building the three views -------------------------------------------------------

    private void rebuild() {
        lines.clear();
        for (int i = 0; i < tabBtns.length; i++) {
            boolean on = i == tab;
            tabBtns[i].setBackground(on ? Ui.pressable(this, Ui.PRIMARY, 10) : Ui.outlineBg(this));
            tabBtns[i].setTextColor(on ? Ui.WHITE : Ui.INK);
        }
        int answered = 0;
        for (Db.CallRow r : rows) {
            if (r.result == Db.ANSWERED) answered++;
        }
        if (rows.isEmpty()) {
            headline.setText("No calls in this period.");
        } else {
            headline.setText(rows.size() + " calls · " + answered + " answered ("
                    + pct(answered, rows.size()) + ")");
            if (tab == 0) buildSummary();
            else if (tab == 1) buildByNumber();
            else buildByTime();
        }
        adapter.notifyDataSetChanged();
    }

    private static String pct(int part, int whole) {
        return whole == 0 ? "0%" : Math.round(part * 100f / whole) + "%";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String heard(int ms, int totalMs, boolean done) {
        return done ? "heard in full" : Util.secs(ms) + " of " + Util.secs(totalMs) + " s";
    }

    private static String hourLabel(long start) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(start);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        return Util.two(hour) + ":00–" + Util.two(hour) + ":59";
    }

    private List<NumberRow> byNumber() {
        LinkedHashMap<String, NumberRow> map = new LinkedHashMap<>();
        for (Db.CallRow r : rows) {
            String key = Csv.key(r.number);
            NumberRow n = map.get(key);
            if (n == null) {
                n = new NumberRow();
                n.number = nz(r.number);
                map.put(key, n);
            }
            if (r.name != null && r.name.length() > 0) n.name = r.name;
            n.attempts++;
            n.lastDial = r.dialTime;
            n.talkMs += r.talkMs;
            if (r.result == Db.ANSWERED) {
                n.answered = true;
                n.result = Db.ANSWERED;
                n.answerTime = r.answerTime;
                if (r.audioMs >= n.audioMs) {
                    n.audioMs = r.audioMs;
                    n.audioTotalMs = r.audioTotalMs;
                }
                if (r.audioDone) n.audioDone = true;
            } else if (!n.answered) {
                n.result = r.result;
            }
        }
        return new ArrayList<>(map.values());
    }

    private List<HourRow> byHour() {
        LinkedHashMap<Long, HourRow> map = new LinkedHashMap<>();
        Calendar c = Calendar.getInstance();
        for (Db.CallRow r : rows) {
            c.setTimeInMillis(r.dialTime);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            Long start = c.getTimeInMillis();
            HourRow h = map.get(start);
            if (h == null) {
                h = new HourRow();
                h.start = start;
                map.put(start, h);
            }
            h.calls++;
            if (r.result == Db.ANSWERED) h.answered++;
            h.talkMs += r.talkMs;
        }
        return new ArrayList<>(map.values());
    }

    private void addLine(String title, String sub, String right, int color) {
        lines.add(new Line(title, sub, right, color, false));
    }

    private void buildSummary() {
        int n = rows.size();
        int[] by = new int[Db.STATUS_COUNT];
        long talk = 0;
        int full = 0;
        int partial = 0;
        for (Db.CallRow r : rows) {
            if (r.result >= 0 && r.result < Db.STATUS_COUNT) by[r.result]++;
            talk += r.talkMs;
            if (r.result == Db.ANSWERED) {
                if (r.audioDone) full++;
                else partial++;
            }
        }
        int answered = by[Db.ANSWERED];
        List<NumberRow> numbers = byNumber();
        int reached = 0;
        for (NumberRow x : numbers) {
            if (x.answered) reached++;
        }
        addLine("Calls made", null, String.valueOf(n), Ui.INK);
        addLine("Different numbers called", reached + " of them answered at least once",
                String.valueOf(numbers.size()), Ui.INK);
        addLine("Answered", pct(answered, n) + " of the calls", String.valueOf(answered), Ui.PRIMARY);
        addLine("Message heard in full", null, String.valueOf(full), Ui.PRIMARY);
        addLine("Hung up before the message ended", null, String.valueOf(partial), Ui.AMBER);
        addLine("Not answered", null, String.valueOf(by[Db.NO_ANSWER]), Ui.AMBER);
        addLine("Busy", null, String.valueOf(by[Db.BUSY]), Ui.AMBER);
        addLine("Rejected", null, String.valueOf(by[Db.REJECTED]), Ui.RED);
        addLine("Failed (unreachable or network problem)", null, String.valueOf(by[Db.FAILED]), Ui.RED);
        addLine("Skipped or cancelled", null,
                String.valueOf(by[Db.SKIPPED] + by[Db.CANCELLED]), Ui.MUTED);
        addLine("Total talk time", "Average " + Util.dur(answered == 0 ? 0 : talk / answered)
                + " per answered call", Util.dur(talk), Ui.INK);
        addLine("First call", null, Util.dateTime(rows.get(0).dialTime), Ui.INK);
        addLine("Last call", null, Util.dateTime(rows.get(n - 1).dialTime), Ui.INK);

        lines.add(new Line("Hour by hour", null, null, 0, true));
        for (HourRow h : byHour()) {
            addLine(Util.day(h.start) + ", " + hourLabel(h.start), h.calls + " calls · "
                    + h.answered + " answered · talk " + Util.dur(h.talkMs),
                    pct(h.answered, h.calls), Ui.PRIMARY);
        }
    }

    private void buildByNumber() {
        for (NumberRow x : byNumber()) {
            String title = x.name.length() > 0 ? x.name + " · " + x.number : x.number;
            StringBuilder sub = new StringBuilder();
            sub.append(x.attempts).append(x.attempts == 1 ? " attempt" : " attempts")
                    .append(" · last ").append(Util.dateTime(x.lastDial));
            if (x.answered) {
                sub.append("\nAnswered ").append(Util.clock(x.answerTime))
                        .append(" · talk ").append(Util.dur(x.talkMs))
                        .append(" · message ").append(heard(x.audioMs, x.audioTotalMs, x.audioDone));
            }
            addLine(title, sub.toString(), Util.statusLabel(x.result), Util.statusColor(x.result));
        }
    }

    private void buildByTime() {
        boolean oneDay = to - from <= DAY;
        for (Db.CallRow r : rows) {
            String when = oneDay ? Util.clock(r.dialTime) : Util.dateTime(r.dialTime);
            StringBuilder sub = new StringBuilder();
            if (r.name != null && r.name.length() > 0) sub.append(r.name).append(" · ");
            sub.append(nz(r.number));
            sub.append("\nRang ").append(Util.secs(r.ringMs)).append(" s");
            if (r.answerTime > 0) {
                sub.append(" · answered ").append(Util.clock(r.answerTime))
                        .append(" · talk ").append(Util.dur(r.talkMs))
                        .append(" · message ").append(heard(r.audioMs, r.audioTotalMs, r.audioDone));
            }
            sub.append(" · ended ").append(Util.clock(r.endTime));
            if (r.attempt > 1) sub.append(" · attempt ").append(r.attempt);
            addLine(when, sub.toString(), Util.statusLabel(r.result), Util.statusColor(r.result));
        }
    }

    // ---- CSV export ---------------------------------------------------------------------

    private String csvByTime() {
        StringBuilder sb = new StringBuilder();
        Csv.line(sb, "Sr", "Date", "Dial time", "Number", "Name", "List", "Attempt", "SIM",
                "Result", "Ring seconds", "Answer time", "Talk seconds", "End time",
                "Message heard (s)", "Message length (s)", "Message heard in full", "Details");
        int i = 0;
        for (Db.CallRow r : rows) {
            i++;
            Csv.line(sb, String.valueOf(i), Util.day(r.dialTime), Util.clock(r.dialTime),
                    nz(r.number), nz(r.name), nz(r.list), String.valueOf(r.attempt), nz(r.sim),
                    Util.statusLabel(r.result), String.valueOf(Util.secs(r.ringMs)),
                    r.answerTime > 0 ? Util.clock(r.answerTime) : "",
                    String.valueOf(Util.secs(r.talkMs)), Util.clock(r.endTime),
                    String.valueOf(Util.secs(r.audioMs)), String.valueOf(Util.secs(r.audioTotalMs)),
                    r.result == Db.ANSWERED ? (r.audioDone ? "Yes" : "No") : "", nz(r.cause));
        }
        return sb.toString();
    }

    private String csvByNumber() {
        StringBuilder sb = new StringBuilder();
        Csv.line(sb, "Sr", "Number", "Name", "Attempts", "Result", "Last call date",
                "Last call time", "Answered at", "Talk seconds", "Message heard (s)",
                "Message length (s)", "Message heard in full");
        int i = 0;
        for (NumberRow x : byNumber()) {
            i++;
            Csv.line(sb, String.valueOf(i), x.number, x.name, String.valueOf(x.attempts),
                    Util.statusLabel(x.result), Util.day(x.lastDial), Util.clock(x.lastDial),
                    x.answered ? Util.dateTime(x.answerTime) : "",
                    String.valueOf(Util.secs(x.talkMs)),
                    x.answered ? String.valueOf(Util.secs(x.audioMs)) : "",
                    x.answered ? String.valueOf(Util.secs(x.audioTotalMs)) : "",
                    x.answered ? (x.audioDone ? "Yes" : "No") : "");
        }
        return sb.toString();
    }

    private String csvSummary() {
        StringBuilder sb = new StringBuilder();
        Csv.line(sb, "AutoCaller report", periodLabel);
        Csv.line(sb, "");
        Csv.line(sb, "Measure", "Value", "Note");
        for (Line l : lines) {
            if (l.header) break;
            Csv.line(sb, l.title, nz(l.right), nz(l.sub));
        }
        Csv.line(sb, "");
        Csv.line(sb, "Date", "Hour", "Calls", "Answered", "Answered %", "Talk seconds");
        for (HourRow h : byHour()) {
            Csv.line(sb, Util.day(h.start), hourLabel(h.start), String.valueOf(h.calls),
                    String.valueOf(h.answered), pct(h.answered, h.calls),
                    String.valueOf(Util.secs(h.talkMs)));
        }
        return sb.toString();
    }

    private String fileName() {
        return "AutoCaller_" + FILE_KIND[tab] + "_" + Util.stamp(System.currentTimeMillis()) + ".csv";
    }

    /** The current view as CSV. Starts with a byte-order mark so Excel reads names correctly. */
    private byte[] csvBytes() {
        String body;
        if (tab == 0) {
            if (lines.isEmpty() && !rows.isEmpty()) buildSummary();
            body = csvSummary();
        } else if (tab == 1) {
            body = csvByNumber();
        } else {
            body = csvByTime();
        }
        return ("﻿" + body).getBytes(StandardCharsets.UTF_8);
    }

    private void save() {
        if (rows.isEmpty()) {
            Ui.toast(this, "There are no calls in this period.");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/csv");
            i.putExtra(Intent.EXTRA_TITLE, fileName());
            startActivityForResult(i, REQ_SAVE);
        } catch (Exception e) {
            Ui.toast(this, "No file picker is available on this phone. Use Share instead.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try {
            load();
            if (rows.isEmpty()) {
                Ui.toast(this, "There are no calls in this period.");
                return;
            }
            OutputStream out = getContentResolver().openOutputStream(data.getData());
            if (out == null) throw new IOException("the file could not be opened");
            try {
                out.write(csvBytes());
            } finally {
                out.close();
            }
            Ui.toast(this, "Report saved.");
        } catch (Exception e) {
            Logx.err("save report", e);
            Ui.toast(this, "The report could not be saved: " + e.getMessage());
        }
    }

    private void share() {
        if (rows.isEmpty()) {
            Ui.toast(this, "There are no calls in this period.");
            return;
        }
        try {
            String name = fileName();
            File f = new File(ReportProvider.dir(this), name);
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(csvBytes());
            } finally {
                out.close();
            }
            Uri uri = ReportProvider.uriFor(name);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/csv");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, "AutoCaller report – " + periodLabel);
            i.setClipData(ClipData.newRawUri(name, uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share report"));
        } catch (Exception e) {
            Logx.err("share report", e);
            Ui.toast(this, "The report could not be shared: " + e.getMessage());
        }
    }

    // ---- rows ---------------------------------------------------------------------------

    private final class LineAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return lines.size();
        }

        @Override
        public Object getItem(int position) {
            return lines.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ReportsActivity a = ReportsActivity.this;
            TextView[] t;
            if (convertView == null) {
                LinearLayout row = Ui.row(a);
                LinearLayout left = Ui.col(a);
                TextView title = Ui.text(a, "", 15, Ui.INK, true);
                TextView sub = Ui.text(a, "", 12, Ui.MUTED, false);
                left.addView(title);
                left.addView(sub);
                row.addView(left, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
                TextView right = Ui.text(a, "", 14, Ui.INK, true);
                right.setGravity(Gravity.END);
                LinearLayout.LayoutParams rp = Ui.lp(Ui.WRAP, Ui.WRAP);
                rp.setMargins(Ui.dp(a, 10), 0, 0, 0);
                row.addView(right, rp);
                t = new TextView[]{title, sub, right};
                row.setTag(t);
                convertView = row;
            } else {
                t = (TextView[]) convertView.getTag();
            }
            Line l = lines.get(position);
            int side = Ui.dp(a, 14);
            convertView.setPadding(side, Ui.dp(a, l.header ? 18 : 11), side, Ui.dp(a, l.header ? 6 : 11));
            t[0].setText(l.title);
            t[0].setTextColor(l.header ? Ui.PRIMARY : Ui.INK);
            t[1].setText(l.sub == null ? "" : l.sub);
            t[1].setVisibility(l.sub == null ? View.GONE : View.VISIBLE);
            t[2].setText(l.right == null ? "" : l.right);
            t[2].setTextColor(l.rightColor == 0 ? Ui.INK : l.rightColor);
            return convertView;
        }
    }
}
