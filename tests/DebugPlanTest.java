package io.freemytv;

import java.util.*;

public final class DebugPlanTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static final String DEV = "development_settings_enabled", USB = "adb_enabled", WIFI = "adb_wifi_enabled";
    private static final class Fake implements DebugPlan.Store {
        final Map<String, String> values = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        int failWrite = -1;
        boolean rejectUsb, failRollback, externalChange;
        Fake(String dev, String usb, String wifi) { values.put(DEV, dev); values.put(USB, usb); values.put(WIFI, wifi); }
        @Override public String read(String key) {
            check(key.equals(DEV) || key.equals(USB), "read allowlist excludes wireless and all other settings");
            if (externalChange && writes.size() == 2 && key.equals(USB)) {
                externalChange = false; values.put(DEV, "0"); values.put(WIFI, "0");
            }
            return values.get(key);
        }
        @Override public void write(String key, String value) throws Exception {
            check(key.equals(DEV) || key.equals(USB), "write allowlist excludes wireless and all other settings");
            writes.add(key);
            if (failRollback && "0".equals(value)) throw new Exception();
            if (!(rejectUsb && key.equals(USB) && "1".equals(value))) values.put(key, value);
            if (writes.size() == failWrite) throw new Exception();
        }
    }
    public static void main(String[] args) throws Exception {
        Fake both = new Fake("0", "0", "1"); DebugPlan.enable(both);
        check(both.writes.equals(Arrays.asList(DEV, USB)), "developer master is enabled before USB");
        check("1".equals(both.values.get(WIFI)), "wireless choice stays on when initially on");
        check(DebugPlan.enabled(both), "both minimum switches verified");
        Fake ready = new Fake("1", "1", "0"); DebugPlan.enable(ready);
        check(ready.writes.isEmpty(), "already-enabled operation is idempotent");
        check("0".equals(ready.values.get(WIFI)), "wireless choice stays off when initially off");
        Fake partial = new Fake("1", null, "0"); DebugPlan.enable(partial);
        check(partial.writes.equals(Collections.singletonList(USB)), "only the missing USB switch is written");
        Fake failed = new Fake("0", "0", "1"); failed.failWrite = 2;
        try { DebugPlan.enable(failed); throw new AssertionError(); }
        catch (DebugPlan.Failure e) { check(e.restored, "partial mutation followed by failure is rolled back"); }
        check("0".equals(failed.values.get(DEV)) && "0".equals(failed.values.get(USB)), "both original values restored");
        check("1".equals(failed.values.get(WIFI)), "rollback leaves wireless untouched");
        Fake absent = new Fake(null, "0", "1"); absent.failWrite = 2;
        try { DebugPlan.enable(absent); throw new AssertionError(); } catch (DebugPlan.Failure ignored) { }
        check(absent.values.get(DEV) == null, "unset developer value restored as unset");
        Fake rejected = new Fake("0", "0", "0"); rejected.rejectUsb = true;
        try { DebugPlan.enable(rejected); throw new AssertionError(); } catch (DebugPlan.Failure ignored) { }
        check("0".equals(rejected.values.get(DEV)), "readback rejection rolls back the developer switch");
        Fake invalid = new Fake("2", "0", "1");
        try { DebugPlan.enable(invalid); throw new AssertionError(); } catch (DebugPlan.Failure ignored) { }
        check(invalid.writes.isEmpty(), "unknown state aborts before any write");
        Fake concurrent = new Fake("0", "0", "1"); concurrent.externalChange = true;
        try { DebugPlan.enable(concurrent); throw new AssertionError(); } catch (DebugPlan.Failure ignored) { }
        check("0".equals(concurrent.values.get(DEV)) && "0".equals(concurrent.values.get(WIFI)), "later user changes are preserved");
        Fake rollback = new Fake("0", "0", "0"); rollback.failWrite = 2; rollback.failRollback = true;
        try { DebugPlan.enable(rollback); throw new AssertionError(); }
        catch (DebugPlan.Failure e) { check(!e.restored, "rollback failure is reported honestly"); }
        Fake close = new Fake("1", "1", "1"); DebugPlan.disable(close);
        check(close.writes.equals(Arrays.asList(USB, DEV)), "USB is closed before the developer master switch");
        check("0".equals(close.values.get(DEV)) && "0".equals(close.values.get(USB)), "both minimum switches close");
        check("1".equals(close.values.get(WIFI)), "Off never writes the independent wireless choice");
        Fake closed = new Fake("0", "0", "0"); DebugPlan.disable(closed);
        check(closed.writes.isEmpty(), "already-off operation is idempotent");
        Fake closeFailure = new Fake("1", "1", "1"); closeFailure.failWrite = 2;
        try { DebugPlan.disable(closeFailure); throw new AssertionError(); }
        catch (DebugPlan.Failure e) { check(e.restored, "failed close restores its previous state"); }
        check("1".equals(closeFailure.values.get(DEV)) && "1".equals(closeFailure.values.get(USB)), "failed close restores both switches");
        check(closeFailure.writes.equals(Arrays.asList(USB, DEV, DEV, USB)), "close rollback restores the master before USB");
        Fake closeAbsent = new Fake(null, "1", "0"); DebugPlan.disable(closeAbsent);
        check("0".equals(closeAbsent.values.get(DEV)) && "0".equals(closeAbsent.values.get(USB)), "missing master value can be explicitly closed");
        System.out.println("PASS: " + checks + " fixed debugging-switch assertions.");
    }
}
