package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Private TMDB configuration shared by explicit tests and the selected recommendation provider. */
final class TmdbSettings {
    private static final String ALIAS = "io.freemytv.tmdb.settings.v1";
    static volatile int version;
    static volatile String diagnostic = "not_tested";
    private TmdbSettings() { }
    static long stamp(Context c) {
        return 31L * prefs(c).getString("cipher", Config.TMDB_KEY).hashCode() + region(c).hashCode();
    }
    private static void changed() { version++; Prefs.version++; }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("tmdb-settings", Context.MODE_PRIVATE); }
    static boolean configured(Context c) { return !prefs(c).getString("cipher", "").isEmpty() || !Config.TMDB_KEY.trim().isEmpty(); }
    static String region(Context c) {
        String region = prefs(c).getString("region", Config.TMDB_REGION).toUpperCase(Locale.ROOT);
        return validRegion(region) ? region : "HK";
    }
    static boolean validRegion(String code) { return Arrays.asList(Locale.getISOCountries()).contains(code); }
    static void region(Context c, String region) {
        if (!validRegion(region)) throw new IllegalArgumentException("Invalid region");
        prefs(c).edit().putString("region", region).apply(); changed();
    }
    static boolean validKey(String value) {
        return value != null && value.length() >= 16 && value.length() <= 4096 && value.matches("[A-Za-z0-9._=-]+");
    }
    private static SecretKey secret() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey)store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    static synchronized void saveKey(Context c, String key) throws Exception {
        if (!validKey(key)) throw new IllegalArgumentException("Invalid API key");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secret());
        byte[] bytes = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
        boolean saved = prefs(c).edit().putString("cipher", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).remove("tested").commit();
        if (!saved) throw new java.io.IOException("Could not save TMDB configuration");
        changed();
    }
    static synchronized String key(Context c) throws Exception {
        String value = prefs(c).getString("cipher", "");
        if (value.isEmpty()) return Config.TMDB_KEY.trim();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Base64.decode(prefs(c).getString("iv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    /** One-time migration from the user's own previous local configuration, delivered directly into app-private storage. */
    static void importPending(Context c) {
        File pending = new File(c.getFilesDir(), "tmdb-settings-import.json");
        if (!pending.exists()) return;
        try (InputStream in = new FileInputStream(pending)) {
            JSONObject data = new JSONObject(read(in, 8192));
            saveKey(c, data.getString("key").trim());
            String region = data.optString("region", "HK").toUpperCase(Locale.ROOT);
            if (validRegion(region)) region(c, region);
        } catch (Exception e) { AppLog.w("TMDB configuration import failed"); }
        finally { if (!pending.delete()) AppLog.w("TMDB temporary import could not be removed"); }
    }
    /** Fixed HTTPS origin, no redirects or URL logging. Returns only a user-safe result. */
    static String test(Context c) {
        HttpsURLConnection connection = null;
        try {
            diagnostic = "credentials";
            String key = key(c);
            if (key.isEmpty()) return Ui.tr("Not set");
            diagnostic = "network";
            boolean queryKey = key.matches("(?i)[a-f0-9]{32}");
            URL url = new URL("https://api.themoviedb.org/3/authentication" + (queryKey ? "?api_key=" + URLEncoder.encode(key, "UTF-8") : ""));
            connection = (HttpsURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(7000); connection.setReadTimeout(7000);
            connection.setRequestProperty("Accept", "application/json");
            if (!queryKey) connection.setRequestProperty("Authorization", "Bearer " + key);
            int code = connection.getResponseCode();
            diagnostic = "http_" + code;
            if (code == 401 || code == 403) return Ui.tr("API key rejected");
            if (code != 200) return Ui.tr("Connection failed");
            try (InputStream in = connection.getInputStream()) {
                return new JSONObject(read(in, 16384)).optBoolean("success") ? Ui.tr("Connection successful") : Ui.tr("Connection failed");
            }
        } catch (Exception e) { diagnostic += "_" + e.getClass().getSimpleName(); return Ui.tr("Connection failed"); }
        finally { if (connection != null) connection.disconnect(); }
    }
    private static String read(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[2048];
        for (int n; (n = in.read(buffer)) != -1;) {
            if (out.size() + n > limit) throw new java.io.IOException("Response too large");
            out.write(buffer, 0, n);
        }
        return out.toString("UTF-8");
    }
}
