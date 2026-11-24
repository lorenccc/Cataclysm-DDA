"""Experimental desktop controller for Cataclysm AI bridge.
Run with Python 3 and the game's save directory as argument.
Manual input is currently needed to wake the game's blocking input loop.
"""
import argparse
import getpass
import json
import os
from pathlib import Path
import time
import urllib.request

p = argparse.ArgumentParser()
p.add_argument("savedir", type=Path)
p.add_argument("--model", default="deepseek/deepseek-v4.1-flash-1017-uncensored")
p.add_argument("--url", default="https://nano-gpt.com/api/v1")
p.add_argument("--prompt", default="You are a survivor. Choose your own goals.")
p.add_argument("--delay", type=float, default=3)
args = p.parse_args()
key = os.environ.get("NANOGPT_API_KEY") or getpass.getpass("NanoGPT key: ")
state = args.savedir / "ai_state.txt"
command = args.savedir / "ai_command.txt"
allowed = set("N NE E SE S SW W NW WAIT".split())
history = []
last = None
while True:
    try:
        if not state.exists() or command.exists():
            time.sleep(1)
            continue
        stamp = state.stat().st_mtime_ns
        if stamp == last:
            time.sleep(1)
            continue
        observation = state.read_text(encoding="utf-8")
        messages = [{"role":"system","content":args.prompt +
                     " Reply with exactly one action: N NE E SE S SW W NW WAIT. "
                     "North is negative y, east is positive x. No other text."}]
        messages += history[-12:]
        messages.append({"role":"user","content":observation})
        req = urllib.request.Request(args.url.rstrip("/") + "/chat/completions",
            data=json.dumps({"model":args.model,"messages":messages,
                             "max_tokens":32,"temperature":0.5}).encode(),
            headers={"Authorization":"Bearer "+key,"Content-Type":"application/json"})
        with urllib.request.urlopen(req, timeout=60) as response:
            action = json.load(response)["choices"][0]["message"]["content"].strip().upper()
        last = stamp
        if action not in allowed:
            print("Invalid action:", action, flush=True)
            continue
        tmp = command.with_suffix(".tmp")
        tmp.write_text(action, encoding="utf-8")
        tmp.replace(command)
        history.extend([messages[-1],{"role":"assistant","content":action}])
        history = history[-12:]
        print(action, flush=True)
        time.sleep(max(0.5,args.delay))
    except KeyboardInterrupt:
        break
    except Exception as exc:
        print(type(exc).__name__, str(exc), flush=True)
        time.sleep(10)
