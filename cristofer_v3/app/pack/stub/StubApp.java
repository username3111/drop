package STUB_PACKAGE_PLACEHOLDER;

import android.app.Application;
import android.content.Context;
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

    private static final String G1, G2, G3, G4, G5, G6, G7, G8, G9, G10, G11, G12, G13, G14;

    static {
        StringBuilder q = new StringBuilder();
        G1  = q.append("dalvik.sy").append("stem.De").append("xClass").append("Loader").toString(); q.setLength(0);
        G2  = q.append("dalvik.sy").append("stem.Ba").append("seDex").append("Class").append("Loader").toString(); q.setLength(0);
        G3  = q.append("android.a").append("pp.Activ").append("ityThre").append("ad").toString(); q.setLength(0);
        G4  = q.append("current").append("Activity").append("Thread").toString(); q.setLength(0);
        G5  = q.append("mPa").append("ckag").append("es").toString(); q.setLength(0);
        G6  = q.append("mCla").append("ssLoad").append("er").toString(); q.setLength(0);
        G7  = q.append("mInitial").append("Applic").append("ation").toString(); q.setLength(0);
        G8  = q.append("mAllApp").append("licat").append("ions").toString(); q.setLength(0);
        G9  = q.append("mApp").append("licat").append("ion").toString(); q.setLength(0);
        G10 = q.append("path").append("List").toString(); q.setLength(0);
        G11 = q.append("native").append("Library").append("Direct").append("ories").toString(); q.setLength(0);
        G12 = q.append("systemN").append("ative").append("Library").append("Direct").append("ories").toString(); q.setLength(0);
        G13 = q.append("native").append("Library").append("PathEle").append("ments").toString(); q.setLength(0);
        G14 = q.append("dex").append("Ele").append("ments").toString(); q.setLength(0);
        System.loadLibrary("DEXLDR_LIBNAME_PLACEHOLDER");
    }

    private native byte[] nativeUnpack();
    private native int LITE_CHECK_SYMBOL_PLACEHOLDER();

    private Application realApp = null;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        int lite = 0;
        try { lite = LITE_CHECK_SYMBOL_PLACEHOLDER(); } catch (Throwable ignored) {}

        if (lite != 0) {
            if (lite != 3) {
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
            byte[] dx = nativeUnpack();
            if (dx == null || dx.length < 32) return;

            ClassLoader par = getClassLoader();
            ClassLoader bootstrap = ClassLoader.getSystemClassLoader().getParent();
            ClassLoader ncl;

            // Detect multi-DEX payload (magic "MDXM")
            boolean isMulti = dx.length >= 8
                    && dx[0] == 0x4D && dx[1] == 0x44 && dx[2] == 0x58 && dx[3] == 0x4D;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (isMulti && android.os.Build.VERSION.SDK_INT >= 27) {
                    // API 27+: InMemoryDexClassLoader(ByteBuffer[], ClassLoader)
                    int count = (dx[4] & 0xFF) | ((dx[5] & 0xFF) << 8) | ((dx[6] & 0xFF) << 16) | ((dx[7] & 0xFF) << 24);
                    ByteBuffer[] bufs = new ByteBuffer[count];
                    int off = 8;
                    for (int i = 0; i < count; i++) {
                        int len = (dx[off] & 0xFF) | ((dx[off+1] & 0xFF) << 8) | ((dx[off+2] & 0xFF) << 16) | ((dx[off+3] & 0xFF) << 24);
                        off += 4;
                        bufs[i] = ByteBuffer.wrap(dx, off, len);
                        off += len;
                    }
                    { byte[] _ie={IMDC_ENC_BYTES}; StringBuilder _is=new StringBuilder(_ie.length); for(byte _b:_ie)_is.append((char)(_b^IMDC_XOR_KEY)); Constructor<?> ct=Class.forName(_is.toString()).getConstructor(ByteBuffer[].class,ClassLoader.class); _is.setLength(0); java.util.Arrays.fill(_ie,(byte)0); ncl=(ClassLoader)ct.newInstance(bufs,par); }
                } else {
                    // Single DEX or API 26 (only first DEX from multi)
                    ByteBuffer buf;
                    if (isMulti) {
                        int len = (dx[8] & 0xFF) | ((dx[9] & 0xFF) << 8) | ((dx[10] & 0xFF) << 16) | ((dx[11] & 0xFF) << 24);
                        buf = ByteBuffer.wrap(dx, 12, len);
                    } else {
                        buf = ByteBuffer.wrap(dx);
                    }
                    { byte[] _ie={IMDC_ENC_BYTES}; StringBuilder _is=new StringBuilder(_ie.length); for(byte _b:_ie)_is.append((char)(_b^IMDC_XOR_KEY)); Constructor<?> ct=Class.forName(_is.toString()).getConstructor(ByteBuffer.class,ClassLoader.class); _is.setLength(0); java.util.Arrays.fill(_ie,(byte)0); ncl=(ClassLoader)ct.newInstance(buf,par); }
                }
                java.util.Arrays.fill(dx, (byte) 0);
            } else {
                File df = new File(base.getCodeCacheDir(), ".r");
                FileOutputStream fo = new FileOutputStream(df);
                // Write first DEX only for file-based loader
                if (isMulti) {
                    int len = (dx[8] & 0xFF) | ((dx[9] & 0xFF) << 8) | ((dx[10] & 0xFF) << 16) | ((dx[11] & 0xFF) << 24);
                    fo.write(dx, 12, len);
                } else {
                    fo.write(dx);
                }
                fo.close();
                java.util.Arrays.fill(dx, (byte) 0);
                Constructor<?> ct = Class.forName(G1).getConstructor(
                        String.class, String.class, String.class, ClassLoader.class);
                ncl = (ClassLoader) ct.newInstance(df.getAbsolutePath(), null, null, bootstrap);
                df.delete();
            }

            cpNativeDirs(par, ncl);
            swapCl(base, ncl);

            Class<?> rc = ncl.loadClass("REAL_APP_FQN_PLACEHOLDER");
            Application ra = (Application) rc.getDeclaredConstructor().newInstance();
            Method at = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            at.setAccessible(true);
            at.invoke(ra, base);
            swapApp(base, ra);
            this.realApp = ra;
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (realApp != null) try { realApp.onCreate(); } catch (Throwable ignored) {}
    }

    @Override
    public void onTerminate() {
        if (realApp != null) try { realApp.onTerminate(); } catch (Throwable ignored) {}
        super.onTerminate();
    }

    @Override
    public void onLowMemory() {
        if (realApp != null) try { realApp.onLowMemory(); } catch (Throwable ignored) {}
        super.onLowMemory();
    }

    @Override
    public void onTrimMemory(int lv) {
        if (realApp != null) try { realApp.onTrimMemory(lv); } catch (Throwable ignored) {}
        super.onTrimMemory(lv);
    }

    private static void cpNativeDirs(ClassLoader src, ClassLoader dst) {
        try {
            Class<?> bc = Class.forName(G2);
            Field plf = bc.getDeclaredField(G10);
            plf.setAccessible(true);
            Object sp = plf.get(src), dp = plf.get(dst);
            if (sp == null || dp == null) return;
            for (String fn : new String[]{G11, G12}) {
                try {
                    Field f = sp.getClass().getDeclaredField(fn);
                    f.setAccessible(true);
                    f.set(dp, f.get(sp));
                } catch (NoSuchFieldException ignored) {}
            }
            try {
                Field ef = sp.getClass().getDeclaredField(G13);
                ef.setAccessible(true);
                ef.set(dp, ef.get(sp));
            } catch (NoSuchFieldException ignored) {}
        } catch (Throwable ignored) {}
    }

    private static void injectNclDex(ClassLoader par, ClassLoader ncl) {
        try {
            Class<?> bdc = Class.forName(G2);
            Field plf = bdc.getDeclaredField(G10);
            plf.setAccessible(true);
            Object parPL = plf.get(par);
            Object nclPL = plf.get(ncl);
            if (parPL == null || nclPL == null) return;

            Field def = nclPL.getClass().getDeclaredField(G14);
            def.setAccessible(true);
            Object[] nclElems = (Object[]) def.get(nclPL);
            Object[] parElems = (Object[]) def.get(parPL);
            if (nclElems == null || nclElems.length == 0) return;

            int parLen = parElems != null ? parElems.length : 0;
            Object[] merged = (Object[]) java.lang.reflect.Array.newInstance(
                nclElems.getClass().getComponentType(), parLen + nclElems.length);
            if (parElems != null) System.arraycopy(parElems, 0, merged, 0, parLen);
            System.arraycopy(nclElems, 0, merged, parLen, nclElems.length);
            def.set(parPL, merged);
        } catch (Throwable ignored) {}
    }

    private static void swapCl(Context ctx, ClassLoader ncl) throws Exception {
        Class<?> ac = Class.forName(G3);
        Object th = ac.getMethod(G4).invoke(null);
        Field pf = ac.getDeclaredField(G5);
        pf.setAccessible(true);
        Object pkgs = pf.get(th);
        Object ar = pkgs.getClass().getMethod("get", Object.class).invoke(pkgs, ctx.getPackageName());
        if (ar == null) return;
        Object la = ar instanceof WeakReference ? ((WeakReference<?>) ar).get() : ar;
        if (la == null) return;
        Field cf = la.getClass().getDeclaredField(G6);
        cf.setAccessible(true);
        cf.set(la, ncl);
    }

    @SuppressWarnings("unchecked")
    private static void swapApp(Context ctx, Application ra) throws Exception {
        Class<?> ac = Class.forName(G3);
        Object th = ac.getMethod(G4).invoke(null);
        Field i0 = ac.getDeclaredField(G7);
        i0.setAccessible(true);
        i0.set(th, ra);
        try {
            Field i1 = ac.getDeclaredField(G8);
            i1.setAccessible(true);
            List<Application> al = (List<Application>) i1.get(th);
            if (al != null && !al.contains(ra)) al.add(ra);
        } catch (NoSuchFieldException ignored) {}
        try {
            Field pf = ac.getDeclaredField(G5);
            pf.setAccessible(true);
            Object pkgs = pf.get(th);
            Object ar = pkgs.getClass().getMethod("get", Object.class).invoke(pkgs, ctx.getPackageName());
            Object la = ar instanceof WeakReference ? ((WeakReference<?>) ar).get() : ar;
            if (la != null) {
                Field af = la.getClass().getDeclaredField(G9);
                af.setAccessible(true);
                af.set(la, ra);
            }
        } catch (Throwable ignored) {}
    }

}
