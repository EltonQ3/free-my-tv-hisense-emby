package io.freemytv;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** One quiet summary in the main menu; full messages live in this separately expandable paper window. */
final class SettingsNotices {
    static final class Entry {
        final long at; final String text;
        Entry(long at, String text) { this.at = at; this.text = text; }
    }
    private SettingsNotices() { }
    static List<Entry> entries(JSONObject state) {
        List<Entry> list = new ArrayList<>(); Set<String> seen = new HashSet<>();
        JSONArray notices = state.optJSONArray("notices");
        if (notices != null) for (int i = 0; i < notices.length(); i++) {
            JSONObject notice = notices.optJSONObject(i); if (notice == null) continue;
            String text = notice.optString("text").trim();
            if (!text.isEmpty() && seen.add(text)) list.add(new Entry(notice.optLong("at"), text));
        }
        String error = state.optString("error").trim();
        if (list.isEmpty() && !error.isEmpty()) list.add(new Entry(0, error));
        return list;
    }
    static String summary(Entry entry) {
        if (entry == null) return Ui.tr("None yet.");
        if (entry.text.contains("5555") || entry.text.contains("127.0.0.1")) return Ui.tr("Home screen switch needs attention");
        return entry.text.replace('\n', ' ').replace('\r', ' ');
    }
    static String age(long at) {
        if (at <= 0) return "";
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60000);
        if (minutes < 1) return Ui.tr("now");
        if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
        if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
        return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
    }
    static Dialog show(Activity activity, JSONObject state, Click click, float scale, int width, int height) {
        final SettingsTheme art = SettingsTheme.current();
        Dialog dialog = new Dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL);
        int padding = px(30, scale); root.setPadding(padding, padding, padding, padding);
        root.setBackground(shape(art.noticeBackground, art.detailRim, 34, scale));
        LinearLayout heading = new LinearLayout(activity); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(activity, Ui.tr("Notifications"), 32, art.text, scale, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, px(64, scale), 1));
        TextView close = text(activity, Ui.tr("Close"), 25, art.text, scale, true);
        close.setGravity(Gravity.CENTER); close.setFocusable(true); close.setClickable(true); close.setDefaultFocusHighlightEnabled(false);
        close.setPadding(px(20, scale), 0, px(20, scale), 0);
        close.setOnFocusChangeListener((v, focused) -> v.setBackground(shape(focused ? art.focus : 0x00ffffff,
                focused ? art.focusRim : 0x00ffffff, 15, scale)));
        close.setOnClickListener(v -> { click.back(); dialog.dismiss(); });
        heading.addView(close, new LinearLayout.LayoutParams(-2, px(54, scale))); root.addView(heading);
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false); scroll.setClipToPadding(false);
        scroll.setPadding(0, px(20, scale), 0, 0);
        LinearLayout messages = new LinearLayout(activity); messages.setOrientation(LinearLayout.VERTICAL); scroll.addView(messages);
        View first = null;
        for (Entry entry : entries(state)) {
            LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(24, scale), px(20, scale), px(24, scale), px(22, scale));
            card.setFocusable(true); card.setDefaultFocusHighlightEnabled(false);
            card.setBackground(shape(art.noticeCard, art.detailRim, 18, scale));
            card.setOnFocusChangeListener((v, focused) -> v.setBackground(shape(focused ? art.focus : art.noticeCard,
                    focused ? art.focusRim : art.detailRim, 18, scale)));
            String age = age(entry.at);
            if (!age.isEmpty()) {
                TextView time = text(activity, age, 21, art.accent, scale, true);
                time.setPadding(0, 0, 0, px(12, scale)); card.addView(time);
            }
            TextView body = text(activity, entry.text, 27, art.text, scale, false); body.setLineSpacing(px(5, scale), 1);
            card.addView(body); card.setContentDescription(age + ". " + entry.text);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = px(16, scale);
            messages.addView(card, params); if (first == null) first = card;
        }
        if (first == null) messages.addView(text(activity, Ui.tr("None yet."), 27, art.value, scale, false));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(root); dialog.setOnCancelListener(d -> click.back());
        dialog.setOnKeyListener((d, code, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT) click.move();
            return false;
        });
        dialog.show(); Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setGravity(Gravity.RIGHT | Gravity.TOP); window.setLayout(px(904, scale), height - px(98, scale));
            WindowManager.LayoutParams attributes = window.getAttributes(); attributes.x = px(56, scale); attributes.y = px(82, scale); window.setAttributes(attributes);
        }
        if (first != null) first.requestFocus(); else close.requestFocus();
        return dialog;
    }
    private static int px(float value, float scale) { return Math.max(1, Math.round(value * scale)); }
    private static TextView text(Activity a, String value, float size, int color, float scale, boolean bold) {
        TextView v = new TextView(a); v.setText(value); v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale); v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        v.setSoundEffectsEnabled(false); return v;
    }
    private static GradientDrawable shape(int fill, int stroke, float radius, float scale) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(radius * scale); d.setStroke(px(1.25f, scale), stroke); return d;
    }
}
