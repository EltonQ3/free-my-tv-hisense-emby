package io.freemytv;

/** Transaction policy for the single stock desktop. The adapter has no caller-supplied package or commands. */
final class StockHomePlan {
    interface Desktop {
        boolean disabled() throws Exception;
        void available(boolean available) throws Exception;
        void stop() throws Exception;
    }

    static void lock(Desktop desktop) throws Exception {
        boolean before = desktop.disabled();
        try {
            if (!before) desktop.available(false);
            if (!desktop.disabled()) throw new Exception("Stock desktop did not become disabled");
            desktop.stop();
            if (!desktop.disabled()) throw new Exception("Stock desktop did not stay disabled");
        } catch (Exception failure) {
            // A failed transaction must not leave a previously available desktop disabled when recovery is possible.
            if (!before) {
                try { desktop.available(true); }
                catch (Exception ignored) { }
            }
            throw failure;
        }
    }

    static void release(Desktop desktop) throws Exception {
        if (desktop.disabled()) desktop.available(true);
        if (desktop.disabled()) throw new Exception("Stock desktop did not become available");
    }
}
