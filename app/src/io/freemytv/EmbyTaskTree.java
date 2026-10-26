package io.freemytv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads identities only; dump text and unrelated component names never leave this parser. */
final class EmbyTaskTree {
    private static final String PACKAGE = "tv.emby.embyatv";
    private static final int MAX_DUMP = 2 * 1024 * 1024;
    private static final int MAX_LINE = 8192;
    private static final Pattern TASK = Pattern.compile("^Task=([0-9]{1,10})(?:\\s.*)?$");
    private static final Pattern RECORD = Pattern.compile(
            "^ActivityRecord\\{([0-9a-fA-F]{1,64}) u([0-9]{1,10}) "
            + "([^\\s{}]{1,768}) t([0-9]{1,10})( f\\}?)?( isExiting)?\\}(?:\\s.*)?$");
    private static final Pattern COMPONENT = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)/"
            + "(\\.?[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)$");

    static final class Task {
        final int id, playbackPages, activities;
        /** Fully qualified class name of the first (topmost) ActivityRecord. */
        final String topActivity;
        private Task(int id, int playbackPages, String topActivity, int activities) {
            this.id = id;
            this.playbackPages = playbackPages;
            this.topActivity = topActivity;
            this.activities = activities;
        }
    }

    private static final class Scope {
        final int id, indent, branch;
        Scope(int id, int indent, int branch) {
            this.id = id; this.indent = indent; this.branch = branch;
        }
    }

    private static final class Facts {
        final int id;
        int activities, playbackPages, branch = -1;
        boolean foreign, otherUser, finishing;
        String top;
        final HashSet<String> records = new HashSet<>();
        Facts(int id) { this.id = id; }
    }

    static Task find(String dump, int user) throws IOException {
        if (dump == null || dump.length() > MAX_DUMP || user < 0)
            throw invalid("Invalid task dump");
        ArrayDeque<Scope> scopes = new ArrayDeque<>();
        Map<Integer, Facts> tasks = new HashMap<>();
        BufferedReader reader = new BufferedReader(new StringReader(dump));
        String line;
        int lines = 0, branch = 0, records = 0;
        while ((line = reader.readLine()) != null) {
            if (++lines > 32768 || line.length() > MAX_LINE)
                throw invalid("Task dump exceeds limits");
            if (line.indexOf('\u0000') >= 0 || line.startsWith("Permission Denial:"))
                throw invalid("Task dump unavailable");
            if (line.trim().isEmpty()) continue;
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            String body = line.substring(indent);
            if (body.startsWith("\t")) throw invalid("Invalid task indentation");
            if (body.startsWith("Permission Denial:")) throw invalid("Task dump unavailable");
            while (!scopes.isEmpty() && indent <= scopes.peek().indent) scopes.pop();
            if (body.startsWith("#")) {
                int end = body.indexOf(' ');
                if (end <= 1 || end > 11) throw invalid("Invalid task row");
                number(body.substring(1, end));
                while (end < body.length() && body.charAt(end) == ' ') end++;
                body = body.substring(end);
            }
            if (body.startsWith("Task=")) {
                Matcher task = TASK.matcher(body);
                if (!task.matches() || indent > 256) throw invalid("Invalid task identity");
                scopes.push(new Scope(number(task.group(1)), indent, ++branch));
                continue;
            }
            if (!body.startsWith("ActivityRecord")) continue;
            if (indent > 256) throw invalid("Invalid activity indentation");
            if (++records > 4096) throw invalid("Too many activity identities");
            Matcher record = RECORD.matcher(body);
            if (!record.matches()) throw invalid("Invalid activity identity");
            int recordUser = number(record.group(2));
            int id = number(record.group(4));
            Scope scope = scopes.peek();
            if (scope != null && scope.id != id)
                throw invalid("Activity does not belong to its task");
            Matcher component = COMPONENT.matcher(record.group(3));
            if (!component.matches()) throw invalid("Invalid activity component");
            String pkg = component.group(1);
            String cls = component.group(2);
            if (pkg.length() > 255 || cls.length() > 512)
                throw invalid("Activity identity exceeds limits");
            Facts facts = tasks.get(id);
            if (facts == null) {
                if (tasks.size() >= 512) throw invalid("Too many task identities");
                facts = new Facts(id);
                tasks.put(id, facts);
            }
            if (recordUser != user) { facts.otherUser = true; continue; }
            if (!PACKAGE.equals(pkg)) { facts.foreign = true; continue; }
            if (cls.charAt(0) == '.') cls = pkg + cls;
            if (!cls.startsWith(PACKAGE + ".")) throw invalid("Invalid native activity class");
            if (facts.branch >= 0 && scope != null && facts.branch != scope.branch)
                throw invalid("Native task identity is repeated");
            if (scope != null) facts.branch = scope.branch;
            if (!facts.records.add(record.group(1).toLowerCase(java.util.Locale.ROOT)))
                throw invalid("Native activity identity is repeated");
            if (facts.top == null) facts.top = cls;
            facts.activities++;
            if (cls.endsWith(".PlaybackOverlayActivity")) facts.playbackPages++;
            facts.finishing |= record.group(5) != null || record.group(6) != null;
        }
        Facts found = null;
        for (Facts facts : tasks.values()) {
            if (facts.activities == 0) continue;
            if (facts.foreign || facts.otherUser || facts.finishing)
                throw invalid("Native task has mixed or finishing activities");
            if (found != null) throw invalid("Multiple native tasks found");
            found = facts;
        }
        return found == null ? null : new Task(found.id, found.playbackPages, found.top, found.activities);
    }

    private static int number(String value) throws IOException {
        if (value.isEmpty() || value.length() > 10) throw invalid("Invalid numeric task identity");
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) < '0' || value.charAt(i) > '9')
                throw invalid("Invalid numeric task identity");
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw invalid("Numeric task identity exceeds limits"); }
    }

    private static IOException invalid(String reason) { return new IOException(reason); }
}
