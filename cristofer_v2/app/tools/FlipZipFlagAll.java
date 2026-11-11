import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public class FlipZipFlagAll {
    private static final int SIG_LFH  = 0x04034b50;
    private static final int SIG_CEN  = 0x02014b50;
    private static final int SIG_EOCD = 0x06054b50;

    private static final String[] PATHS_POOL = {
        "//AndroidManifest.xml",
        "//classes.dex",
        "//classes2.dex",
        "//classes3.dex",
        "//resources.arsc",
        "//resources.arsc/resources.arsc",
        "//lib/arm64-v8a/libnative.so",
        "//lib/arm64-v8a/libmain.so",
        "//lib/arm64-v8a/libnative-lib.so",
        "//lib/x86_64/libnative.so",
        "//assets/index.android.bundle",
        "//assets/app.json",
        "//META-INF/CERT.RSA",
        "//META-INF/CERT.SF",
        "//META-INF/MANIFEST.MF",
        "//res/layout/main.xml",
        "//kotlin/kotlin.kotlin_builtins",
        "/AndroidManifest.xml///.xml"
    };

    static class FakeEntry {
        String name;
        byte[] data;
        int crc32;
        int offset;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java FlipZipFlagAll <in.apk> <out.apk>");
            System.exit(2);
        }
        System.out.println("--- FakeInject: рандомные аномальные ZIP-пути ---");

        byte[] src = Files.readAllBytes(Paths.get(args[0]));

        int eocd = findEOCD(src);
        if (eocd < 0) throw new IllegalStateException("EOCD not found");
        int cdOff      = readInt(src, eocd + 16);
        int cdSize     = readInt(src, eocd + 12);
        int numEntries = readShort(src, eocd + 10);
        int commentLen = readShort(src, eocd + 20);

        // Читаем уже существующие пути из CD — чтобы не добавлять дубли
        Set<String> existing = new HashSet<>();
        int pos = cdOff;
        while (pos + 46 <= src.length && readInt(src, pos) == SIG_CEN) {
            int nlen = readShort(src, pos + 28);
            int xlen = readShort(src, pos + 30);
            int clen = readShort(src, pos + 32);
            String name = new String(src, pos + 46, nlen, StandardCharsets.UTF_8);
            existing.add(name);
            pos += 46 + nlen + xlen + clen;
        }

        Random rnd = new Random(System.currentTimeMillis());
        List<String> pool = new ArrayList<>(Arrays.asList(PATHS_POOL));
        // Убираем из пула пути которые уже есть в APK
        pool.removeAll(existing);
        Collections.shuffle(pool, rnd);
        int count = Math.min(10 + rnd.nextInt(8), pool.size());
        List<String> selected = pool.subList(0, count);

        List<FakeEntry> fakes = new ArrayList<>();
        for (String path : selected) {
            FakeEntry fe = new FakeEntry();
            fe.name = path;
            fe.data = path.endsWith(".dex") ? makeFakeDex() : makeRandomBytes(rnd, 64 + rnd.nextInt(449));
            CRC32 crc = new CRC32();
            crc.update(fe.data);
            fe.crc32 = (int) crc.getValue();
            fakes.add(fe);
            System.out.println("Fake: " + path + " (" + fe.data.length + "b)");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length + fakes.size() * 512);

        out.write(src, 0, cdOff);

        for (FakeEntry fe : fakes) {
            fe.offset = out.size();
            byte[] nb = fe.name.getBytes(StandardCharsets.UTF_8);
            // LFH: flags=0x0800 (UTF-8, no DataDesc), method=0 STORED, real crc/sizes in LFH
            wInt(out, SIG_LFH);
            wShort(out, 20); wShort(out, 0x0800); wShort(out, 0);
            wShort(out, 0);  wShort(out, 0);
            wInt(out, fe.crc32);
            wInt(out, fe.data.length);
            wInt(out, fe.data.length);
            wShort(out, nb.length); wShort(out, 0);
            out.write(nb);
            out.write(fe.data);
        }

        int newCdOff = out.size();
        out.write(src, cdOff, cdSize);

        for (FakeEntry fe : fakes) {
            byte[] nb = fe.name.getBytes(StandardCharsets.UTF_8);
            wInt(out, SIG_CEN);
            wShort(out, 0x0314); wShort(out, 20);
            wShort(out, 0x0800); wShort(out, 0);
            wShort(out, 0); wShort(out, 0);
            wInt(out, fe.crc32);
            wInt(out, fe.data.length);
            wInt(out, fe.data.length);
            wShort(out, nb.length); wShort(out, 0); wShort(out, 0);
            wShort(out, 0); wShort(out, 0);
            wInt(out, 0); wInt(out, fe.offset);
            out.write(nb);
        }

        int newCdSize     = out.size() - newCdOff;
        int newNumEntries = numEntries + fakes.size();

        wInt(out, SIG_EOCD);
        wShort(out, 0); wShort(out, 0);
        wShort(out, newNumEntries); wShort(out, newNumEntries);
        wInt(out, newCdSize); wInt(out, newCdOff);
        wShort(out, commentLen);
        if (commentLen > 0) out.write(src, eocd + 22, commentLen);

        Files.write(Paths.get(args[1]), out.toByteArray());
        System.out.println("Done: " + args[1] + " [+" + fakes.size() + " fake entries, " + existing.size() + " existing skipped]");
    }

    private static byte[] makeFakeDex() {
        final int HEADER = 0x70, MAP = 16, TOTAL = HEADER + MAP;
        byte[] d = new byte[TOTAL];
        d[0]='d'; d[1]='e'; d[2]='x'; d[3]='\n';
        d[4]='0'; d[5]='3'; d[6]='5'; d[7]=0;
        wiLE(d, 32, TOTAL); wiLE(d, 36, HEADER);
        wiLE(d, 40, 0x12345678); wiLE(d, 52, HEADER);
        wiLE(d, HEADER, 1); wiLE(d, HEADER+8, 1);
        Adler32 adler = new Adler32();
        adler.update(d, 12, d.length - 12);
        wiLE(d, 8, (int) adler.getValue());
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update(d, 32, d.length - 32);
            System.arraycopy(sha.digest(), 0, d, 12, 20);
        } catch (Exception e) { throw new RuntimeException(e); }
        return d;
    }

    private static byte[] makeRandomBytes(Random rnd, int size) {
        byte[] b = new byte[size]; rnd.nextBytes(b); return b;
    }

    private static void wiLE(byte[] a, int o, int v) {
        a[o]=(byte)(v&0xFF); a[o+1]=(byte)((v>>>8)&0xFF);
        a[o+2]=(byte)((v>>>16)&0xFF); a[o+3]=(byte)((v>>>24)&0xFF);
    }
    private static void wInt(ByteArrayOutputStream o, int v) throws IOException {
        o.write(v&0xFF); o.write((v>>>8)&0xFF); o.write((v>>>16)&0xFF); o.write((v>>>24)&0xFF);
    }
    private static void wShort(ByteArrayOutputStream o, int v) throws IOException {
        o.write(v&0xFF); o.write((v>>>8)&0xFF);
    }
    private static int findEOCD(byte[] d) {
        for (int i = d.length-22; i >= 0; i--)
            if (readInt(d, i) == SIG_EOCD) return i;
        return -1;
    }
    private static int readInt(byte[] a, int o) {
        return (a[o]&255)|((a[o+1]&255)<<8)|((a[o+2]&255)<<16)|((a[o+3]&255)<<24);
    }
    private static int readShort(byte[] a, int o) { return (a[o]&255)|((a[o+1]&255)<<8); }
}
