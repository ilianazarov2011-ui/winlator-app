package com.winlator.porthub;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Environment;
import android.util.Log;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

/** Ядро PortHub: контейнеры, Windows-префикс, программы, запуск. */
public class Engine {

    private static final String TAG = "PortHub";
    public static final String CONTAINER_NAME = "Windows";
    private static final String DXWRAPPER = "dxvk";
    private static final String PREFS = "porthub";

    public static final String DEF_PROFILE = "balance";
    public static final String DEF_SCREEN = "1280x720";
    public static final String DEF_PRESET = "Стабильно";
    public static final String DEF_STARTUP = "0";
    public static final String DEF_MEM = "1024";
    public static final String DEF_FPS = "30";
    public static final String DEF_WEBENGINE = "off";
    public static final String DEF_AUTOSTART = "on";
    public static final String DEF_WINVER = "win10";
    public static final String DEF_WINCOMPONENTS = Container.DEFAULT_WINCOMPONENTS;

    public static final String LEAN_WINCOMPONENTS =
            "direct3d=1,directsound=1,directmusic=0,directshow=0,directplay=0,xaudio=1,vcrun2005=0,vcrun2010=1,wmdecoder=1";

    public static final String[] PROFILES = {"lite", "balance", "max"};
    public static final String[] PROFILE_LABELS = {"Лайт", "Баланс", "Максимум"};
    public static final String[] WINVERS = {"win10", "win11"};
    public static final String[] WINVERS_LABELS = {"Windows 10", "Windows 11"};
    public static final String[] SCREENS = {"960x540", "1280x720", "1024x576"};
    public static final String[] SCREEN_LABELS = {"960x540", "1280x720", "1024x576"};
    public static final String[] PRESETS = {"Стабильно", "Быстро", "Максимум"};
    public static final String[] STARTUP = {"0", "1"};
    public static final String[] STARTUP_LABELS = {"Все службы", "Только нужные"};
    public static final String[] MEM = {"512", "1024", "2048"};
    public static final String[] FPS = {"30", "60", "0"};
    public static final String[] FPS_LABELS = {"30", "60", "без лимита"};
    public static final String[] WE = {"off", "on"};
    public static final String[] WE_LABELS = {"Выкл", "Вкл"};
    public static final String[] ON_OFF = {"off", "on"};
    public static final String[] ON_OFF_LABELS = {"Выкл", "Вкл"};

    private static final StringBuilder LOG = new StringBuilder();

    public interface LogSink { void progress(int p); }
    public interface Done { void run(); }

    public static synchronized void log(String value) {
        Log.i(TAG, value);
        LOG.append(value).append('\n');
        if (LOG.length() > 400000) LOG.delete(0, 200000);
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

    public static String human(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return (bytes / 1024 / 1024 / 1024) + " ГБ";
        if (bytes >= 1024L * 1024L) return (bytes / 1024 / 1024) + " МБ";
        if (bytes >= 1024L) return (bytes / 1024) + " КБ";
        return bytes + " Б";
    }

    // ------------------------------------------------------------------ настройки

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    public static void resetAll(Context ctx) { prefs(ctx).edit().clear().apply(); }

    public static boolean isAutostart(Context ctx) {
        return "on".equals(get(ctx, "autostart", DEF_AUTOSTART));
    }

    private static String ckey(Container c, String key) { return "c" + id(c) + "." + key; }

    public static String getFor(Context ctx, Container c, String key, String def) {
        SharedPreferences p = prefs(ctx);
        String k = ckey(c, key);
        return p.contains(k) ? p.getString(k, def) : get(ctx, key, def);
    }

    public static void setFor(Context ctx, Container c, String key, String value) {
        prefs(ctx).edit().putString(ckey(c, key), value).apply();
    }

    public static void clearOverrides(Context ctx, Container c) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        String prefix = "c" + id(c) + ".";
        for (String k : prefs(ctx).getAll().keySet()) if (k.startsWith(prefix)) e.remove(k);
        e.apply();
    }

    public static void applyProfile(Context ctx, String profile) {
        set(ctx, "profile", profile);
        if ("lite".equals(profile)) {
            set(ctx, "screen", "960x540");
            set(ctx, "mem", "512");
            set(ctx, "startup", "1");
            set(ctx, "fps", "30");
            set(ctx, "preset", "Стабильно");
            set(ctx, "wincomponents", LEAN_WINCOMPONENTS);
            log("профиль ЛАЙТ");
        }
        else if ("max".equals(profile)) {
            set(ctx, "screen", "1280x720");
            set(ctx, "mem", "1024");
            set(ctx, "startup", "0");
            set(ctx, "fps", "0");
            set(ctx, "preset", "Максимум");
            set(ctx, "wincomponents", DEF_WINCOMPONENTS);
            log("профиль МАКСИМУМ");
        }
        else {
            set(ctx, "screen", DEF_SCREEN);
            set(ctx, "mem", DEF_MEM);
            set(ctx, "startup", DEF_STARTUP);
            set(ctx, "fps", DEF_FPS);
            set(ctx, "preset", DEF_PRESET);
            set(ctx, "wincomponents", DEF_WINCOMPONENTS);
            log("профиль БАЛАНС");
        }
        try {
            for (Object o : manager(ctx).getContainers()) clearOverrides(ctx, (Container) o);
        } catch (Throwable t) { log("профиль: " + t); }
    }

    public static String currentProfile(Context ctx) { return get(ctx, "profile", DEF_PROFILE); }

    public static void turbo(Context ctx) { applyProfile(ctx, "lite"); applyToExisting(ctx); }

    public static void restore(Context ctx) { resetAll(ctx); applyProfile(ctx, "balance"); applyToExisting(ctx); }

    public static void enableKeepAlive(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences("com.winlator_preferences", Context.MODE_PRIVATE);
            p.edit()
                    .putBoolean("enable_background_protection", true)
                    .putBoolean("enable_background_wakelock", true)
                    .apply();
            log("фоновая защита движка включена");
        } catch (Throwable t) { log("keepalive: " + t); }
    }

    public static void dumpPrefs(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir().getParentFile(), "shared_prefs");
            File[] fs = dir.listFiles();
            if (fs == null || fs.length == 0) { log("shared_prefs: пусто"); return; }
            for (File f : fs) { log("--- prefs " + f.getName() + " ---"); log(tail(f, 40)); }
        } catch (Throwable t) { log("prefs: " + t); }
    }

    private static String buildEnv(Context ctx, Container c) {
        String preset = getFor(ctx, c, "preset", DEF_PRESET);
        String box;
        if ("Максимум".equals(preset)) {
            box = "BOX64_DYNAREC_BIGBLOCK=3 BOX64_DYNAREC_SAFEFLAGS=0 BOX64_DYNAREC_FASTNAN=1"
                + " BOX64_DYNAREC_FASTROUND=2 BOX64_DYNAREC_CALLRET=1 BOX64_DYNAREC_FORWARD=1024";
        } else if ("Быстро".equals(preset)) {
            box = "BOX64_DYNAREC_BIGBLOCK=2 BOX64_DYNAREC_SAFEFLAGS=1 BOX64_DYNAREC_FASTNAN=1";
        } else {
            box = "BOX64_DYNAREC_BIGBLOCK=1";
        }
        String env = Container.DEFAULT_ENV_VARS + " " + box + " BOX64_NOBANNER=1 WINEDEBUG=-all";
        int fps = getInt(ctx, ckey(c, "fps"), -1);
        if (fps < 0) fps = getInt(ctx, "fps", Integer.parseInt(DEF_FPS));
        if (fps > 0) env = env + " DXVK_FRAME_RATE=" + fps;
        if ("on".equals(getFor(ctx, c, "webengine", DEF_WEBENGINE))) {
            env = env + " QTWEBENGINE_DISABLE_SANDBOX=1 QTWEBENGINE_CHROMIUM_FLAGS=--disable-gpu";
        }
        return env;
    }

    // ------------------------------------------------------------------ контейнеры

    public static ContainerManager manager(Context ctx) { return new ContainerManager(ctx); }

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

    public static ArrayList<Container> containers(Context ctx) {
        ArrayList<Container> out = new ArrayList<>();
        try { for (Object o : manager(ctx).getContainers()) out.add((Container) o); }
        catch (Throwable t) { log("containers: " + t); }
        return out;
    }

    public static Container activeContainer(Context ctx) {
        try {
            ContainerManager cm = manager(ctx);
            int want = getInt(ctx, "active_cid", -1);
            if (want >= 0) {
                Container c = cm.getContainerById(want);
                if (c != null) return c;
            }
            for (Object o : cm.getContainers()) {
                Container c = (Container) o;
                if (findExe(c.getRootDir(), "RobloxStudioBeta.exe") != null) return c;
            }
            if (!cm.getContainers().isEmpty()) return cm.getContainers().get(0);
        } catch (Throwable t) { log("activeContainer: " + t); }
        return null;
    }

    public static void setActive(Context ctx, Container c) { set(ctx, "active_cid", String.valueOf(id(c))); }

    public static Container ensureContainer(Context ctx, ContainerManager cm) {
        Container act = activeContainer(ctx);
        if (act != null) { finish(ctx, act); return act; }

        log("создаю контейнер \"" + CONTAINER_NAME + "\"...");
        final JSONObject data = new JSONObject();
        try {
            data.put("name", CONTAINER_NAME);
            data.put("screenSize", get(ctx, "screen", DEF_SCREEN));
        } catch (Throwable ignored) {}

        final Container[] made = new Container[1];
        ((Activity) ctx).runOnUiThread(() -> cm.createContainerAsync(data, cont -> made[0] = (Container) cont));

        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 360000) {
            sleep(2000);
            if (made[0] != null) return finish(ctx, made[0]);
            for (Object o : cm.getContainers()) {
                Container c = (Container) o;
                if (CONTAINER_NAME.equals(c.getName())) return finish(ctx, c);
            }
        }
        log("! контейнер не появился за 6 минут");
        return null;
    }

    public static void createContainer(final Context ctx, final String name, final String screen,
                                       final String winver, final Done done) {
        final ContainerManager cm = manager(ctx);
        final JSONObject data = new JSONObject();
        try {
            data.put("name", name);
            data.put("screenSize", screen);
        } catch (Throwable t) { log("create: " + t); }

        final Container[] made = new Container[1];
        ((Activity) ctx).runOnUiThread(() -> cm.createContainerAsync(data, cont -> made[0] = (Container) cont));

        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            Container c = null;
            while (System.currentTimeMillis() - t0 < 420000) {
                sleep(1500);
                if (made[0] != null) { c = made[0]; break; }
                for (Object o : cm.getContainers()) {
                    Container x = (Container) o;
                    if (name.equals(x.getName()) && x.getRootDir() != null
                            && new File(x.getRootDir(), ".wine").isDirectory()) { c = x; break; }
                }
                if (c != null) break;
            }
            if (c == null) { log("! контейнер не создался"); if (done != null) done.run(); return; }
            setFor(ctx, c, "screen", screen);
            setFor(ctx, c, "winver", winver);
            applySettings(ctx, c);
            setActive(ctx, c);
            log("контейнер \"" + name + "\" готов, id=" + id(c));
            if (done != null) done.run();
        }).start();
    }

    public static void removeContainer(Context ctx, Container c, final Done done) {
        final ContainerManager cm = manager(ctx);
        ((Activity) ctx).runOnUiThread(() -> cm.removeContainerAsync(c, () -> {
            log("контейнер удалён");
            if (done != null) done.run();
        }));
    }

    public static void duplicateContainer(Context ctx, Container c, final Done done) {
        final ContainerManager cm = manager(ctx);
        ((Activity) ctx).runOnUiThread(() -> cm.duplicateContainerAsync(c, () -> {
            log("контейнер скопирован");
            if (done != null) done.run();
        }));
    }

    private static Container finish(Context ctx, Container c) {
        File root = c.getRootDir();
        if (root != null) {
            long t = System.currentTimeMillis();
            while (System.currentTimeMillis() - t < 60000) {
                File[] kids = root.listFiles();
                boolean hasDir = false;
                if (kids != null) for (File k : kids) if (k.isDirectory()) { hasDir = true; break; }
                if (hasDir) break;
                sleep(1000);
            }
        }
        applySettings(ctx, c);
        return c;
    }

    public static void applySettings(Context ctx, Container c) {
        if (c.getName() == null || c.getName().isEmpty()) c.setName(CONTAINER_NAME);
        c.setScreenSize(getFor(ctx, c, "screen", DEF_SCREEN));
        c.setGraphicsDriver("system");
        c.setDXWrapper(DXWRAPPER);
        c.setAudioDriver("alsa");
        c.setEnvVars(buildEnv(ctx, c));
        c.setStartupSelection((byte) getInt(ctx, ckey(c, "startup"), getInt(ctx, "startup", Integer.parseInt(DEF_STARTUP))));
        c.setWinComponents(getFor(ctx, c, "wincomponents", DEF_WINCOMPONENTS));
        c.putExtra("winVersion", getFor(ctx, c, "winver", DEF_WINVER));
        c.putExtra("videoMemorySize", getFor(ctx, c, "mem", DEF_MEM));
        c.saveData();
        applyWinVersion(ctx, c);
        log("настройки контейнера " + id(c) + ": экран=" + c.getScreenSize()
                + " windows=" + getFor(ctx, c, "winver", DEF_WINVER));
    }

    public static void applyToExisting(Context ctx) {
        try {
            for (Object o : manager(ctx).getContainers()) applySettings(ctx, (Container) o);
        } catch (Throwable t) { log("! применить настройки: " + t); }
    }

    public static void applyInBackground(final Context ctx) {
        new Thread(() -> applyToExisting(ctx)).start();
    }

    // ------------------------------------------------------------------ Windows в префиксе

    /** Движок extraData "winVersion" не читает: версия Windows живёт в реестре префикса. */
    public static void applyWinVersion(Context ctx, Container c) {
        String v = getFor(ctx, c, "winver", DEF_WINVER);
        boolean win11 = "win11".equals(v);
        File userReg = new File(c.getRootDir(), ".wine/user.reg");
        File sysReg = new File(c.getRootDir(), ".wine/system.reg");
        if (!userReg.isFile()) { log("префикс ещё не создан"); return; }
        setRegistryValue(userReg, "[Software\\\\Wine]", "Version", win11 ? "win11" : "win10");
        if (sysReg.isFile()) {
            String sec = "[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion]";
            setRegistryValue(sysReg, sec, "CurrentVersion", "10.0");
            setRegistryValue(sysReg, sec, "CurrentBuild", win11 ? "22000" : "19043");
            setRegistryValue(sysReg, sec, "CurrentBuildNumber", win11 ? "22000" : "19043");
            setRegistryValue(sysReg, sec, "ProductName", win11 ? "Microsoft Windows 11" : "Microsoft Windows 10");
        }
    }

    /** Настоящее имя владельца Windows (видно в «Свойствах системы»). */
    public static void setOwner(Context ctx, Container c, String name) {
        set(ctx, "owner", name);
        File userReg = new File(c.getRootDir(), ".wine/user.reg");
        File sysReg = new File(c.getRootDir(), ".wine/system.reg");
        setRegistryValue(userReg, "[Software\\\\Microsoft\\\\Windows\\\\CurrentVersion]", "RegisteredOwner", name);
        setRegistryValue(sysReg, "[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion]", "RegisteredOwner", name);
        log("владелец Windows: " + name);
    }

    public static String owner(Context ctx) { return get(ctx, "owner", "PortHub User"); }

    /** Рисует обои и прописывает их в реестр Windows. */
    public static void setWallpaper(Container c) {
        try {
            File dir = new File(c.getRootDir(), ".wine/drive_c/PortHub");
            dir.mkdirs();
            File out = new File(dir, "wallpaper.png");
            Bitmap bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint bg = new Paint();
            bg.setShader(new LinearGradient(0, 0, 1280, 720, 0xFF0B1220, 0xFF1E3A8A, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, 1280, 720, bg);
            Paint c1 = new Paint(); c1.setAntiAlias(true); c1.setColor(0x5522D3EE);
            canvas.drawCircle(980, 170, 250, c1);
            Paint c2 = new Paint(); c2.setAntiAlias(true); c2.setColor(0x448B5CF6);
            canvas.drawCircle(250, 570, 310, c2);
            Paint t1 = new Paint(); t1.setAntiAlias(true); t1.setColor(0xFFFFFFFF); t1.setTextSize(70); t1.setFakeBoldText(true);
            canvas.drawText("PortHub", 80, 350, t1);
            Paint t2 = new Paint(); t2.setAntiAlias(true); t2.setColor(0xCCFFFFFF); t2.setTextSize(28);
            canvas.drawText("Windows 11 on Android", 84, 396, t2);
            FileOutputStream fos = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            File userReg = new File(c.getRootDir(), ".wine/user.reg");
            setRegistryValue(userReg, "[Control Panel\\\\Desktop]", "WallPaper", "C:\\\\PortHub\\\\wallpaper.png");
            setRegistryValue(userReg, "[Control Panel\\\\Desktop]", "WallpaperStyle", "10");
            setRegistryValue(userReg, "[Control Panel\\\\Desktop]", "TileWallpaper", "0");
            log("обои PortHub установлены");
        } catch (Throwable t) { log("обои: " + t); }
    }

    private static boolean setRegistryValue(File file, String section, String key, String value) {
        try {
            if (file == null || !file.isFile()) return false;
            String text = read(file);
            int s = text.indexOf(section);
            if (s < 0) return false;
            int afterHeader = text.indexOf('\n', s);
            if (afterHeader < 0) return false;
            int nextSection = text.indexOf("\n[", afterHeader);
            int end = nextSection < 0 ? text.length() : nextSection + 1;

            String head = text.substring(0, afterHeader + 1);
            String body = text.substring(afterHeader + 1, end);
            String tailPart = text.substring(end);

            String newLine = "\"" + key + "\"=\"" + value + "\"";
            int k = body.indexOf("\"" + key + "\"");
            if (k >= 0) {
                int nl = body.indexOf('\n', k);
                if (nl < 0) nl = body.length();
                body = body.substring(0, k) + newLine + body.substring(nl);
            } else {
                int insert = 0;
                if (body.startsWith("#time=")) {
                    int nl = body.indexOf('\n');
                    insert = nl < 0 ? body.length() : nl + 1;
                }
                body = body.substring(0, insert) + newLine + "\n" + body.substring(insert);
            }

            FileOutputStream fos = new FileOutputStream(file);
            fos.write((head + body + tailPart).getBytes("UTF-8"));
            fos.close();
            return true;
        } catch (Throwable t) { log("registry: " + t); return false; }
    }

    // ------------------------------------------------------------------ запуск

    public static void launch(Context ctx, Container c, String execArgs) {
        ContainerManager cm = manager(ctx);
        int i = id(c);
        if (i < 0) { log("! id не определился"); return; }
        Container fresh = cm.getContainerById(i);
        if (fresh != null) c = fresh;
        applySettings(ctx, c);
        if (execArgs != null) {
            c.putExtra("execArgs", execArgs);
            c.saveData();
            log("запуск: " + execArgs);
        } else {
            log("запуск рабочего стола Windows");
        }
        Intent it = new Intent(ctx, XServerDisplayActivity.class);
        it.putExtra("container_id", i);
        ctx.startActivity(it);
    }

    public static void launchExe(Context ctx, Container c, String winPath, String args) {
        launch(ctx, c, (args == null || args.isEmpty()) ? winPath : winPath + " " + args);
    }

    public static void runCommand(Context ctx, Container c, String cmd) {
        launchExe(ctx, c, "C:\\windows\\system32\\cmd.exe", "/c " + cmd);
    }

    public static void launchContainer(Context ctx, Container c) {
        File exe = findExe(c.getRootDir(), "RobloxStudioBeta.exe");
        if (exe != null) {
            String win = winPath(exe, c.getRootDir());
            if (win != null) { launchExe(ctx, c, win, ""); return; }
        }
        launch(ctx, c, null);
    }

    public static void openEngineUi(Context ctx) {
        try { ctx.startActivity(new Intent(ctx, com.winlator.MainActivity.class)); }
        catch (Throwable t) { log("! не открыть меню движка: " + t); }
    }

    // ------------------------------------------------------------------ закреплённое

    public static JSONArray pinned(Context ctx) {
        try { return new JSONArray(get(ctx, "pinned", "[]")); } catch (Throwable t) { return new JSONArray(); }
    }

    public static void pin(Context ctx, String name, int cid, String exe, String args) {
        try {
            JSONArray a = pinned(ctx);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && exe.equals(o.optString("exe"))) return;
            }
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("cid", cid);
            o.put("exe", exe);
            o.put("args", args == null ? "" : args);
            a.put(o);
            set(ctx, "pinned", a.toString());
            log("закреплено: " + name);
        } catch (Throwable t) { log("pin: " + t); }
    }

    public static void unpin(Context ctx, String exe) {
        try {
            JSONArray a = pinned(ctx);
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !exe.equals(o.optString("exe"))) out.put(o);
            }
            set(ctx, "pinned", out.toString());
        } catch (Throwable t) { log("unpin: " + t); }
    }

    // ------------------------------------------------------------------ файлы

    public static File[] listDir(File dir) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return new File[0];
        Arrays.sort(kids, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return kids;
    }

    public static ArrayList<File> listExes(File root) {
        ArrayList<File> out = new ArrayList<>();
        scan(root, 6, out, 0);
        return out;
    }

    private static void scan(File dir, int depth, ArrayList<File> out, int level) {
        if (dir == null || level > depth || out.size() > 500) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                if (level == 0 && k.getName().equalsIgnoreCase("windows")) continue;
                scan(k, depth, out, level + 1);
            } else {
                String n = k.getName().toLowerCase();
                if (n.endsWith(".exe") && !n.equals("unins000.exe") && !n.startsWith("vcredist")) out.add(k);
            }
        }
    }

    public static File findExe(File dir, String name) {
        if (dir == null) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory()) {
                File f = findExe(k, name);
                if (f != null) return f;
            } else if (k.getName().equalsIgnoreCase(name)) return k;
        }
        return null;
    }

    public static String winPath(File f, File containerDir) {
        if (f == null) return null;
        if (containerDir != null) {
            String base = containerDir.getAbsolutePath();
            if (f.getAbsolutePath().startsWith(base)) {
                String rel = f.getAbsolutePath().substring(base.length());
                int i = rel.indexOf("drive_c/");
                if (i >= 0) return "C:\\" + rel.substring(i + "drive_c/".length()).replace('/', '\\');
            }
        }
        String abs = f.getAbsolutePath();
        String dl = downloads().getAbsolutePath();
        if (abs.startsWith(dl)) return "D:\\" + trim(abs.substring(dl.length()));
        String st = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (abs.startsWith(st)) return "E:\\" + trim(abs.substring(st.length()));
        return null;
    }

    private static String trim(String v) {
        String out = v;
        while (out.startsWith("/")) out = out.substring(1);
        return out.replace('/', '\\');
    }

    public static File downloads() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
    }

    public static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long sz = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) sz += dirSize(k);
        return sz;
    }

    // ------------------------------------------------------------------ чистка

    public static long cleanJunk(Context ctx) {
        long freed = 0;
        try {
            for (Object o : manager(ctx).getContainers()) {
                File root = ((Container) o).getRootDir();
                if (root == null) continue;
                freed += wipe(new File(root, ".wine/drive_c/windows/temp"));
                freed += wipe(new File(root, "tmp"));
                File[] us = new File(root, ".wine/drive_c/users").listFiles();
                if (us != null) for (File u : us) {
                    File local = new File(u, "AppData/Local");
                    freed += wipe(new File(local, "Temp"));
                    freed += trimLogs(new File(local, "Roblox/logs"), 3);
                    freed += wipe(new File(local, "Roblox/StudioCache"));
                }
            }
            File[] fs = downloads().listFiles();
            if (fs != null) for (File f : fs) {
                String n = f.getName().toLowerCase();
                if (f.isFile() && n.contains("robloxstudio") && n.endsWith(".exe")) {
                    long sz = f.length();
                    if (f.delete()) freed += sz;
                }
            }
        } catch (Throwable t) { log("чистка: " + t); }
        log("очищено: " + human(freed));
        return freed;
    }

    private static long wipe(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long sz = dirSize(dir);
        deleteRec(dir);
        dir.mkdirs();
        return sz;
    }

    private static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }

    private static long trimLogs(File dir, int keep) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null || fs.length <= keep) return 0;
        Arrays.sort(fs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        long freed = 0;
        for (int i = keep; i < fs.length; i++) {
            long sz = dirSize(fs[i]);
            if (fs[i].delete()) freed += sz;
        }
        return freed;
    }

    // ------------------------------------------------------------------ процессы

    public static ArrayList<String> listProcesses() {
        ArrayList<String> out = new ArrayList<>();
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            int pid;
            try { pid = Integer.parseInt(d.getName()); } catch (Throwable t) { continue; }
            String cmd = readCmd(d);
            if (cmd == null) continue;
            String l = cmd.toLowerCase();
            if (l.contains("wine") || l.contains("box64") || l.contains("roblox")
                    || l.contains("services.exe") || l.contains("explorer.exe") || l.contains("cmd.exe")) {
                out.add(pid + "|" + cmd);
            }
        }
        Collections.sort(out);
        return out;
    }

    public static int killPid(int pid) {
        try {
            android.os.Process.killProcess(pid);
            log("убит pid " + pid);
            return 1;
        } catch (Throwable t) { log("kill: " + t); return 0; }
    }

    public static int killWineProcesses() {
        int me = android.os.Process.myPid();
        int killed = 0;
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return 0;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            int pid;
            try { pid = Integer.parseInt(d.getName()); } catch (Throwable t) { continue; }
            if (pid == me) continue;
            String cmd = readCmd(d);
            if (cmd == null) continue;
            String l = cmd.toLowerCase();
            if (l.contains("roblox") || l.contains("wine") || l.contains("box64")
                    || l.contains("services.exe") || l.contains("explorer.exe")) {
                try { android.os.Process.killProcess(pid); killed++; log("убит pid " + pid + ": " + cmd); }
                catch (Throwable ignored) {}
            }
        }
        log("убито процессов: " + killed);
        return killed;
    }

    private static String readCmd(File dir) {
        try (InputStream in = new FileInputStream(new File(dir, "cmdline"))) {
            byte[] b = new byte[512];
            int n = in.read(b);
            if (n <= 0) return null;
            String s = new String(b, 0, n).replace('\0', ' ').trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------------ логи

    public static String tail(File f, int lines) {
        try {
            ArrayList<String> all = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String l;
                while ((l = r.readLine()) != null) all.add(l);
            }
            int from = Math.max(0, all.size() - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < all.size(); i++) sb.append(all.get(i)).append('\n');
            return sb.toString();
        } catch (Throwable t) { return "не прочитать: " + t; }
    }

    public static File newestLog(File dir, String mustContain) {
        if (dir == null) return null;
        File best = null;
        ArrayDeque<File> q = new ArrayDeque<>();
        q.add(dir);
        int guard = 0;
        while (!q.isEmpty() && guard++ < 30000) {
            File f = q.poll();
            File[] kids = f.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (k.isDirectory()) { q.add(k); continue; }
                String n = k.getName().toLowerCase();
                if (!n.endsWith(".log")) continue;
                if (mustContain != null && !k.getAbsolutePath().toLowerCase().contains(mustContain)) continue;
                if (best == null || k.lastModified() > best.lastModified()) best = k;
            }
        }
        return best;
    }

    public static String engineLog(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "brief", "-t", "2000"});
            BufferedReader r = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) {
                String low = l.toLowerCase();
                if (low.contains("winlator") || low.contains("wine") || low.contains("box64")
                        || low.contains("dxvk") || low.contains("vulkan") || low.contains("xserver")
                        || low.contains("err:") || low.contains("fixme") || low.contains("porthub")) {
                    sb.append(l).append('\n');
                }
            }
            r.close();
            p.destroy();
        } catch (Throwable t) { log("logcat не сработал: " + t); }
        return sb.length() > 0 ? sb.toString() : "лог пуст";
    }

    // ------------------------------------------------------------------ установщик

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
            return code == 200 || code == 206;
        } catch (Throwable t) { return false; }
    }

    private static File findExistingInstaller() {
        File[] files = downloads().listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName().toLowerCase();
            if (f.isFile() && n.contains("robloxstudio") && n.endsWith(".exe")) return f;
        }
        return null;
    }

    public static File downloadInstaller(String guid, LogSink sink) {
        File have = findExistingInstaller();
        if (have != null) { log("уже есть установщик"); return have; }
        String[] urls = {
                "https://setup.rbxcdn.com/version-" + guid + "-RobloxStudioInstaller.exe",
                "https://setup.rbxcdn.com/version-" + guid + "-RobloxStudioLauncherBeta.exe",
                "https://setup.rbxcdn.com/version-" + guid + "/RobloxStudioInstaller.exe"
        };
        for (String u : urls) if (probe(u)) return downloadTo(u, u.substring(u.lastIndexOf('/') + 1), sink);
        log("! ни один адрес не подошёл");
        return null;
    }

    private static File downloadTo(String url, String name, LogSink sink) {
        File dir = downloads();
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
                    if (p != last && p % 5 == 0) { last = p; sink.progress(p); }
                }
            }
            fos.close();
            in.close();
            log("скачано: " + human(out.length()));
            return out;
        } catch (Throwable t) { log("! скачивание: " + t); return null; }
    }
            }
