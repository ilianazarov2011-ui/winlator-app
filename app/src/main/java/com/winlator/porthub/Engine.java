package com.winlator.porthub;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;

/** Логика PortHub: контейнер, настройки, скачивание Студии, запуск. */
public class Engine {

    private static final String TAG = "PortHub";
    public static final String CONTAINER_NAME = "Studio";
    private static final String DXVK = "dxvk-1.10.3";

    // значения для настроек (порядок = порядок кнопок)
    public static final String[] SCREENS = {"960x540", "1024x576", "1280x720"};
    public static final String[] SCREEN_LABELS = {"960x540 — быстро", "1024x576", "1280x720 — чётко"};
    public static final String[] PRESETS = {"Стабильно", "Быстро", "Максимум"};
    public static final String[] STARTUP = {"0", "1"};
    public static final String[] STARTUP_LABELS = {"Все службы", "Только нужные"};
    public static final String[] MEM = {"512", "1024", "2048"};
    public static final String[] FPS = {"30", "60", "0"};
    public static final String[] FPS_LABELS = {"30", "60", "без лимита"};

    private static final StringBuilder LOG = new StringBuilder();

    public interface LogSink { void progress(int p); }

    public static synchronized void log(String s) {
        Log.i(TAG, s);
        LOG.append(s).append('\n');
        if (LOG.length() > 300000) LOG.delete(0, 150000);
    }

    public static synchronized String logText() { return LOG.toString(); }
    public static synchronized void clearLog() { LOG.setLength(0); }

    public static String read(File f) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        } catch (Throwable t) { return "ошибка чтения: " + t; }
        return sb.toString();
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    // ------------------------------------------------------------------ настройки

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("porthub", Context.MODE_PRIVATE);
    }

    public static String get(Context ctx, String key, String def) {
        return prefs(ctx).getString(key, def);
    }

    public static int getInt(Context ctx, String key, int def) {
        try { return Integer.parseInt(get(ctx, key, String.valueOf(def))); } catch (Throwable t) { return def; }
    }

    public static void set(Context ctx, String key, String value) {
        prefs(ctx).edit().putString(key, value).apply();
    }

    public static void reset(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static String buildEnv(Context ctx) {
        String preset = get(ctx, "preset", "Быстро");
        String box;
        if ("Стабильно".equals(preset)) {
            box = "BOX64_DYNAREC_BIGBLOCK=1 BOX64_DYNAREC_SAFEFLAGS=2";
        } else if ("Максимум".equals(preset)) {
            box = "BOX64_DYNAREC_BIGBLOCK=3 BOX64_DYNAREC_SAFEFLAGS=0 BOX64_DYNAREC_FASTNAN=1"
                + " BOX64_DYNAREC_FASTROUND=2 BOX64_DYNAREC_CALLRET=1 BOX64_DYNAREC_FORWARD=1024";
        } else {
            box = "BOX64_DYNAREC_BIGBLOCK=2 BOX64_DYNAREC_SAFEFLAGS=1 BOX64_DYNAREC_FASTNAN=1"
                + " BOX64_DYNAREC_FASTROUND=1";
        }
        String env = box + " BOX64_NOBANNER=1 BOX64_DYNAREC_STRONGMEM=0 WINEDEBUG=-all";
        int fps = getInt(ctx, "fps", 30);
        if (fps > 0) env = env + " DXVK_FRAME_RATE=" + fps;
        return env;
    }

    // ------------------------------------------------------------------ контейнер

    public static ContainerManager manager(Context ctx) { return new ContainerManager(ctx); }

    /** Настоящий id лежит в поле Container.id. */
    public static int id(Container c) {
        if (c == null) return -1;
        try {
            Field f = Container.class.getDeclaredField("id");
            f.setAccessible(true);
            Object v = f.get(c);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable t) { log("поле id недоступно: " + t); }
        try {
            File r = c.getRootDir();
            if (r != null) return Integer.parseInt(r.getName());
        } catch (Throwable ignored) {}
        return -1;
    }

    public static Container ensureContainer(Context ctx, ContainerManager cm) {
        for (Object o : cm.getContainers()) {
            Container c = (Container) o;
            log("  контейнер id=" + id(c) + " \"" + c.getName() + "\"");
        }
        for (Object o : cm.getContainers()) {
            Container c = (Container) o;
            if (findStudioExe(c.getRootDir()) != null) {
                log("в контейнере id=" + id(c) + " уже стоит Студия — беру его");
                return finish(ctx, c);
            }
        }
        for (Object o : cm.getContainers()) {
            Container c = (Container) o;
            if (CONTAINER_NAME.equals(c.getName())) { log("нашёл контейнер " + CONTAINER_NAME); return finish(ctx, c); }
        }

        log("создаю контейнер \"" + CONTAINER_NAME + "\"...");
        final JSONObject data = new JSONObject();
        try {
            data.put("name", CONTAINER_NAME);
            data.put("screenSize", get(ctx, "screen", "960x540"));
        } catch (Throwable ignored) {}

        final Container[] made = new Container[1];
        ((Activity) ctx).runOnUiThread(() -> cm.createContainerAsync(data, cont -> {
            made[0] = (Container) cont;
            log("колбэк: контейнер готов");
        }));

        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 360000) {
            sleep(2000);
            if (made[0] != null) return finish(ctx, made[0]);
            for (Object o : cm.getContainers()) {
                Container c = (Container) o;
                if (CONTAINER_NAME.equals(c.getName())) { log("контейнер появился в списке"); return finish(ctx, c); }
            }
        }
        log("! контейнер не появился за 6 минут");
        return null;
    }

    private static Container finish(Context ctx, Container c) {
        File root = c.getRootDir();
        log("папка контейнера: " + root);
        if (root != null) {
            long t = System.currentTimeMillis();
            while (System.currentTimeMillis() - t < 360000) {
                File[] kids = root.listFiles();
                boolean hasDir = false;
                if (kids != null) for (File k : kids) if (k.isDirectory()) { hasDir = true; break; }
                if (hasDir) break;
                sleep(2000);
            }
        }
        applySettings(ctx, c);
        dump(c);
        return c;
    }

    public static void applySettings(Context ctx, Container c) {
        if (c.getName() == null || c.getName().isEmpty()) c.setName(CONTAINER_NAME);
        c.setScreenSize(get(ctx, "screen", "960x540"));
        c.setGraphicsDriver("system");
        c.setDXWrapper(DXVK);
        c.setAudioDriver("alsa");
        c.setEnvVars(buildEnv(ctx));
        c.setStartupSelection((byte) getInt(ctx, "startup", 1));
        c.putExtra("winVersion", "win10");
        c.putExtra("videoMemorySize", get(ctx, "mem", "1024"));
        c.saveData();
        log("настройки применены: экран=" + c.getScreenSize()
                + " службы=" + c.getStartupSelection()
                + " память=" + c.getExtra("videoMemorySize", "?")
                + " fps=" + get(ctx, "fps", "30")
                + " профиль=" + get(ctx, "preset", "Быстро"));
    }

    /** Переприменить настройки ко всем существующим контейнерам. */
    public static void applyToExisting(Context ctx) {
        try {
            ContainerManager cm = manager(ctx);
            for (Object o : cm.getContainers()) applySettings(ctx, (Container) o);
        } catch (Throwable t) { log("! применить настройки: " + t); }
    }

    public static void applyInBackground(Context ctx) {
        new Thread(() -> applyToExisting(ctx)).start();
    }

    public static void dump(Container c) {
        log("--- состояние контейнера ---");
        log("  id = " + id(c));
        log("  name = " + c.getName());
        log("  screenSize = " + c.getScreenSize());
        log("  graphicsDriver = " + c.getGraphicsDriver());
        log("  dxwrapper = " + c.getDXWrapper());
        log("  audioDriver = " + c.getAudioDriver());
        log("  box64Preset = " + c.getBox64Preset());
        log("  envVars = " + c.getEnvVars());
        log("  startupSelection = " + c.getStartupSelection());
        log("  rootDir = " + c.getRootDir());
        String[] keys = {"winVersion", "videoMemorySize", "box64Version", "execArgs"};
        for (String k : keys) log("  extra[" + k + "] = " + c.getExtra(k, "нет"));
        File cf = c.getConfigFile();
        if (cf != null && cf.exists()) {
            log("--- файл " + cf.getName() + " ---");
            log(read(cf));
            log("--- /файл ---");
        }
    }

    public static void launch(Context ctx, Container c, String execArgs) {
        ContainerManager cm = manager(ctx);
        int i = id(c);
        log("--- запуск ---");
        log("id контейнера = " + i + ", папка = " + c.getRootDir());

        if (i < 0) {
            log("! id не определился — открываю меню движка");
            openEngineUi(ctx);
            return;
        }

        Container fresh = cm.getContainerById(i);
        log("getContainerById(" + i + ") -> " + (fresh == null ? "null" : "\"" + fresh.getName() + "\""));
        if (fresh != null) c = fresh;

        if (execArgs != null) {
            c.putExtra("execArgs", execArgs);
            c.saveData();
            log("команда запуска: " + execArgs);
        }

        Intent it = new Intent(ctx, XServerDisplayActivity.class);
        it.putExtra("container_id", i);
        ctx.startActivity(it);
    }

    public static void openEngineUi(Context ctx) {
        try { ctx.startActivity(new Intent(ctx, com.winlator.MainActivity.class)); }
        catch (Throwable t) { log("! не открыть меню движка: " + t); }
    }

    // ------------------------------------------------------------------ студия

    public static File findStudioExe(File dir) {
        if (dir == null) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory()) {
                File f = findStudioExe(k);
                if (f != null) return f;
            } else if (k.getName().equalsIgnoreCase("RobloxStudioBeta.exe")) return k;
        }
        return null;
    }

    public static String toWindowsPath(File f, File containerDir) {
        if (f == null || containerDir == null) return null;
        String rel = f.getAbsolutePath().substring(containerDir.getAbsolutePath().length());
        int i = rel.indexOf("drive_c/");
        if (i < 0) return null;
        return "C:\\" + rel.substring(i + "drive_c/".length()).replace('/', '\\');
    }

    private static String httpText(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            BufferedReader r = new BufferedReader(new java.io.InputStreamReader(c.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            return sb.toString();
        } catch (Throwable t) { log("! " + url + " -> " + t); return null; }
    }

    public static String fetchVersionGuid() {
        String guid = null;
        String t = httpText("https://clientsettingscdn.roblox.com/v1/client-version/WindowsStudio64");
        if (t != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"clientVersionUpload\"\\s*:\\s*\"([^\"]+)\"").matcher(t);
            if (m.find()) guid = m.group(1).replace("version-", "");
        }
        log("версия Студии: " + (guid == null ? "не определилась" : guid));
        return guid;
    }

    private static boolean probe(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            c.disconnect();
            log("  проба [" + code + "] " + url);
            return code == 200 || code == 206;
        } catch (Throwable t) { log("  проба [--] " + url + " (" + t + ")"); return false; }
    }

    private static File findExistingInstaller() {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName().toLowerCase();
            if (f.isFile() && n.contains("robloxstudio") && n.endsWith(".exe")) {
                log("нашёл готовый установщик: " + f);
                return f;
            }
        }
        return null;
    }

    public static File downloadInstaller(String guid, LogSink sink) {
        File have = findExistingInstaller();
        if (have != null) return have;

        String[] urls = {
                "https://setup.rbxcdn.com/version-" + guid + "-RobloxStudioInstaller.exe",
                "https://setup.rbxcdn.com/version-" + guid + "-RobloxStudioLauncherBeta.exe",
                "https://setup.rbxcdn.com/version-" + guid + "/RobloxStudioInstaller.exe"
        };
        log("перебираю адреса установщика...");
        for (String u : urls) if (probe(u)) return downloadTo(u, u.substring(u.lastIndexOf('/') + 1), sink);
        log("! ни один адрес не подошёл");
        return null;
    }

    private static File downloadTo(String url, String name, LogSink sink) {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, name);
        log("качаю: " + url);
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            int total = c.getContentLength();
            InputStream in = c.getInputStream();
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            long read = 0;
            int last = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                read += n;
                if (total > 0) {
                    int p = (int) (read * 100 / total);
                    if (p != last && p % 5 == 0) {
                        last = p;
                        sink.progress(p);
                        if (p % 20 == 0) log("  скачано " + p + "%");
                    }
                }
            }
            fos.close();
            in.close();
            log("скачано: " + (out.length() / 1024 / 1024) + " МБ -> " + out);
            return out;
        } catch (Throwable t) { log("! скачивание: " + t); return null; }
    }
}
