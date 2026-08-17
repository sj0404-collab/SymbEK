package dev.symbiosis.kenji;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * Runs before KenjinxApplication. Restores stash and copies Eden firmware
 * into official AppPath so MainActivity does not hang on Loading.
 * Never throws into the official process.
 */
public class SeedProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        try {
            if (getContext() != null) DataSeed.ensure(getContext());
        } catch (Throwable t) {
            Log.e("KenjiSpace", "auto-seed", t);
        }
        return true;
    }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }

    @Override
    public String getType(Uri uri) { return null; }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String sel, String[] args) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String sel, String[] args) { return 0; }
}
