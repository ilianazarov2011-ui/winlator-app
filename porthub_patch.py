#!/usr/bin/env python3
"""Правит файлы движка под PortHub. Запускается в сборке."""
import pathlib
import re

MANIFEST = pathlib.Path("app/src/main/AndroidManifest.xml")
STRINGS = pathlib.Path("app/src/main/res/values/strings.xml")

OUR_ACTIVITY = '''
        <activity
            android:name="com.winlator.porthub.PortHubActivity"
            android:theme="@style/AppThemeDark"
            android:exported="true"
            android:label="PortHub"
            android:screenOrientation="sensor"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
'''


def patch_manifest():
    t = MANIFEST.read_text(encoding="utf-8")
    if "PortHubActivity" in t:
        print("манифест уже пропатчен")
        return
    t = re.sub(r'\n\s*<category android:name="android\.intent\.category\.LAUNCHER"\s*/>',
               '', t, count=1)
    t = t.replace("    </application>", OUR_ACTIVITY + "    </application>")
    MANIFEST.write_text(t, encoding="utf-8")
    print("манифест пропатчен")


def patch_strings():
    t = STRINGS.read_text(encoding="utf-8")
    t2 = re.sub(r'(<string name="app_name">)[^<]*(</string>)', r'\1PortHub\2', t, count=1)
    if t2 != t:
        STRINGS.write_text(t2, encoding="utf-8")
        print("название приложения -> PortHub")


def patch_repositories():
    for name in ("build.gradle", "settings.gradle"):
        p = pathlib.Path(name)
        if not p.exists():
            continue
        t = p.read_text(encoding="utf-8")
        if "repositories" not in t:
            continue
        blocks = len(re.findall(r'repositories\s*\{', t))
        t2 = re.sub(r'(repositories\s*\{)', r'\1\n        google()', t)
        if t2 != t:
            p.write_text(t2, encoding="utf-8")
            print("в " + name + ": добавлен google() в " + str(blocks) + " блок(ов)")


def patch_version():
    p = pathlib.Path("app/build.gradle")
    if not p.exists():
        return
    t = p.read_text(encoding="utf-8")
    t2 = re.sub(r'versionName\s+"[^"]*"', 'versionName "0.1"', t, count=1)
    if t2 != t:
        p.write_text(t2, encoding="utf-8")
        print("versionName -> 0.1")


patch_manifest()
patch_strings()
patch_repositories()
patch_version()
