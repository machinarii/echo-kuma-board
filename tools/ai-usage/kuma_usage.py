#!/usr/bin/env python3
"""
Weekly Claude and Codex plan usage for Kuma Board's bottom strip.

Runs on the Mac that uses Claude Code / Codex. Uses no credentials: it never reads or sends a token. It reads only numbers the two
tools already write locally, and publishes a numbers-only JSON file that the board fetches over
the tailnet.

  tap -- CMD...   Claude Code status line wrapper. Reads the status line JSON on stdin, saves
                  rate_limits.seven_day.{used_percentage,resets_at}, then runs CMD with the same
                  stdin and passes its output through (your normal status line keeps working).
  login           One-time, run by you in your own terminal: signs a dedicated Claude Code
                  config folder in to claude.ai (`claude auth login`). The token stays with Claude
                  Code (Keychain); this script never reads it. Optional: without it, frontier-model
                  windows are not shown and the Claude number comes from the status line tap only.
  collect         Reads the saved Claude numbers and the newest Codex session logs
                  (~/.codex/sessions, only the weekly rate_limits entries), and writes usage.json.
                  Every 10 minutes, if `login` was done, also asks Claude Code for the plan's weekly
                  windows (all models plus per-model ones such as "Fable") with its `get_usage`
                  request. No prompt is sent, so it uses no quota. Run every 2 minutes by the LaunchAgent.
  serve           Answers GET /usage.json (and /kuma-usage/usage.json) on 127.0.0.1:31995 and
                  nothing else; `tailscale serve` proxies the tailnet path to it. Read-only, no
                  directory listing, no other files.
  show            Prints the published file.

Published file (all times are Unix epoch seconds):
  {"version":1,"generated_at":…,"claude":{"week_pct":42.0,"resets_at":…,"as_of":…},"codex":{…},
   "models":[{"tool":"claude","name":"Fable","week_pct":61.0,"resets_at":…,"as_of":…}]}

Only an allowlist of numeric fields is ever copied. Session logs contain prompts; nothing but the
rate-limit numbers is read out of them.
"""
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from datetime import datetime

HOME = os.path.expanduser("~")
STATE_DIR = os.environ.get("KUMA_USAGE_STATE", os.path.join(HOME, ".local/state/kuma-board-usage"))
PUBLIC_DIR = os.environ.get("KUMA_USAGE_PUBLIC", os.path.join(HOME, ".local/share/kuma-board-usage/public"))
CODEX_SESSIONS = os.environ.get("KUMA_USAGE_CODEX", os.path.join(HOME, ".codex/sessions"))
CLAUDE_STATE = os.path.join(STATE_DIR, "claude.json")
WEEK_MINUTES = 7 * 24 * 60
SANE = (1_577_836_800, 4_102_444_800)  # 2020..2100
CODEX_FILES = 8        # newest session logs to look at
CODEX_TAIL = 4 << 20   # bytes read from the end of each log
CLAUDE_BIN = os.environ.get("KUMA_USAGE_CLAUDE", "/opt/homebrew/bin/claude")
CLAUDE_LOGIN = os.path.join(STATE_DIR, "claude-login")   # dedicated CLAUDE_CONFIG_DIR
CLAUDE_PLAN = os.path.join(STATE_DIR, "claude-plan.json")
PLAN_EVERY = 600       # seconds between get_usage calls
PLAN_TIMEOUT = 90
MAX_MODELS = 4
# Display names for Codex limit buckets (owner choice): "premium" is the gpt-6-astra window.
CODEX_NAMES = {"premium": "Astra"}
NAME_OK = re.compile(r"[^A-Za-z0-9 .+-]")


def meter(pct, resets_at, as_of):
    """The only shape that is ever written. Returns None for anything that isn't a sane number."""
    try:
        pct = float(pct)
        as_of = int(as_of)
    except (TypeError, ValueError):
        return None
    if not (0.0 <= pct <= 1000.0) or not (SANE[0] <= as_of <= SANE[1]):
        return None
    out = {"week_pct": round(min(pct, 100.0), 1), "as_of": as_of}
    try:
        r = int(resets_at)
        if SANE[0] <= r <= SANE[1]:
            out["resets_at"] = r
    except (TypeError, ValueError):
        pass
    return out


def model(tool, name, m):
    """A per-model window. Names must already be what the board accepts (letters, digits, space . + -,
    at most 16 characters); anything else is dropped rather than rewritten."""
    name = name.strip() if isinstance(name, str) else ""
    if not m or not name or len(name) > 16 or NAME_OK.search(name) or not name[0].isalnum():
        return None
    return {"tool": tool, "name": name, **m}


def iso_seconds(v):
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return int(v)
    if isinstance(v, str):
        try:
            return int(datetime.fromisoformat(v.replace("Z", "+00:00")).timestamp())
        except ValueError:
            return None
    return None


def write_atomic(path, obj, mode=0o644):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix=".tmp-")
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(obj, f, separators=(",", ":"))
        os.chmod(tmp, mode)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


# ---------------------------------------------------------------- Claude (status line tap)

def tap(cmd):
    raw = sys.stdin.buffer.read()
    try:
        data = json.loads(raw)
        week = (data.get("rate_limits") or {}).get("seven_day") or {}
        m = meter(week.get("used_percentage"), week.get("resets_at"), time.time())
        if m:
            write_atomic(CLAUDE_STATE, m, 0o600)
    except Exception:
        pass  # never break the status line
    if not cmd:
        return 0
    try:
        return subprocess.run(cmd, input=raw).returncode
    except OSError as e:
        print(f"status line: {e.strerror}", file=sys.stderr)
        return 1


def claude_meter():
    try:
        with open(CLAUDE_STATE) as f:
            d = json.load(f)
        return meter(d.get("week_pct"), d.get("resets_at"), d.get("as_of"))
    except (OSError, ValueError, AttributeError):
        return None


# ---------------------------------------------------------------- Claude (plan windows via get_usage)

def _clean_env():
    """Only what Claude Code needs to find its dedicated login. No inherited tokens or API keys."""
    env = {k: os.environ[k] for k in ("HOME", "USER", "LOGNAME", "LANG", "TMPDIR") if k in os.environ}
    env["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin"
    env["CLAUDE_CONFIG_DIR"] = CLAUDE_LOGIN
    return env


def login():
    os.makedirs(CLAUDE_LOGIN, mode=0o700, exist_ok=True)
    os.chmod(CLAUDE_LOGIN, 0o700)
    print("Sign in with the claude.ai account whose plan usage the board should show.")
    os.execve(CLAUDE_BIN, [CLAUDE_BIN, "auth", "login"], _clean_env())


def _ask_usage():
    """Runs Claude Code once with only a get_usage control request (no prompt, no quota used).
    Returns the response's rate_limits dict, or None. Nothing from its output is logged."""
    cwd = os.path.join(STATE_DIR, "claude-cwd")   # empty folder: no project settings or CLAUDE.md
    os.makedirs(cwd, mode=0o700, exist_ok=True)
    req = {"type": "control_request", "request_id": "kuma-usage",
           "request": {"subtype": "get_usage", "skip_behaviors": True}}
    cmd = [CLAUDE_BIN, "-p", "--input-format", "stream-json", "--output-format", "stream-json",
           "--verbose", "--strict-mcp-config", "--no-session-persistence",
           "--settings", '{"disableAllHooks":true}']
    try:
        out = subprocess.run(cmd, input=(json.dumps(req) + "\n").encode(), capture_output=True,
                             cwd=cwd, env=_clean_env(), timeout=PLAN_TIMEOUT).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    for line in out.splitlines():
        try:
            o = json.loads(line)
        except ValueError:
            continue
        if not isinstance(o, dict) or o.get("type") != "control_response":
            continue
        r = o.get("response") or {}
        if r.get("request_id") not in (None, "kuma-usage") or r.get("subtype") != "success":
            return None
        u = r.get("response") or {}
        rl = u.get("rate_limits") if u.get("rate_limits_available") else None
        return rl if isinstance(rl, dict) else None
    return None


def refresh_plan(force=False):
    """Every PLAN_EVERY seconds (only after `login`): saves Claude's weekly windows, numbers only."""
    if not os.path.isdir(CLAUDE_LOGIN):
        return
    try:
        with open(CLAUDE_PLAN) as f:
            prev = json.load(f)
        if not force and time.time() - prev.get("tried_at", 0) < PLAN_EVERY:
            return
    except (OSError, ValueError, AttributeError):
        prev = {}
    now = int(time.time())
    rl = _ask_usage()
    state = {k: prev[k] for k in ("week", "models") if k in prev} if isinstance(prev, dict) else {}
    state["tried_at"] = now
    if rl is not None:
        week = rl.get("seven_day") or {}
        state["week"] = meter(week.get("utilization"), iso_seconds(week.get("resets_at")), now)
        models = []
        for b in rl.get("model_scoped") or []:
            if isinstance(b, dict):
                m = model("claude", b.get("display_name"),
                          meter(b.get("utilization"), iso_seconds(b.get("resets_at")), now))
                if m:
                    models.append(m)
        state["models"] = models[:MAX_MODELS]
    write_atomic(CLAUDE_PLAN, state, 0o600)


def claude_plan():
    try:
        with open(CLAUDE_PLAN) as f:
            d = json.load(f)
        week = d.get("week") or {}
        w = meter(week.get("week_pct"), week.get("resets_at"), week.get("as_of"))
        models = []
        for x in d.get("models") or []:
            m = model("claude", x.get("name"), meter(x.get("week_pct"), x.get("resets_at"), x.get("as_of")))
            if m:
                models.append(m)
        return w, models
    except (OSError, ValueError, AttributeError, TypeError):
        return None, []


# ---------------------------------------------------------------- Codex (session logs)

def _ts(line_obj, fallback):
    t = line_obj.get("timestamp")
    if isinstance(t, str):
        try:
            return int(datetime.fromisoformat(t.replace("Z", "+00:00")).timestamp())
        except ValueError:
            pass
    return int(fallback)


def _weekly(rl):
    for key in ("primary", "secondary"):
        w = rl.get(key)
        if isinstance(w, dict) and w.get("window_minutes") == WEEK_MINUTES:
            return w
    return None


def codex_meters():
    """Newest weekly window per Codex limit bucket. "codex" is the plan-wide one; others (such as
    "premium") are model windows. Returns (main, [model, ...])."""
    files = glob.glob(os.path.join(CODEX_SESSIONS, "*", "*", "*", "*.jsonl"))
    files.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    best = {}    # limit_id -> (meter, display name)
    for path in files[:CODEX_FILES]:
        try:
            size = os.path.getsize(path)
            with open(path, "rb") as f:
                f.seek(max(0, size - CODEX_TAIL))
                lines = f.read().splitlines()
            mtime = os.path.getmtime(path)
        except OSError:
            continue
        seen = set()
        for line in reversed(lines):
            if b'"rate_limits"' not in line:
                continue
            try:
                obj = json.loads(line)
            except ValueError:
                continue
            payload = obj.get("payload") if isinstance(obj, dict) else None
            rl = payload.get("rate_limits") if isinstance(payload, dict) else None
            if not isinstance(rl, dict):
                continue
            lid = rl.get("limit_id") if isinstance(rl.get("limit_id"), str) else "codex"
            if lid in seen:
                continue
            w = _weekly(rl)
            if not w:
                continue
            seen.add(lid)    # newest weekly entry for this bucket in this file
            m = meter(w.get("used_percent"), w.get("resets_at"), _ts(obj, mtime))
            if m and (lid not in best or m["as_of"] > best[lid][0]["as_of"]):
                name = CODEX_NAMES.get(lid) or (rl.get("limit_name") if isinstance(rl.get("limit_name"), str)
                                                else lid.replace("_", " ").title())
                best[lid] = (m, name)
    main = best.pop("codex", (None, None))[0]
    models = [x for x in (model("codex", n, m) for lid, (m, n) in sorted(best.items())) if x]
    return main, models[:MAX_MODELS]


# ---------------------------------------------------------------- publish

def collect(force_plan=False):
    refresh_plan(force_plan)
    report = {"version": 1, "generated_at": int(time.time())}
    plan_week, claude_models = claude_plan()
    # The newer of the status line tap and get_usage.
    claude = max((m for m in (claude_meter(), plan_week) if m), key=lambda m: m["as_of"], default=None)
    codex, codex_models = codex_meters()
    if claude:
        report["claude"] = claude
    if codex:
        report["codex"] = codex
    models = (claude_models + codex_models)[:MAX_MODELS]
    if models:
        report["models"] = models
    write_atomic(os.path.join(PUBLIC_DIR, "usage.json"), report)
    return report


SERVE_HOST = "127.0.0.1"
SERVE_PORT = int(os.environ.get("KUMA_USAGE_PORT", "31995"))


def serve():
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    allowed = {"/usage.json", "/kuma-usage/usage.json"}
    path = os.path.join(PUBLIC_DIR, "usage.json")

    class Handler(BaseHTTPRequestHandler):
        server_version = "kuma-usage"
        sys_version = ""

        def _send(self, head_only):
            if self.path.split("?", 1)[0] not in allowed:
                self.send_error(404)
                return
            try:
                with open(path, "rb") as f:
                    body = f.read(64 * 1024)
            except OSError:
                self.send_error(503, "no data yet")
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if not head_only:
                self.wfile.write(body)

        def do_GET(self):
            self._send(False)

        def do_HEAD(self):
            self._send(True)

        def log_message(self, *args):
            pass

    ThreadingHTTPServer((SERVE_HOST, SERVE_PORT), Handler).serve_forever()


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    if argv[0] == "tap":
        rest = argv[1:]
        if rest and rest[0] == "--":
            rest = rest[1:]
        return tap(rest)
    if argv[0] == "login":
        login()
        return 0
    if argv[0] == "collect":
        r = collect(force_plan="--now" in argv[1:])
        parts = [f"{k} {r[k]['week_pct']}%" for k in ("claude", "codex") if k in r]
        parts += [f"{m['tool']}/{m['name']} {m['week_pct']}%" for m in r.get("models", [])]
        print("ok: " + (", ".join(parts) or "no data yet"))
        return 0
    if argv[0] == "serve":
        serve()
        return 0
    if argv[0] == "show":
        with open(os.path.join(PUBLIC_DIR, "usage.json")) as f:
            print(f.read())
        return 0
    print(f"unknown command {argv[0]!r}; see --help", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
