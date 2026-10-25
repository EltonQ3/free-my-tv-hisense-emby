package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Shell-only provisioning. The intent carries a one-use loopback address, never credentials. */
public final class EmbyConfigReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                if ("io.freemytv.EMBY_STATUS".equals(intent.getAction())) {
                    JSONObject status = Recs.status(context);
                    status.put("configured", new File(context.getFilesDir(), "emby-connection.json").isFile());
                    status.put("homeSource", HomeSource.status(context));
                    result.setResultData(status.toString());
                    result.setResultCode(-1);
                    return;
                }
                if (!"io.freemytv.EMBY_CONFIGURE".equals(intent.getAction())) throw new IOException();
                URL url = new URL(intent.getStringExtra("config_url"));
                if (!"http".equals(url.getProtocol()) || !"127.0.0.1".equals(url.getHost())
                        || url.getUserInfo() != null || url.getPort() < 1024 || url.getQuery() != null
                        || url.getRef() != null || url.getPath().length() < 24) throw new IOException();
                HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                JSONObject config;
                try {
                    if (connection.getResponseCode() != 200) throw new IOException();
                    try (InputStream in = connection.getInputStream()) { config = new JSONObject(read(in, 8192)); }
                } finally { connection.disconnect(); }
                EmbyRecs.saveConfig(context, config);
                result.setResultData("Emby connection saved privately");
                result.setResultCode(-1);
            } catch (Exception e) {
                // Do not disclose URLs, tokens or response bodies in broadcast results or logs.
                result.setResultData("Emby operation failed");
                result.setResultCode(0);
            } finally { result.finish(); }
        }, "EmbyProvision").start();
    }

    private static String read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > limit) throw new IOException();
            out.write(buffer, 0, n);
        }
        return out.toString("UTF-8");
    }
}
