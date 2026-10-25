package io.freemytv;

/** Artwork themes are independent of the home screen's day/night setting. */
final class SettingsTheme {
    static final String[] KEYS = {"chiikawa", "classic", "fate-oath", "fate-twilight", "lucy-night-city", "witch-holy-night"};
    static final SettingsTheme CHIIKAWA = new SettingsTheme(0), CLASSIC = new SettingsTheme(1), OATH = new SettingsTheme(2), TWILIGHT = new SettingsTheme(3), LUCY = new SettingsTheme(4), MAHOYO = new SettingsTheme(5);
    final String key, asset;
    final boolean dark, classic, illustrated;
    final int noticeBackground, noticeCard, brandText, brandMuted;
    final int text, ink, accent, value, muted, faint, body, arrow, warning, focus, focusRim, shadow, detail, detailRim, valueFocus, disabled;
    private SettingsTheme(int kind) {
        key = KEYS[kind]; dark = kind == 1 || kind == 2; classic = kind == 1; illustrated = kind >= 2;
        asset = kind == 5 ? "wallpapers/witch-holy-night-v1.png" : kind == 0 ? "wallpapers/chiikawa-settings-v2.png" : kind == 2 ? "wallpapers/fate-oath-v1.png" : kind == 3 ? "wallpapers/fate-twilight-v1.png" : kind == 4 ? "wallpapers/lucy-night-city-v5.png" : "";
        noticeBackground = kind == 5 ? 0xFFE2EDF7 : kind == 4 ? 0xFFE0E3F1 : kind == 2 ? 0xff211f18 : kind == 3 ? 0xfff9ebdd : dark ? 0xff2a3745 : 0xfffffef3;
        noticeCard = kind == 5 ? 0xFFF4F8FE : kind == 4 ? 0xFFEEF0FA : kind == 2 ? 0xff302a1d : kind == 3 ? 0xfffff4e8 : dark ? 0xff303e4c : 0xfff5f8e8;
        text = kind == 5 ? 0xFF283E59 : kind == 4 ? 0xFF263048 : kind == 2 ? 0xFFEAD7A5 : kind == 3 ? 0xFF503343 : dark ? 0xFFDCE4EC : 0xFF4C3B2E;
        ink = kind == 5 ? 0xFF172E48 : kind == 4 ? 0xFF152239 : kind == 2 ? 0xFFFFF3D5 : kind == 3 ? 0xFF402736 : dark ? 0xFFF0F4F8 : 0xFF493D32;
        accent = kind == 5 ? 0xFF496B91 : kind == 4 ? 0xFF3B4A72 : kind == 2 ? 0xFFD8B25E : kind == 3 ? 0xFF835047 : dark ? 0xFFB8CAD8 : 0xFF61734F;
        value = kind == 5 ? 0xFF405C7C : kind == 4 ? 0xFF414565 : kind == 2 ? 0xFFD0C3A2 : kind == 3 ? 0xFF6A4755 : dark ? 0xFFBBCBD7 : 0xFF58644B;
        muted = kind == 5 ? 0xFF4E6884 : kind == 4 ? 0xFF4C5575 : kind == 2 ? 0xFFC1B493 : kind == 3 ? 0xFF704D59 : dark ? 0xFFB1BFCC : 0xFF5E6952;
        faint = kind == 5 ? 0xFF657D97 : kind == 4 ? 0xFF606683 : kind == 2 ? 0xFFB3A481 : kind == 3 ? 0xFF87666F : dark ? 0xFF98ADBD : 0xFF718064;
        body = kind == 5 ? 0xFF3D5875 : kind == 4 ? 0xFF3D4766 : kind == 2 ? 0xFFD4C6A5 : kind == 3 ? 0xFF624350 : dark ? 0xFFBCC9D4 : 0xFF5E6C51;
        arrow = kind == 5 ? 0xBB597D9F : kind == 4 ? 0xCC58607F : kind == 2 ? 0xBFE2BF74 : kind == 3 ? 0xBB885A61 : dark ? 0xBBD0DCE5 : 0xAA71845C;
        warning = kind == 5 ? 0xFF8A592F : kind == 4 ? 0xFF935229 : kind == 2 ? 0xFFE6BD74 : kind == 3 ? 0xFF8F4D25 : dark ? 0xFFE1BB87 : 0xFF9A6D2B;
        focus = kind == 5 ? 0xFFEEF7FF : kind == 4 ? 0xFFD9EEF5 : kind == 2 ? 0xFF493A1C : kind == 3 ? 0xFFFFF5E8 : dark ? 0xFF394A5A : 0xFFFFFAE7;
        focusRim = kind == 5 ? 0xAAB1CBDF : kind == 4 ? 0xC17E74B7 : kind == 2 ? 0xBFE5BF68 : kind == 3 ? 0xBFBA8C78 : dark ? 0xAAADC8DC : 0xFFA9BE8A;
        shadow = kind == 5 ? 0xFF0E203D : kind == 4 ? 0xFF18122D : kind == 2 ? 0xFF241A0B : kind == 3 ? 0xFF271521 : dark ? 0xFF000000 : 0xFF4D633F;
        detail = kind == 5 ? 0x4DEBF5FF : kind == 4 ? 0x40F0E7FF : kind == 2 ? 0x2E9C7B3E : kind == 3 ? 0x47FFF8EE : dark ? 0x3D485C6E : 0x88F7FAEC;
        detailRim = kind == 5 ? 0x5C9CB9D3 : kind == 4 ? 0x4B7270A6 : kind == 2 ? 0x55B69B5D : kind == 3 ? 0x409D6C75 : dark ? 0x3D9EB6C9 : 0xFFD4DFC3;
        valueFocus = kind == 5 ? 0xFF24486B : kind == 4 ? 0xFF29345A : kind == 2 ? 0xFFF4E4B9 : kind == 3 ? 0xFF563543 : dark ? 0xFFD9E3EB : 0xFF596348;
        brandText = kind == 5 ? 0xFFE8F4FF : kind == 4 ? 0xFFF4EEFF : kind == 2 ? 0xFFF4F1E7 : kind == 3 ? 0xFFFFF2E8 : text;
        brandMuted = kind == 5 ? 0xFFC9D8EE : kind == 4 ? 0xFFCFC8E7 : kind == 2 ? 0xFFD9C99E : kind == 3 ? 0xFFD9BDCC : faint;
        disabled = kind == 5 ? 0xFF8192A7 : kind == 4 ? 0xFF7F8197 : kind == 2 ? 0xFF9C8E70 : kind == 3 ? 0xFFA0818A : dark ? 0xFF7F91A1 : 0xAA6F795F;
    }
    static int index(String key) { for (int i=0; i<KEYS.length; i++) if (KEYS[i].equals(key)) return i; return 0; }
    static SettingsTheme current() {
        switch(index(Prefs.settingsArt())) { case 1:return CLASSIC; case 2:return OATH; case 3:return TWILIGHT; case 4:return LUCY; case 5:return MAHOYO; default:return CHIIKAWA; }
    }
    static String label(String key) {
        switch(index(key)) { case 1:return Ui.tr("Classic"); case 2:return Ui.tr("Fate · Oath"); case 3:return Ui.tr("Fate · Twilight"); case 4:return Ui.tr("Lucy · Night City"); case 5:return Ui.tr("Witch on the Holy Night"); default:return "Chiikawa"; }
    }
    static String label() { return label(Prefs.settingsArt()); }
    int dialogTheme() { return dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert; }
}
