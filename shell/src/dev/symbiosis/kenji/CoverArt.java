package dev.symbiosis.kenji;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Covers without touching libkenjinx in the launcher process.
 *
 * Order: cache → sidecar jpg → Eden/Kenji icon folders → NSP Control NCA.
 * A missing cover is a placeholder, never a crash.
 */
public final class CoverArt {
    private CoverArt() {}

    public static File cacheFile(Context context, GameItem game) {
        File dir = new File(context.getCacheDir(), "covers");
        dir.mkdirs();
        String id = game.titleId;
        if (id == null || id.isEmpty()) {
            id = Integer.toHexString(game.key().hashCode());
        }
        return new File(dir, id + ".jpg");
    }

    public static Bitmap load(Context context, GameItem game) {
        if (game == null) return null;
        File cache = cacheFile(context, game);
        if (cache.isFile() && cache.length() > 32) {
            Bitmap b = BitmapFactory.decodeFile(cache.getAbsolutePath());
            if (b != null) return b;
        }
        File found = findExisting(context, game);
        if (found != null) {
            copy(found, cache);
            return BitmapFactory.decodeFile(found.getAbsolutePath());
        }
        byte[] jpeg = extractNsp(context, game.path);
        if (jpeg != null && jpeg.length > 64) {
            write(cache, jpeg);
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        }
        return null;
    }

    private static File findExisting(Context context, GameItem game) {
        String tid = game.titleId == null ? "" : game.titleId.toLowerCase(Locale.US);
        String tidU = game.titleId == null ? "" : game.titleId.toUpperCase(Locale.US);
        File rom = game.path.startsWith("/") ? new File(game.path) : null;
        if (rom != null && rom.isFile()) {
            String stem = rom.getName();
            int dot = stem.lastIndexOf('.');
            if (dot > 0) stem = stem.substring(0, dot);
            File parent = rom.getParentFile();
            if (parent != null) {
                String[] names = {
                        stem + ".jpg", stem + ".png", stem + ".jpeg",
                        tidU + ".jpg", tidU + ".png", tid + ".jpg", tid + ".png",
                        "cover.jpg", "cover.png", "icon.jpg", "icon.png",
                        "folder.jpg", "folder.png"
                };
                File walk = parent;
                for (int up = 0; up < 3 && walk != null; up++) {
                    for (String n : names) {
                        File f = new File(walk, n);
                        if (f.isFile() && f.length() > 32) return f;
                    }
                    File[] kids = walk.listFiles();
                    if (kids != null) {
                        String lowStem = stem.toLowerCase(Locale.US);
                        for (File k : kids) {
                            String kn = k.getName().toLowerCase(Locale.US);
                            if (!k.isFile() || k.length() <= 32) continue;
                            boolean img = kn.endsWith(".jpg") || kn.endsWith(".jpeg") || kn.endsWith(".png");
                            if (!img) continue;
                            if ((!tid.isEmpty() && kn.contains(tid))
                                    || kn.contains(lowStem)
                                    || kn.contains("chimera")
                                    || kn.contains("cover")
                                    || kn.contains("icon")) {
                                return k;
                            }
                        }
                    }
                    walk = walk.getParentFile();
                }
            }
        }
        File[] roots = new File[]{
                DataSeed.appPath(context),
                DataSeed.bestEden(context),
                DataSeed.userRoot(context),
                new File(android.os.Environment.getExternalStorageDirectory(),
                        "Android/data/org.kenjinx.android/files"),
                new File(android.os.Environment.getExternalStorageDirectory(),
                        "Android/data/dev.eden.eden_emulator/files"),
                new File(android.os.Environment.getExternalStorageDirectory(),
                        "Android/data/org.yuzu.yuzu_emu/files")
        };
        String[] rel = {
                "cache/game_list/" + tid + ".png",
                "cache/game_list/" + tidU + ".png",
                "cache/game_list/" + tid + ".jpg",
                "cache/game_list/" + tidU + ".jpg",
                "cache/" + tid + ".png",
                "cache/" + tidU + ".jpg",
                "cache/icons/" + tid + ".png",
                "cache/icons/" + tidU + ".jpg",
                "games/" + tidU + "/icon.jpg",
                "games/" + tidU + "/icon.png",
                "games/" + tid + "/icon.jpg",
                "nand/cache/" + tid + ".png",
                "icons/" + tid + ".png",
                "icons/" + tidU + ".jpg"
        };
        for (File root : roots) {
            if (root == null) continue;
            for (String r : rel) {
                File f = new File(root, r);
                if (f.isFile() && f.length() > 32) return f;
            }
        }
        return null;
    }

    private static byte[] extractNsp(Context context, String path) {
        if (path == null || !path.startsWith("/")) return null;
        File rom = new File(path);
        if (!rom.isFile()) return null;
        String low = rom.getName().toLowerCase(Locale.US);
        if (!low.endsWith(".nsp")) return null;
        File keys = new File(DataSeed.appPath(context), "system/prod.keys");
        Map<String, byte[]> map = readKeys(keys);
        byte[] headerKey = map.get("header_key");
        if (headerKey == null || headerKey.length != 32) return null;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(rom, "r");
            byte[] magic = new byte[4];
            raf.readFully(magic);
            if (!"PFS0".equals(new String(magic, StandardCharsets.US_ASCII))) return null;
            int count = Integer.reverseBytes(raf.readInt());
            int strSize = Integer.reverseBytes(raf.readInt());
            raf.readInt();
            if (count <= 0 || count > 4096 || strSize <= 0 || strSize > 1_000_000) return null;
            long[] offs = new long[count];
            long[] sizes = new long[count];
            int[] nameOff = new int[count];
            for (int i = 0; i < count; i++) {
                offs[i] = Long.reverseBytes(raf.readLong());
                sizes[i] = Long.reverseBytes(raf.readLong());
                nameOff[i] = Integer.reverseBytes(raf.readInt());
                raf.readInt();
            }
            byte[] strings = new byte[strSize];
            raf.readFully(strings);
            long dataStart = 16L + 24L * count + strSize;
            for (int i = 0; i < count; i++) {
                String name = cstr(strings, nameOff[i]).toLowerCase(Locale.US);
                if (!name.endsWith(".nca") || name.endsWith(".cnmt.nca")) continue;
                if (sizes[i] < 0xC00 || sizes[i] > 12L * 1024 * 1024) continue;
                byte[] nca = new byte[(int) sizes[i]];
                raf.seek(dataStart + offs[i]);
                raf.readFully(nca);
                byte[] jpeg = controlIcon(nca, map, headerKey);
                if (jpeg != null) return jpeg;
            }
        } catch (Throwable ignored) {
        } finally {
            if (raf != null) try { raf.close(); } catch (Exception ignored) {}
        }
        return null;
    }

    private static byte[] controlIcon(byte[] nca, Map<String, byte[]> keys, byte[] headerKey) {
        try {
            byte[] header = decryptXts(headerKey, slice(nca, 0, 0xC00));
            if (header.length < 0x340) return null;
            if (header[0x200] != 'N' || header[0x201] != 'C' || header[0x202] != 'A') return null;
            int contentType = header[0x205] & 0xFF;
            if (contentType != 2) return null; // Control
            boolean hasRights = false;
            for (int i = 0; i < 16; i++) {
                if (header[0x230 + i] != 0) { hasRights = true; break; }
            }
            if (hasRights) return null; // needs title.keys
            int keyGen = header[0x220] & 0xFF;
            if (keyGen == 0) keyGen = header[0x206] & 0xFF;
            int kaIndex = header[0x207] & 0xFF;
            String kaName = kaIndex == 1 ? "ocean" : kaIndex == 2 ? "system" : "application";
            String kaKey = String.format(Locale.US, "key_area_key_%s_%02x", kaName, keyGen);
            byte[] kak = keys.get(kaKey);
            if (kak == null || kak.length != 16) return null;
            byte[] decKeys = aesEcb(kak, slice(header, 0x300, 0x40), false);
            if (decKeys.length < 48) return null;
            byte[] sectionKey = slice(decKeys, 32, 16);
            int startSector = le32(header, 0x240);
            int endSector = le32(header, 0x244);
            if (endSector <= startSector) return null;
            int start = startSector * 0x200;
            int size = (endSector - startSector) * 0x200;
            if (start < 0 || size <= 0 || start + size > nca.length) return null;
            if (size > 8 * 1024 * 1024) size = 8 * 1024 * 1024;
            byte[] ctr = new byte[16];
            // FS header lives at the start of the section; CTR field at +0x140 after decrypt.
            // First decrypt a window with zeroed offset, then refine.
            byte[] section = aesCtr(sectionKey, ctr, slice(nca, start, size), 0);
            // After decrypt, bytes at 0x140 are the stored CTR. Re-decrypt if needed.
            if (section.length > 0x148) {
                byte[] stored = slice(section, 0x140, 8);
                boolean nonzero = false;
                for (byte b : stored) if (b != 0) nonzero = true;
                if (nonzero) {
                    System.arraycopy(stored, 0, ctr, 0, 8);
                    section = aesCtr(sectionKey, ctr, slice(nca, start, size), 0);
                }
            }
            return findJpeg(section);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] findJpeg(byte[] data) {
        if (data == null) return null;
        int start = -1;
        for (int i = 0; i < data.length - 3; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD8 && (data[i + 2] & 0xFF) == 0xFF) {
                start = i;
                break;
            }
        }
        if (start < 0) return null;
        for (int i = start + 3; i < data.length - 1; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD9) {
                int len = i + 2 - start;
                if (len < 64 || len > 600_000) continue;
                byte[] out = new byte[len];
                System.arraycopy(data, start, out, 0, len);
                return out;
            }
        }
        return null;
    }

    private static Map<String, byte[]> readKeys(File prod) {
        Map<String, byte[]> map = new HashMap<String, byte[]>();
        if (prod == null || !prod.isFile()) return map;
        try {
            java.util.Scanner s = new java.util.Scanner(prod, "UTF-8");
            while (s.hasNextLine()) {
                String line = s.nextLine().trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                int eq = line.indexOf('=');
                String k = line.substring(0, eq).trim().toLowerCase(Locale.US);
                String hex = line.substring(eq + 1).trim().replace(" ", "");
                if (hex.length() < 32 || hex.length() % 2 != 0) continue;
                boolean ok = true;
                for (int i = 0; i < hex.length(); i++) {
                    char c = hex.charAt(i);
                    if (Character.digit(c, 16) < 0) { ok = false; break; }
                }
                if (!ok) continue;
                byte[] b = new byte[hex.length() / 2];
                for (int i = 0; i < b.length; i++) {
                    b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                }
                map.put(k, b);
            }
            s.close();
        } catch (Exception ignored) {
        }
        return map;
    }

    private static byte[] decryptXts(byte[] key, byte[] data) throws Exception {
        SecretKeySpec k1 = new SecretKeySpec(key, 0, 16, "AES");
        SecretKeySpec k2 = new SecretKeySpec(key, 16, 16, "AES");
        Cipher dec = Cipher.getInstance("AES/ECB/NoPadding");
        Cipher twk = Cipher.getInstance("AES/ECB/NoPadding");
        dec.init(Cipher.DECRYPT_MODE, k1);
        twk.init(Cipher.ENCRYPT_MODE, k2);
        byte[] out = new byte[data.length];
        int sector = 0;
        int off = 0;
        final int SECTOR = 0x200;
        while (off < data.length) {
            byte[] tweak = new byte[16];
            long v = sector;
            for (int i = 0; i < 16; i++) {
                tweak[i] = (byte) (v & 0xFF);
                v >>>= 8;
            }
            byte[] t = twk.doFinal(tweak);
            int i = 0;
            while (i < SECTOR && off + i < data.length) {
                byte[] block = new byte[16];
                System.arraycopy(data, off + i, block, 0, 16);
                xor(block, t);
                byte[] plain = dec.doFinal(block);
                xor(plain, t);
                System.arraycopy(plain, 0, out, off + i, 16);
                gf128(t);
                i += 16;
            }
            off += SECTOR;
            sector++;
        }
        return out;
    }

    private static byte[] aesEcb(byte[] key, byte[] data, boolean encrypt) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(data);
    }

    private static byte[] aesCtr(byte[] key, byte[] ctr0, byte[] data, long offset) throws Exception {
        byte[] ctr = ctr0.clone();
        long blocks = offset / 16;
        for (int i = 15; i >= 8 && blocks > 0; i--) {
            long v = (ctr[i] & 0xFF) + (blocks & 0xFF);
            ctr[i] = (byte) v;
            blocks = (blocks >>> 8) + (v >>> 8);
        }
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(ctr));
        return c.doFinal(data);
    }

    private static void xor(byte[] a, byte[] b) {
        for (int i = 0; i < a.length; i++) a[i] = (byte) (a[i] ^ b[i]);
    }

    private static void gf128(byte[] t) {
        int carry = 0;
        for (int i = 0; i < 16; i++) {
            int b = t[i] & 0xFF;
            t[i] = (byte) ((b << 1) | carry);
            carry = b >>> 7;
        }
        if (carry != 0) t[0] = (byte) (t[0] ^ 0x87);
    }

    private static int le32(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static byte[] slice(byte[] src, int off, int len) {
        byte[] o = new byte[len];
        System.arraycopy(src, off, o, 0, len);
        return o;
    }

    private static String cstr(byte[] s, int off) {
        if (off < 0 || off >= s.length) return "";
        int e = off;
        while (e < s.length && s[e] != 0) e++;
        return new String(s, off, e - off, StandardCharsets.UTF_8);
    }

    private static void copy(File from, File to) {
        try {
            FileInputStream in = new FileInputStream(from);
            FileOutputStream out = new FileOutputStream(to);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
        } catch (Exception ignored) {
        }
    }

    private static void write(File to, byte[] data) {
        try {
            FileOutputStream out = new FileOutputStream(to);
            out.write(data);
            out.close();
        } catch (Exception ignored) {
        }
    }
}
