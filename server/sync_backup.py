#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把一份备份 JSON 镜像同步到服务端（覆盖式）

与 import_backup.py 的区别：
  - 所有记录统一用「当前时间」作为 updatedAt，确保能压过云端旧导入
  - 云端有、备份里没有的记录，自动打删除墓碑（避免留下重复项）
  - 先拉全量做 diff，再一次性提交

    python sync_backup.py 备份.json --url http://127.0.0.1:18080 --token <同步码>
    python sync_backup.py 备份.json --dry-run
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request


def build_changes(data, ts):
    """按备份内容构造变更集，统一时间戳 ts"""
    changes = []
    keys = set()

    for date, items in (data.get("tasks") or {}).items():
        if not isinstance(items, list):
            continue
        for order, t in enumerate(items):
            if not isinstance(t, dict) or not t.get("id"):
                continue
            rid = "%s|%s" % (date, t["id"])
            keys.add(("task", rid))
            changes.append({
                "kind": "task",
                "id": rid,
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
                "updatedAt": ts,
                "deleted": 0,
            })

    for date, s in (data.get("study") or {}).items():
        if not isinstance(s, dict):
            continue
        if not any(s.get(k) for k in ("hours", "start", "place", "review")):
            continue
        keys.add(("study", date))
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
            "updatedAt": ts,
            "deleted": 0,
        })

    checkins = data.get("checkins") or {}
    if isinstance(checkins, list):
        checkins = {d: {"time": ""} for d in checkins}
    for date, c in checkins.items():
        keys.add(("checkin", date))
        changes.append({
            "kind": "checkin",
            "id": date,
            "body": {"date": date, "time": (c.get("time") if isinstance(c, dict) else "") or ""},
            "updatedAt": ts,
            "deleted": 0,
        })

    for order, s in enumerate(data.get("subjects") or []):
        if not isinstance(s, dict) or not s.get("key"):
            continue
        keys.add(("subject", s["key"]))
        changes.append({
            "kind": "subject",
            "id": s["key"],
            "body": {
                "key": s["key"],
                "name": s.get("name") or s["key"],
                "color": s.get("color") or "#8A8F99",
                "order": order,
            },
            "updatedAt": ts,
            "deleted": 0,
        })

    settings = data.get("settings")
    if isinstance(settings, dict) and settings:
        keys.add(("setting", "main"))
        changes.append({
            "kind": "setting",
            "id": "main",
            "body": settings,
            "updatedAt": ts,
            "deleted": 0,
        })

    return changes, keys


def call(url, token, payload):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("backup")
    ap.add_argument("--url", default="http://127.0.0.1:18080")
    ap.add_argument("--token", default="")
    ap.add_argument("--token-file", default="")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--batch", type=int, default=300)
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

    base = args.url.rstrip("/")
    ts = int(time.time() * 1000)

    # 1) 拉云端全量
    cloud = []
    if not args.dry_run:
        req = urllib.request.Request(
            base + "/sync?since=0", headers={"Authorization": "Bearer " + token}
        )
        with urllib.request.urlopen(req, timeout=60) as r:
            cloud = json.loads(r.read().decode("utf-8")).get("changes") or []
    else:
        try:
            with open("server/_cloud.json", "r", encoding="utf-8") as f:
                cloud = json.load(f).get("changes") or []
        except OSError:
            cloud = []

    alive = {(c["kind"], c["id"]): c for c in cloud if not c["deleted"]}

    # 2) 构造新内容 + 找出云端多余的
    changes, keys = build_changes(data, ts)

    tombstones = []
    for k, c in alive.items():
        if k in keys:
            continue
        tombstones.append({
            "kind": k[0],
            "id": k[1],
            "body": {},
            "updatedAt": ts,
            "deleted": 1,
        })
    changes.extend(tombstones)

    print("云端现有 %d 条有效记录" % len(alive))
    print("写入 %d 条（其中新增/更新 %d，删除标记 %d）"
          % (len(changes), len(changes) - len(tombstones), len(tombstones)))
    for t in tombstones:
        old = alive[(t["kind"], t["id"])]
        title = ""
        if t["kind"] == "task":
            title = str((old.get("body") or {}).get("task", {}).get("title", ""))[:24]
        print("   - 清理 %s %s %s" % (t["kind"], t["id"], title))

    if args.dry_run:
        return 0

    # 3) 分批提交
    total = 0
    for i in range(0, len(changes), args.batch):
        chunk = changes[i:i + args.batch]
        res = call(base + "/sync", token, {"since": ts, "changes": chunk})
        total += res.get("applied", 0)
        print("  批次 %d：应用 %s 条，seq=%s"
              % (i // args.batch + 1, res.get("applied"), res.get("seq")))

    print("完成，共应用 %d 条" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
