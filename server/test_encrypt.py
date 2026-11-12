#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在临时库上验证加密闭环，不碰生产数据。"""
import json
import os
import shutil
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

TMP = tempfile.mkdtemp(prefix="ectest-")
os.environ["TODO_DB"] = os.path.join(TMP, "t.db")
os.environ["TODO_SECRET"] = os.path.join(TMP, "k.key")
os.environ["TODO_BACKUP"] = os.path.join(TMP, "backup")

import sync_server as S  # noqa: E402

fails = 0


def ok(name, cond, detail=None):
    global fails
    if cond:
        print("  ok   %s" % name)
    else:
        fails += 1
        print("  FAIL %s   %r" % (name, detail))


S.init_db()
S.AES_KEY = S.load_key()
con = S.connect()
now = int(time.time() * 1000)

SECRET_TEXT = "这条内容不该以明文出现在数据库里"

print("[1] 写入一条")
S.apply_changes(con, [{
    "kind": "task", "id": "t1",
    "body": {"title": SECRET_TEXT, "subject": "work"},
    "updatedAt": now,
}], now)
con.commit()

raw = con.execute("SELECT body FROM records WHERE kind='task' AND id='t1'").fetchone()[0]
ok("落库是密文", raw.startswith("enc1:"), raw[:20])
ok("库里搜不到内容", SECRET_TEXT not in raw, None)
ok("文件里也搜不到", SECRET_TEXT.encode("utf-8") not in open(os.environ["TODO_DB"], "rb").read(), None)

print("[2] 读回来是明文")
changes, _ = S.pull_changes(con, 0)
ok("能解回原内容", changes and changes[0]["body"]["title"] == SECRET_TEXT, changes[:1])

print("[3] 同一条重放不占新 seq（密文随机，必须比对明文）")
before = S.current_seq(con)
for _ in range(3):
    S.apply_changes(con, [{
        "kind": "task", "id": "t1",
        "body": {"title": SECRET_TEXT, "subject": "work"},
        "updatedAt": now,
    }], now)
    con.commit()
ok("seq 没涨", S.current_seq(con) == before, [before, S.current_seq(con)])

print("[4] 内容真变了要能写进去")
S.apply_changes(con, [{
    "kind": "task", "id": "t1",
    "body": {"title": SECRET_TEXT + "（改）", "subject": "work"},
    "updatedAt": now + 1000,
}], now + 1000)
con.commit()
changes, _ = S.pull_changes(con, before)
ok("改动生效", changes and changes[0]["body"]["title"].endswith("（改）"), changes[:1])

print("[5] 旧版本不会覆盖新版本")
S.apply_changes(con, [{
    "kind": "task", "id": "t1",
    "body": {"title": "过期内容", "subject": "work"},
    "updatedAt": now,
}], now)
con.commit()
changes, _ = S.pull_changes(con, 0)
ok("仍是新版本", changes[0]["body"]["title"].endswith("（改）"), changes[0]["body"]["title"])

print("[6] 兼容历史明文记录")
con.execute(
    "INSERT INTO records (kind,id,body,updated_at,deleted,seq) VALUES (?,?,?,?,0,?)",
    ("task", "old1", json.dumps({"title": "老数据"}, ensure_ascii=False), now, S.bump_seq(con)),
)
con.commit()
changes, _ = S.pull_changes(con, 0)
old = [c for c in changes if c["id"] == "old1"]
ok("老明文仍可读", old and old[0]["body"]["title"] == "老数据", old[:1])

print("[7] 密钥错误时不会静默丢数据")
keyfile = os.environ["TODO_SECRET"]
os.remove(keyfile)
try:
    S.load_key()
    ok("缺密钥应拒绝启动", False, "竟然自动生成了新密钥")
except SystemExit:
    ok("缺密钥时拒绝启动", True)

con.close()
shutil.rmtree(TMP, ignore_errors=True)
print("")
print("失败 %d 项" % fails)
sys.exit(1 if fails else 0)
