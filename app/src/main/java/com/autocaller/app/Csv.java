package com.autocaller.app;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSV reading / writing and phone-number clean-up.
 * Uses no Android classes, so it can be tested on a PC with plain Java.
 */
final class Csv {

    /** Result of reading a number list. Each row is {name, number}. */
    static final class Result {
        final ArrayList<String[]> rows = new ArrayList<>();
        int invalid;      // rows whose phone number could not be understood
        int duplicates;   // repeated numbers that were dropped
        int damaged;      // numbers a spreadsheet turned into 9.19877E+11 and that lost digits
        String numberColumn = "";
        String nameColumn = "";
    }

    private static final Pattern SCIENTIFIC =
            Pattern.compile("([0-9])(?:\\.([0-9]+))?[eE]\\+?([0-9]{1,2})");

    private Csv() {
    }

    /** Splits CSV text into rows of cells. Handles quotes, and comma / semicolon / tab / pipe. */
    static ArrayList<String[]> parse(String text) {
        ArrayList<String[]> out = new ArrayList<>();
        if (text == null) return out;
        if (text.length() > 0 && text.charAt(0) == '﻿') text = text.substring(1);
        char delim = delimiter(text);
        ArrayList<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean any = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"' && cell.toString().trim().length() == 0) {
                cell.setLength(0);
                inQuotes = true;
                any = true;
            } else if (ch == delim) {
                row.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                if (any || cell.toString().trim().length() > 0) {
                    row.add(cell.toString());
                    out.add(row.toArray(new String[0]));
                }
                row.clear();
                cell.setLength(0);
                any = false;
            } else {
                cell.append(ch);
            }
        }
        if (any || cell.toString().trim().length() > 0) {
            row.add(cell.toString());
            out.add(row.toArray(new String[0]));
        }
        return out;
    }

    static char delimiter(String text) {
        int comma = 0, semi = 0, tab = 0, pipe = 0, lines = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length() && lines < 25; i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (ch == ',') comma++;
                else if (ch == ';') semi++;
                else if (ch == '\t') tab++;
                else if (ch == '|') pipe++;
                else if (ch == '\n') lines++;
            }
        }
        char best = ',';
        int max = comma;
        if (semi > max) {
            best = ';';
            max = semi;
        }
        if (tab > max) {
            best = '\t';
            max = tab;
        }
        if (pipe > max) best = '|';
        return best;
    }

    /**
     * True for values like 9.19877E+11: a spreadsheet wrote a long number in scientific
     * notation and threw digits away, so the real phone number cannot be recovered.
     */
    static boolean isDamagedScientific(String raw) {
        if (raw == null) return false;
        Matcher m = SCIENTIFIC.matcher(raw.trim());
        if (!m.matches()) return false;
        int fraction = m.group(2) == null ? 0 : m.group(2).length();
        int exponent = Integer.parseInt(m.group(3));
        return exponent - fraction >= 3;
    }

    /**
     * Cleans one cell into a dialable number (digits, optional leading +).
     * Returns null when the cell is not a phone number (7 to 15 digits are accepted).
     */
    static String normalizeNumber(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() == 0) return null;
        if (SCIENTIFIC.matcher(s).matches()) {
            if (isDamagedScientific(s)) return null;
            try {
                s = new BigDecimal(s).toPlainString();
            } catch (Exception e) {
                return null;
            }
        }
        int dot = s.indexOf('.');
        if (dot > 0 && s.substring(dot + 1).matches("0+")) s = s.substring(0, dot); // 9876543210.0
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                b.append(ch);
            } else if (ch == '+' && b.length() == 0) {
                b.append('+');
            } else if (ch == ' ' || ch == '-' || ch == '(' || ch == ')' || ch == ' '
                    || ch == '\'' || ch == '\t') {
                continue;
            } else {
                return null;
            }
        }
        int digits = b.length() - (b.length() > 0 && b.charAt(0) == '+' ? 1 : 0);
        if (digits < 7 || digits > 15) return null;
        return b.toString();
    }

    /** Last ten digits: used to spot the same number written in different ways. */
    static String key(String number) {
        if (number == null) return "";
        String d = number.startsWith("+") ? number.substring(1) : number;
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    private static boolean looksLikeMobile(String normalized) {
        String k = key(normalized);
        int digits = normalized.startsWith("+") ? normalized.length() - 1 : normalized.length();
        return k.length() == 10 && k.charAt(0) >= '6' && digits <= 13;
    }

    /** Finds the phone-number column (and a name column if there is one) and reads the rows. */
    static Result extract(List<String[]> table) {
        Result r = new Result();
        if (table == null || table.isEmpty()) return r;
        int cols = 0;
        for (String[] row : table) cols = Math.max(cols, row.length);
        if (cols == 0) return r;

        int[] valid = new int[cols];
        int[] mobile = new int[cols];
        for (String[] row : table) {
            for (int i = 0; i < row.length; i++) {
                String num = normalizeNumber(row[i]);
                if (num != null) {
                    valid[i]++;
                    if (looksLikeMobile(num)) mobile[i]++;
                } else if (isDamagedScientific(row[i])) {
                    valid[i]++;
                }
            }
        }
        int numCol = 0;
        for (int i = 1; i < cols; i++) {
            if (mobile[i] > mobile[numCol] || (mobile[i] == mobile[numCol] && valid[i] > valid[numCol])) {
                numCol = i;
            }
        }

        String[] head = table.get(0);
        boolean hasHeader = head.length <= numCol
                || (normalizeNumber(head[numCol]) == null && !isDamagedScientific(head[numCol]));
        if (hasHeader) {
            for (int i = 0; i < head.length; i++) {
                String h = head[i].trim().toLowerCase(Locale.US);
                boolean phoneWord = h.contains("phone") || h.contains("mobile") || h.equals("mob")
                        || h.contains("mob.") || h.contains("cell") || h.contains("whatsapp")
                        || h.contains("contact no") || h.contains("contact number");
                if (phoneWord && valid[i] > 0) {
                    numCol = i;
                    break;
                }
            }
        }

        int nameCol = -1;
        if (hasHeader) {
            for (int i = 0; i < head.length; i++) {
                if (i != numCol && head[i].trim().toLowerCase(Locale.US).contains("name")) {
                    nameCol = i;
                    break;
                }
            }
        }
        int first = hasHeader ? 1 : 0;
        if (nameCol < 0) {
            for (int i = 0; i < cols && nameCol < 0; i++) {
                if (i == numCol) continue;
                int text = 0, filled = 0;
                for (int k = first; k < table.size(); k++) {
                    String[] row = table.get(k);
                    if (row.length > i && row[i].trim().length() > 0) {
                        filled++;
                        if (!row[i].trim().matches("[0-9+\\-. ()/:,]*")) text++;
                    }
                }
                if (filled > 0 && text * 2 > filled) nameCol = i;
            }
        }

        HashSet<String> seen = new HashSet<>();
        for (int k = first; k < table.size(); k++) {
            String[] row = table.get(k);
            String rawNum = row.length > numCol ? row[numCol] : "";
            String num = normalizeNumber(rawNum);
            if (num == null) {
                if (isDamagedScientific(rawNum)) r.damaged++;
                else if (rawNum.trim().length() > 0) r.invalid++;
                continue;
            }
            if (!seen.add(key(num))) {
                r.duplicates++;
                continue;
            }
            String name = nameCol >= 0 && row.length > nameCol ? row[nameCol].trim() : "";
            r.rows.add(new String[]{name, num});
        }
        r.numberColumn = hasHeader && head.length > numCol && head[numCol].trim().length() > 0
                ? head[numCol].trim() : "column " + (numCol + 1);
        r.nameColumn = nameCol < 0 ? ""
                : (hasHeader && head.length > nameCol && head[nameCol].trim().length() > 0
                ? head[nameCol].trim() : "column " + (nameCol + 1));
        return r;
    }

    // ---- writing ------------------------------------------------------------------------

    static String cell(String s) {
        if (s == null) return "";
        // keep spreadsheet apps from running a cell that starts with = or @ as a formula
        if (s.length() > 0 && (s.charAt(0) == '=' || s.charAt(0) == '@')) s = "'" + s;
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0
                || s.indexOf('\r') >= 0;
        return quote ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    static void line(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells[i]));
        }
        sb.append("\r\n");
    }
}
