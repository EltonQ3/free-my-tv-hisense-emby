package io.freemytv;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.JSONObject;

/** User-granted DUMP reads native task/process identities only. No shell privilege or saved task ids. */
final class EmbyTvTasks {
    static volatile String lastStatus = "{}";
    private EmbyTvTasks() { }
    static boolean permitted(Context c) {
        return c.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.REORDER_TASKS) == PackageManager.PERMISSION_GRANTED;
    }
    static volatile String processStatus = "{}";
    static int processId(Context c) throws Exception {
        try {
            int uid = c.getPackageManager().getApplicationInfo(EmbyNativePlayer.TV, 0).uid;
            String dump = read(c, "window", "sessions");
            processStatus = new JSONObject().put("state", "read").put("uid", uid).put("length", dump.length())
                    .put("header", dump.contains("WINDOW MANAGER SESSIONS")).toString();
            int pid = EmbyProcessIdentity.parse(dump, uid, Process.myUid());
            processStatus = new JSONObject().put("pid", pid).toString();
            return pid;
        } catch (IOException e) {
            String reason = e.getMessage();
            if (reason == null || !reason.matches("[A-Za-z ][A-Za-z ]{1,80}")) reason = "Unavailable";
            processStatus = new JSONObject(processStatus).put("error", reason).toString();
            throw e;
        }
    }
    static void untrackedStartup(Context c) {
        int boot = android.provider.Settings.Global.getInt(c.getContentResolver(), "boot_count", -1);
        c.getSharedPreferences("emby-direct-state", Context.MODE_PRIVATE).edit().putInt("untrackedBoot", boot).apply();
    }
    static boolean directAllowed(Context c, int pid, boolean hasTask) {
        android.content.SharedPreferences p = c.getSharedPreferences("emby-direct-state", Context.MODE_PRIVATE);
        int boot = android.provider.Settings.Global.getInt(c.getContentResolver(), "boot_count", -1);
        if (pid == 0 && !hasTask) p.edit().remove("untrackedBoot").apply();
        else if (p.getInt("untrackedBoot", -2) == boot) return false;
        if (pid > 0 && !hasTask) p.edit().putInt("unsafePid", pid).putInt("boot", boot).apply();
        return pid == 0 || p.getInt("unsafePid", -1) != pid || p.getInt("boot", -2) != boot;
    }
    static EmbyTaskTree.Task find(Context c) throws Exception {
        EmbyTaskTree.Task task = EmbyTaskTree.find(read(c, "window", "containers"), 0);
        JSONObject status = new JSONObject().put("state", task == null ? "missing" : "found");
        if (task != null) status.put("task", task.id).put("top", task.topActivity).put("players", task.playbackPages);
        lastStatus = status.toString();
        return task;
    }
    private static String read(Context c, String service, String argument) throws Exception {
        if (!permitted(c)) { lastStatus = "{\"state\":\"permission-required\"}"; throw new IOException("Task permission unavailable"); }
        if (!Process.myUserHandle().equals(android.os.UserHandle.getUserHandleForUid(0)))
            throw new IOException("Unsupported TV user");
        java.lang.Process process = new ProcessBuilder("/system/bin/dumpsys", service, argument)
                .redirectErrorStream(true).start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<String> result = reader.submit(() -> {
            try (InputStream in = process.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[4096]; int n;
                while ((n = in.read(bytes)) >= 0) {
                    if (out.size() + n > 256 * 1024) throw new IOException("Task information too large");
                    out.write(bytes, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        });
        try {
            String containers = result.get(3500, TimeUnit.MILLISECONDS);
            if (!process.waitFor(500, TimeUnit.MILLISECONDS) || process.exitValue() != 0)
                throw new IOException("Task information unavailable");
            if (containers.contains("Permission Denial") || containers.contains("Permission denied"))
                throw new IOException("Task information denied");
            return containers;
        } finally {
            process.destroyForcibly(); result.cancel(true); reader.shutdownNow();
        }
    }
    static void restore(Activity c, EmbyTaskTree.Task task, long readAt) throws Exception {
        if (!permitted(c) || task == null || task.id <= 0 || SystemClock.elapsedRealtime() - readAt > 1500
                || !EmbyNativePlayer.TV.equals(EmbyNativePlayer.preferred(c)) || !c.hasWindowFocus())
            throw new IOException("Native task changed");
        c.getSystemService(ActivityManager.class).moveTaskToFront(task.id, 0);
    }
}
