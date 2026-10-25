package io.freemytv;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import java.io.IOException;

/** Original synthesized navigation cues, limited to the home screen and settings. */
final class Click {
    private SoundPool pool;
    private int move, open, back;
    private volatile boolean moveReady, openReady, backReady;
    private long lastMove;

    Click(Context c) {
        try {
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            pool.setOnLoadCompleteListener((p, sample, status) -> {
                if (status == 0 && sample == move) moveReady = true;
                if (status == 0 && sample == open) openReady = true;
                if (status == 0 && sample == back) backReady = true;
            });
            try (AssetFileDescriptor fd = c.getAssets().openFd("sounds/focus-reference-v1.wav")) {
                move = pool.load(fd, 1);
            }
            try (AssetFileDescriptor fd = c.getAssets().openFd("sounds/confirm-reference-v1.wav")) {
                open = pool.load(fd, 1);
            }
            try (AssetFileDescriptor fd = c.getAssets().openFd("sounds/back-reference-v1.wav")) {
                back = pool.load(fd, 1);
            }
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable");
            release();
        }
    }

    void move() {
        long now = SystemClock.elapsedRealtime();
        if (pool != null && moveReady && Prefs.sounds() && now - lastMove >= 55) {
            lastMove = now;
            pool.play(move, 0.24f, 0.24f, 1, 0, 1f);
        }
    }

    void open() {
        if (pool != null && openReady && Prefs.sounds()) pool.play(open, 0.40f, 0.40f, 2, 0, 1f);
    }

    void back() {
        if (pool != null && backReady && Prefs.sounds()) pool.play(back, 0.34f, 0.34f, 2, 0, 1f);
    }

    void release() {
        if (pool != null) pool.release();
        pool = null;
        moveReady = openReady = backReady = false;
    }
}
