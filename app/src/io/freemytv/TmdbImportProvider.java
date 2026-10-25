package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** A write-only import from the already-authorized ADB shell. Credentials never pass through intent extras or public files. */
public final class TmdbImportProvider extends ContentProvider {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final AtomicBoolean writing = new AtomicBoolean();
    @Override public boolean onCreate() { return true; }
    private void authorize() {
        int uid = Binder.getCallingUid();
        if (uid != 2000 && uid != Process.myUid()) throw new SecurityException("Authorized debugging shell required");
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        authorize();
        if (!"io.freemytv.private-settings".equals(uri.getAuthority()) || !"/tmdb".equals(uri.getPath()) || !"w".equals(mode))
            throw new FileNotFoundException("Unsupported import");
        if (!writing.compareAndSet(false, true)) throw new FileNotFoundException("Import in progress");
        File temporary = new File(getContext().getFilesDir(), "tmdb-settings-import.tmp");
        try {
            ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(temporary,
                    ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_WRITE_ONLY,
                    new Handler(Looper.getMainLooper()), error -> IO.execute(() -> {
                        try {
                            if (error == null) {
                                File pending = new File(getContext().getFilesDir(), "tmdb-settings-import.json");
                                if (temporary.renameTo(pending)) TmdbSettings.importPending(getContext());
                            }
                        } finally { temporary.delete(); writing.set(false); }
                    }));
            // The parent directory is private too; this explicitly retains owner-only access.
            temporary.setReadable(false, false); temporary.setWritable(false, false);
            temporary.setReadable(true, true); temporary.setWritable(true, true);
            return descriptor;
        } catch (IOException e) {
            writing.set(false); temporary.delete(); throw new FileNotFoundException("Cannot open private import");
        }
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        authorize();
        if (!"status".equals(method)) throw new IllegalArgumentException("Unsupported operation");
        Bundle result = new Bundle();
        result.putBoolean("configured", TmdbSettings.configured(getContext()));
        result.putBoolean("encrypted", !getContext().getSharedPreferences("tmdb-settings", 0).getString("cipher", "").isEmpty());
        result.putString("region", TmdbSettings.region(getContext()));
        result.putBoolean("temporary", new File(getContext().getFilesDir(), "tmdb-settings-import.tmp").exists()
                || new File(getContext().getFilesDir(), "tmdb-settings-import.json").exists());
        return result;
    }
    @Override public String getType(Uri uri) { authorize(); return "application/json"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
