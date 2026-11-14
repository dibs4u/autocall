package com.autocaller.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** The number list: import a CSV, add numbers by hand, and see the status of each number. */
public class ContactsActivity extends Activity {

    static final String EXTRA_IMPORT = "import";
    private static final int REQ_CSV = 31;
    private static final int MAX_FILE_BYTES = 20 * 1024 * 1024;

    private final ArrayList<Db.Contact> items = new ArrayList<>();
    private RowAdapter adapter;
    private TextView summary;
    private Db db;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = Db.get(this);
        Ui.Screen s = Ui.screen(this, "Numbers", true, false);

        LinearLayout buttons = Ui.row(this);
        Button importBtn = Ui.primary(this, "Import CSV");
        Button addBtn = Ui.outline(this, "Add numbers");
        Button moreBtn = Ui.outline(this, "More");
        buttons.addView(importBtn, Ui.share(this, 0));
        buttons.addView(addBtn, Ui.share(this, 8));
        buttons.addView(moreBtn, Ui.share(this, 8));
        s.body.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP));

        summary = Ui.text(this, "", 13, Ui.MUTED, false);
        s.body.addView(summary, Ui.below(this, 10));

        ListView list = new ListView(this);
        list.setBackground(Ui.shape(Ui.WHITE, Ui.dp(this, 14), Ui.LINE, Ui.dp(this, 1)));
        list.setClipToOutline(true);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f);
        lp.setMargins(0, Ui.dp(this, 10), 0, 0);
        s.body.addView(list, lp);

        TextView empty = Ui.text(this, "The list is empty.\n\nImport a CSV file with one phone"
                + " number per row. A column with names is optional, for example:\n\n"
                + "Name,Mobile\nRamesh,9876543210", 14, Ui.MUTED, false);
        empty.setGravity(Gravity.CENTER);
        s.body.addView(empty, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        list.setEmptyView(empty);

        importBtn.setOnClickListener(v -> pickCsv());
        addBtn.setOnClickListener(v -> addNumbers());
        moreBtn.setOnClickListener(v -> moreMenu());
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= items.size()) return;
            final Db.Contact c = items.get(position);
            Ui.menu(this, c.number, new String[]{"Call this number again (set to Pending)",
                    "Remove from the list"}, index -> {
                if (CampaignService.running) {
                    Ui.toast(this, "Stop the calling first.");
                    return;
                }
                if (index == 0) db.setPending(c.id);
                else db.deleteContact(c.id);
                reload();
            });
        });

        if (b == null && getIntent().getBooleanExtra(EXTRA_IMPORT, false)) pickCsv();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        items.clear();
        items.addAll(db.contacts());
        adapter.notifyDataSetChanged();
        int[] cnt = db.statusCounts();
        int toCall = cnt[Db.PENDING] + cnt[Db.RETRY];
        summary.setText(items.size() + " numbers · " + toCall + " to call · "
                + cnt[Db.ANSWERED] + " answered. Tap a number to call it again or remove it.");
    }

    // ---- CSV import ---------------------------------------------------------------------

    private void pickCsv() {
        if (CampaignService.running) {
            Ui.toast(this, "Stop the calling first.");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(Intent.createChooser(i, "Choose the CSV file"), REQ_CSV);
        } catch (Exception e) {
            Ui.toast(this, "No file picker is available on this phone.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CSV && resultCode == RESULT_OK && data != null && data.getData() != null) {
            readCsv(data.getData());
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try {
            Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) name = c.getString(0);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.length() == 0) name = uri.getLastPathSegment();
        return name == null || name.length() == 0 ? "list" : name;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_FILE_BYTES) throw new IOException("the file is too large");
        }
        return out.toByteArray();
    }

    private static String decode(byte[] d) {
        // "Unicode text" exports are UTF-16 and start with a byte-order mark.
        if (d.length >= 2) {
            int b0 = d[0] & 0xFF;
            int b1 = d[1] & 0xFF;
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) {
                return new String(d, StandardCharsets.UTF_16);
            }
        }
        return new String(d, StandardCharsets.UTF_8);
    }

    private void readCsv(final Uri uri) {
        final String fileName = displayName(uri);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                Csv.Result result = null;
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("the file could not be opened");
                    byte[] data;
                    try {
                        data = readAll(in);
                    } finally {
                        in.close();
                    }
                    if (data.length >= 2 && data[0] == 'P' && data[1] == 'K') {
                        error = "That is an Excel workbook, not a CSV file.\n\nIn Excel or Google"
                                + " Sheets choose Save as (or Download) > CSV, then import the"
                                + " CSV file.";
                    } else {
                        result = Csv.extract(Csv.parse(decode(data)));
                    }
                } catch (Exception e) {
                    Logx.err("import", e);
                    error = "The file could not be read: " + e.getMessage();
                }
                final String err = error;
                final Csv.Result res = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (err != null) Ui.info(ContactsActivity.this, "Import", err);
                        else showImportResult(res, fileName);
                    }
                });
            }
        }).start();
    }

    private void showImportResult(final Csv.Result r, final String fileName) {
        if (r.rows.isEmpty()) {
            Ui.info(this, "Nothing to import", r.damaged > 0
                    ? "The spreadsheet saved the phone numbers as values like 9.19877E+11, which"
                    + " throws digits away.\n\nIn the spreadsheet, select the number column,"
                    + " format it as Number with 0 decimals (or as Text), save as CSV again and"
                    + " import that file."
                    : "No phone numbers were found in that file. It needs one phone number per"
                    + " row, 7 to 15 digits long.");
            return;
        }
        StringBuilder m = new StringBuilder();
        m.append(r.rows.size()).append(" numbers found (taken from ").append(r.numberColumn);
        if (r.nameColumn.length() > 0) m.append(", names from ").append(r.nameColumn);
        m.append(").\n\nFirst rows:\n");
        for (int i = 0; i < Math.min(3, r.rows.size()); i++) {
            String[] row = r.rows.get(i);
            m.append("• ").append(row[1]);
            if (row[0].length() > 0) m.append("   ").append(row[0]);
            m.append('\n');
        }
        if (r.duplicates > 0) m.append('\n').append(r.duplicates).append(" repeated numbers left out.");
        if (r.invalid > 0) m.append('\n').append(r.invalid).append(" rows skipped (not a phone number).");
        if (r.damaged > 0) {
            m.append('\n').append(r.damaged).append(" numbers skipped: the spreadsheet saved them"
                    + " like 9.19877E+11 and digits were lost.");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Import numbers")
                .setMessage(m.toString())
                .setNegativeButton("Cancel", null);
        if (items.isEmpty()) {
            b.setPositiveButton("Import", (d, w) -> doImport(r, fileName, true));
        } else {
            b.setPositiveButton("Add to list", (d, w) -> doImport(r, fileName, false));
            b.setNeutralButton("Replace list", (d, w) -> doImport(r, fileName, true));
        }
        b.show();
    }

    private void doImport(Csv.Result r, String fileName, boolean replace) {
        if (CampaignService.running) {
            Ui.toast(this, "Stop the calling first.");
            return;
        }
        int added = db.addContacts(r.rows, fileName, replace);
        Prefs.putString(this, Prefs.LIST_NAME, fileName);
        reload();
        int already = r.rows.size() - added;
        Ui.toast(this, added + " numbers added"
                + (already > 0 ? " (" + already + " were already in the list)." : "."));
    }

    // ---- adding by hand, and list actions -----------------------------------------------

    private void addNumbers() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setHint("9876543210, Ramesh\n9812345678");
        LinearLayout box = Ui.col(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(Ui.text(this, "One number per line. A name after a comma is optional.",
                13, Ui.MUTED, false));
        box.addView(input, Ui.below(this, 6));
        new AlertDialog.Builder(this)
                .setTitle("Add numbers")
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    Csv.Result r = Csv.extract(Csv.parse(input.getText().toString()));
                    if (r.rows.isEmpty()) {
                        Ui.toast(this, "No valid phone numbers were found.");
                        return;
                    }
                    int added = db.addContacts(r.rows, "Added by hand", false);
                    reload();
                    Ui.toast(this, added + " numbers added.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void moreMenu() {
        Ui.menu(this, "List", new String[]{"Call unanswered numbers again",
                "Reset every number to Pending", "Clear the whole list"}, index -> {
            if (CampaignService.running) {
                Ui.toast(this, "Stop the calling first.");
                return;
            }
            if (index == 0) {
                db.requeueUnanswered();
                reload();
                Ui.toast(this, "Unanswered numbers are back in the queue.");
            } else if (index == 1) {
                Ui.confirm(this, "Reset the list?", "Every number goes back to Pending and will be"
                        + " called again on the next run. The call history in Reports is kept.",
                        "Reset", () -> {
                            db.resetAll();
                            reload();
                        });
            } else {
                Ui.confirm(this, "Clear the list?", "All numbers are removed from the list. The"
                        + " call history in Reports is kept.", "Clear", () -> {
                            db.clearContacts();
                            reload();
                        });
            }
        });
    }

    // ---- rows ---------------------------------------------------------------------------

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView[] t;
            if (convertView == null) {
                ContactsActivity a = ContactsActivity.this;
                LinearLayout row = Ui.row(a);
                int p = Ui.dp(a, 12);
                row.setPadding(Ui.dp(a, 14), p, Ui.dp(a, 14), p);
                LinearLayout left = Ui.col(a);
                TextView title = Ui.text(a, "", 15, Ui.INK, true);
                TextView sub = Ui.text(a, "", 12, Ui.MUTED, false);
                left.addView(title);
                left.addView(sub);
                row.addView(left, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
                TextView status = Ui.text(a, "", 13, Ui.MUTED, true);
                row.addView(status);
                t = new TextView[]{title, sub, status};
                row.setTag(t);
                convertView = row;
            } else {
                t = (TextView[]) convertView.getTag();
            }
            Db.Contact c = items.get(position);
            boolean named = c.name != null && c.name.length() > 0;
            t[0].setText(named ? c.name : c.number);
            String tried = c.attempts <= 0 ? "not called yet"
                    : c.attempts + (c.attempts == 1 ? " attempt" : " attempts") + " · last "
                    + Util.dateTime(c.lastTime);
            t[1].setText(named ? c.number + " · " + tried : tried);
            t[2].setText(Util.statusLabel(c.status));
            t[2].setTextColor(Util.statusColor(c.status));
            return convertView;
        }
    }
}
