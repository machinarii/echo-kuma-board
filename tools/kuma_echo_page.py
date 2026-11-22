#!/usr/bin/env python3
"""
Set up the Uptime Kuma status page that Kuma Board reads (slug "echo").

What it does (dry run by default; nothing changes without --apply):
  1. Renames monitors you list with --rename, when the old name exists.
  2. Creates the public status page "echo" if it doesn't exist.
  3. Puts the monitors you list with --monitor on it, in that order, in one group (with --all,
     every other monitor follows, except the Uptime Kuma self-check). Monitors you list but Kuma
     doesn't have are reported, not created (they need a URL or host you choose in Kuma).

Credentials: never passed on the command line. The script reads KUMA_USERNAME / KUMA_PASSWORD from
the environment if set (e.g. from your secret manager), otherwise asks at a hidden prompt. A 2FA code is
asked for if your account has 2FA.

Run (from the repo root, in your own terminal):
  uvx --from 'python-socketio[client]==5.11.4' --with websocket-client==1.8.0 \\
      python tools/kuma_echo_page.py --url https://kuma.example.com \\
      --monitor Internet --monitor DNS --monitor NAS   # dry run: shows the plan
  ... python tools/kuma_echo_page.py --apply    # makes the changes

Add one monitor to the existing page, leaving everything else as it is:
  ... python tools/kuma_echo_page.py --add UPS            # dry run
  ... python tools/kuma_echo_page.py --add UPS --apply
  --add matches the Kuma name exactly, or else as a unique part of it ("ups" finds "UPS - Office").
"""
import argparse
import getpass
import json
import os
import sys
import time
import urllib.request

import socketio

KUMA_URL = os.environ.get("KUMA_URL", "")
SLUG = "echo"
PAGE_TITLE = "Echo"
GROUP_NAME = "Servers"

# Board order (left to right, top to bottom), from --monitor.
BOARD = []
# Kuma name -> board name, from --rename.
RENAMES = {}
LEAVE_OFF = {"uptime kuma"}


def norm(s):
    return " ".join(str(s).split()).casefold()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apply", action="store_true", help="make the changes (default: dry run)")
    ap.add_argument("--url", default=KUMA_URL, help="Kuma address (default: $KUMA_URL)")
    ap.add_argument("--monitor", action="append", default=[], metavar="NAME",
                    help="a monitor for the page, in board order (repeatable)")
    ap.add_argument("--all", action="store_true",
                    help="also put every other Kuma monitor on the page (after the board's ones), "
                         "except the Uptime Kuma self-check")
    ap.add_argument("--rename", action="append", default=[], metavar="'KUMA NAME=BOARD NAME'",
                    help="extra rename, e.g. --rename 'Google DNS=Internet' (repeatable)")
    ap.add_argument("--add", action="append", default=[], metavar="NAME",
                    help="append this Kuma monitor to the existing page and change nothing else "
                         "(exact name, or a unique part of it; repeatable)")
    args = ap.parse_args()
    if not args.url:
        sys.exit("Give the Kuma address with --url or $KUMA_URL")
    BOARD.extend(args.monitor)
    for item in args.rename:
        old, sep, new = item.partition("=")
        if not sep or not old.strip() or not new.strip():
            sys.exit(f"--rename needs 'Kuma name=Board name', got {item!r}")
        RENAMES[old.strip()] = new.strip()

    sio = socketio.Client(reconnection=False)
    state = {"monitors": None}

    @sio.on("monitorList")
    def _monitors(data):
        state["monitors"] = data

    print(f"Connecting to {args.url} ...")
    sio.connect(args.url, transports=["websocket"], wait_timeout=15)

    username = os.environ.get("KUMA_USERNAME") or input("Kuma username: ").strip()
    password = os.environ.get("KUMA_PASSWORD") or getpass.getpass("Kuma password (hidden): ")
    res = sio.call("login", {"username": username, "password": password, "token": ""}, timeout=20)
    if res.get("tokenRequired"):
        token = getpass.getpass("2FA code (hidden): ").strip()
        res = sio.call("login", {"username": username, "password": password, "token": token}, timeout=20)
    password = None
    if not res.get("ok"):
        sys.exit(f"Login failed: {res.get('msg', 'unknown error')}")
    print("Logged in.")

    for _ in range(100):
        if state["monitors"] is not None:
            break
        time.sleep(0.1)
    monitors = state["monitors"] or {}
    by_name = {norm(m["name"]): m for m in monitors.values()}
    print(f"Kuma has {len(monitors)} monitors.\n")

    if args.add:
        add_to_page(sio, args, monitors)
        return

    # 1. Renames
    renames = []
    for old, new in RENAMES.items():
        m = by_name.get(norm(old))
        if m and norm(new) not in by_name:
            renames.append((m, new))
    print("Renames:" if renames else "Renames: none needed")
    for m, new in renames:
        print(f"  #{m['id']}: {m['name']!r} -> {new!r}")
    final_name = {m["id"]: m["name"] for m in monitors.values()}
    for m, new in renames:
        final_name[m["id"]] = new
    by_final = {norm(n): mid for mid, n in final_name.items()}

    # 2/3. Page contents
    on_page, missing = [], []
    for name in BOARD:
        mid = by_final.get(norm(name))
        (on_page.append((mid, name)) if mid is not None else missing.append(name))
    placed = {mid for mid, _ in on_page}
    left_off = sorted(n for mid, n in final_name.items() if mid not in placed and norm(n) in LEAVE_OFF)
    others = sorted(n for mid, n in final_name.items() if mid not in placed and norm(n) not in LEAVE_OFF)
    if args.all:
        # Kuma order (by id) after the board's own order. Group monitors (type "group") are skipped.
        mon_by_id = {m["id"]: m for m in monitors.values()}
        for mid in sorted(final_name, key=int):
            m = mon_by_id[mid]
            if mid in placed or norm(final_name[mid]) in LEAVE_OFF or m.get("type") == "group":
                continue
            on_page.append((m["id"], final_name[mid]))
        others = []

    print(f"\nStatus page '{SLUG}' will show {len(on_page)} monitors, in this order:")
    for mid, name in on_page:
        print(f"  #{mid:<4} {name}")
    if missing:
        print("\nOn the board but not in Kuma (create these in Kuma, then run again):")
        for n in missing:
            print(f"  - {n}")
    if left_off:
        print("\nLeft off the page, as asked: " + ", ".join(left_off))
    if others:
        print("\nOther Kuma monitors, NOT on the page: " + ", ".join(others))
        print("  Map one to a board name with --rename 'Kuma name=Board name', or add them all with --all.")

    if not args.apply:
        print("\nDry run: nothing changed. Run again with --apply to make these changes.")
        sio.disconnect()
        return

    print("\nApplying ...")
    for m, new in renames:
        edited = dict(m)
        edited["name"] = new
        r = sio.call("editMonitor", edited, timeout=30)
        print(f"  rename #{m['id']} -> {new!r}: {'ok' if r.get('ok') else 'FAILED: ' + str(r.get('msg'))}")

    page = sio.call("getStatusPage", SLUG, timeout=20)
    if not page.get("ok"):
        r = sio.call("addStatusPage", (PAGE_TITLE, SLUG), timeout=20)
        if not r.get("ok"):
            sys.exit(f"  create status page: FAILED: {r.get('msg')}")
        print(f"  created status page '{SLUG}'")
        page = sio.call("getStatusPage", SLUG, timeout=20)
        if not page.get("ok"):
            sys.exit(f"  read status page: FAILED: {page.get('msg')}")
    config = dict(page["config"])
    config["logo"] = config.get("icon") or "/icon.svg"
    groups = [{"name": GROUP_NAME, "monitorList": [{"id": mid} for mid, _ in on_page]}]
    r = sio.call("saveStatusPage", (SLUG, config, config["logo"], groups), timeout=30)
    print(f"  save status page: {'ok' if r.get('ok') else 'FAILED: ' + str(r.get('msg'))}")
    sio.disconnect()

    # Verify through the same public endpoint the board uses.
    with urllib.request.urlopen(f"{args.url}/api/status-page/{SLUG}", timeout=15) as resp:
        data = json.load(resp)
    names = [m["name"] for g in data.get("publicGroupList", []) for m in g.get("monitorList", [])]
    print(f"\nPublic page now lists {len(names)} monitors: " + ", ".join(names))


def public_page_ids(url):
    with urllib.request.urlopen(f"{url}/api/status-page/{SLUG}", timeout=15) as resp:
        data = json.load(resp)
    return [m["id"] for g in data.get("publicGroupList", []) for m in g.get("monitorList", [])]


def find_monitor(monitors, wanted):
    """Exact name first, else the only monitor whose name contains `wanted` as a word."""
    cands = [m for m in monitors.values() if m.get("type") != "group"]
    exact = [m for m in cands if norm(m["name"]) == norm(wanted)]
    if exact:
        return exact, None
    words = norm(wanted).split()
    part = [m for m in cands
            if any(norm(m["name"]).split()[i:i + len(words)] == words
                   for i in range(len(norm(m["name"]).split())))]
    return part, None if len(part) == 1 else ("no monitor matches" if not part else "several match")


def add_to_page(sio, args, monitors):
    """Append monitors to the existing page; its current monitors and order are kept."""
    current = public_page_ids(args.url)
    print(f"Page '{SLUG}' shows {len(current)} monitors now.")
    to_add, failed = [], False
    for wanted in args.add:
        found, err = find_monitor(monitors, wanted)
        if err:
            failed = True
            names = ", ".join(repr(m["name"]) for m in found) or "-"
            print(f"  {wanted!r}: {err} ({names}). Use the exact Kuma name.")
        elif found[0]["id"] in current or found[0]["id"] in [m["id"] for m in to_add]:
            print(f"  {wanted!r}: #{found[0]['id']} {found[0]['name']!r} is already on the page")
        else:
            print(f"  {wanted!r}: will add #{found[0]['id']} {found[0]['name']!r}")
            to_add.append(found[0])
    if failed or not to_add:
        sio.disconnect()
        sys.exit(1 if failed else 0)
    if not args.apply:
        print("\nDry run: nothing changed. Run again with --apply to add them.")
        sio.disconnect()
        return

    page = sio.call("getStatusPage", SLUG, timeout=20)
    if not page.get("ok"):
        sys.exit(f"  read status page: FAILED: {page.get('msg')}. Run without --add first to create it.")
    config = dict(page["config"])
    config["logo"] = config.get("icon") or "/icon.svg"
    ids = current + [m["id"] for m in to_add]
    groups = [{"name": GROUP_NAME, "monitorList": [{"id": mid} for mid in ids]}]
    r = sio.call("saveStatusPage", (SLUG, config, config["logo"], groups), timeout=30)
    print(f"  save status page: {'ok' if r.get('ok') else 'FAILED: ' + str(r.get('msg'))}")
    sio.disconnect()
    after = public_page_ids(args.url)
    missing = [m["name"] for m in to_add if m["id"] not in after]
    print(f"Public page now lists {len(after)} monitors" +
          (f"; NOT showing: {', '.join(missing)}" if missing else "; added ones are showing."))


if __name__ == "__main__":
    main()
