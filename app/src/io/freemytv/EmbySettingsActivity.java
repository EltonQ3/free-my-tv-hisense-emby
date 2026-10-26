package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local-only configuration. Connection changes are committed only after validation and profile selection. */
public final class EmbySettingsActivity extends Activity implements Recs.Listener {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private JSONObject saved = new JSONObject();
    private EditText server, key;
    private TextView status;
    private LinearLayout rows;
    private Button profile, auto, delay, count, sync, accountRemote, player;
    private Click click;
    private boolean connecting;
    private String profileName = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Watchdog.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        click = new Click(this);
        try { saved = EmbyRecs.readConfig(this); } catch (Exception ignored) { }
        profileName = saved.optString("user_name");
        LinearLayout root = new LinearLayout(this);
        root.setPadding(dp(40), dp(32), dp(40), dp(24));
        root.setBackgroundColor(0xff202126);
        LinearLayout intro = new LinearLayout(this); intro.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Emby", 46); intro.addView(title);
        intro.addView(text(Ui.tr("RECOMMENDATIONS"), 18));
        status = text("", 17); status.setPadding(0, dp(24), dp(20), 0); intro.addView(status);
        root.addView(intro, new LinearLayout.LayoutParams(0, -1, .40f));
        ScrollView scroll = new ScrollView(this);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows); root.addView(scroll, new LinearLayout.LayoutParams(0, -1, .60f));
        server = field(Ui.tr("Server"), saved.optString("server_url"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        server.setHint("http://192.168.0.10:8096");
        key = field(Ui.tr("API Key"), "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setHint(saved.optString("api_key").isEmpty() ? Ui.tr("API Key") : Ui.tr("Keep current key"));
        profile = button(Ui.tr("Account"), this::connect);
        button(Ui.tr("Connect"), this::connect);
        player = button("", this::choosePlayer);
        auto = button("", () -> { Prefs.setEmbyAutoTabs(!Prefs.embyAutoTabs()); values(); });
        delay = button("", () -> choose(Ui.tr("Idle seconds"), "embyIdleSeconds", Prefs.embyIdleSeconds(), new int[] {5,10,15,30}));
        count = button("", () -> choose(Ui.tr("Poster count"), "embyPosterCount", Prefs.embyPosterCount(), new int[] {5,8,10}));
        sync = button("", () -> choose(Ui.tr("Sync minutes"), "embySyncMinutes", Prefs.embySyncMinutes(), new int[] {15,30,60}));
        accountRemote = button("", this::accountControl);
        button(Ui.tr("Sync player account"), () -> startActivity(new android.content.Intent(this, EmbyStartActivity.class).putExtra("accountOnly", true)));
        button(Ui.tr("Refresh now"), () -> { Recs.get(this).invalidate(); Recs.get(this).refreshIfStale(); status.setText(Ui.tr("Connecting…")); });
        values(); setContentView(root);
        if (EmbyRecs.configured(this)) io.execute(() -> {
            try {
                JSONObject user = new JSONObject(EmbyRecs.request(this, "/Users/" + saved.getString("user_id")));
                String name = user.optString("Name");
                runOnUiThread(() -> { if (!isFinishing()) { profileName = name; values(); } });
            } catch (Exception ignored) { }
        });
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(Color.WHITE); return v;
    }
    private EditText field(String label, String value, int inputType) {
        rows.addView(text(label, 16));
        EditText v = new EditText(this); v.setText(value); v.setTextSize(18); v.setTextColor(Color.WHITE);
        v.setHintTextColor(0xffa5a5aa); v.setInputType(inputType); v.setSingleLine(true); v.setSoundEffectsEnabled(false);
        v.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        v.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        rows.addView(v, new LinearLayout.LayoutParams(-1, dp(54))); return v;
    }
    private Button button(String name, Runnable action) {
        Button b = new Button(this); b.setText(name); b.setTextSize(17); b.setAllCaps(false); b.setSoundEffectsEnabled(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54)); p.bottomMargin = dp(5);
        rows.addView(b, p); b.setOnClickListener(v -> { click.open(); action.run(); }); return b;
    }
    private void values() {
        auto.setText(Ui.tr("Auto-switch tabs") + " · " + (Prefs.embyAutoTabs() ? Ui.tr("On") : Ui.tr("Off")));
        delay.setText(Ui.tr("Idle seconds") + " · " + Prefs.embyIdleSeconds());
        count.setText(Ui.tr("Poster count") + " · " + Prefs.embyPosterCount());
        sync.setText(Ui.tr("Sync minutes") + " · " + Prefs.embySyncMinutes());
        profile.setText(Ui.tr("Account") + " · " + (profileName.isEmpty() ? "—" : profileName));
        player.setText(Ui.tr("Playback app") + " · " + EmbyNativePlayer.label(EmbyNativePlayer.preferred(this)));
        accountRemote.setText(Ui.tr("Auto-switch Emby account") + " · " + (!Prefs.embyAutoAccount() ? Ui.tr("Off")
                : EmbyAccountService.connected() ? Ui.tr("On") : Ui.tr("Permission needed")));
    }
    private void choosePlayer() {
        java.util.List<String> packages = EmbyNativePlayer.installed(this);
        if (packages.isEmpty()) return;
        String[] names = new String[packages.size()]; int selected = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = EmbyNativePlayer.label(packages.get(i));
            if (packages.get(i).equals(EmbyNativePlayer.preferred(this))) selected = i;
        }
        AlertDialog d = new AlertDialog.Builder(this).setTitle(Ui.tr("Playback app"))
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    click.open(); Prefs.setEmbyPlayer(packages.get(which));
                    Recs.get(this).invalidate(); values(); dialog.dismiss();
                }).setNegativeButton(Ui.tr("Cancel"), null).create();
        show(d);
    }
    private void accountControl() {
        if (Prefs.embyAutoAccount() && EmbyAccountService.connected()) {
            Prefs.setEmbyAutoAccount(false); values(); return;
        }
        Prefs.setEmbyAutoAccount(true); values();
        if (EmbyAccountService.connected()) return;
        AlertDialog d = new AlertDialog.Builder(this).setTitle(Ui.tr("Auto-switch Emby account"))
                .setMessage(Ui.tr("Only controls Emby after you open a home poster. Enable Emby Account Remote in the next screen. Passwords and PINs stay manual."))
                .setPositiveButton(Ui.tr("Settings"), (dialog, which) -> {
                    click.open();
                    try { startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (Exception e) { status.setText(Ui.tr("Couldn't open TV Settings")); }
                }).setNegativeButton(Ui.tr("Cancel"), null).create();
        show(d); d.getButton(AlertDialog.BUTTON_POSITIVE).setSoundEffectsEnabled(false);
    }
    private void choose(String title, String preference, int current, int[] choices) {
        String[] labels = new String[choices.length]; int selected = 0;
        for (int i = 0; i < choices.length; i++) { labels[i] = "" + choices[i]; if (choices[i] == current) selected = i; }
        AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, selected, (dialog, which) -> {
            click.open(); Prefs.setEmbyNumber(preference, choices[which]); values(); dialog.dismiss();
        }).setNegativeButton(Ui.tr("Cancel"), null).create();
        show(d);
    }
    private void show(AlertDialog d) {
        d.setOnKeyListener((dialog, code, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT) click.move();
            return false;
        });
        d.setOnCancelListener(dialog -> click.back()); d.show();
        if (d.getListView() != null) d.getListView().setSoundEffectsEnabled(false);
        if (d.getButton(AlertDialog.BUTTON_NEGATIVE) != null) d.getButton(AlertDialog.BUTTON_NEGATIVE).setSoundEffectsEnabled(false);
    }
    private void connect() {
        if (connecting) return;
        final String address = server.getText().toString().trim();
        String typed = key.getText().toString().trim();
        if (typed.isEmpty() && !address.replaceAll("/+$", "").equals(saved.optString("server_url").replaceAll("/+$", ""))) {
            status.setText(Ui.tr("Enter a new key when changing servers")); return;
        }
        final String token = typed.isEmpty() ? saved.optString("api_key") : typed;
        connecting = true; status.setText(Ui.tr("Connecting…"));
        io.execute(() -> {
            try {
                String url = EmbyHttp.server(address);
                JSONObject info = new JSONObject(new String(EmbyHttp.get(url + "/System/Info", token, 1024 * 1024), StandardCharsets.UTF_8));
                JSONArray users = new JSONArray(new String(EmbyHttp.get(url + "/Users", token, 1024 * 1024), StandardCharsets.UTF_8));
                String[] names = new String[users.length()];
                for (int i = 0; i < names.length; i++) names[i] = users.getJSONObject(i).optString("Name");
                if (names.length == 0 || !EmbyRecs.validId(info.optString("Id"))) throw new Exception();
                runOnUiThread(() -> {
                    connecting = false;
                    if (isFinishing()) return;
                    AlertDialog d = new AlertDialog.Builder(this).setTitle(Ui.tr("Account")).setItems(names, (dialog, which) -> {
                        try {
                            JSONObject cfg = new JSONObject().put("server_url", url).put("api_key", token)
                                    .put("server_id", info.getString("Id")).put("user_id", users.getJSONObject(which).getString("Id"))
                                    .put("user_name", names[which]).put("device_id", saved.optString("device_id", UUID.randomUUID().toString()));
                            EmbyRecs.saveConfig(this, cfg); saved = cfg; profileName = names[which]; key.setText(""); key.setHint(Ui.tr("Keep current key"));
                            server.setText(url); Prefs.version++; Recs.get(this).connectionChanged();
                            values(); status.setText(Ui.tr("Saved")); click.open();
                        } catch (Exception e) { status.setText(Ui.tr("Connection failed")); }
                    }).setNegativeButton(Ui.tr("Cancel"), null).create();
                    show(d);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { connecting = false; if (!isFinishing()) status.setText(Ui.tr("Connection failed")); });
            }
        });
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT) click.move();
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() { click.back(); super.onBackPressed(); }
    @Override public void onResume() { super.onResume(); values(); Recs.get(this).addListener(this); onRecs(); }
    @Override public void onPause() { Recs.get(this).removeListener(this); super.onPause(); }
    @Override public void onRecs() {
        JSONObject info = Recs.status(this);
        status.setText(!info.optString("error").isEmpty() ? Ui.tr("Connection failed")
                : info.optInt("count") > 0 ? "✓ Emby · " + info.optInt("count") : "");
    }
    @Override public void onDestroy() {
        io.shutdownNow();
        Click finished = click; if (finished != null) new Handler(Looper.getMainLooper()).postDelayed(finished::release, 1000);
        super.onDestroy();
    }
}
