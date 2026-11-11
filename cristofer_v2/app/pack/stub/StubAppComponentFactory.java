package STUB_PACKAGE_PLACEHOLDER;

import android.app.AppComponentFactory;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

// Compiled into stub DEX. Catches ClassNotFoundException for providers (e.g. FirebaseInitProvider)
// that live in the encrypted payload — which is not loaded in decoy mode. API 28+ only;
// older versions ignore android:appComponentFactory so this class is never called there.
public class STUB_FACTORY_CLS_PLACEHOLDER extends AppComponentFactory {

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        try {
            return super.instantiateProvider(cl, className);
        } catch (ClassNotFoundException e) {
            android.util.Log.w("StubFactory", "Provider missing in decoy mode: " + className);
            return new NoOpProvider();
        }
    }

    private static final class NoOpProvider extends ContentProvider {
        @Override public boolean onCreate() { return false; }
        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public String getType(Uri u) { return null; }
        @Override public Uri insert(Uri u, ContentValues v) { return null; }
        @Override public int delete(Uri u, String s, String[] a) { return 0; }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
    }
}
