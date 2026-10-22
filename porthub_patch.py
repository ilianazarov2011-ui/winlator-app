#!/usr/bin/env python3
"""Правит манифест и название движка под PortHub. Запускается в сборке."""
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
    # убираем иконку у родной активности движка, чтобы осталась только наша
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


patch_manifest()
patch_strings()
