package io.freemytv;

/** The complete write allowlist for the home button. No caller-supplied setting names or values. */
final class DebugPlan {
    private static final String[] KEYS = {"development_settings_enabled", "adb_enabled"};
    interface Store {
        String read(String key) throws Exception;
        void write(String key, String value) throws Exception;
    }
    static final class Failure extends Exception {
        final boolean restored;
        Failure(boolean restored) { super("Debugging switches were not verified"); this.restored = restored; }
    }
    static void enable(Store store) throws Failure {
        set(store, true);
    }

    static void disable(Store store) throws Failure {
        set(store, false);
    }

    private static void set(Store store, boolean on) throws Failure {
        String[] before = new String[KEYS.length];
        boolean[] attempted = new boolean[KEYS.length];
        int[] order = on ? new int[] {0, 1} : new int[] {1, 0};
        String wanted = on ? "1" : "0";
        try {
            for (int i = 0; i < KEYS.length; i++) {
                before[i] = store.read(KEYS[i]);
                if (before[i] != null && !before[i].equals("0") && !before[i].equals("1")) throw new Exception();
            }
            for (int i : order) if (!wanted.equals(before[i])) {
                attempted[i] = true;
                store.write(KEYS[i], wanted);
                if (!wanted.equals(store.read(KEYS[i]))) throw new Exception();
            }
            for (String key : KEYS) if (!wanted.equals(store.read(key))) throw new Exception();
        } catch (Exception error) {
            boolean restored = true;
            for (int at = order.length - 1; at >= 0; at--) {
                int i = order[at];
                if (!attempted[i]) continue;
                try {
                    // Do not overwrite a later user/system change away from the value we attempted.
                    if (wanted.equals(store.read(KEYS[i]))) store.write(KEYS[i], before[i]);
                } catch (Exception ignored) { restored = false; }
            }
            throw new Failure(restored);
        }
    }

    static boolean enabled(Store store) throws Exception {
        for (String key : KEYS) if (!"1".equals(store.read(key))) return false;
        return true;
    }
}
