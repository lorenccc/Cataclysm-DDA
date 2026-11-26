"""Exercise first install and the observer on an Android test device via adb."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.cleverraven.cataclysmdda.lorencai"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=30)


def click_text(wanted, timeout):
    last_labels = []
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            adb("shell","rm","-f","/sdcard/ai-screen.xml")
            adb("shell", "uiautomator", "dump", "/sdcard/ai-screen.xml")
            tree = ET.fromstring(adb("shell", "cat", "/sdcard/ai-screen.xml"))
        except (subprocess.SubprocessError, ET.ParseError):
            time.sleep(1)
            continue
        nodes = list(tree.iter("node"))
        if wanted != "Start Game" and any(n.get("package") == "com.google.android.apps.nexuslauncher" for n in nodes):
            raise RuntimeError("Returned to Android launcher while waiting for " + wanted)
        last_labels = [n.get("text") for n in nodes if n.get("text")]
        for node in nodes:
            if node.get("text", "").casefold() == wanted.casefold():
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                print("Tapped", wanted, flush=True)
                return
        for node in nodes:
            if node.get("scrollable") == "true" and wanted in ("API и личность", "Память персонажа", "Software rendering"):
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                x = str((x1+x2)//2)
                adb("shell","input","swipe",x,str(y2-10),x,str(y1+10),"400")
                break
        if "ИИ" in last_labels and wanted in ("API и личность", "Память персонажа"):
            click_text("ИИ", 10)
        time.sleep(1)
    raise RuntimeError(f"Android UI did not display {wanted!r}. Visible labels: {last_labels!r}")


def exercise(software):
    adb("shell", "am", "start", "-n", PACKAGE + "/com.cleverraven.cataclysmdda.SplashScreen")
    if software:
        click_text("Software rendering", 240)
    click_text("Start Game", 240)
    time.sleep(12)  # SDL finishes applying its final orientation after the activity appears.
    click_text("ИИ", 120)
    click_text("API и личность", 60)
    click_text("Сохранить", 60)
    click_text("ИИ", 60)
    click_text("Память персонажа", 60)
    click_text("Закрыть", 60)
    print("Installed ARM64 APK, opened game activity, saved settings and opened memory.")


def report_failure():
    print(adb("logcat", "-b", "crash", "-d")[-14000:], flush=True)
    lines = adb("logcat", "-d", "-t", "4000").splitlines()
    print("\n".join(line for line in lines if any(
        key in line for key in ("SDL", "FATAL", "libmain.so", "cataclysmdda", "AndroidRuntime", "cdda ", "libc++")
    ))[-15000:], flush=True)
    for name in ("debug.log","crash.log"):
        result = subprocess.run(["adb","shell","tail","-80",
            f"/sdcard/Android/data/{PACKAGE}/files/config/{name}"], capture_output=True, text=True)
        print(result.stdout[-9000:],flush=True)


# Match a contemporary phone. The emulator action defaults to a 640x320 display.
adb("shell", "wm", "size", "1080x2400")
adb("shell", "wm", "density", "300")
adb("install", "-r", "Cataclysm-AI-0.1-arm64.apk")
try:
    exercise(False)
except RuntimeError as failure:
    print("Default renderer:", failure, flush=True)
    report_failure()
    adb("shell", "pm", "clear", PACKAGE)
    adb("logcat", "-c")
    try:
        exercise(True)
        print("Observer UI passed using software rendering.", flush=True)
    except Exception:
        report_failure()
        raise
