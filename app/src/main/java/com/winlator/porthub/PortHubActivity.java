package com.winlator.porthub;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Date;
import java.util.Locale;

public class PortHubActivity extends AppCompatActivity {

    private static final int BG = 0xFF0B0F14;
    private static final int CARD = 0xFF151B23;
    private static final int CARD_HI = 0xFF1D2632;
    private static final int TEXT = 0xFFE6EDF3;
    private static final int DIM = 0xFF8B949E;
    private static final int ACCENT = 0xFF3B82F6;

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private LinearLayout settingsBox;
    private TextView logView;
    private TextView statusView;
    private TextView diagView;
    private ProgressBar bar;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean busy = false;

    private interface Pick { void on(String value); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashHandler();
        setContentView(buildUi());
        Engine.log("=== PortHub 0.1 запущен, " + new Date() + " ===");

        File crash = new File(getFilesDir(), "crash.txt");
        if (crash.exists()) {
            Engine.log("--- прошлое падение ---");
            Engine.log(Engine.read(crash));
            crash.delete();
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100); } catch (Throwable ignored) {}
        }
        refreshLog();
    }

    // ------------------------------------------------------------------ отрисовка

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Drawable press(Drawable base) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 16));
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private View bigButton(String label, int color, Runnable onClick) {
        TextView b = text(label, 17, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(16), dp(16), dp(16));
        b.setBackground(press(round(color, 14)));
        b.setClickable(true);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private TextView smallButton(String label, Runnable onClick) {
        TextView b = text(label, 12, TEXT, false);
        b.setPadding(dp(10), dp(5), dp(10), dp(5));
        b.setBackground(press(round(CARD_HI, 10)));
        b.setClickable(true);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private TextView chip(String label, boolean selected) {
        TextView t = text(label, 12, selected ? 0xFFFFFFFF : TEXT, selected);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(press(round(selected ? ACCENT : CARD_HI, 20)));
        t.setClickable(true);
        return t;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(16), dp(16), dp(10));
        header.addView(text("PortHub", 24, TEXT, true));
        header.addView(text("Windows-программы на телефоне · бета 0.1", 12, DIM, false));
        root.addView(header);

        ScrollView sc = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), dp(12));
        sc.addView(content);
        root.addView(sc, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        content.addView(primaryCard());
        content.addView(actionsCard());
        content.addView(settingsCard());
        content.addView(diagCard());

        root.addView(logCard());
        return root;
    }

    private View primaryCard() {
        LinearLayout c = card();
        c.addView(bigButton("ЗАПУСТИТЬ СТУДИЮ", ACCENT, this::startStudio));
        statusView = text("готов", 12, DIM, false);
        statusView.setPadding(0, dp(10), 0, 0);
        c.addView(statusView);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(4));
        lp.topMargin = dp(8);
        c.addView(bar, lp);
        TextView hint = text("Если висит «Запуск...» — жми «Сброс сессии», потом Пуск снова.", 11, DIM, false);
        hint.setPadding(0, dp(8), 0, 0);
        c.addView(hint);
        return c;
    }

    private View actionsCard() {
        LinearLayout c = card();

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        TextView a = smallButton("Меню движка", () -> Engine.openEngineUi(this));
        TextView b = smallButton("Сброс сессии", this::resetSession);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        p1.rightMargin = dp(6);
        row1.addView(a, p1);
        row1.addView(b, new LinearLayout.LayoutParams(0, WRAP, 1f));
        c.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(6), 0, 0);
        TextView d = smallButton("Лог движка", this::dumpEngineLog);
        TextView e = smallButton("Лог Студии", this::dumpStudioLog);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        p2.rightMargin = dp(6);
        row2.addView(d, p2);
        row2.addView(e, new LinearLayout.LayoutParams(0, WRAP, 1f));
        c.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setPadding(0, dp(6), 0, 0);
        row3.addView(smallButton("Скачать Студию заново", this::downloadOnly),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        c.addView(row3);
        return c;
    }

    private View settingsCard() {
        LinearLayout c = card();
        c.addView(text("НАСТРОЙКИ", 11, DIM, true));

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setPadding(0, dp(8), 0, dp(4));
        TextView turbo = smallButton("Турбо (быстрее)", () -> {
            Engine.turbo(this);
            buildSettings();
            status("включён режим Турбо");
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        tp.rightMargin = dp(6);
        quick.addView(turbo, tp);
        quick.addView(smallButton("Как было (надёжно)", () -> {
            Engine.restore(this);
            buildSettings();
            status("настройки возвращены к рабочим");
        }), new LinearLayout.LayoutParams(0, WRAP, 1f));
        c.addView(quick);

        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(settingsBox);
        buildSettings();
        return c;
    }

    private void buildSettings() {
        settingsBox.removeAllViews();

        addChips("Разрешение экрана", Engine.SCREENS, Engine.SCREEN_LABELS,
                Engine.get(this, "screen", Engine.DEF_SCREEN), v -> {
                    Engine.set(this, "screen", v);
                    buildSettings();
                    Engine.applyInBackground(this);
                    status("разрешение: " + v);
                });

        addChips("Скорость Box64", Engine.PRESETS, Engine.PRESETS,
                Engine.get(this, "preset", Engine.DEF_PRESET), v -> {
                    Engine.set(this, "preset", v);
                    buildSettings();
                    Engine.applyInBackground(this);
                    status("профиль: " + v);
                });

        addChips("Службы Wine при старте", Engine.STARTUP, Engine.STARTUP_LABELS,
                Engine.get(this, "startup", Engine.DEF_STARTUP), v -> {
                    Engine.set(this, "startup", v);
                    buildSettings();
                    Engine.applyInBackground(this);
                    status("службы Wine: " + (v.equals("1") ? "только нужные" : "все"));
                });

        addChips("Видео память, МБ", Engine.MEM, Engine.MEM,
                Engine.get(this, "mem", Engine.DEF_MEM), v -> {
                    Engine.set(this, "mem", v);
                    buildSettings();
                    Engine.applyInBackground(this);
                    status("видео память: " + v);
                });

        addChips("Лимит FPS", Engine.FPS, Engine.FPS_LABELS,
                Engine.get(this, "fps", Engine.DEF_FPS), v -> {
                    Engine.set(this, "fps", v);
                    buildSettings();
                    Engine.applyInBackground(this);
                    status("лимит FPS: " + v);
                });

        addChips("WebEngine (UI Студии)", Engine.WE, Engine.WE_LABELS,
                Engine.get(this, "webengine", Engine.DEF_WEBENGINE), v -> {
                    Engine.set(this, "webengine", v);
                    buildSettings();
                    Engine.applyInBackground(this);
                    status("WebEngine: " + (v.equals("on") ? "включено" : "выключено"));
                });
    }

    private void addChips(String title, String[] values, String[] labels, String current, Pick onPick) {
        settingsBox.addView(text(title, 12, DIM, false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(12));
        for (int i = 0; i < values.length; i++) {
            final String v = values[i];
            TextView ch = chip(labels[i], v.equals(current));
            ch.setOnClickListener(x -> onPick.on(v));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.rightMargin = dp(6);
            row.addView(ch, lp);
        }
        settingsBox.addView(row);
    }

    private View diagCard() {
        LinearLayout c = card();
        c.addView(text("ДИАГНОСТИКА", 11, DIM, true));
        diagView = text("нажми «Проверить»", 12, DIM, false);
        diagView.setPadding(0, dp(8), 0, dp(10));
        c.addView(diagView);
        c.addView(smallButton("Проверить", this::diagnostics));
        return c;
    }

    private View logCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(0xFF0F141A);
        c.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("ЛОГ", 11, DIM, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView copy = smallButton("Копировать", this::copyLog);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(WRAP, WRAP);
        cp.rightMargin = dp(6);
        head.addView(copy, cp);
        head.addView(smallButton("Очистить", () -> { Engine.clearLog(); refreshLog(); }));
        c.addView(head);

        logView = text("", 9.5f, 0xFF9DA7B3, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        ScrollView s = new ScrollView(this);
        s.addView(logView);
        c.addView(s, new LinearLayout.LayoutParams(MATCH, dp(170)));
        return c;
    }

    // ------------------------------------------------------------------ действия

    private void status(String s) { statusView.setText(s); }
    private void refreshLog() { logView.setText(Engine.logText()); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("log", Engine.logText()));
            toast("Лог скопирован — вставь его в чат");
        } catch (Throwable t) { toast("Не скопировалось"); }
    }

    private void runBg(String what, Runnable body) {
        if (busy) { toast("Занят, подожди"); return; }
        busy = true;
        status(what + "...");
        new Thread(() -> {
            try { body.run(); } catch (Throwable t) { Engine.log("!! " + what + ": " + t); }
            ui.post(() -> { busy = false; status("готово"); refreshLog(); });
        }).start();
    }

    private void startStudio() {
        runBg("Запуск Студии", () -> {
            ContainerManager cm = Engine.manager(this);
            Container c = Engine.ensureContainer(this, cm);
            if (c == null) return;

            File dir = c.getRootDir();
            File studio = Engine.findStudioExe(dir);
            if (studio != null) {
                String win = Engine.toWindowsPath(studio, dir);
                Engine.log("Студия найдена: " + win);
                if (win != null) { ui.post(() -> Engine.launch(this, c, win)); return; }
            }

            Engine.log("Студия ещё не установлена — нужен установщик");
            String guid = Engine.fetchVersionGuid();
            if (guid == null) return;
            ui.post(() -> { bar.setVisibility(View.VISIBLE); bar.setProgress(0); });
            File f = Engine.downloadInstaller(guid, p -> ui.post(() -> {
                bar.setProgress(p);
                status("Скачано " + p + "%");
            }));
            ui.post(() -> bar.setVisibility(View.GONE));
            if (f == null) return;
            ui.post(() -> Engine.launch(this, c, "D:\\" + f.getName()));
        });
    }

    private void downloadOnly() {
        runBg("Скачивание", () -> {
            String guid = Engine.fetchVersionGuid();
            if (guid != null) Engine.downloadInstaller(guid, p -> ui.post(() -> status("Скачано " + p + "%")));
        });
    }

    private void resetSession() {
        runBg("Сброс сессии", () -> {
            Engine.log("ищу зависшие процессы Wine/Studio...");
            Engine.killWineProcesses();
        });
    }

    private void dumpStudioLog() {
        runBg("Читаю лог Студии", () -> {
            ContainerManager cm = Engine.manager(this);
            for (Object o : cm.getContainers()) {
                File log = Engine.newestStudioLog(((Container) o).getRootDir());
                if (log != null) {
                    Engine.log("=== ЛОГ СТУДИИ: " + log.getAbsolutePath() + " ===");
                    Engine.log(Engine.tail(log, 150));
                    Engine.log("=== /ЛОГ СТУДИИ ===");
                    return;
                }
            }
            Engine.log("лог Студии не найден");
        });
    }

    private void dumpEngineLog() {
        runBg("Читаю лог движка", () -> {
            Engine.log("=== ЛОГ ДВИЖКА ===");
            Engine.log(Engine.engineLog(this));
            Engine.log("=== /ЛОГ ДВИЖКА ===");
        });
    }

    private void diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Устройство: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("SoC: ").append(Build.HARDWARE).append('\n');
        sb.append("Ядер: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        sb.append("RAM: ").append(ramGb()).append(" ГБ\n");
        sb.append("Свободно: ").append(freeGb()).append(" ГБ\n");
        sb.append("GPU: ").append(gpuRenderer()).append('\n');
        sb.append("Оценка под DXVK: ").append(score()).append(" / 100");
        diagView.setText(sb.toString());
        Engine.log(sb.toString());
        refreshLog();
    }

    private String ramGb() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("MemTotal")) {
                    String digits = l.replaceAll("[^0-9]", "");
                    return String.format(Locale.US, "%.1f", Long.parseLong(digits) / 1024.0 / 1024.0);
                }
            }
        } catch (Throwable ignored) {}
        return "?";
    }

    private String freeGb() {
        try {
            StatFs st = new StatFs(getFilesDir().getAbsolutePath());
            return String.format(Locale.US, "%.1f", st.getAvailableBytes() / 1024.0 / 1024.0 / 1024.0);
        } catch (Throwable t) { return "?"; }
    }

    private String gpuRenderer() {
        try {
            EGLDisplay d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            EGL14.eglInitialize(d, ver, 0, ver, 1);
            EGLConfig[] cfg = new EGLConfig[1];
            int[] num = new int[1];
            EGL14.eglChooseConfig(d,
                    new int[]{EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE}, 0,
                    cfg, 0, 1, num, 0);
            EGLContext ctx = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            EGLSurface surf = EGL14.eglCreatePbufferSurface(d, cfg[0],
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(d, surf, surf, ctx);
            String s = GLES20.glGetString(GLES20.GL_RENDERER);
            EGL14.eglTerminate(d);
            return s == null ? "?" : s;
        } catch (Throwable t) { return "не определился"; }
    }

    private int score() {
        int s = 100;
        try { if (Double.parseDouble(ramGb()) < 8) s -= 20; } catch (Throwable ignored) {}
        try { if (Double.parseDouble(freeGb()) < 8) s -= 20; } catch (Throwable ignored) {}
        if (!gpuRenderer().toLowerCase().contains("adreno")) s -= 20;
        if (Runtime.getRuntime().availableProcessors() < 8) s -= 10;
        return Math.max(s, 0);
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (FileWriter w = new FileWriter(new File(getFilesDir(), "crash.txt"))) {
                w.write(new Date() + "\n" + t.getName() + "\n" + Log.getStackTraceString(e));
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
    }
