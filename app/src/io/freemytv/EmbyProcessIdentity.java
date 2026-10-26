package io.freemytv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only explicit WindowManager session identities, never task or memory values. */
final class EmbyProcessIdentity {
    private static final String PACKAGE = "tv.emby.embyatv";
    private static final String CALLER_PACKAGE = "io.freemytv";
    private static final String TITLE = "WINDOW MANAGER SESSIONS (dumpsys window sessions)";
    private static final int MAX_BYTES = 256 * 1024;
    private static final Pattern SESSION = Pattern.compile(
            "^Session Session\\{([0-9a-fA-F]{1,64}) ([1-9][0-9]{0,9}):([^\\s{}:]{1,32})\\}:$");
    private static final Pattern UID = Pattern.compile("^u(0|[1-9][0-9]{0,4})([asi])(0|[1-9][0-9]{0,4})$");
    private static final Pattern PKG = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*$");

    private static final class Entry {
        final int pid;
        final Uid uid;
        String pkg;
        Boolean dead;
        Entry(int pid, Uid uid) { this.pid = pid; this.uid = uid; }
    }

    private static final class Uid {
        final int standard, raw;
        Uid(int standard, int raw) { this.standard = standard; this.raw = raw; }
        int value(int format) { return format == 1 ? standard : raw; }
        int matches(int expected) {
            return (standard == expected ? 1 : 0) | (raw == expected ? 2 : 0);
        }
    }

    /** Zero means a complete valid sessions table contains no native Emby session. */
    static int parse(String dump, int expectedNativeUid, int callerUid) throws IOException {
        if (!primaryApp(expectedNativeUid) || !primaryApp(callerUid) || expectedNativeUid == callerUid)
            throw invalid("Distinct primary-user application UIDs required");
        if (dump == null || dump.length() > MAX_BYTES
                || dump.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw invalid("Invalid process dump size");
        if (dump.indexOf('\u0000') >= 0) throw invalid("Invalid process dump text");
        BufferedReader reader = new BufferedReader(new StringReader(dump));
        HashSet<String> identities = new HashSet<>();
        ArrayList<Entry> entries = new ArrayList<>();
        boolean header = false;
        Entry entry = null;
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("permission denial") || lower.contains("permission denied")
                    || lower.contains("securityexception")) throw invalid("Process dump permission denied");
            if (TITLE.equals(line)) {
                if (header) throw invalid("Repeated sessions header");
                header = true;
                continue;
            }
            if (!header) throw invalid("Missing sessions header");
            if (line.startsWith("Session")) {
                finish(entry, entries);
                Matcher match = SESSION.matcher(line);
                if (!match.matches()) throw invalid("Invalid session identity");
                if (identities.size() >= 2048 || !identities.add(match.group(1).toLowerCase(Locale.ROOT)))
                    throw invalid("Repeated or excessive session identity");
                entry = new Entry(positive(match.group(2)), uid(match.group(3)));
                continue;
            }
            if (entry == null) throw invalid("Session fields have no identity");
            for (String token : line.split("\\s+")) {
                if (token.startsWith("mPackageName=")) {
                    if (entry.pkg != null) throw invalid("Repeated session package");
                    String pkg = token.substring("mPackageName=".length());
                    if (pkg.length() > 255 || !PKG.matcher(pkg).matches()) throw invalid("Invalid session package");
                    entry.pkg = pkg;
                } else if (token.startsWith("mClientDead=")) {
                    if (entry.dead != null) throw invalid("Repeated session liveness");
                    String value = token.substring("mClientDead=".length());
                    if (!"true".equals(value) && !"false".equals(value)) throw invalid("Invalid session liveness");
                    entry.dead = Boolean.valueOf(value);
                }
            }
        }
        if (!header) throw invalid("Missing sessions header");
        finish(entry, entries);

        int formats = 3, ownFormats = 3;
        boolean own = false, nativeSession = false;
        for (Entry e : entries) {
            if (e.uid.standard < 0) formats &= ~1;
            if (e.uid.raw < 0) formats &= ~2;
            if (CALLER_PACKAGE.equals(e.pkg)) {
                own = true;
                ownFormats &= e.uid.matches(callerUid);
            }
            if (PACKAGE.equals(e.pkg)) nativeSession = true;
            else if (e.pkg.toLowerCase(Locale.ROOT).startsWith(PACKAGE))
                throw invalid("Foreign native session identity");
        }
        if (formats == 0 || ownFormats == 0) throw invalid("Inconsistent session UID format");
        // The caller is the format witness: never guess from the native UID alone.
        if (!own || (ownFormats != 1 && ownFormats != 2)) {
            if (!nativeSession) return 0;
            throw invalid("Unverified session UID format");
        }
        if ((formats & ownFormats) == 0) throw invalid("Inconsistent session UID format");
        formats = ownFormats;
        int found = 0;
        for (Entry e : entries) {
            int value = e.uid.value(formats);
            if (!PACKAGE.equals(e.pkg)) {
                if (value == expectedNativeUid || (value == callerUid && !CALLER_PACKAGE.equals(e.pkg)))
                    throw invalid("Foreign session UID identity");
                continue;
            }
            if (value != expectedNativeUid || e.dead) throw invalid("Native session UID or liveness mismatch");
            if (found != 0 && found != e.pid) throw invalid("Multiple native process identities");
            found = e.pid;
        }
        return found;
    }

    private static void finish(Entry entry, ArrayList<Entry> entries) throws IOException {
        if (entry == null) return;
        if (entry.pkg == null || entry.dead == null) throw invalid("Incomplete session identity");
        entries.add(entry);
    }

    private static boolean primaryApp(int uid) { return uid >= 10000 && uid < 100000; }

    private static Uid uid(String token) throws IOException {
        if (token.matches("0|[1-9][0-9]{0,9}")) {
            int value = nonnegative(token);
            return new Uid(value, value);
        }
        Matcher match = UID.matcher(token);
        if (!match.matches()) throw invalid("Invalid session UID");
        int user = nonnegative(match.group(1)), id = nonnegative(match.group(3));
        char kind = match.group(2).charAt(0);
        if (kind == 'a') {
            int standard = id < 90000 ? combined(user, id + 10000) : -1;
            int raw = id >= 10000 ? combined(user, id) : -1;
            if (standard < 0 && raw < 0) throw invalid("Invalid application UID");
            return new Uid(standard, raw);
        } else if (kind == 'i') {
            if (id >= 1000) throw invalid("Invalid isolated UID");
            id += 99000;
        } else if (id >= 10000) throw invalid("Invalid system UID");
        int value = combined(user, id);
        if (value < 0) throw invalid("Session UID exceeds limits");
        return new Uid(value, value);
    }

    private static int combined(int user, int id) {
        long value = (long) user * 100000L + id;
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    private static int positive(String value) throws IOException {
        int n = nonnegative(value);
        if (n == 0) throw invalid("Invalid session PID");
        return n;
    }
    private static int nonnegative(String value) throws IOException {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw invalid("Numeric identity exceeds limits"); }
    }
    private static IOException invalid(String message) { return new IOException(message); }
}
