package net.kayralrics.autoclicker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class TargetStore {
    static final class Target {
        String id;
        String name;
        boolean enabled;
        Target(String id, String name, boolean enabled) {
            this.id = id; this.name = name; this.enabled = enabled;
        }
    }

    static final class Settings {
        int interval = 500;
        int threshold = 85;
        List<Target> targets = new ArrayList<>();
    }

    private final File directory;
    private final AtomicFile settingsFile;

    TargetStore(Context context) {
        directory = new File(context.getFilesDir(), "targets");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create private target directory");
        }
        settingsFile = new AtomicFile(new File(context.getFilesDir(), "settings.json"));
    }

    synchronized Settings read() {
        Settings s = new Settings();
        if (!settingsFile.getBaseFile().exists()) return s;
        try (InputStream in = settingsFile.openRead()) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) >= 0) bytes.write(chunk, 0, n);
            JSONObject data = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            s.interval = clamp(data.optInt("interval", 500), 100, 10000);
            s.threshold = clamp(data.optInt("threshold", 85), 50, 99);
            JSONArray items = data.optJSONArray("targets");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("id", "");
                    if (!id.matches("[a-f0-9\\-]{36}")) continue;
                    File image = new File(directory, id + ".png");
                    if (image.isFile()) {
                        s.targets.add(new Target(id, item.optString("name", "target"),
                            item.optBoolean("enabled", true)));
                    }
                }
            }
            return s;
        } catch (Exception e) {
            throw new IllegalStateException("Stored settings could not be loaded: " + e.getMessage(), e);
        }
    }

    synchronized void write(Settings s) {
        FileOutputStream stream = null;
        try {
            JSONObject data = new JSONObject();
            data.put("interval", clamp(s.interval, 100, 10000));
            data.put("threshold", clamp(s.threshold, 50, 99));
            JSONArray items = new JSONArray();
            for (Target t : s.targets) {
                JSONObject item = new JSONObject();
                item.put("id", t.id);
                item.put("name", t.name);
                item.put("enabled", t.enabled);
                items.put(item);
            }
            data.put("targets", items);
            stream = settingsFile.startWrite();
            stream.write(data.toString(2).getBytes(StandardCharsets.UTF_8));
            settingsFile.finishWrite(stream);
        } catch (Exception e) {
            if (stream != null) settingsFile.failWrite(stream);
            throw new IllegalStateException("Failed saving settings", e);
        }
    }

    synchronized Target add(Bitmap crop) {
        if (crop == null || crop.getWidth() < 12 || crop.getHeight() < 12 ||
            (long) crop.getWidth() * crop.getHeight() > 2_000_000L) {
            throw new IllegalArgumentException("Select a region from 12x12 to 2 million pixels.");
        }
        ensureTexture(crop);
        Settings s = read();
        String id = UUID.randomUUID().toString();
        File temp = new File(directory, id + ".tmp");
        File image = new File(directory, id + ".png");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            if (!crop.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("PNG encoding failed");
            }
            output.getFD().sync();
        } catch (Exception e) {
            temp.delete();
            throw new IllegalStateException("Cannot save target image", e);
        }
        if (!temp.renameTo(image)) {
            temp.delete();
            throw new IllegalStateException("Cannot finalize target image");
        }
        Target target = new Target(id, "target " + (s.targets.size() + 1), true);
        s.targets.add(target);
        try {
            write(s);
        } catch (RuntimeException e) {
            image.delete();
            throw e;
        }
        return target;
    }

    synchronized void remove(String id) {
        Settings s = read();
        s.targets.removeIf(t -> t.id.equals(id));
        write(s);
        new File(directory, id + ".png").delete();
    }

    Bitmap load(String id) {
        return BitmapFactory.decodeFile(new File(directory, id + ".png").getAbsolutePath());
    }

    static int clamp(int v, int low, int high) { return Math.max(low, Math.min(high, v)); }

    private static void ensureTexture(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        double sum = 0, square = 0;
        int count = 0;
        for (int y = 0; y < h; y += Math.max(1, h / 45)) {
            for (int x = 0; x < w; x += Math.max(1, w / 45)) {
                int p = b.getPixel(x, y);
                double v = ((p >> 16) & 255) * .299 + ((p >> 8) & 255) * .587 +
                           (p & 255) * .114;
                sum += v;
                square += v * v;
                count++;
            }
        }
        double variance = square / count - (sum / count) * (sum / count);
        if (variance < 49.0) {
            throw new IllegalArgumentException("Region is too uniform. Include text or an icon.");
        }
    }
}
