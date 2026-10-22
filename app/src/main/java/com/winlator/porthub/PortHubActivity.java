package com.winlator.porthub;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
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
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.winlator.container.Container;
import com.winlator.container.ContainerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.function.Supplier;

/** PortHub — эмулятор Windows 11 на Android: рабочий стол, Пуск, контейнеры, программы. */
public class PortHubActivity extends AppCompatActivity {

    private static final String[] TABS = {"Рабочий стол", "Контейнеры", "Программы", "Настройки", "Логи"};
    private static boolean autoLaunched = false;

    private Style s;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout contentHost;
    private TextView[] navItems = new TextView[TABS.length];
    private TextView statusView;
    private ProgressBar bar;
    private TextView logView;
    private boolean busy = false;
    private int tab = 0;
    private File browseDir;
    private ArrayList<File> found = new ArrayList<>();
    private int scanToken = 0;

    private interface Pick { void on(String value); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        s = new Style(this);
        Style.applyTheme(Engine.get(this, "theme", "blue"));
        installCrashHandler();
        setContentView(rootView());
        Engine.log("=== PortHub 1.0, " + new Date() + " ===");

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
        Engine.enableKeepAlive(this);
        Engine.dumpPrefs(this);
        makeShortcut(false);
        showTab(0);

        boolean fromShortcut = "studio".equals(getIntent() == null ? null : getIntent().getStringExtra("go"));
        if (fromShortcut) ui.postDelayed(this::startWindows, 300);
        else if (Engine.isAutostart(this) && !autoLaunched) {
            autoLaunched = true;
            ui.postDelayed(this::startWindows, 1200);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && "studio".equals(intent.getStringExtra("go"))) ui.postDelayed(this::startWindows, 300);
    }

    // ------------------------------------------------------------------ каркас

    private View rootView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Style.BG);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(s.dp(16), s.dp(14), s.dp(16), s.dp(8));
        LinearLayout top = s.row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(s.text("PortHub", 23, Style.TEXT, true), new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
        TextView ver = s.text("Windows 11 · 1.0", 11, Style.DIM, false);
        ver.setPadding(s.dp(8), s.dp(4), s.dp(8), s.dp(4));
        ver.setBackground(s.round(Style.CARD2, 10));
        top.addView(ver);
        head.addView(top);
        statusView = s.text("готов", 11, Style.DIM, false);
        statusView.setPadding(0, s.dp(6), 0, 0);
        head.addView(statusView);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Style.MATCH, s.dp(3));
        blp.topMargin = s.dp(6);
        head.addView(bar, blp);
        root.addView(head);

        contentHost = new LinearLayout(this);
        contentHost.setOrientation(LinearLayout.VERTICAL);
        root.addView(contentHost, new LinearLayout.LayoutParams(Style.MATCH, 0, 1f));

        LinearLayout nav = s.row();
        nav.setBackgroundColor(Style.BG2);
        nav.setPadding(s.dp(6), s.dp(6), s.dp(6), s.dp(6));
        nav.setGravity(Gravity.CENTER_VERTICAL);

        TextView start = s.text("⊞", 17, 0xFFFFFFFF, true);
        start.setGravity(Gravity.CENTER);
        start.setPadding(s.dp(12), s.dp(8), s.dp(12), s.dp(8));
        start.setBackground(s.press(s.round(Style.ACCENT, 12)));
        start.setOnClickListener(v -> openStart());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Style.WRAP, Style.WRAP);
        slp.rightMargin = s.dp(6);
        nav.addView(start, slp);

        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = s.text(TABS[i], 11, Style.DIM, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(s.dp(2), s.dp(10), s.dp(2), s.dp(10));
            t.setBackground(s.press(s.round(0x00000000, 12)));
            t.setOnClickListener(v -> showTab(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Style.WRAP, 1f);
            lp.rightMargin = s.dp(3);
            nav.addView(t, lp);
            navItems[i] = t;
        }
        root.addView(nav);
        return root;
    }

    private void showTab(int index) {
        tab = index;
        contentHost.removeAllViews();
        View page;
        if (index == 0) page = pageDesktop();
        else if (index == 1) page = pageContainers();
        else if (index == 2) page = pageApps();
        else if (index == 3) page = pageSettings();
        else page = pageLogs();
        contentHost.addView(page, new LinearLayout.LayoutParams(Style.MATCH, Style.MATCH));
        for (int i = 0; i < navItems.length; i++) {
            boolean on = i == index;
            navItems[i].setTextColor(on ? 0xFFFFFFFF : Style.DIM);
            navItems[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            navItems[i].setBackground(s.press(s.round(on ? Style.ACCENT : 0x00000000, 12)));
        }
    }

    private void refresh() { showTab(tab); }

    private ScrollView scroll(LinearLayout content) {
        ScrollView sc = new ScrollView(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(s.dp(12), s.dp(12), s.dp(12), s.dp(24));
        sc.addView(content);
        return sc;
    }

    private void dialog(View content) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Style.BG2);
        sv.addView(content);
        new AlertDialog.Builder(this).setView(sv).show();
    }

    // ------------------------------------------------------------------ Рабочий стол

    private View pageDesktop() {
        LinearLayout c = new LinearLayout(this);
        Container act = Engine.activeContainer(this);

        LinearLayout hero = s.card();
        LinearLayout trow = s.row();
        trow.setGravity(Gravity.CENTER_VERTICAL);
        trow.addView(s.text("WINDOWS 11 НА ANDROID", 11, Style.DIM, true), new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
        trow.addView(s.text(clock(), 11, Style.DIM, false));
        hero.addView(trow);
        hero.addView(s.text(act == null ? "контейнера пока нет" : act.getName() + "  ·  id " + Engine.id(act), 15, Style.TEXT, true));
        if (act != null) {
            hero.addView(s.text("экран " + Engine.getFor(this, act, "screen", Engine.DEF_SCREEN)
                    + "  ·  " + Engine.getFor(this, act, "winver", Engine.DEF_WINVER)
                    + "  ·  пользователь " + Engine.owner(this), 11, Style.DIM, false));
        }
        hero.addView(s.gap(10));
        hero.addView(s.big("ЗАПУСТИТЬ WINDOWS", Style.ACCENT, this::startWindows));
        LinearLayout r1 = s.row();
        r1.setPadding(0, s.dp(8), 0, 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Style.WRAP, 1f);
        p.rightMargin = s.dp(6);
        r1.addView(s.small("Диспетчер задач", this::openTaskManager), p);
        r1.addView(s.small("Терминал", this::openTerminal), new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
        hero.addView(r1);
        c.addView(hero);

        LinearLayout desk = s.card();
        desk.addView(s.text("ПРОГРАММЫ", 11, Style.DIM, true));
        JSONArray arr = Engine.pinned(this);
        if (arr.length() == 0) {
            desk.addView(s.text("Пусто. Закрепи .exe во вкладке «Программы».", 11, Style.DIM, false));
        } else {
            LinearLayout grid = null;
            int col = 0;
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (col == 0) { grid = s.row(); desk.addView(grid); }
                col = (col + 1) % 3;
                LinearLayout icon = new LinearLayout(this);
                icon.setOrientation(LinearLayout.VERTICAL);
                icon.setGravity(Gravity.CENTER);
                icon.setPadding(s.dp(4), s.dp(8), s.dp(4), s.dp(8));
                String name = o.optString("name");
                TextView tile = s.text(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.US),
                        20, 0xFFFFFFFF, true);
                tile.setGravity(Gravity.CENTER);
                tile.setBackground(s.round(tileColor(i), 14));
                tile.setPadding(s.dp(14), s.dp(14), s.dp(14), s.dp(14));
                icon.addView(tile);
                TextView label = s.text(name, 10, Style.DIM, false);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                icon.addView(label);
                icon.setOnClickListener(v -> launchPinned(o));
                icon.setOnLongClickListener(v -> {
                    Engine.unpin(this, o.optString("exe"));
                    toast("убрано с рабочего стола");
                    refresh();
                    return true;
                });
                grid.addView(icon, new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
            }
            desk.addView(s.text("долгое нажатие — убрать иконку", 10, Style.DIM, false));
        }
        c.addView(desk);

        LinearLayout prof = s.card();
        prof.addView(s.text("ПРОФИЛЬ НАГРУЗКИ", 11, Style.DIM, true));
        prof.addView(chips(Engine.PROFILES, Engine.PROFILE_LABELS, Engine.currentProfile(this), v -> {
            Engine.applyProfile(this, v);
            Engine.applyInBackground(this);
            refresh();
        }));
        prof.addView(s.text("«Лайт» — меньше разрешение и память, минимум служб Windows. «Баланс» — проверенная конфигурация.", 11, Style.DIM, false));
        c.addView(prof);

        LinearLayout quick = s.card();
        quick.addView(s.text("СИСТЕМА", 11, Style.DIM, true));
        LinearLayout r2 = s.row();
        r2.setPadding(0, s.dp(8), 0, 0);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, Style.WRAP, 1f);
        p2.rightMargin = s.dp(6);
        r2.addView(s.small("Сброс сессии", this::resetSession), p2);
        r2.addView(s.small("Очистить мусор", this::cleanJunk), new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
        quick.addView(r2);
        LinearLayout r3 = s.row();
        r3.setPadding(0, s.dp(6), 0, 0);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(0, Style.WRAP, 1f);
        p3.rightMargin = s.dp(6);
        r3.addView(s.small("Ярлык на рабочий стол", () -> makeShortcut(true)), p3);
        r3.addView(s.small("Меню движка", () -> Engine.openEngineUi(this)), new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
        quick.addView(r3);
        c.addView(quick);

        return scroll(c);
    }

    private int tileColor(int i) {
        int[] colors = {Style.ACCENT, Style.ACCENT2, 0xFF0EA5E9, 0xFF22C55E, 0xFFF59E0B, 0xFFEF4444};
        return colors[i % colors.length];
    }

    private String clock() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        return String.format(Locale.US, "%02d:%02d  %02d.%02d", cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.MONTH) + 1);
    }

    private void launchPinned(JSONObject o) {
        try {
            Container c = Engine.manager(this).getContainerById(o.optInt("cid"));
            if (c == null) c = Engine.activeContainer(this);
            if (c == null) { toast("нет контейнера"); return; }
            Engine.launchExe(this, c, o.optString("exe"), o.optString("args"));
        } catch (Throwable t) { toast("не запустилось: " + t); }
    }

    // ------------------------------------------------------------------ Пуск

    private void openStart() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(s.dp(14), s.dp(14), s.dp(14), s.dp(14));

        box.addView(s.text("ЗАКРЕПЛЕНО", 11, Style.DIM, true));
        JSONArray arr = Engine.pinned(this);
        if (arr.length() == 0) box.addView(s.text("пусто — закрепи программы во вкладке «Программы»", 11, Style.DIM, false));
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            box.addView(startRow(o.optString("name"), o.optString("exe"), () -> launchPinned(o)));
        }

        box.addView(s.line());
        box.addView(s.text("СИСТЕМА", 11, Style.DIM, true));
        box.addView(startRow("Проводник", "диски C: и D:", () -> { browseDir = null; showTab(2); }));
        box.addView(startRow("Диспетчер задач", "процессы Wine и Box64", this::openTaskManager));
        box.addView(startRow("Терминал", "выполнить команду в Windows", this::openTerminal));
        box.addView(startRow("Персонализация", "имя владельца и обои", () -> showTab(3)));
        box.addView(startRow("Контейнеры", "создать или настроить", () -> showTab(1)));
        box.addView(startRow("Обои PortHub", "поставить обои в Windows", this::applyWallpaper));
        box.addView(startRow("Сброс сессии", "убить зависшие процессы", this::resetSession));
        box.addView(startRow("Очистить мусор", "логи и временные файлы", this::cleanJunk));
        box.addView(startRow("Ярлык на рабочий стол", "быстрый запуск Windows", () -> makeShortcut(true)));

        dialog(box);
    }

    private View startRow(String title, String sub, final Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(s.dp(10), s.dp(10), s.dp(10), s.dp(10));
        row.setBackground(s.press(s.round(Style.CARD, 12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Style.MATCH, Style.WRAP);
        lp.bottomMargin = s.dp(6);
        row.setLayoutParams(lp);
        row.addView(s.text(title, 13, Style.TEXT, true));
        if (sub != null && !sub.isEmpty()) row.addView(s.text(sub, 10, Style.DIM, false));
        row.setOnClickListener(v -> { try { onClick.run(); } catch (Throwable ignored) {} });
        return row;
    }

    private void openTaskManager() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(s.dp(14), s.dp(14), s.dp(14), s.dp(14));
        ArrayList<String> list = Engine.listProcesses();
        box.addView(s.text("ПРОЦЕССОВ: " + list.size(), 11, Style.DIM, true));
        if (list.isEmpty()) box.addView(s.text("ничего не запущено", 12, Style.DIM, false));
        final AlertDialog[] holder = new AlertDialog[1];
        for (final String item : list) {
            String[] parts = item.split("\\|", 2);
            final int pid = Integer.parseInt(parts[0]);
            box.addView(startRow("pid " + pid, parts.length > 1 ? parts[1] : "", () -> {
                Engine.killPid(pid);
                if (holder[0] != null) holder[0].dismiss();
                ui.postDelayed(this::openTaskManager, 400);
            }));
        }
        box.addView(s.gap(6));
        box.addView(s.small("Убить всё Wine", () -> {
            Engine.killWineProcesses();
            if (holder[0] != null) holder[0].dismiss();
        }));
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Style.BG2);
        sv.addView(box);
        AlertDialog d = new AlertDialog.Builder(this).setView(sv).create();
        holder[0] = d;
        d.show();
    }

    private void openTerminal() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(s.dp(14), s.dp(14), s.dp(14), s.dp(14));
        box.addView(s.text("КОМАНДА В WINDOWS", 11, Style.DIM, true));
        final EditText cmd = new EditText(this);
        cmd.setHint("например: ver   ·   dir C:\\   ·   ipconfig");
        cmd.setTextColor(Style.TEXT);
        cmd.setHintTextColor(Style.DIM);
        cmd.setInputType(InputType.TYPE_CLASS_TEXT);
        cmd.setBackground(s.round(Style.CARD, 12));
        cmd.setPadding(s.dp(12), s.dp(10), s.dp(12), s.dp(10));
        box.addView(cmd);
        box.addView(s.gap(8));
        box.addView(s.big("Выполнить", Style.ACCENT, () -> {
            String c = cmd.getText().toString().trim();
            if (!c.isEmpty()) runCommand(c);
        }));
        box.addView(s.gap(6));
        box.addView(s.small("Открыть cmd.exe (окно в Windows)", this::openCmd));
        dialog(box);
    }

    private void runCommand(String command) {
        runBg("Команда", () -> {
            Container c = Engine.activeContainer(this);
            if (c == null) return "нет контейнера";
            final String cc = command;
            ui.post(() -> Engine.runCommand(this, c, cc));
            return "выполняю: " + cc;
        });
    }

    private void openCmd() {
        runBg("cmd.exe", () -> {
            Container c = Engine.activeContainer(this);
            if (c == null) return "нет контейнера";
            ui.post(() -> Engine.launchExe(this, c, "C:\\windows\\system32\\cmd.exe", ""));
            return "открываю cmd";
        });
    }

    private void applyWallpaper() {
        runBg("Обои", () -> {
            Container c = Engine.activeContainer(this);
            if (c == null) return "нет контейнера";
            Engine.setWallpaper(c);
            return "обои поставлены";
        });
    }

    // ------------------------------------------------------------------ Контейнеры

    private View pageContainers() {
        LinearLayout c = new LinearLayout(this);

        LinearLayout make = s.card();
        make.addView(s.text("НОВЫЙ КОНТЕЙНЕР", 11, Style.DIM, true));
        final EditText name = new EditText(this);
        name.setHint("название, например Work");
        name.setTextColor(Style.TEXT);
        name.setHintTextColor(Style.DIM);
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        name.setBackground(s.round(Style.CARD2, 12));
        name.setPadding(s.dp(12), s.dp(10), s.dp(12), s.dp(10));
        make.addView(name);
        make.addView(s.gap(8));
        make.addView(s.text("Разрешение", 11, Style.DIM, false));
        final String[] screen = {"960x540"};
        make.addView(chips(Engine.SCREENS, Engine.SCREEN_LABELS, "960x540", v -> screen[0] = v));
        make.addView(s.text("Windows", 11, Style.DIM, false));
        final String[] winver = {"win11"};
        make.addView(chips(Engine.WINVERS, Engine.WINVERS_LABELS, "win11", v -> winver[0] = v));
        make.addView(s.gap(8));
        make.addView(s.big("Создать контейнер", Style.ACCENT2, () -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) n = "Container";
            final String nn = n;
            runBg("Создаю " + nn, () -> {
                Engine.createContainer(this, nn, screen[0], winver[0], () -> ui.post(this::refresh));
                return "контейнер создан";
            });
        }));
        c.addView(make);

        for (final Container cont : Engine.containers(this)) {
            LinearLayout card = s.card();
            LinearLayout head = s.row();
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(s.text(cont.getName(), 15, Style.TEXT, true), new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
            TextView idb = s.text("id " + Engine.id(cont), 11, Style.DIM, false);
            idb.setPadding(s.dp(8), s.dp(4), s.dp(8), s.dp(4));
            idb.setBackground(s.round(Style.CARD2, 10));
            head.addView(idb);
            card.addView(head);
            card.addView(s.text("экран " + cont.getScreenSize()
                    + "  ·  " + Engine.getFor(this, cont, "winver", Engine.DEF_WINVER)
                    + "  ·  память " + Engine.getFor(this, cont, "mem", Engine.DEF_MEM) + " МБ"
                    + "  ·  на диске " + Engine.human(Engine.dirSize(cont.getRootDir())), 11, Style.DIM, false));

            LinearLayout acts = s.row();
            acts.setPadding(0, s.dp(10), 0, 0);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, Style.WRAP, 1f);
            ap.rightMargin = s.dp(6);
            acts.addView(s.small("Запустить", () -> runBg("Запуск", () -> {
                ui.post(() -> Engine.launchContainer(this, cont));
                return "запускаю";
            })), ap);
            acts.addView(s.small("Сделать активным", () -> { Engine.setActive(this, cont); refresh(); }),
                    new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
            card.addView(acts);

            LinearLayout acts2 = s.row();
            acts2.setPadding(0, s.dp(6), 0, 0);
            LinearLayout.LayoutParams ap2 = new LinearLayout.LayoutParams(0, Style.WRAP, 1f);
            ap2.rightMargin = s.dp(6);
            acts2.addView(s.small("Копировать", () -> runBg("Копирую", () -> {
                Engine.duplicateContainer(this, cont, () -> ui.post(this::refresh));
                return "скопировано";
            })), ap2);
            acts2.addView(s.small("Удалить", () -> confirmDelete(cont)), new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
            card.addView(acts2);

            card.addView(s.line());
            card.addView(s.text("Настройки этого контейнера", 11, Style.DIM, true));
            card.addView(s.text("Разрешение", 11, Style.DIM, false));
            card.addView(chips(Engine.SCREENS, Engine.SCREEN_LABELS, Engine.getFor(this, cont, "screen", Engine.DEF_SCREEN),
                    v -> { Engine.setFor(this, cont, "screen", v); Engine.applyInBackground(this); refresh(); }));
            card.addView(s.text("Windows", 11, Style.DIM, false));
            card.addView(chips(Engine.WINVERS, Engine.WINVERS_LABELS, Engine.getFor(this, cont, "winver", Engine.DEF_WINVER),
                    v -> { Engine.setFor(this, cont, "winver", v); Engine.applyInBackground(this); refresh(); }));
            card.addView(s.text("Видео память, МБ", 11, Style.DIM, false));
            card.addView(chips(Engine.MEM, Engine.MEM, Engine.getFor(this, cont, "mem", Engine.DEF_MEM),
                    v -> { Engine.setFor(this, cont, "mem", v); Engine.applyInBackground(this); refresh(); }));
            card.addView(s.text("Службы Windows при старте", 11, Style.DIM, false));
            card.addView(chips(Engine.STARTUP, Engine.STARTUP_LABELS, Engine.getFor(this, cont, "startup", Engine.DEF_STARTUP),
                    v -> { Engine.setFor(this, cont, "startup", v); Engine.applyInBackground(this); refresh(); }));
            card.addView(s.text("Скорость Box64", 11, Style.DIM, false));
            card.addView(chips(Engine.PRESETS, Engine.PRESETS, Engine.getFor(this, cont, "preset", Engine.DEF_PRESET),
                    v -> { Engine.setFor(this, cont, "preset", v); Engine.applyInBackground(this); refresh(); }));
            card.addView(s.text("Лимит FPS", 11, Style.DIM, false));
            card.addView(chips(Engine.FPS, Engine.FPS_LABELS, Engine.getFor(this, cont, "fps", Engine.DEF_FPS),
                    v -> { Engine.setFor(this, cont, "fps", v); Engine.applyInBackground(this); refresh(); }));
            card.addView(s.text("WebEngine", 11, Style.DIM, false));
            card.addView(chips(Engine.WE, Engine.WE_LABELS, Engine.getFor(this, cont, "webengine", Engine.DEF_WEBENGINE),
                    v -> { Engine.setFor(this, cont, "webengine", v); Engine.applyInBackground(this); refresh(); }));

            c.addView(card);
        }
        return scroll(c);
    }

    private void confirmDelete(final Container cont) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить " + cont.getName() + "?")
                .setMessage("Всё содержимое контейнера, включая установленные программы, будет стёрто.")
                .setPositiveButton("Удалить", (d, w) -> runBg("Удаляю", () -> {
                    Engine.removeContainer(this, cont, () -> ui.post(this::refresh));
                    return "удалено";
                }))
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ------------------------------------------------------------------ Программы

    private View pageApps() {
        LinearLayout c = new LinearLayout(this);
        final Container cont = Engine.activeContainer(this);

        LinearLayout tools = s.card();
        tools.addView(s.text("ПРОВОДНИК", 11, Style.DIM, true));
        tools.addView(s.text("Нажми на .exe, чтобы запустить или установить.", 11, Style.DIM, false));
        LinearLayout r = s.row();
        r.setPadding(0, s.dp(8), 0, 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Style.WRAP, 1f);
        p.rightMargin = s.dp(6);
        r.addView(s.small("Найти программы", this::scanApps), p);
        r.addView(s.small("Загрузки (D:)", () -> { browseDir = Engine.downloads(); refresh(); }),
                new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
        tools.addView(r);
        LinearLayout r2 = s.row();
        r2.setPadding(0, s.dp(6), 0, 0);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, Style.WRAP, 1f);
        p2.rightMargin = s.dp(6);
        r2.addView(s.small("Диск C:", () -> {
            browseDir = cont == null ? null : new File(cont.getRootDir(), ".wine/drive_c");
            refresh();
        }), p2);
        r2.addView(s.small("Скачать Студию", this::downloadOnly), new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
        tools.addView(r2);
        c.addView(tools);

        if (browseDir != null && browseDir.isDirectory()) {
            LinearLayout br = s.card();
            br.addView(s.text("ПУТЬ: " + Engine.winPath(browseDir, cont == null ? null : cont.getRootDir()), 11, Style.DIM, true));
            int shown = 0;
            for (final File k : Engine.listDir(browseDir)) {
                if (shown++ > 200) break;
                LinearLayout row = s.row();
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(s.text((k.isDirectory() ? "[ ] " : "     ") + k.getName(), 12,
                        k.isDirectory() ? Style.TEXT : Style.DIM, k.isDirectory()),
                        new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
                if (!k.isDirectory() && k.getName().toLowerCase().endsWith(".exe")) {
                    final String wp = Engine.winPath(k, cont == null ? null : cont.getRootDir());
                    row.addView(s.small("Пуск", () -> runExe(cont, wp, k.getName())));
                    row.addView(s.small("Закрепить", () -> {
                        Engine.pin(this, k.getName(), Engine.id(cont), wp, "");
                        toast("закреплено");
                    }));
                }
                row.setPadding(0, s.dp(5), 0, s.dp(5));
                if (k.isDirectory()) row.setOnClickListener(v -> { browseDir = k; refresh(); });
                br.addView(row);
            }
            if (browseDir.getParentFile() != null) {
                br.addView(s.gap(6));
                br.addView(s.small(".. вверх", () -> { browseDir = browseDir.getParentFile(); refresh(); }));
            }
            c.addView(br);
        }

        LinearLayout list = s.card();
        list.addView(s.text("НАЙДЕННЫЕ ПРОГРАММЫ", 11, Style.DIM, true));
        if (found.isEmpty()) list.addView(s.text("Нажми «Найти программы».", 11, Style.DIM, false));
        for (final File f : found) {
            final String wp = Engine.winPath(f, cont == null ? null : cont.getRootDir());
            LinearLayout row = s.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(s.text(f.getName(), 12, Style.TEXT, false), new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
            row.addView(s.small("Пуск", () -> runExe(cont, wp, f.getName())));
            row.addView(s.small("Закрепить", () -> {
                Engine.pin(this, f.getName(), Engine.id(cont), wp, "");
                toast("закреплено");
            }));
            row.setPadding(0, s.dp(5), 0, s.dp(5));
            list.addView(row);
        }
        c.addView(list);

        LinearLayout pin = s.card();
        pin.addView(s.text("ЗАКРЕПЛЕНО", 11, Style.DIM, true));
        JSONArray arr = Engine.pinned(this);
        if (arr.length() == 0) pin.addView(s.text("пусто", 11, Style.DIM, false));
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            LinearLayout row = s.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(s.text(o.optString("name"), 12, Style.TEXT, false), new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
            row.addView(s.small("Убрать", () -> { Engine.unpin(this, o.optString("exe")); refresh(); }));
            row.setPadding(0, s.dp(5), 0, s.dp(5));
            row.setOnClickListener(v -> launchPinned(o));
            pin.addView(row);
        }
        c.addView(pin);
        return scroll(c);
    }

    private void runExe(final Container cont, final String winPath, final String name) {
        if (cont == null) { toast("нет контейнера"); return; }
        if (winPath == null) { toast("путь не определился"); return; }
        runBg("Запуск " + name, () -> {
            ui.post(() -> Engine.launchExe(this, cont, winPath, ""));
            return "запускаю";
        });
    }

    private void scanApps() {
        final Container cont = Engine.activeContainer(this);
        if (cont == null) { toast("нет контейнера"); return; }
        final int token = ++scanToken;
        runBg("Ищу программы", () -> {
            ArrayList<File> res = Engine.listExes(new File(cont.getRootDir(), ".wine/drive_c"));
            if (token == scanToken) { found = res; ui.post(this::refresh); }
            return "найдено " + res.size();
        });
    }

    // ------------------------------------------------------------------ Настройки

    private View pageSettings() {
        LinearLayout c = new LinearLayout(this);

        LinearLayout per = s.card();
        per.addView(s.text("ПЕРСОНАЛИЗАЦИЯ WINDOWS", 11, Style.DIM, true));
        final EditText owner = new EditText(this);
        owner.setHint("имя владельца Windows");
        owner.setText(Engine.owner(this));
        owner.setTextColor(Style.TEXT);
        owner.setHintTextColor(Style.DIM);
        owner.setInputType(InputType.TYPE_CLASS_TEXT);
        owner.setBackground(s.round(Style.CARD2, 12));
        owner.setPadding(s.dp(12), s.dp(10), s.dp(12), s.dp(10));
        per.addView(owner);
        LinearLayout pr = s.row();
        pr.setPadding(0, s.dp(8), 0, 0);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, Style.WRAP, 1f);
        pp.rightMargin = s.dp(6);
        pr.addView(s.small("Сохранить имя", () -> runBg("Имя владельца", () -> {
            Container act = Engine.activeContainer(this);
            if (act == null) return "нет контейнера";
            Engine.setOwner(this, act, owner.getText().toString().trim());
            return "имя сохранено";
        })), pp);
        pr.addView(s.small("Поставить обои", this::applyWallpaper), new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
        per.addView(pr);
        per.addView(s.gap(8));
        per.addView(s.text("Тема интерфейса", 11, Style.DIM, false));
        per.addView(chips(Style.THEMES, Style.THEME_LABELS, Style.THEME, v -> {
            Engine.set(this, "theme", v);
            Style.applyTheme(v);
            refresh();
        }));
        c.addView(per);

        LinearLayout top = s.card();
        top.addView(s.text("ПРОФИЛЬ", 11, Style.DIM, true));
        LinearLayout r = s.row();
        r.setPadding(0, s.dp(8), 0, 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Style.WRAP, 1f);
        p.rightMargin = s.dp(6);
        r.addView(s.small("Лайт (быстрее)", () -> { Engine.turbo(this); refresh(); }), p);
        r.addView(s.small("Как было (надёжно)", () -> { Engine.restore(this); refresh(); }),
                new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
        top.addView(r);
        top.addView(s.gap(8));
        top.addView(chips(Engine.PROFILES, Engine.PROFILE_LABELS, Engine.currentProfile(this), v -> {
            Engine.applyProfile(this, v);
            Engine.applyInBackground(this);
            refresh();
        }));
        c.addView(top);

        LinearLayout g = s.card();
        g.addView(s.text("ОБЩИЕ НАСТРОЙКИ", 11, Style.DIM, true));
        chipRow(g, "Запускать Windows при открытии", Engine.ON_OFF, Engine.ON_OFF_LABELS,
                Engine.get(this, "autostart", Engine.DEF_AUTOSTART), v -> { Engine.set(this, "autostart", v); refresh(); });
        chipRow(g, "Разрешение", Engine.SCREENS, Engine.SCREEN_LABELS,
                Engine.get(this, "screen", Engine.DEF_SCREEN), v -> { Engine.set(this, "screen", v); Engine.applyInBackground(this); refresh(); });
        chipRow(g, "Windows", Engine.WINVERS, Engine.WINVERS_LABELS,
                Engine.get(this, "winver", Engine.DEF_WINVER), v -> { Engine.set(this, "winver", v); Engine.applyInBackground(this); refresh(); });
        chipRow(g, "Видео память, МБ", Engine.MEM, Engine.MEM,
                Engine.get(this, "mem", Engine.DEF_MEM), v -> { Engine.set(this, "mem", v); Engine.applyInBackground(this); refresh(); });
        chipRow(g, "Службы Windows", Engine.STARTUP, Engine.STARTUP_LABELS,
                Engine.get(this, "startup", Engine.DEF_STARTUP), v -> { Engine.set(this, "startup", v); Engine.applyInBackground(this); refresh(); });
        chipRow(g, "Скорость Box64", Engine.PRESETS, Engine.PRESETS,
                Engine.get(this, "preset", Engine.DEF_PRESET), v -> { Engine.set(this, "preset", v); Engine.applyInBackground(this); refresh(); });
        chipRow(g, "Лимит FPS", Engine.FPS, Engine.FPS_LABELS,
                Engine.get(this, "fps", Engine.DEF_FPS), v -> { Engine.set(this, "fps", v); Engine.applyInBackground(this); refresh(); });
        chipRow(g, "WebEngine", Engine.WE, Engine.WE_LABELS,
                Engine.get(this, "webengine", Engine.DEF_WEBENGINE), v -> { Engine.set(this, "webengine", v); Engine.applyInBackground(this); refresh(); });
        c.addView(g);

        LinearLayout misc = s.card();
        misc.addView(s.text("ПРОЧЕЕ", 11, Style.DIM, true));
        LinearLayout r2 = s.row();
        r2.setPadding(0, s.dp(8), 0, 0);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, Style.WRAP, 1f);
        p2.rightMargin = s.dp(6);
        r2.addView(s.small("Ярлык на рабочий стол", () -> makeShortcut(true)), p2);
        r2.addView(s.small("Стереть настройки", () -> { Engine.resetAll(this); refresh(); }),
                new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
        misc.addView(r2);
        misc.addView(s.gap(8));
        misc.addView(s.text("Свободно: " + freeGb() + " ГБ   ·   RAM: " + ramGb() + " ГБ", 11, Style.DIM, false));
        c.addView(misc);
        return scroll(c);
    }

    private void chipRow(LinearLayout host, String title, String[] values, String[] labels, String current, Pick onPick) {
        host.addView(s.text(title, 11, Style.DIM, false));
        host.addView(chips(values, labels, current, onPick));
    }

    private LinearLayout chips(String[] values, String[] labels, String current, Pick onPick) {
        LinearLayout row = s.row();
        row.setPadding(0, s.dp(6), 0, s.dp(10));
        for (int i = 0; i < values.length; i++) {
            final String v = values[i];
            TextView ch = s.chip(labels[i], v.equals(current));
            ch.setOnClickListener(x -> onPick.on(v));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Style.WRAP, Style.WRAP);
            lp.rightMargin = s.dp(6);
            row.addView(ch, lp);
        }
        return row;
    }

    // ------------------------------------------------------------------ Логи

    private View pageLogs() {
        LinearLayout c = new LinearLayout(this);
        LinearLayout tools = s.card();
        tools.addView(s.text("ДИАГНОСТИКА", 11, Style.DIM, true));
        LinearLayout r = s.row();
        r.setPadding(0, s.dp(8), 0, 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Style.WRAP, 1f);
        p.rightMargin = s.dp(6);
        r.addView(s.small("Лог движка", this::dumpEngineLog), p);
        r.addView(s.small("Лог Студии", this::dumpStudioLog), new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
        tools.addView(r);
        LinearLayout r2 = s.row();
        r2.setPadding(0, s.dp(6), 0, 0);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, Style.WRAP, 1f);
        p2.rightMargin = s.dp(6);
        r2.addView(s.small("Проверить телефон", this::diagnostics), p2);
        r2.addView(s.small("Копировать лог", this::copyLog), new LinearLayout.LayoutParams(0, Style.WRAP, 1f));
        tools.addView(r2);
        tools.addView(s.gap(8));
        tools.addView(s.small("Очистить лог", () -> { Engine.clearLog(); refresh(); }));
        c.addView(tools);

        LinearLayout box = s.card();
        box.addView(s.text("ЛОГ ПРИЛОЖЕНИЯ", 11, Style.DIM, true));
        logView = s.mono(Engine.logText(), 9.5f, 0xFF9DA7B3);
        ScrollView sc = new ScrollView(this);
        sc.addView(logView);
        box.addView(sc, new LinearLayout.LayoutParams(Style.MATCH, s.dp(340)));
        c.addView(box);
        return scroll(c);
    }

    // ------------------------------------------------------------------ действия

    private void status(String v) { statusView.setText(v); }
    private void toast(String v) { Toast.makeText(this, v, Toast.LENGTH_SHORT).show(); }

    private void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("log", Engine.logText()));
            toast("лог скопирован");
        } catch (Throwable t) { toast("не скопировалось"); }
    }

    private void runBg(String what, Supplier<String> body) {
        if (busy) { toast("занят, подожди"); return; }
        busy = true;
        status(what + "...");
        new Thread(() -> {
            String done = "готово";
            try {
                String r = body.get();
                if (r != null) done = r;
            } catch (Throwable t) {
                Engine.log("!! " + what + ": " + t);
                done = "ошибка: " + t;
            }
            final String msg = done;
            ui.post(() -> { busy = false; status(msg); if (logView != null) logView.setText(Engine.logText()); });
        }).start();
    }

    private void startWindows() {
        runBg("Запуск Windows", () -> {
            ContainerManager cm = Engine.manager(this);
            Container c = Engine.ensureContainer(this, cm);
            if (c == null) return "контейнера нет";
            ui.post(() -> Engine.launchContainer(this, c));
            return "запускаю Windows";
        });
    }

    private void downloadOnly() {
        runBg("Скачивание", () -> {
            String guid = Engine.fetchVersionGuid();
            if (guid != null) Engine.downloadInstaller(guid, p -> ui.post(() -> status("скачано " + p + "%")));
            return "в Загрузках";
        });
    }

    private void resetSession() {
        runBg("Сброс сессии", () -> "убито процессов: " + Engine.killWineProcesses());
    }

    private void cleanJunk() {
        runBg("Чистка", () -> {
            Engine.killWineProcesses();
            return "освобождено " + Engine.human(Engine.cleanJunk(this));
        });
    }

    private void dumpEngineLog() {
        runBg("Читаю лог движка", () -> {
            Engine.log("=== ЛОГ ДВИЖКА ===");
            Engine.log(Engine.engineLog(this));
            Engine.log("=== /ЛОГ ДВИЖКА ===");
            ui.post(this::refresh);
            return "лог готов";
        });
    }

    private void dumpStudioLog() {
        runBg("Читаю лог Студии", () -> {
            for (Object o : Engine.manager(this).getContainers()) {
                File log = Engine.newestLog(((Container) o).getRootDir(), "roblox");
                if (log != null) {
                    Engine.log("=== ЛОГ: " + log.getAbsolutePath() + " ===");
                    Engine.log(Engine.tail(log, 150));
                    Engine.log("=== /ЛОГ ===");
                    ui.post(this::refresh);
                    return "лог готов";
                }
            }
            return "лог не найден";
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
        sb.append("Оценка: ").append(score()).append(" / 100");
        Engine.log(sb.toString());
        toast("записано в лог");
        refresh();
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
            String v = GLES20.glGetString(GLES20.GL_RENDERER);
            EGL14.eglTerminate(d);
            return v == null ? "?" : v;
        } catch (Throwable t) { return "не определился"; }
    }

    private int score() {
        int v = 100;
        try { if (Double.parseDouble(ramGb()) < 8) v -= 20; } catch (Throwable ignored) {}
        try { if (Double.parseDouble(freeGb()) < 8) v -= 20; } catch (Throwable ignored) {}
        if (!gpuRenderer().toLowerCase().contains("adreno")) v -= 20;
        if (Runtime.getRuntime().availableProcessors() < 8) v -= 10;
        return Math.max(v, 0);
    }

    private void makeShortcut(boolean pin) {
        try {
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null) { toast("ярлыки не поддерживаются"); return; }
            Intent it = new Intent(this, PortHubActivity.class);
            it.setAction("ai.venice.porthub.STUDIO");
            it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            it.putExtra("go", "studio");
            ShortcutInfo si = new ShortcutInfo.Builder(this, "studio")
                    .setShortLabel("Windows 11")
                    .setLongLabel("Запустить Windows 11")
                    .setRank(0)
                    .setIcon(Icon.createWithResource(this, com.winlator.R.mipmap.ic_launcher))
                    .setIntent(it)
                    .build();
            sm.setDynamicShortcuts(Collections.singletonList(si));
            if (pin) {
                if (sm.isRequestPinShortcutSupported()) {
                    sm.requestPinShortcut(si, null);
                    toast("подтверди добавление ярлыка");
                } else toast("ярлык в меню иконки (долгое нажатие)");
            } else Engine.log("ярлык «Windows 11» зарегистрирован");
        } catch (Throwable t) {
            Engine.log("ярлык: " + t);
            if (pin) toast("не получилось: " + t);
        }
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
