package com.winlator.porthub;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
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

    private TextView logView;
    private TextView statusView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashHandler();
        setContentView(buildUi());
        Engine.log("=== PortHub запущен, " + new Date() + " ===");

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

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("PortHub — Roblox Studio на телефоне");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("готов");
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setTextColor(Color.GRAY);
        root.addView(statusView);

        root.addView(button("ЗАПУСТИТЬ СТУДИЮ", this::startStudio));
        root.addView(button("Меню движка", () -> Engine.openEngineUi(this)));
        root.addView(button("Скачать Студию заново", this::downloadOnly));
        root.addView(button("Диагностика", this::diagnostics));
        root.addView(button("Скопировать лог", this::copyLog));
        root.addView(button("Очистить лог", () -> { Engine.clearLog(); refreshLog(); }));

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

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
            File f = Engine.downloadInstaller(guid, p -> ui.post(() -> status("Скачано " + p + "%")));
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

    private void diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Устройство: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Плата/SoC: ").append(Build.HARDWARE).append('\n');
        sb.append("Ядер: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        sb.append("RAM: ").append(ramGb()).append(" ГБ\n");
        sb.append("Свободно: ").append(freeGb()).append(" ГБ\n");
        sb.append("GPU: ").append(gpuRenderer()).append('\n');
        sb.append("Оценка под DXVK: ").append(score()).append(" / 100\n");
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
