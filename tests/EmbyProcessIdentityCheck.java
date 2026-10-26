package io.freemytv;

import java.io.IOException;
import java.util.Arrays;

public final class EmbyProcessIdentityCheck {
    private static int checks;
    private static final int UID = 10085, CALLER_UID = 10072;
    private static final String TITLE = "WINDOW MANAGER SESSIONS (dumpsys window sessions)\n";
    private static String session(String hash, String pid, String uid, String pkg, String dead) {
        return "  Session Session{" + hash + " " + pid + ":" + uid + "}:\n"
                + "    mNumWindow=0 mCanAddInternalSystemWindow=false mClientDead=" + dead + "\n"
                + "    mPackageName=" + pkg + "\n";
    }
    private static String nativeSession(String hash, int pid) {
        return session(hash, Integer.toString(pid), "u0a10085", "tv.emby.embyatv", "false");
    }
    private static String ownSession(String hash, String uid) {
        return session(hash, "4210", uid, "io.freemytv", "false");
    }
    private static final String OWN = ownSession("aaa001", "u0a10072");
    private static int parse(String dump) throws Exception {
        return EmbyProcessIdentity.parse(dump == null ? null : dump + "\n" + OWN, UID, CALLER_UID);
    }
    private static void check(boolean okay, String why) {
        checks++;
        if (!okay) throw new AssertionError(why);
    }
    private static String repeat(char value, int count) {
        char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars);
    }
    private static void refusesExact(String dump, int nativeUid, int callerUid, String why) throws Exception {
        try { EmbyProcessIdentity.parse(dump, nativeUid, callerUid); throw new AssertionError(why); }
        catch (IOException expected) {
            check(!expected.getMessage().contains("foreign.private"), "error does not disclose foreign contents: " + why);
        }
    }
    private static void refusesExact(String dump, String why) throws Exception {
        refusesExact(dump, UID, CALLER_UID, why);
    }
    private static void refuses(String dump, String why) throws Exception {
        refusesExact(dump == null ? null : dump + "\n" + OWN, why);
    }
    public static void main(String[] args) throws Exception {
        String nativeRow = nativeSession("435e041", 2536);
        String system = session("aaaa", "1001", "1000", "android", "false");
        String foreign = session("bbbb", "9301", "u0a10031", "foreign.private", "false");
        check(parse(TITLE + nativeRow) == 2536, "Hisense raw appId is calibrated by the caller's known UID");
        check(parse(TITLE + system + nativeRow + foreign) == 2536, "exact package and UID survive unrelated sessions");
        check(parse(TITLE + nativeRow.replace("u0a10085", "10085")) == 2536, "numeric native UID is exact after caller calibration");
        check(parse((TITLE + nativeRow).replace("\n", "\r\n")) == 2536, "CRLF input is supported");
        String standard = TITLE + ownSession("abc001", "u0a72") + nativeRow.replace("u0a10085", "u0a85");
        check(EmbyProcessIdentity.parse(standard, UID, CALLER_UID) == 2536, "standard app offset format is calibrated independently");
        check(EmbyProcessIdentity.parse(standard.replace("u0a85", "10085"), UID, CALLER_UID) == 2536,
                "numeric native UID is valid with a standard caller witness");
        check(EmbyProcessIdentity.parse(TITLE + nativeRow + OWN, UID, CALLER_UID) == 2536, "caller witness can follow native row");
        check(EmbyProcessIdentity.parse(TITLE + OWN + ownSession("abc002", "u0a10072") + nativeRow, UID, CALLER_UID) == 2536,
                "multiple caller sessions may consistently corroborate the raw format");
        check(EmbyProcessIdentity.parse(standard + ownSession("abc002", "u0a72"), UID, CALLER_UID) == 2536,
                "multiple caller sessions may consistently corroborate the standard format");
        check(EmbyProcessIdentity.parse(standard + ownSession("abc002", "10072"), UID, CALLER_UID) == 2536,
                "numeric caller session is neutral when a formatted caller already calibrates");
        refusesExact(TITLE + nativeRow, "native session requires a caller witness");
        refusesExact(TITLE + ownSession("abc001", "10072") + nativeRow, "numeric-only caller cannot distinguish formats");
        refusesExact(TITLE + ownSession("abc001", "10072") + nativeRow.replace("u0a10085", "u0a85"),
                "native UID cannot select a format on behalf of a neutral caller");
        refusesExact(TITLE + ownSession("abc001", "10072") + nativeRow
                + session("abc002", "9000", "u0a90000", "foreign.private", "false"),
                "foreign UID cannot select a format on behalf of a neutral caller");
        refusesExact(TITLE + OWN + ownSession("abc002", "u0a72") + nativeRow, "contradictory caller witnesses are rejected");
        refusesExact(TITLE + ownSession("abc001", "u0a10073") + nativeRow, "wrong raw caller UID is rejected");
        refusesExact(TITLE + ownSession("abc001", "u0a73") + nativeRow, "wrong standard caller UID is rejected");
        refusesExact(TITLE + OWN.replace("io.freemytv", "io.freemytv.evil") + nativeRow, "near-prefix caller package cannot calibrate");
        refusesExact(TITLE + OWN.replace("io.freemytv", "IO.FREEMYTV") + nativeRow, "caller package is case-sensitive");
        refusesExact(TITLE + OWN + nativeRow.replace("u0a10085", "u0a85"), "raw caller rejects standard native identity");
        refusesExact(standard.replace("u0a85", "u0a10085"), "standard caller rejects raw native identity");
        refusesExact(TITLE + OWN + nativeRow + session("abc002", "9000", "u0a31", "foreign.private", "false"),
                "raw caller rejects an impossible raw application UID elsewhere in the table");
        refusesExact(TITLE + OWN + nativeRow + session("abc002", "9000", "u0a10072", "foreign.private", "false"),
                "foreign package cannot claim caller UID");
        check(EmbyProcessIdentity.parse(TITLE, UID, CALLER_UID) == 0, "valid empty table needs no caller witness");
        check(EmbyProcessIdentity.parse(TITLE + system + foreign, UID, CALLER_UID) == 0, "complete foreign-only table needs no caller witness");
        check(EmbyProcessIdentity.parse(TITLE + ownSession("abc001", "10072"), UID, CALLER_UID) == 0,
                "numeric caller without native session is explicit native absence");
        check(parse(TITLE) == 0, "valid sessions table with caller only means native absence");
        check(parse(TITLE + foreign.replace("mClientDead=false", "mClientDead=true")) == 0,
                "dead foreign session does not invalidate native absence");
        check(parse(TITLE + foreign.replace("u0a10031", "u10a10031")) == 0, "valid foreign secondary-user UID is ignored");
        check(parse(TITLE + foreign.replace("u0a10031", "u0i31")) == 0, "valid foreign isolated UID is ignored");
        check(parse(TITLE + foreign.replace("u0a10031", "u1s1000")) == 0, "valid foreign formatted system UID is ignored");
        check(parse(TITLE + nativeRow + nativeSession("cccc", 2536)) == 2536, "two native sessions may corroborate one PID");
        refuses(TITLE + nativeRow + nativeSession("cccc", 2537), "different native PIDs are ambiguous");
        refuses(TITLE + nativeRow + nativeRow, "repeated exact session identity");
        refuses(TITLE + nativeRow.replace("mClientDead=false", "mClientDead=true"), "dead native session is unsafe");
        refuses(TITLE + nativeRow + nativeSession("cccc", 2536).replace("mClientDead=false", "mClientDead=true"),
                "live session cannot hide a dead native session");
        for (String uid : new String[]{"u0a85", "u1a10085", "1000", "20085", "u0a10086", "-10085", "u-1a10085", "u0a-1",
                "u0a90000", "u0i1000", "u1s10000", "2147483648", "u99999a85", "u00a10085", "u0a010085", "bad"})
            refuses(TITLE + nativeRow.replace("u0a10085", uid), "mismatched or malformed native UID: " + uid);
        for (String pid : new String[]{"0", "-1", "+2536", "02536", "2147483648", "99999999999", "2536x", "", "2 536", "２５３６"})
            refuses(TITLE + nativeRow.replace("2536:", pid + ":"), "malformed, zero, negative or overflow PID: " + pid);
        for (String pkg : new String[]{"tv.emby.embyatv.evil", "tv.emby.embyatv2", "tv.emby.embyatv:remote", "TV.EMBY.EMBYATV", "", "foreign.private"})
            refuses(TITLE + nativeRow.replace("tv.emby.embyatv", pkg), "foreign or spoof package cannot identify native PID: " + pkg);
        for (String value : new String[]{"", "FALSE", "null", "0", "unknown"})
            refuses(TITLE + nativeRow.replace("mClientDead=false", "mClientDead=" + value), "malformed liveness: " + value);
        refuses(TITLE + nativeRow.replace("    mPackageName=tv.emby.embyatv\n", ""), "missing package is not clear absence");
        refuses(TITLE + nativeRow.replace(" mClientDead=false", ""), "missing liveness is ambiguous");
        refuses(TITLE + nativeRow + "    mPackageName=tv.emby.embyatv\n", "duplicate package field");
        refuses(TITLE + nativeRow + "    mClientDead=false\n", "duplicate liveness field");
        refuses(nativeRow, "missing fixed sessions header");
        refuses(TITLE + TITLE + nativeRow, "repeated sessions header");
        refuses(TITLE + "mPackageName=tv.emby.embyatv\n", "package cannot supply missing session identity");
        refuses(TITLE + nativeRow.replace("Session Session{", "Session{"), "malformed session prefix");
        refuses(TITLE + nativeRow.replace("435e041", repeat('a', 65)), "oversize session token");
        refuses(TITLE + nativeRow.replace("}:\n", "}\n"), "malformed session terminator");
        for (String denied : new String[]{"Permission Denial: can't dump window", "permission denied", "java.lang.SecurityException: blocked"}) {
            refuses(denied, "permission failure without header");
            refuses(TITLE + nativeRow + denied, "permission failure after valid session");
        }
        refuses("** MEMINFO in pid 2536 [tv.emby.embyatv] **", "obsolete meminfo parser is gone");
        refuses("No process found for: tv.emby.embyatv", "obsolete meminfo absence is gone");
        refusesExact(null, "null output"); refusesExact("", "empty output"); refusesExact(" \r\n\t", "whitespace output");
        refuses(TITLE + nativeRow + "\u0000", "NUL cannot truncate identity inspection");
        int max = 256 * 1024;
        String bounded = TITLE + OWN + nativeRow;
        check(EmbyProcessIdentity.parse(bounded + repeat('x', max - bounded.length()), UID, CALLER_UID) == 2536,
                "exact 256 KiB output is accepted");
        refusesExact(bounded + repeat('x', max - bounded.length() + 1), "one byte above limit");
        refusesExact(bounded + repeat('\u4e2d', max / 3 + 1), "UTF-8 multibyte output observes byte cap");
        for (int uid : new int[]{-1, 0, 1000, 9999, 100000, 110085, Integer.MAX_VALUE}) {
            refusesExact(TITLE + OWN + nativeRow, uid, CALLER_UID, "native UID must be primary-user app UID");
            refusesExact(TITLE + OWN + nativeRow, UID, uid, "caller UID must be primary-user app UID");
        }
        refusesExact(TITLE + OWN + nativeRow, UID, UID, "caller and native must be distinct application identities");
        System.out.println("PASS: " + checks + " caller-calibrated WindowManager UID format, package/PID, liveness and bounded-output assertions.");
    }
}
