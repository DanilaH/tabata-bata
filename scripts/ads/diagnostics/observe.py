"""Read-only CDP observer for demo ad WebViews on a fresh CI emulator.

No request bodies, headers, cookies, query strings or ad interactions are collected.
"""
import json
import re
import subprocess
import threading
import time
import urllib.request
from urllib.parse import urlsplit
import websocket

lock = threading.Lock()
deadline = time.monotonic() + 900

def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, stderr=subprocess.DEVNULL, timeout=5).strip()

def origin(url):
    try:
        u = urlsplit(url)
        return u.scheme + "://" + (u.hostname or "")
    except ValueError:
        return "invalid"

def scrub(value):
    return re.sub(r"https?://[^\s)\]<>\"']+", lambda m: origin(m.group()), str(value))[:1200]

def record(target, kind, **data):
    with lock:
        print(json.dumps({"target": target, "kind": kind, **data}), flush=True)

def observe(target, number):
    requests = {}
    try:
        ws = websocket.create_connection(target["webSocketDebuggerUrl"], timeout=2, suppress_origin=True)
        for number_id, domain in enumerate(("Network", "Runtime", "Log"), 1):
            ws.send(json.dumps({"id": number_id, "method": domain + ".enable"}))
        record(number, "attached", origin=origin(target.get("url", "")))
        while time.monotonic() < deadline:
            try:
                event = json.loads(ws.recv())
            except websocket.WebSocketTimeoutException:
                continue
            method = event.get("method")
            p = event.get("params", {})
            request_id = p.get("requestId")
            if method == "Network.requestWillBeSent":
                requests[request_id] = origin(p["request"]["url"])
                record(number, "request", origin=requests[request_id], type=p.get("type"))
            elif method == "Network.responseReceived":
                response = p["response"]
                record(number, "response", origin=origin(response["url"]), status=response["status"],
                    mime=response.get("mimeType"), type=p.get("type"))
            elif method == "Network.loadingFailed":
                record(number, "network-failure", origin=requests.get(request_id),
                    error=p.get("errorText"), blocked=p.get("blockedReason"), type=p.get("type"))
            elif method == "Runtime.exceptionThrown":
                details = p.get("exceptionDetails", {})
                record(number, "exception", text=scrub(details.get("text")),
                    description=scrub(details.get("exception", {}).get("description", "")))
            elif method == "Runtime.consoleAPICalled" and p.get("type") in ("error", "warning"):
                record(number, "console", level=p.get("type"),
                    text=scrub(" ".join(str(a.get("value", "")) for a in p.get("args", []))))
            elif method == "Log.entryAdded":
                entry = p.get("entry", {})
                if entry.get("level") in ("error", "warning"):
                    record(number, "log", level=entry.get("level"), text=scrub(entry.get("text", "")))
    except Exception as e:
        record(number, "observer-ended", error=type(e).__name__)

seen = set()
forwarded = None
while time.monotonic() < deadline:
    try:
        pid = adb("shell", "pidof", "ru.tabatabata").split()[0]
        names = [line.split()[-1].removeprefix("@") for line in adb("shell", "cat", "/proc/net/unix").splitlines()
            if "webview_devtools_remote_" + pid in line]
        if names:
            if forwarded != names[0]:
                adb("forward", "tcp:9229", "localabstract:" + names[0])
                forwarded = names[0]
            with urllib.request.urlopen("http://127.0.0.1:9229/json/list", timeout=2) as response:
                targets = json.load(response)
            for target in targets:
                if target.get("webSocketDebuggerUrl") and target["id"] not in seen:
                    seen.add(target["id"])
                    threading.Thread(target=observe, args=(target, len(seen)), daemon=True).start()
    except (subprocess.SubprocessError, OSError, ValueError, IndexError):
        pass
    time.sleep(.5)
