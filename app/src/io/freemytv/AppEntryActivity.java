package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONObject;

/** The app icon returns to our desktop on UXS; its settings remain available from the home gear. */
public final class AppEntryActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        JSONObject mode = HomeMode.state(this);
        boolean home = HomeMode.keepHisenseApps() && HomeMode.FREE.equals(mode.optString("mode"))
                && !mode.optBoolean("homeReverted");
        // Some firmware disables package-replaced receivers. Normal app entry can complete the one-time migration.
        if (home && !mode.optBoolean("hisenseDualHome")) HomeMode.verify(this);
        startActivity(new Intent(this, home ? HomeActivity.class : SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        finish();
    }
}
