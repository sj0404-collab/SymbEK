package dev.symbiosis.kenji;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * Runs before KenjinxApplication. Restores stash and points bis/ at Eden
 * nand (or another Kenji bis) via shortcuts — no firmware copy.
 * Never throws into the official process.
 */
public class SeedProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        try {
            if (getContext() != null) {
                WebWipe.run(getContext());
                DataSeed.ensure(getContext());
            }
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
