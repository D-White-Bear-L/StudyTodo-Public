#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""同步服务自测：本地起一个临时实例跑全链路。

    python test_sync.py

不需要服务器，用临时目录造库，跑完自动清理。
"""

import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
TMP = os.path.join(HERE, ".test-tmp")
PORT = 18099
TOKEN = "test-token-abcdef"
BASE = "http://127.0.0.1:%d" % PORT

passed = 0
failed = 0


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print("  ok   %s" % name)
    else:
        failed += 1
        print("  FAIL %s  %s" % (name, detail))


def call(method, path, payload=None, token=TOKEN):
    url = BASE + path
    data = None
    headers = {}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}


def wait_ready():
    for _ in range(50):
        try:
            code, _ = call("GET", "/health", token=None)
            if code == 200:
                return True
        except Exception:
            pass
        time.sleep(0.2)
    return False


def main():
    if os.path.isdir(TMP):
        shutil.rmtree(TMP)
    os.makedirs(TMP)
    with open(os.path.join(TMP, "token"), "w", encoding="utf-8") as f:
        f.write(TOKEN + "\n")

    env = dict(os.environ)
    env.update(
        TODO_DB=os.path.join(TMP, "todo.db"),
        TODO_TOKEN=os.path.join(TMP, "token"),
        TODO_BIND="127.0.0.1",
        TODO_PORT=str(PORT),
    )
    proc = subprocess.Popen(
        [sys.executable, os.path.join(HERE, "sync_server.py")],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    try:
        if not wait_ready():
            print("服务没起来")
            return 1

        print("[1] 鉴权")
        code, _ = call("GET", "/sync?since=0", token=None)
        check("无 token 拒绝 401", code == 401, code)
        code, _ = call("GET", "/sync?since=0", token="wrong")
        check("错 token 拒绝 401", code == 401, code)
        code, body = call("GET", "/health", token=None)
        check("health 免鉴权", code == 200 and body.get("records") == 0, body)

        print("[2] 首次推送")
        now = int(time.time() * 1000)
        tasks = [
            {"kind": "task", "id": "2026-11-12|aaa", "body": {"date": "2026-11-12", "task": {"id": "aaa", "title": "数学第三章 习题", "subject": "math", "note": "第 12-30 题", "done": False, "createdAt": now}}, "updatedAt": now, "deleted": 0},
            {"kind": "task", "id": "2026-11-12|bbb", "body": {"date": "2026-11-12", "task": {"id": "bbb", "title": "英语阅读两篇", "subject": "english", "note": "", "done": True, "createdAt": now}}, "updatedAt": now, "deleted": 0},
            {"kind": "study", "id": "2026-11-12", "body": {"date": "2026-11-12", "study": {"hours": "6.5", "start": "08:00", "place": "图书馆", "review": "错题重做"}}, "updatedAt": now, "deleted": 0},
            {"kind": "checkin", "id": "2026-11-12", "body": {"date": "2026-11-12"}, "updatedAt": now, "deleted": 0},
            {"kind": "setting", "id": "main", "body": {"theme": "#4BC85C", "showCompletion": True, "sort": "manual"}, "updatedAt": now, "deleted": 0},
        ]
        code, res = call("POST", "/sync", {"since": 0, "changes": tasks})
        check("推送成功", code == 200 and res.get("applied") == 5, res)
        check("返回全量 5 条", len(res.get("changes", [])) == 5, len(res.get("changes", [])))
        seq1 = res.get("seq")

        print("[3] 中文往返")
        got = [c for c in res["changes"] if c["kind"] == "task" and c["id"].endswith("aaa")][0]
        check("标题中文无损", got["body"]["task"]["title"] == "数学第三章 习题", got["body"]["task"]["title"])
        check("备注中文无损", got["body"]["task"]["note"] == "第 12-30 题", got["body"]["task"]["note"])
        check("布尔值保留", got["body"]["task"]["done"] is False, got["body"]["task"]["done"])

        print("[4] 增量游标")
        code, res = call("GET", "/sync?since=%d" % seq1)
        check("紧跟 seq 无新数据", len(res["changes"]) == 0 and res["seq"] == seq1, res)

        print("[5] 幂等重放")
        code, res = call("POST", "/sync", {"since": 0, "changes": tasks})
        check("重复推送 applied=0", res.get("applied") == 0, res.get("applied"))
        check("seq 不增长", res.get("seq") == seq1, (res.get("seq"), seq1))

        print("[6] 后写覆盖")
        newer = now + 1000
        upd = {"kind": "task", "id": "2026-11-12|aaa", "body": {"date": "2026-11-12", "task": {"id": "aaa", "title": "数学第三章 习题", "subject": "math", "note": "第 12-30 题", "done": True, "createdAt": now}}, "updatedAt": newer, "deleted": 0}
        code, res = call("POST", "/sync", {"since": seq1, "changes": [upd]})
        check("新时间戳被接受", res.get("applied") == 1, res.get("applied"))
        check("拉回已完成状态", res["changes"][0]["body"]["task"]["done"] is True, res["changes"][0]["body"]["task"]["done"])

        print("[7] 旧数据不覆盖新的")
        old = dict(upd)
        old["updatedAt"] = now - 5000
        old["body"] = {"date": "2026-11-12", "task": {"id": "aaa", "title": "旧覆盖尝试", "subject": "math", "note": "", "done": False, "createdAt": now}}
        code, res = call("POST", "/sync", {"since": 0, "changes": [old]})
        check("被拒绝覆盖", res.get("applied") == 0, res.get("applied"))
        code, res = call("GET", "/sync?since=0")
        cur = [c for c in res["changes"] if c["id"].endswith("aaa")][0]
        check("内容仍是新版本", cur["body"]["task"]["title"] == "数学第三章 习题", cur["body"]["task"]["title"])

        print("[8] 删除墓碑")
        delmsg = {"kind": "task", "id": "2026-11-12|bbb", "body": {"date": "2026-11-12"}, "updatedAt": newer + 1000, "deleted": 1}
        code, res = call("POST", "/sync", {"since": cur["seq"], "changes": [delmsg]})
        delrec = [c for c in res["changes"] if c["id"].endswith("bbb")]
        check("墓碑出现在变更里", len(delrec) == 1 and delrec[0]["deleted"] == 1, delrec)

        print("[9] 时钟超前钳制")
        future = int(time.time() * 1000) + 86400 * 1000
        c = {"kind": "study", "id": "2026-11-13", "body": {"date": "2026-11-13", "study": {"hours": "1"}}, "updatedAt": future, "deleted": 0}
        code, res = call("POST", "/sync", {"since": 0, "changes": [c]})
        rec = [x for x in res["changes"] if x["id"] == "2026-11-13"][0]
        check("未来时间被钳到服务器时间", rec["updatedAt"] <= res["now"] + 1000, (rec["updatedAt"], res["now"]))

        print("[10] 非法输入")
        code, res = call("POST", "/sync", {"since": 0, "changes": [{"kind": "hack", "id": "x"}, {"kind": "task", "id": ""}, "junk"]})
        check("非法 kind/id 被忽略", code == 200 and res.get("applied") == 0, res.get("applied"))
        code, res = call("GET", "/sync?since=abc")
        check("非法 since 回落 0", code == 200 and len(res.get("changes", [])) > 0, code)

        print("[11] CORS 预检")
        req = urllib.request.Request(BASE + "/sync", method="OPTIONS")
        with urllib.request.urlopen(req, timeout=10) as r:
            allow = r.headers.get("Access-Control-Allow-Origin")
        check("OPTIONS 返回 *", allow == "*", allow)

        print()
        print("通过 %d 项，失败 %d 项" % (passed, failed))
        return 0 if failed == 0 else 1
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
        shutil.rmtree(TMP, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
