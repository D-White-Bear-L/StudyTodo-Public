#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把一份备份 JSON 灌进同步服务

用于首次上云、或者服务器库重建后恢复。可以和客户端重复导入，同 id 会按时间覆盖。

    python import_backup.py 备份.json --url http://127.0.0.1:18080 --token <同步码>
    python import_backup.py 备份.json --url http://127.0.0.1:18080 --token-file token

参数：
    --dry-run    只统计不写入
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def build_changes(data, now):
    changes = []

    tasks = data.get("tasks") or {}
    for date, items in tasks.items():
        if not isinstance(items, list):
            continue
        for order, t in enumerate(items):
            if not isinstance(t, dict) or not t.get("id"):
                continue
            changes.append({
                "kind": "task",
                "id": "%s|%s" % (date, t["id"]),
                "body": {
                    "date": date,
                    "order": order,
                    "task": {
                        "id": t["id"],
                        "title": t.get("title") or "",
                        "subject": t.get("subject") or "other",
                        "note": t.get("note") or "",
                        "done": bool(t.get("done")),
                        "createdAt": int(t.get("createdAt") or 0),
                    },
                },
                "updatedAt": int(t.get("createdAt") or 0),
                "deleted": 0,
            })

    study = data.get("study") or {}
    for date, s in study.items():
        if not isinstance(s, dict):
            continue
        if not any(s.get(k) for k in ("hours", "start", "place", "review")):
            continue
        changes.append({
            "kind": "study",
            "id": date,
            "body": {
                "date": date,
                "study": {
                    "hours": s.get("hours") or "",
                    "start": s.get("start") or "",
                    "place": s.get("place") or "",
                    "review": s.get("review") or "",
                },
            },
            "updatedAt": int(s.get("createdAt") or 0),
            "deleted": 0,
        })

    checkins = data.get("checkins") or {}
    if isinstance(checkins, list):
        checkins = {d: {"time": ""} for d in checkins}
    for date, c in checkins.items():
        time_str = c.get("time") if isinstance(c, dict) else ""
        changes.append({
            "kind": "checkin",
            "id": date,
            "body": {"date": date, "time": time_str or ""},
            "updatedAt": 0,
            "deleted": 0,
        })

    subjects = data.get("subjects") or []
    for order, s in enumerate(subjects):
        if not isinstance(s, dict) or not s.get("key"):
            continue
        changes.append({
            "kind": "subject",
            "id": s["key"],
            "body": {
                "key": s["key"],
                "name": s.get("name") or s["key"],
                "color": s.get("color") or "#8A8F99",
                "order": order,
            },
            "updatedAt": 0,
            "deleted": 0,
        })

    settings = data.get("settings")
    if isinstance(settings, dict) and settings:
        changes.append({
            "kind": "setting",
            "id": "main",
            "body": settings,
            "updatedAt": 0,
            "deleted": 0,
        })

    return changes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("backup")
    ap.add_argument("--url", default="http://127.0.0.1:18080")
    ap.add_argument("--token", default="")
    ap.add_argument("--token-file", default="")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    token = args.token
    if not token and args.token_file:
        with open(args.token_file, "r", encoding="utf-8") as f:
            token = f.readline().strip()
    if not token and not args.dry_run:
        print("缺少 token", file=sys.stderr)
        return 2

    with open(args.backup, "r", encoding="utf-8") as f:
        data = json.load(f)

    changes = build_changes(data, 0)
    counts = {}
    for c in changes:
        counts[c["kind"]] = counts.get(c["kind"], 0) + 1

    print("待写入：", ", ".join("%s %d" % (k, v) for k, v in sorted(counts.items())))
    print("合计 %d 条" % len(changes))
    if args.dry_run:
        return 0

    url = args.url.rstrip("/") + "/sync"
    payload = json.dumps({"since": 0, "changes": changes}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + token,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            res = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print("HTTP %s: %s" % (e.code, e.read().decode("utf-8", "replace")), file=sys.stderr)
        return 1

    print("写入 %d 条，服务端 seq=%s" % (res.get("applied", 0), res.get("seq")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
