package io.freemytv;

public final class StockHomePlanTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static final class Desktop implements StockHomePlan.Desktop {
        boolean disabled, rejectDisable, failAfterDisable, rejectEnable, failStop, reenableOnStop;
        int enables, disables, stops;
        @Override public boolean disabled() { return disabled; }
        @Override public void available(boolean on) throws Exception {
            if (on) { enables++; if (!rejectEnable) disabled = false; }
            else { disables++; if (!rejectDisable) disabled = true; if (failAfterDisable) throw new Exception("lost acknowledgement"); }
        }
        @Override public void stop() throws Exception {
            stops++; if (reenableOnStop) disabled = false; if (failStop) throw new Exception("stop failed");
        }
    }
    private static boolean lockFails(Desktop d) { try { StockHomePlan.lock(d); return false; } catch (Exception e) { return true; } }
    public static void main(String[] args) throws Exception {
        Desktop d = new Desktop(); StockHomePlan.lock(d);
        check(d.disabled && d.disables == 1 && d.stops == 1, "blocking disables then force-stops stock");
        StockHomePlan.release(d);
        check(!d.disabled && d.enables == 1, "deliberate entry restores stock availability");
        StockHomePlan.release(d);
        check(d.enables == 1, "already available entry does not mutate package state");
        StockHomePlan.lock(d);
        check(d.disabled && d.disables == 2, "returning home blocks again after release");
        StockHomePlan.lock(d);
        check(d.disables == 2 && d.stops == 3, "an already blocked desktop needs no repeated disable");
        d = new Desktop(); d.rejectDisable = true;
        check(lockFails(d) && !d.disabled, "rejected disable reports failure");
        check(d.stops == 0, "never stop a package whose disabled state was not verified");
        d = new Desktop(); d.failAfterDisable = true;
        check(lockFails(d) && !d.disabled && d.enables == 1, "lost disable acknowledgement restores the previous available state");
        d = new Desktop(); d.failStop = true;
        check(lockFails(d) && !d.disabled && d.enables == 1, "failed force-stop rolls back an available desktop");
        d = new Desktop(); d.disabled = true; d.failStop = true;
        check(lockFails(d) && d.disabled && d.enables == 0, "failure never enables a desktop that was already disabled");
        d = new Desktop(); d.reenableOnStop = true;
        check(lockFails(d) && !d.disabled, "system reenabling is detected rather than reported as blocked");
        d = new Desktop(); d.disabled = true; d.rejectEnable = true;
        boolean failed = false; try { StockHomePlan.release(d); } catch (Exception e) { failed = true; }
        check(failed && d.disabled, "failed release cannot claim stock is available");
        check(d.stops == 0 && d.disables == 0, "release never stops or disables stock");
        d = new Desktop(); d.failStop = true; d.rejectEnable = true;
        check(lockFails(d) && d.disabled, "unrecoverable partial failure remains observable for persisted recovery");
        System.out.println("PASS: " + checks + " stock-home transaction assertions.");
    }
}
