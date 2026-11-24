"""Exercise first install and the observer on an Android test device via adb."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.cleverraven.cataclysmdda.lorencai"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=30)


def click_text(wanted, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/ai-screen.xml")
            tree = ET.fromstring(adb("shell", "cat", "/sdcard/ai-screen.xml"))
        except (subprocess.SubprocessError, ET.ParseError):
            time.sleep(1)
            continue
        for node in tree.iter("node"):
            if node.get("text", "").casefold() == wanted.casefold():
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                return
        time.sleep(1)
    raise RuntimeError(f"Android UI did not display {wanted!r}")


adb("install", "-r", "Cataclysm-AI-0.1-arm64.apk")
adb("shell", "am", "start", "-n", PACKAGE + "/com.cleverraven.cataclysmdda.SplashScreen")
click_text("Start Game", 240)
click_text("ИИ", 120)
click_text("API и личность", 30)
click_text("Сохранить", 30)
click_text("ИИ", 30)
click_text("Память персонажа", 30)
click_text("Закрыть", 30)
print("Installed ARM64 APK, opened game activity, saved settings and opened memory.")
