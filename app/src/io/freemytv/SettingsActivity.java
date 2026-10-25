package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Home screen"
 * opens the Free My TV / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private Click click;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override public void dump(String prefix, java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        super.dump(prefix, fd, writer, args);
        if (view != null) writer.println(prefix + "SettingsNavigation: page=" + view.screen + ", focus=" + view.focus
                + ", source=" + (Prefs.embyRecommendations() ? "emby" : "tmdb") + ", top=" + HomeApps.topButtonKeys() + ", tmdbTest=" + TmdbSettings.diagnostic + ", art=" + Prefs.settingsArt() + ", artRender=" + view.backdrop.status());
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        click = new Click(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        // Let the exit cue finish while this activity closes.
        if (click != null) {
            Click finished = click;
            new Handler(Looper.getMainLooper()).postDelayed(finished::release, 1000);
        }
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5,
                PERFORMANCE = 6, POSTERS = 7, TMDB = 8, SOURCE = 9, ART_CHOOSER = 10;
        private static final int MAIN_POSTERS = 3, MAIN_SHELF = 4, MAIN_TOP_BUTTONS = 5, MAIN_REMOTE = 6,
                MAIN_PERFORMANCE = 7, MAIN_ART = 8, MAIN_NOTICE = 9;
        private static final ExecutorService TMDB_IO = Executors.newSingleThreadExecutor();
        private static final float LIST_ROW_H = 68, LIST_ROW_GAP = 6;
        private static final int PICK_VISIBLE = 9;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_GAP = 8, CHOICE_H = 104;
        private static final float MAIN_TOP = 190, ROW_H = 60, MAIN_GAP = 10, NOTICE_TOP = 900, NOTICE_H = 108;
        private SettingsTheme art = SettingsTheme.current();

        private final SettingsActivity activity;
        private final SettingsBackdrop backdrop = new SettingsBackdrop();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[13];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor (also the top buttons' one, with editingTop): what it can hold (selected ones first, in
        // order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private boolean editingTop;
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0, PICK_LANGUAGE = -1;
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private long saverMillis;
        private String saverStatus = "";
        private boolean savingSaver;
        private DebugPrompt debugPrompt;
        private Dialog noticesWindow, tmdbEditor;
        private boolean tmdbBusy, tmdbSet;
        private String tmdbResult = "", noticePreview = "", noticeAge = "", tmdbRegionName = "";
        private StaticLayout tmdbExplanation, tmdbCredit;
        private long pageAt;
        private int drawnScreen = -1;
        private final Runnable debugChanged = this::invalidate;
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow, focusRim,
                pageHeading, detailFill, detailRim;
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                updateAuxiliary();
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            saverMillis = ScreensaverTiming.read(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            setFocusable(true);
            setSoundEffectsEnabled(false);
            setContentDescription("Free My TV · " + Ui.tr("Your TV's home screen and screensaver"));
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row == MAIN_NOTICE) return Ui.tr("Notifications") + ", " + noticePreview;
                    if (row == 0) {
                        String busyText = busyText();
                        String value = busy() ? (busyText.isEmpty() ? Ui.tr("Starting…") : busyText)
                                : freeActive() ? "Free My TV" : Ui.tr("Original");
                        return Ui.trf("Home Screen, {mode}", "mode", value);
                    }
                    String[] names = {Ui.tr("Screensaver, preview"), Ui.tr("Home Settings"), Ui.tr("Poster settings"),
                            Ui.tr("Shelf"), Ui.tr("Top Buttons"), Ui.tr("Remote Buttons"), Ui.tr("Performance information"), Ui.tr("Theme settings")};
                    return row - 1 < names.length ? names[row - 1] : "";
                }
                case PERFORMANCE: return Ui.tr("Back");
                case POSTERS: return row == 0 ? Ui.tr("Source") + ", " + (Prefs.embyRecommendations() ? "Emby" : "TMDB") : row == 1 ? "Emby" : "TMDB";
                case ART_CHOOSER: return SettingsTheme.label(SettingsTheme.KEYS[row]) + (SettingsTheme.KEYS[row].equals(Prefs.settingsArt()) ? Ui.tr(", selected") : "");
                case SOURCE: return (row == 0 ? "Emby" : "TMDB") + ((row == 0) == Prefs.embyRecommendations() ? Ui.tr(", selected") : "");
                case TMDB: return tmdbLabels()[row] + ", " + tmdbValues()[row];
                case HOME_CHOICE: {
                    String name = row == 0 ? "Free My TV, " + Ui.tr("no ads, with the new screensaver")
                            : "Original, " + Ui.tr("the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". In use") : "") + ", " + Ui.trf("{i} of {n}", "i", row + 1, "n", 2);
                }
                case HOME_SETTINGS:
                case REMOTE_KEYS: {
                    boolean keys = screen == REMOTE_KEYS;
                    String[] labels = keys ? keyLabels() : settingLabels(), values = keys ? keyValues() : settingValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", selected") : "")
                            + ", " + Ui.trf("{i} of {n}", "i", at + 1, "n", pickLabels.size());
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    if (editingTop) {
                        return app[1] + ", " + (place >= 0 ? Ui.trf("in the top buttons, place {n}", "n", place + 1)
                                : Ui.tr("not in the top buttons"));
                    }
                    return app[1] + ", " + (place >= 0 ? Ui.trf("on the shelf, place {n}", "n", place + 1)
                            : Ui.tr("not on the shelf"));
                }
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                top = row == MAIN_NOTICE ? NOTICE_TOP : MAIN_TOP + row * (ROW_H + MAIN_GAP);
                height = row == MAIN_NOTICE ? NOTICE_H : ROW_H;
            } else if (screen == ART_CHOOSER) {
                top = 220 + row * 148; height = 124;
            } else if (screen == PERFORMANCE) {
                top = 920; height = 66;
            } else if (screen == POSTERS) {
                top = 220 + row * 122; height = 100;
            } else if (screen == TMDB || screen == SOURCE) {
                top = 220 + row * 96; height = 80;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + 20 + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else {
                top = LIST_TOP + 20 + row * (listRowHeight() + LIST_ROW_GAP);
                height = listRowHeight();
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            StockHomeGate.refresh(getContext());
            DebugControls.subscribe(getContext(), debugChanged);
            if (!savingSaver) { saverMillis = ScreensaverTiming.read(getContext()); saverStatus = ""; }
            running = true;
            backdrop.load(getContext(), this::invalidate);
            TMDB_IO.execute(() -> { TmdbSettings.importPending(getContext()); post(() -> { updateAuxiliary(); invalidate(); }); });
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            DebugControls.unsubscribe(debugChanged);
            if (debugPrompt != null) { debugPrompt.dismissNow(); debugPrompt = null; }
            if (noticesWindow != null) { noticesWindow.dismiss(); noticesWindow = null; }
            if (tmdbEditor != null) { tmdbEditor.dismiss(); tmdbEditor = null; }
            running = false;
            backdrop.pause();
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            if (moving || tmdbBusy || SystemClock.uptimeMillis() - pageAt < 180 || backdrop.animating(SystemClock.uptimeMillis())) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            art = SettingsTheme.current();
            w = width;
            h = height;
            k = w / 1920f;
            backdrop.setSize(w, h);
            backdrop.load(getContext(), this::invalidate);
            title = Ui.text(Ui.displaySemibold, (art.classic ? 46 : 38) * k, art.brandText);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 24 * k, art.accent);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 19 * k, art.faint);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.semibold, 30 * k, art.text);
            rowValue = Ui.text(Ui.medium, 28 * k, art.value);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, art.value);
            header = Ui.text(Ui.semibold, 21 * k, art.accent);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, art.muted);
            cellValue = Ui.numbers(Ui.regular, 25 * k, art.muted);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, art.warning);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, art.body));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(art.focus);
            focusRim = new Paint(Paint.ANTI_ALIAS_FLAG); focusRim.setStyle(Paint.Style.STROKE);
            focusRim.setStrokeWidth(1.5f * k); focusRim.setColor(art.focusRim);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            pageHeading = Ui.text(Ui.displaySemibold, 34 * k, art.text);
            detailFill = new Paint(Paint.ANTI_ALIAS_FLAG); detailFill.setColor(art.detail);
            detailRim = new Paint(Paint.ANTI_ALIAS_FLAG); detailRim.setStyle(Paint.Style.STROKE); detailRim.setStrokeWidth(k); detailRim.setColor(art.detailRim);
            TextPaint explanation = new TextPaint(Ui.text(Ui.regular, 25 * k, art.value));
            int textWidth = Math.max(1, Math.round((LIST_R - LIST_X - 60) * k));
            tmdbExplanation = null;
            TextPaint credit = new TextPaint(Ui.text(Ui.regular, 20 * k, art.accent));
            String attribution = Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB.");
            tmdbCredit = StaticLayout.Builder.obtain(attribution, 0, attribution.length(), credit, textWidth).setIncludePad(false).build();
            errorShown = null;
            updateAuxiliary();
        }

        private void applyArtwork() {
            if (w > 0 && h > 0) onSizeChanged(w, h, w, h);
            for (Spring spring : pop) spring.snap(0);
            drawnScreen = -1; invalidate();
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return 10;
                case PERFORMANCE: return 1;
                case POSTERS: return 3;
                case ART_CHOOSER: return SettingsTheme.KEYS.length;
                case SOURCE: return 2;
                case TMDB: return 3;
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return settingLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            backdrop.draw(c, up);

            // On the left: big symbol, title, description.
            float cx = 500 * k;
            version.setColor(art.brandMuted);
            if (art.classic) {
                Symbols.draw(getContext(), c, "sparkles", cx, 408 * k, 116 * k, 0xA6BDCFDE);
                c.drawText("Free My TV", cx, 584 * k, title);
                c.drawText(Ui.tr("Your TV's home screen and screensaver"), cx, 630 * k, subtitle);
                c.drawText(versionName(getContext()), cx, 673 * k, version);
            } else {
                c.drawText("Free My TV", cx, 106 * k, title);
                c.drawText(versionName(getContext()), cx, 145 * k, version);
            }

            version.setColor(art.faint);

            if (drawnScreen != screen) { drawnScreen = screen; pageAt = up; }
            float page = Ui.clamp((up - pageAt) / 180f); page = 1 - (1 - page) * (1 - page);
            int saved = c.save();
            if (page < .999f) {
                c.saveLayerAlpha(960 * k, 82 * k, 1864 * k, h, Math.round(255 * page));
                c.translate((1 - page) * 12 * k, 0);
            }
            if (screen == MAIN) drawMain(c, seconds);
            else if (screen == PERFORMANCE) drawPerformance(c);
            else if (screen == POSTERS) drawPosters(c);
            else if (screen == TMDB) drawTmdb(c);
            else if (screen == SOURCE) drawSource(c);
            else if (screen == ART_CHOOSER) drawArtThemes(c);
            else if (screen == HOME_CHOICE) drawChoice(c);
            else if (screen == PICKER) drawPicker(c);
            else if (screen == SHELF_EDIT) drawShelfEditor(c);
            else drawList(c);
            c.restoreToCount(saved);
        }

        private void updateAuxiliary() {
            if (notice == null) return;
            List<SettingsNotices.Entry> entries = SettingsNotices.entries(state);
            SettingsNotices.Entry latest = entries.isEmpty() ? null : entries.get(0);
            noticePreview = TextUtils.ellipsize(SettingsNotices.summary(latest), notice, (LIST_R - LIST_X - 100) * k,
                    TextUtils.TruncateAt.END).toString();
            noticeAge = latest == null ? "" : SettingsNotices.age(latest.at);
            tmdbSet = TmdbSettings.configured(getContext());
            String code = TmdbSettings.region(getContext());
            tmdbRegionName = TextUtils.ellipsize(new Locale("", code).getDisplayCountry(Ui.locale()) + " · " + code,
                    new TextPaint(rowValue), 420 * k, TextUtils.TruncateAt.END).toString();
            String message = busy() ? busyText() : state.optString("error");
            int width = Math.max(1, Math.round((LIST_R - LIST_X - 60) * k));
            if (!message.equals(errorShown) || errorLayout != null && errorLayout.getWidth() != width) {
                errorShown = message;
                errorLayout = message.isEmpty() ? null : StaticLayout.Builder.obtain(message, 0, message.length(),
                        new TextPaint(busy() ? notice : warn), width).setIncludePad(false).setMaxLines(5)
                        .setEllipsize(TextUtils.TruncateAt.END).build();
            }
        }

        private void drawMain(Canvas c, float seconds) {
            c.drawText(Ui.tr("Settings"), LIST_X * k + 30 * k, 145 * k, pageHeading);
            String[] labels = {Ui.tr("Home Screen"), Ui.tr("Screensaver"), Ui.tr("Home Settings"), Ui.tr("Poster settings"),
                    Ui.tr("Shelf"), Ui.tr("Top Buttons"), Ui.tr("Remote Buttons"), Ui.tr("Performance information"), Ui.tr("Theme settings")};
            for (int i = 0; i < labels.length; i++) {
                rowRect.set(LIST_X * k, (MAIN_TOP + i * (ROW_H + MAIN_GAP)) * k, LIST_R * k,
                        (MAIN_TOP + i * (ROW_H + MAIN_GAP) + ROW_H) * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(art.text, art.ink, f));
                float baseline = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, baseline, rowLabel);
                float right = rowRect.right - 30 * k;
                if (i == 0) {
                    String mode = busy() ? Ui.tr("Starting…") : freeActive() ? "Free My TV" : Ui.tr("Original");
                    rowValue.setColor(art.value); c.drawText(mode, right, baseline, rowValue);
                    if (busy()) {
                        spinner.setColor(art.ink);
                        Ui.spinner(c, right - rowValue.measureText(mode) - 26 * k, rowRect.centerY(), 11 * k, seconds * 1.3f, spinner);
                    }
                } else if (i == 1) {
                    rowValue.setColor(art.value); c.drawText(Ui.tr("Preview"), right, baseline, rowValue);
                } else {
                    if (i == MAIN_ART) { rowValue.setColor(art.value); c.drawText(SettingsTheme.label(), right - 42 * k, baseline, rowValue); }
                    Symbols.draw(getContext(), c, "chevron_right", right - 7 * k, rowRect.centerY(), 22 * k, art.arrow);
                }
            }
            drawNoticeSummary(c);
        }

        private void drawNoticeSummary(Canvas c) {
            rowRect.set(LIST_X * k, NOTICE_TOP * k, LIST_R * k, (NOTICE_TOP + NOTICE_H) * k);
            c.drawRoundRect(rowRect, 22 * k, 22 * k, detailFill);
            c.drawRoundRect(rowRect, 22 * k, 22 * k, detailRim);
            drawRowBackground(c, MAIN_NOTICE);
            c.drawText(Ui.tr("Notifications"), rowRect.left + 30 * k, rowRect.top + 35 * k, header);
            version.setTextAlign(Paint.Align.RIGHT);
            c.drawText(noticeAge, rowRect.right - 30 * k, rowRect.top + 35 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
            c.drawText(noticePreview, rowRect.left + 30 * k, rowRect.top + 78 * k, notice);
            Symbols.draw(getContext(), c, "chevron_right", rowRect.right - 30 * k, rowRect.top + 76 * k, 20 * k, art.arrow);
        }

        private void drawPerformance(Canvas c) {
            c.drawText(Ui.tr("Performance information"), LIST_X * k + 30 * k, 160 * k, pageHeading);
            c.drawText(Ui.tr("Latest recorded comparison"), LIST_X * k + 30 * k, 224 * k, notice);
            drawComparison(c, 304 * k);
            rowRect.set(LIST_X * k, 920 * k, LIST_R * k, 986 * k);
            drawRowBackground(c, 0); rowLabel.setColor(art.ink);
            c.drawText(Ui.tr("Back"), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
        }

        private void drawPosters(Canvas c) {
            c.drawText(Ui.tr("Poster settings"), LIST_X * k + 30 * k, 160 * k, pageHeading);
            String[] labels = {Ui.tr("Source"), "Emby", "TMDB"};
            String[] descriptions = {Ui.tr("RECOMMENDATIONS"), Ui.tr("Server") + " · " + Ui.tr("Emby account"), Ui.tr("API Key") + " · " + Ui.tr("Region")};
            for (int i = 0; i < 3; i++) {
                rowRect.set(LIST_X * k, (220 + i * 122) * k, LIST_R * k, (320 + i * 122) * k);
                c.drawRoundRect(rowRect, 22 * k, 22 * k, detailFill); c.drawRoundRect(rowRect, 22 * k, 22 * k, detailRim);
                drawRowBackground(c, i); rowLabel.setColor(art.ink); rowSub.setColor(art.accent);
                c.drawText(labels[i], rowRect.left + 30 * k, rowRect.top + 39 * k, rowLabel);
                c.drawText(descriptions[i], rowRect.left + 30 * k, rowRect.top + 76 * k, rowSub);
                rowValue.setColor(art.value);
                String value = i == 0 ? (Prefs.embyRecommendations() ? "Emby" : "TMDB")
                        : (i == 1 ? EmbyRecs.configured(getContext()) : tmdbSet) ? Ui.tr("Set") : Ui.tr("Not set");
                c.drawText(value, rowRect.right - 66 * k, rowRect.centerY() + Ui.halfCap(rowValue), rowValue);
                Symbols.draw(getContext(), c, "chevron_right", rowRect.right - 30 * k, rowRect.centerY(), 22 * k, art.arrow);
            }
            c.drawText(Ui.tr("RECOMMENDATIONS"), LIST_X * k + 30 * k, 660 * k, header);
            String[] labelsStatus = {Ui.tr("Available posters"), Ui.tr("Sync minutes")};
            String[] values = {hasKey ? String.valueOf(recs.optInt("count")) : "—", String.valueOf(Prefs.embySyncMinutes())};
            for (int i = 0; i < labelsStatus.length; i++) {
                if (i == 1 && !Prefs.embyRecommendations()) continue;
                float y = (722 + i * 62) * k;
                c.drawText(labelsStatus[i], LIST_X * k + 30 * k, y, cellLabel);
                c.drawText(values[i], LIST_R * k - 30 * k, y, cellValue);
            }
            if (!recs.optString("error").isEmpty()) c.drawText(Ui.tr("Connection failed"), LIST_X * k + 30 * k, 874 * k, warn);
        }

        private void chooseRecommendationSource() {
            screen = SOURCE; focus = Prefs.embyRecommendations() ? 0 : 1;
            invalidate(); spoken.focusMoved();
        }

        private void drawSource(Canvas c) {
            c.drawText(Ui.tr("Source"), LIST_X * k + 30 * k, 160 * k, pageHeading);
            for (int i = 0; i < 2; i++) {
                rowRect.set(LIST_X * k, (220 + i * 96) * k, LIST_R * k, (300 + i * 96) * k);
                c.drawRoundRect(rowRect, 22 * k, 22 * k, detailFill); c.drawRoundRect(rowRect, 22 * k, 22 * k, detailRim);
                drawRowBackground(c, i); rowLabel.setColor(art.ink);
                c.drawText(i == 0 ? "Emby" : "TMDB", rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if ((i == 0) == Prefs.embyRecommendations())
                    Symbols.draw(getContext(), c, "check", rowRect.right - 34 * k, rowRect.centerY(), 26 * k, art.accent);
            }
        }

        private void drawArtThemes(Canvas c) {
            c.drawText(Ui.tr("Theme settings"), LIST_X * k + 30 * k, 160 * k, pageHeading);
            int count = SettingsTheme.KEYS.length;
            float gap = 20, rowHeight = Math.min(124, (788 - (count - 1) * gap) / count);
            for (int i = 0; i < count; i++) {
                float top = 220 + i * (rowHeight + gap);
                rowRect.set(LIST_X*k,top*k,LIST_R*k,(top+rowHeight)*k);
                c.drawRoundRect(rowRect,22*k,22*k,detailFill); c.drawRoundRect(rowRect,22*k,22*k,detailRim);
                drawRowBackground(c,i); rowLabel.setColor(art.ink);
                c.drawText(SettingsTheme.label(SettingsTheme.KEYS[i]),rowRect.left+30*k,rowRect.centerY()+Ui.halfCap(rowLabel),rowLabel);
                if (SettingsTheme.KEYS[i].equals(Prefs.settingsArt())) Symbols.draw(getContext(),c,"check",rowRect.right-34*k,rowRect.centerY(),26*k,art.accent);
            }
        }

        private String[] tmdbLabels() { return new String[] {Ui.tr("API Key"), Ui.tr("Region"), Ui.tr("Test connection")}; }
        private String[] tmdbValues() { return new String[] {tmdbSet ? Ui.tr("Set") : Ui.tr("Not set"), tmdbRegionName,
                tmdbBusy ? Ui.tr("Connecting…") : tmdbResult}; }

        private void drawTmdb(Canvas c) {
            c.drawText("TMDB", LIST_X * k + 30 * k, 160 * k, pageHeading);
            String[] labels = tmdbLabels(), values = tmdbValues();
            for (int i = 0; i < labels.length; i++) {
                rowRect.set(LIST_X * k, (220 + i * 96) * k, LIST_R * k, (300 + i * 96) * k);
                drawRowBackground(c, i); rowLabel.setColor(art.ink); rowValue.setColor(art.value);
                float y = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, y, rowLabel);
                if (!values[i].isEmpty()) c.drawText(values[i], rowRect.right - 30 * k, y, rowValue);
                else Symbols.draw(getContext(), c, "chevron_right", rowRect.right - 30 * k, rowRect.centerY(), 22 * k, art.arrow);
            }
            if (tmdbExplanation != null) {
                c.save(); c.translate(LIST_X * k + 30 * k, 602 * k); tmdbExplanation.draw(c); c.restore();
            }
            if (tmdbCredit != null) {
                c.save(); c.translate(LIST_X * k + 30 * k, 930 * k); tmdbCredit.draw(c); c.restore();
            }
        }

        /** List row: no background; focused, white with a shadow (the Apple TV focus). Returns how focused it is. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            if (f > 0.01f) {
                float s = 1 + 0.02f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
                float r = 18 * k * s;
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(art.shadow, 0.24f * f));
                c.drawRoundRect(tmp, r, r, shadow);
                tmp.offset(0, -10 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                focusRim.setAlpha(Math.round(255 * f)); c.drawRoundRect(tmp, r, r, focusRim);
                rowRect.set(tmp);
            }
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARISON"), x, y, header);
            cellValue.setColor(art.accent);
            c.drawText(Ui.tr("Original"), colA, y, cellValue);
            c.drawText("Free My TV", colB, y, cellValue);
            cellValue.setColor(art.muted);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Home button"), "homeMs"}, {Ui.tr("Free memory"), "memAvailableMB"},
                    {Ui.tr("Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (70 + 84 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
            return y + (70 + 84 * rows.length) * k;
        }

        /** Home screen choice: Free My TV or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, art.text);
            c.drawText(Ui.tr("Home Screen"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            String[][] options = {{"Free My TV", Ui.tr("No ads, with the new screensaver")},
                    {Ui.tr("Original"), Ui.tr("The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + 20 + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(art.text, art.ink, f));
                rowSub.setColor(blend(art.value, Ui.alpha(art.valueFocus, 0.87f), f));
                c.drawText(options[i][0], rowRect.left + 30 * k, rowRect.top + 44 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 30 * k, rowRect.top + 80 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(art.text, art.ink, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, art.value);
            c.drawText(Ui.tr("Switching takes a few seconds; the screen blinks while it measures."), LIST_X * k + 30 * k,
                    (LIST_TOP + 20 + 2 * (CHOICE_H + ROW_GAP) + 40) * k, hint);
            if (errorLayout != null) {
                c.save(); c.translate(LIST_X * k + 30 * k, 640 * k); errorLayout.draw(c); c.restore();
            }
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return Ui.trf("{n} ms", "n", v);
            if (key.equals("memAvailableMB") && v >= 1024) return Ui.trf("{n} GB", "n", String.format(Ui.locale(), "%.2f", v / 1024.0));
            return Ui.trf("{n} MB", "n", v);
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("now");
            if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
            if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
            return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
        }

        private static String versionName(Context c) {
            try {
                return "Free My TV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Free My TV";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && (focus > 0 || scrolling() && pickScroll > 0)) activity.click.move();
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && (focus < rowCount() - 1 || scrolling() && pickScroll + rowCount() < listSize())) activity.click.move();
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == SHELF_EDIT) {
                        activity.click.move();
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && (screen == HOME_SETTINGS || screen == REMOTE_KEYS)) {
                        activity.click.move();
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) {
                        if (!down && !e.isCanceled()) activity.click.back();
                        return false;
                    }
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            activity.click.back();
                            if (screen == PICKER && (pickFor == PICK_CITY || pickFor == PICK_LANGUAGE)) {
                                focus = pickFor == PICK_CITY ? CITY_ROW : 0;
                                screen = HOME_SETTINGS;
                            } else if (screen == PICKER) {
                                focus = pickFor - 1;
                                screen = REMOTE_KEYS;
                            } else if (screen == SOURCE) {
                                screen = POSTERS; focus = 0;
                            } else if (screen == TMDB) {
                                screen = POSTERS; focus = 2;
                            } else {
                                focus = screen == HOME_SETTINGS ? 2 : screen == SHELF_EDIT ? (editingTop ? MAIN_TOP_BUTTONS : MAIN_SHELF)
                                        : screen == REMOTE_KEYS ? MAIN_REMOTE : screen == PERFORMANCE ? MAIN_PERFORMANCE
                                        : screen == POSTERS ? MAIN_POSTERS : screen == ART_CHOOSER ? MAIN_ART : 0;
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /** Home Settings: language, temperature, theme, sounds, weather, city, quotes, then one row per index. */
        private static final int SAVER_ROW = 4, CITY_ROW = 6, COIN_ROWS = 8, GENERAL_END = COIN_ROWS + HomeData.COINS.length;
        private int stockHomeRow() { return GENERAL_END; }
        private float listRowHeight() { return screen == HOME_SETTINGS ? (HomeMode.keepHisenseApps() ? 56 : 62) : LIST_ROW_H; }

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Language"));
            l.add(Ui.tr("Temperature"));
            l.add(Ui.tr("Theme"));
            l.add(Ui.tr("Navigation sounds"));
            l.add(Ui.tr("Screen saver delay"));
            l.add(Ui.tr("Weather widget"));
            l.add(Ui.tr("City"));
            l.add(Ui.tr("Stock indices"));
            for (int i = 0; i < HomeData.COINS.length; i++) l.add(HomeData.coinName(i));
            if (HomeMode.keepHisenseApps()) l.add("聚好看");
            return l.toArray(new String[0]);
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang);
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("unit|Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark") : Ui.tr("Automatic");
            v[3] = onOff(Prefs.sounds());
            v[SAVER_ROW] = !saverStatus.isEmpty() ? saverStatus : saverMillis <= 0 ? Ui.tr("Off")
                    : Ui.trf("{n} min", "n", Math.max(1, saverMillis / 60000));
            v[5] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
            v[7] = onOff(Prefs.quotes());
            // No quotes widget, nowhere to show prices: the indices read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < GENERAL_END; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            if (HomeMode.keepHisenseApps()) v[stockHomeRow()] = StockHomeGate.enabled(getContext()) ? Ui.tr("On demand") : Ui.tr("Always available");
            return v;
        }

        private void editTmdbKey() {
            if (tmdbBusy) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true); field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28 * k);
            field.setTextColor(art.text); field.setHintTextColor(art.faint);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.setHint(tmdbSet ? Ui.tr("Keep current key") : Ui.tr("API Key")); field.setSaveEnabled(false);
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_DONE);
            AlertDialog dialog = new AlertDialog.Builder(activity, art.dialogTheme())
                    .setTitle(Ui.tr("API Key")).setView(field).setPositiveButton(Ui.tr("Save"), null)
                    .setNegativeButton(Ui.tr("Cancel"), null).create();
            dialog.setOnCancelListener(d -> activity.click.back());
            dialog.setOnDismissListener(d -> { field.getText().clear(); tmdbEditor = null; });
            dialog.show(); tmdbEditor = dialog;
            if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setSoundEffectsEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setSoundEffectsEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = field.getText().toString().trim();
                if (value.isEmpty() && tmdbSet) { activity.click.back(); dialog.dismiss(); return; }
                if (!TmdbSettings.validKey(value)) { field.setError(Ui.tr("API key rejected")); return; }
                activity.click.open(); tmdbBusy = true; dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                TMDB_IO.execute(() -> {
                    boolean success;
                    try { TmdbSettings.saveKey(getContext(), value); success = true; }
                    catch (Exception e) { success = false; }
                    final boolean saved = success;
                    post(() -> {
                        tmdbBusy = false; tmdbResult = ""; updateAuxiliary(); invalidate();
                        if (!dialog.isShowing()) return;
                        if (saved) dialog.dismiss();
                        else { field.setError(Ui.tr("Couldn't save settings")); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); }
                    });
                });
            });
        }

        private void chooseTmdbRegion() {
            List<String> codes = new ArrayList<>(java.util.Arrays.asList("HK", "CN", "TW", "US", "JP", "GB"));
            List<String> rest = new ArrayList<>();
            for (String code : Locale.getISOCountries()) if (!codes.contains(code)) rest.add(code);
            Collections.sort(rest, (a, b) -> new Locale("", a).getDisplayCountry(Ui.locale()).compareTo(new Locale("", b).getDisplayCountry(Ui.locale())));
            codes.addAll(rest); String[] labels = new String[codes.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = new Locale("", codes.get(i)).getDisplayCountry(Ui.locale()) + " · " + codes.get(i);
            AlertDialog dialog = new AlertDialog.Builder(activity, art.dialogTheme())
                    .setTitle(Ui.tr("Region")).setSingleChoiceItems(labels, codes.indexOf(TmdbSettings.region(getContext())), (d, which) -> {
                        activity.click.open(); TmdbSettings.region(getContext(), codes.get(which)); updateAuxiliary(); invalidate(); d.dismiss();
                    }).setNegativeButton(Ui.tr("Cancel"), null).create();
            dialog.setOnCancelListener(d -> activity.click.back()); dialog.show(); tmdbEditor = dialog;
            dialog.setOnDismissListener(d -> tmdbEditor = null);
            dialog.getListView().setSoundEffectsEnabled(false); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setSoundEffectsEnabled(false);
        }

        private void testTmdb() {
            if (tmdbBusy) return;
            if (!tmdbSet) { tmdbResult = Ui.tr("Not set"); invalidate(); return; }
            tmdbBusy = true; tmdbResult = ""; invalidate();
            TMDB_IO.execute(() -> {
                String result = TmdbSettings.test(getContext());
                post(() -> { tmdbBusy = false; tmdbResult = result; updateAuxiliary(); invalidate(); spoken.readAgain(); });
            });
        }

        private void stockHomeOptions() {
            String[] choices = {Ui.tr("Always available"), Ui.tr("On demand (USB debugging required)"), Ui.trf("Open {app}", "app", "聚好看")};
            AlertDialog d = new AlertDialog.Builder(activity).setTitle("聚好看")
                    .setItems(choices, (dialog, which) -> {
                        activity.click.open();
                        if (which == 2) {
                            Runnable open = () -> HomeApps.open(activity, HomeApps.app("com.jamdeo.tv.vod", "聚好看"), this, 0, 0, 1, 1);
                            if (DebugControls.on()) open.run();
                            else postDelayed(() -> {
                                if (running && isAttachedToWindow() && activity.hasWindowFocus())
                                    debugPrompt = new DebugPrompt(activity, activity.click, null, k, false,
                                            new RectF(rowBox(stockHomeRow())), w, h, open);
                            }, 180);
                        } else StockHomeGate.configure(getContext(), which == 1, message -> {
                            if (!activity.isFinishing()) android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show();
                            invalidate(); spoken.readAgain();
                        });
                    }).setNegativeButton(Ui.tr("Cancel"), null).create();
            d.setOnCancelListener(dialog -> activity.click.back()); d.show();
            d.getListView().setSoundEffectsEnabled(false);
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setSoundEffectsEnabled(false);
        }

        /** "auto", then English and every language with a catalogue. */
        private static String[] languageChoices() {
            String[] all = Ui.languages();
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("On") : Ui.tr("Off");
        }

        private String[] keyLabels() {
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Tabii button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("BRAVIA CORE button"),
                    Ui.tr("Netflix button"), Ui.tr("Disney+ button"),
                    Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Crunchyroll button"), Ui.tr("Google Play button")};
            return new String[] {Ui.tr("Netflix button"), Ui.tr("YouTube button"),
                    Ui.tr("Prime Video button"), Ui.tr("TCL Channel button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(getContext())) {
                if (!app[0].equals(getContext().getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // On Sony, "nothing" leaves the key to the TV, which opens its usual app.
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Sony default") : Ui.tr("Nothing"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                return;
            }
            switch (focus) {
                case 0: break;   // OK opens the language picker.
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case 3: Prefs.setSounds(!Prefs.sounds()); break;
                case SAVER_ROW: setSaver(ScreensaverTiming.next(saverMillis, dir)); return;
                case 5: Prefs.setWeather(!Prefs.weather()); break;
                case CITY_ROW: return;
                case 7: Prefs.setQuotes(!Prefs.quotes()); break;
                default:
                    if (focus >= GENERAL_END) return;
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(focus - COIN_ROWS, !Prefs.coin(focus - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private void chooseSaver() {
            if (savingSaver) return;
            String[] names = new String[ScreensaverTiming.OPTIONS.length]; int current = -1;
            for (int i = 0; i < names.length; i++) {
                names[i] = Ui.trf("{n} min", "n", ScreensaverTiming.OPTIONS[i] / 60000);
                if (ScreensaverTiming.OPTIONS[i] == saverMillis) current = i;
            }
            AlertDialog d = new AlertDialog.Builder(activity).setTitle(Ui.tr("Screen saver delay"))
                    .setSingleChoiceItems(names, current, (dialog, which) -> {
                        activity.click.open(); dialog.dismiss(); setSaver(ScreensaverTiming.OPTIONS[which]);
                    }).setNegativeButton(Ui.tr("Cancel"), null).create();
            d.setOnCancelListener(dialog -> activity.click.back()); d.show();
            d.getListView().setSoundEffectsEnabled(false);
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setSoundEffectsEnabled(false);
        }
        private void setSaver(long millis) {
            if (savingSaver) return;
            savingSaver = true; saverStatus = Ui.tr("Connecting…"); invalidate();
            new Thread(() -> {
                String error = "";
                try { ScreensaverTiming.set(getContext(), millis); }
                catch (Exception e) { error = e.getMessage() == null ? Ui.tr("Connection failed") : e.getMessage(); }
                final String result = error;
                post(() -> {
                    savingSaver = false;
                    if (result.isEmpty()) { saverMillis = ScreensaverTiming.read(getContext()); saverStatus = ""; }
                    else {
                        saverStatus = Ui.tr("Connection failed");
                        if (!activity.isFinishing()) android.widget.Toast.makeText(activity,
                                Ui.trf("Error: {error}.", "error", result), android.widget.Toast.LENGTH_LONG).show();
                        postDelayed(() -> { if (!savingSaver) { saverStatus = ""; invalidate(); } }, 5000);
                    }
                    invalidate();
                });
            }, "screensaver-time").start();
        }

        private void askCity() {
            if (Ui.tr("Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.trf("Couldn't search now: {error}", "error", e.getMessage());
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == HOME_SETTINGS) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor(boolean top) {
            editingTop = top;
            shelfOrder.clear();
            if (top) {
                shelfOrder.addAll(HomeApps.topButtonKeys());
                rebuildShelfRows(HomeApps.topCandidates());
            } else {
                // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the
                // limit) without a row to take it off.
                PackageManager pm = getContext().getPackageManager();
                for (String pkg : HomeApps.shelfPackages(getContext())) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg);
                rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            }
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            if (editingTop) Prefs.setTopButtons(shelfOrder); else Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (editingTop || shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);   // three buttons: no limit to reach
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, art.text);
            c.drawText(editingTop ? Ui.tr("Top Buttons") : Ui.tr("Shelf"), LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(place >= 0 ? art.text : art.disabled, place >= 0 ? art.ink : art.disabled, f));
                rowValue.setColor(blend(art.body, art.valueFocus, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], new TextPaint(rowLabel), 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 30 * k, base, rowLabel);
                c.drawText(place >= 0 ? "#" + (place + 1) : Ui.tr("shelf|Off"), rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, art.value);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText(editingTop ? Ui.tr("OK adds or removes a button. ← → change the order.")
                    : Ui.trf("OK adds or removes (up to {n}). ← → change the order.", "n", HomeApps.MAX_SHELF),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else if (which == PICK_LANGUAGE) {
                String[] choices = languageChoices();
                for (String code : choices) {
                    pickLabels.add(code.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(code));
                }
                pickCurrent = java.util.Arrays.asList(choices).indexOf(Prefs.language());
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, art.text);
            String title = pickFor == PICK_CITY ? Ui.tr("City")
                    : pickFor == PICK_LANGUAGE ? Ui.tr("Language") : keyLabels()[pickFor - 1];
            c.drawText(title, LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + 20 + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(art.text, art.ink, f));
                c.drawText(pickLabels.get(at), rowRect.left + 30 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(art.text, art.ink, f));
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, art.value);
            float hy = (LIST_TOP + 20 + rows * (LIST_ROW_H + LIST_ROW_GAP) + 36) * k;
            c.drawText((pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK picks, Back cancels."),
                    LIST_X * k + 30 * k, hy, hint);
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS;
            String[] labels = keys ? keyLabels() : settingLabels();
            String[] values = keys ? keyValues() : settingValues();
            Paint heading = Ui.text(Ui.displaySemibold, 34 * k, art.text);
            float rowHeight = listRowHeight();
            c.drawText(keys ? Ui.tr("Remote Buttons") : Ui.tr("Home Settings"),
                    LIST_X * k + 30 * k, (LIST_TOP - 24) * k, heading);
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + 20 + i * (rowHeight + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + rowHeight * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(blend(art.text, art.ink, f));
                boolean locked = !keys && i >= COIN_ROWS && i < GENERAL_END && !Prefs.quotes();
                rowValue.setColor(blend(locked ? Ui.alpha(art.disabled, 0.6f) : art.body, locked ? Ui.alpha(art.disabled, 0.6f) : art.valueFocus, f));
                if (locked) rowLabel.setColor(blend(art.accent, art.disabled, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 30 * k, base, rowLabel);
                c.drawText(values[i], rowRect.right - 30 * k, base, rowValue);
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, art.value);
            float hy = (LIST_TOP + 20 + labels.length * (rowHeight + LIST_ROW_GAP) + 36) * k;
            c.drawText(Ui.tr("OK or the arrows change the value."), LIST_X * k + 30 * k, hy, hint);
            if (keys) {
                c.drawText(RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                        : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                        : RemoteKeys.SONY ? Ui.tr("Sony default: the button opens what it always did.")
                        : RemoteKeys.FORMOVIE ? Ui.tr("The other buttons: the TV itself controls them.")
                        : RemoteKeys.supported() ? Ui.tr("Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("TCL remote buttons: on this TV they work as always."), LIST_X * k + 30 * k, hy + 38 * k, hint);
            }
        }

        private void activate() {
            activity.click.open();
            Context c = getContext();
            if (screen == ART_CHOOSER) {
                String key = SettingsTheme.KEYS[focus];
                if (!key.equals(Prefs.settingsArt())) { Prefs.setSettingsArt(key); applyArtwork(); }
            } else if (screen == SOURCE) {
                if (Prefs.embyRecommendations() != (focus == 0)) {
                    Prefs.setRecommendationSource(focus == 0);
                    Recs.get(getContext()).refreshIfStale();
                }
                screen = POSTERS; focus = 0;
                recs = Recs.status(getContext()); hasKey = Recs.hasKey(getContext()); updateAuxiliary();
            } else if (screen == POSTERS) {
                if (focus == 0) { chooseRecommendationSource(); return; }
                if (focus == 1) { activity.startActivity(new Intent(activity, EmbySettingsActivity.class)); return; }
                screen = TMDB; focus = 0; updateAuxiliary();
            } else if (screen == TMDB) {
                if (focus == 0) editTmdbKey(); else if (focus == 1) chooseTmdbRegion(); else testTmdb();
                return;
            } else if (screen == PERFORMANCE) {
                screen = MAIN; focus = MAIN_PERFORMANCE;
            } else if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = 0;
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                focus = CITY_ROW;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                Prefs.setLanguage(languageChoices()[pickScroll + focus]);
                focus = 0;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == HOME_SETTINGS && HomeMode.keepHisenseApps() && focus == stockHomeRow()) {
                stockHomeOptions();
                return;
            } else if (screen == HOME_SETTINGS && focus == 0) {
                openPicker(PICK_LANGUAGE);
            } else if (screen == HOME_SETTINGS && focus == CITY_ROW) {
                askCity();
                return;
            } else if (screen == HOME_SETTINGS && focus == SAVER_ROW) {
                chooseSaver();
                return;
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1) {
                pause();
                activity.showPreview();
            } else if (focus == 2) {
                screen = HOME_SETTINGS;
                focus = 0;
            } else if (focus == MAIN_POSTERS) {
                screen = POSTERS; focus = 0;
            } else if (focus == MAIN_SHELF) {
                openShelfEditor(false);
            } else if (focus == MAIN_TOP_BUTTONS) {
                openShelfEditor(true);
            } else if (focus == MAIN_REMOTE) {
                HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                loadApps();
                screen = REMOTE_KEYS;
                focus = 0;
            } else if (focus == MAIN_PERFORMANCE) {
                screen = PERFORMANCE; focus = 0;
            } else if (focus == MAIN_NOTICE) {
                noticesWindow = SettingsNotices.show(activity, state, activity.click, k, w, h);
                return;
            } else if (focus == MAIN_ART) {
                screen = ART_CHOOSER; focus = SettingsTheme.index(Prefs.settingsArt());
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
