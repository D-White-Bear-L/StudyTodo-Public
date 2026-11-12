#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
每日任务清单 · 同步服务

设计要点
--------
1. 记录级增量同步：一条任务 / 一天学习 / 一天打卡 / 一个标签 / 一份设置，
   各自是 records 表里的一行。客户端只传「变了的那几条」。
2. 游标用自增 seq，不用时间戳 —— 手机时钟漂了也不会漏同步。
3. 删除写墓碑（deleted=1），否则一台设备删掉的记录会被另一台的旧数据复活。
4. 冲突按 updated_at 后写覆盖（LWW），不同记录之间完全互不影响。
5. 只依赖 Python 标准库 + cryptography，无编译、无 ORM。
6. **记录内容在落库前用 AES-256-GCM 加密**，数据库文件被拷走也读不出内容。
   只加密 body 字段；kind / id / updated_at / seq 保持明文，供增量同步和去重使用，
   这些字段只含记录类型、随机 id 和时间，不含任何业务内容。

接口
----
GET  /health                    健康检查，不需要鉴权
GET  /sync?since=<seq>          拉取 seq 之后的变更
POST /sync  {since, changes}    推送本地变更，同时返回 seq 之后的变更
Authorization: Bearer <token>
"""

import base64
import json
import os
import sqlite3
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.environ.get("TODO_DB", os.path.join(ROOT, "data", "todo.db"))
TOKEN_PATH = os.environ.get("TODO_TOKEN", os.path.join(ROOT, "token"))
STATIC_DIR = os.environ.get("TODO_STATIC", os.path.join(ROOT, "www"))
BIND = os.environ.get("TODO_BIND", "0.0.0.0")
PORT = int(os.environ.get("TODO_PORT", "18080"))

MIME = {
    ".html": "text/html; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".webmanifest": "application/manifest+json; charset=utf-8",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".ico": "image/x-icon",
    ".txt": "text/plain; charset=utf-8",
    # 安卓浏览器要靠这个类型才会走「安装」流程，否则只当普通文件下载
    ".apk": "application/vnd.android.package-archive",
}

MAX_BODY = 4 * 1024 * 1024
MAX_PULL = 5000                 # 单次最多回多少条，剩下的让客户端再拉一轮
CLOCK_SKEW_LIMIT = 60 * 1000    # 客户端时间比服务器快超过 1 分钟就钳到服务器时间

BACKUP_DIR = os.environ.get("TODO_BACKUP", os.path.join(ROOT, "backup"))
BACKUP_KEEP = 14                # 留最近 14 份，一天一份

VALID_KINDS = {"task", "study", "checkin", "subject", "setting"}

# ---------- 落库加密 ----------
SECRET_PATH = os.environ.get("TODO_SECRET", os.path.join(ROOT, "secret.key"))
ENC_PREFIX = "enc1:"            # 密文前缀；没有前缀的是历史明文，读时原样返回
ENC_AAD = b"todo-sync/body/v1"  # 防止密文被挪到别的用途上
AES_KEY = None                  # 启动时 load_key() 填充


def _db_has_ciphertext():
    """库里有没有加密过的记录 —— 用来判断密钥丢失后是否允许重新生成"""
    if not os.path.exists(DB_PATH):
        return False
    try:
        con = sqlite3.connect(DB_PATH)
        try:
            row = con.execute(
                "SELECT 1 FROM records WHERE body LIKE ? LIMIT 1", (ENC_PREFIX + "%",)
            ).fetchone()
            return row is not None
        finally:
            con.close()
    except sqlite3.Error:
        return False


def load_key():
    """读密钥；没有就生成一个。

    注意：库里已经有密文却没有密钥文件时**直接拒绝启动**，不能悄悄生成新密钥，
    否则旧数据会被当成「解不开的垃圾」，看起来就是数据全丢。
    """
    if os.path.exists(SECRET_PATH):
        with open(SECRET_PATH, "r") as f:
            key = bytes.fromhex(f.read().strip())
        if len(key) != 32:
            raise SystemExit("密钥文件 %s 格式不对（应为 64 位十六进制）" % SECRET_PATH)
        return key

    if _db_has_ciphertext():
        raise SystemExit(
            "数据库里已有加密数据，但找不到密钥文件 %s。\n"
            "加密密钥丢失后数据无法恢复，请从备份里找回 secret.key 再启动。" % SECRET_PATH
        )

    key = os.urandom(32)
    fd = os.open(SECRET_PATH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(key.hex())
    log("已生成数据密钥 %s（务必备份，丢失后数据无法解密）" % SECRET_PATH)
    return key


def enc_body(plain):
    """明文 JSON 字符串 → 带前缀的密文（已经是密文就原样返回，便于重放）"""
    if plain.startswith(ENC_PREFIX):
        return plain
    nonce = os.urandom(12)
    ct = AESGCM(AES_KEY).encrypt(nonce, plain.encode("utf-8"), ENC_AAD)
    return ENC_PREFIX + base64.b64encode(nonce + ct).decode("ascii")


def dec_body(stored):
    """密文 → 明文；历史明文记录（无前缀）原样返回，保证平滑过渡"""
    if not isinstance(stored, str) or not stored.startswith(ENC_PREFIX):
        return stored
    raw = base64.b64decode(stored[len(ENC_PREFIX):])
    return AESGCM(AES_KEY).decrypt(raw[:12], raw[12:], ENC_AAD).decode("utf-8")

# 单用户场景，并发压力约等于 0；一把锁把 SQLite 的写串行化，省掉一堆边界情况
LOCK = threading.Lock()
TOKENS = set()
_LAST_BACKUP = {"day": ""}


def log(msg):
    print("[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg), flush=True)


def load_tokens():
    """token 文件每行一个，支持一次配多个（换码时新旧并存，不用强制重登）"""
    tokens = set()
    try:
        with open(TOKEN_PATH, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#"):
                    tokens.add(line)
    except OSError as e:
        log("读取 token 失败: %s" % e)
    return tokens


def connect():
    con = sqlite3.connect(DB_PATH, timeout=10, isolation_level=None)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute("PRAGMA synchronous=NORMAL")
    return con


def init_db():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    con = connect()
    con.executescript(
        """
        CREATE TABLE IF NOT EXISTS records (
            kind       TEXT    NOT NULL,
            id         TEXT    NOT NULL,
            body       TEXT    NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted    INTEGER NOT NULL DEFAULT 0,
            seq        INTEGER NOT NULL,
            PRIMARY KEY (kind, id)
        );
        CREATE INDEX IF NOT EXISTS records_seq ON records(seq);
        CREATE TABLE IF NOT EXISTS counter (
            id  INTEGER PRIMARY KEY CHECK (id = 1),
            seq INTEGER NOT NULL
        );
        INSERT OR IGNORE INTO counter (id, seq) VALUES (1, 0);
        """
    )
    con.close()


def bump_seq(con):
    """seq 单调递增，删掉的记录也不回收编号，保证游标永远向前"""
    con.execute("UPDATE counter SET seq = seq + 1 WHERE id = 1")
    return con.execute("SELECT seq FROM counter WHERE id = 1").fetchone()[0]


def current_seq(con):
    return con.execute("SELECT seq FROM counter WHERE id = 1").fetchone()[0]


def maybe_backup():
    """一天备份一次，随手在请求里触发，省掉 cron。失败不影响主流程。"""
    today = time.strftime("%Y-%m-%d")
    if _LAST_BACKUP["day"] == today:
        return
    _LAST_BACKUP["day"] = today
    try:
        os.makedirs(BACKUP_DIR, exist_ok=True)
        dst_path = os.path.join(BACKUP_DIR, "todo-%s.db" % today)
        if os.path.exists(dst_path):
            return
        with LOCK:
            src = connect()
            dst = sqlite3.connect(dst_path)
            with dst:
                src.backup(dst)
            dst.close()
            src.close()
        log("已备份 %s" % dst_path)

        files = sorted(
            f for f in os.listdir(BACKUP_DIR) if f.startswith("todo-") and f.endswith(".db")
        )
        for old in files[:-BACKUP_KEEP]:
            try:
                os.remove(os.path.join(BACKUP_DIR, old))
            except OSError:
                pass
    except Exception as e:
        log("备份失败: %r" % e)


def clamp_ts(ts, now):
    """客户端时间只用于判断先后，不代表可信。超前的钳掉，缺失的按现在算。"""
    try:
        ts = int(ts)
    except (TypeError, ValueError):
        return now
    if ts <= 0:
        return 0
    if ts > now + CLOCK_SKEW_LIMIT:
        return now
    return ts


def norm_body(raw):
    if raw is None:
        return "{}"
    return json.dumps(raw, ensure_ascii=False, separators=(",", ":"))


def apply_changes(con, changes, now):
    """把客户端变更合并进库，返回实际写入的条数"""
    applied = 0
    for c in changes:
        if not isinstance(c, dict):
            continue
        kind = str(c.get("kind") or "")
        rid = str(c.get("id") or "")
        if kind not in VALID_KINDS or not rid or len(rid) > 128:
            continue

        ts = clamp_ts(c.get("updatedAt"), now)
        deleted = 1 if c.get("deleted") else 0
        body = norm_body(c.get("body"))

        row = con.execute(
            "SELECT updated_at, body, deleted FROM records WHERE kind=? AND id=?", (kind, rid)
        ).fetchone()

        if row is not None:
            old_ts, old_body, old_deleted = row
            # 比较的是解密后的内容：密文每次都不一样，直接比密文会永远判定为「有变化」
            try:
                old_plain = dec_body(old_body)
            except Exception:
                old_plain = None
            # 完全相同的重放：跳过，别白白吃掉一个 seq
            if old_plain == body and old_deleted == deleted:
                continue
            # 服务端已有更新的版本，本地这条是旧的，不覆盖
            if old_ts > ts:
                continue

        seq = bump_seq(con)
        con.execute(
            "INSERT INTO records (kind, id, body, updated_at, deleted, seq) "
            "VALUES (?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(kind, id) DO UPDATE SET "
            "body=excluded.body, updated_at=excluded.updated_at, "
            "deleted=excluded.deleted, seq=excluded.seq",
            (kind, rid, enc_body(body), ts, deleted, seq),
        )
        applied += 1
    return applied


def pull_changes(con, since):
    rows = con.execute(
        "SELECT kind, id, body, updated_at, deleted, seq FROM records "
        "WHERE seq > ? ORDER BY seq LIMIT ?",
        (since, MAX_PULL),
    ).fetchall()

    out = []
    for kind, rid, body, ts, deleted, seq in rows:
        try:
            parsed = json.loads(dec_body(body))
        except Exception as e:
            # 单条解不开就跳过并记日志：既不让整次同步挂掉，也不会把坏数据覆盖到客户端
            log("记录解密失败 kind=%s id=%s: %r" % (kind, rid, e))
            continue
        out.append(
            {
                "kind": kind,
                "id": rid,
                "body": parsed,
                "updatedAt": ts,
                "deleted": deleted,
                "seq": seq,
            }
        )
    has_more = len(rows) == MAX_PULL
    return out, has_more


def do_sync(payload):
    """推 + 拉一次往返完成，减少一个来回"""
    since = 0
    try:
        since = int(payload.get("since") or 0)
    except (TypeError, ValueError):
        since = 0
    changes = payload.get("changes") or []
    if not isinstance(changes, list):
        changes = []

    now = int(time.time() * 1000)
    with LOCK:
        con = connect()
        try:
            con.execute("BEGIN IMMEDIATE")
            applied = apply_changes(con, changes, now)
            con.execute("COMMIT")
            seq_now = current_seq(con)
            pulled, has_more = pull_changes(con, since)
        except Exception:
            try:
                con.execute("ROLLBACK")
            except Exception:
                pass
            raise
        finally:
            con.close()

    return {
        "ok": True,
        "now": now,
        "seq": seq_now,
        "applied": applied,
        "hasMore": has_more,
        "changes": pulled,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "todo-sync/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        log("%s %s" % (self.address_string(), fmt % args))

    # ---------- 基础输出 ----------

    def _cors(self):
        # 只靠 Bearer token 鉴权，不用 cookie，所以 * 是安全的
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
        self.send_header("Access-Control-Max-Age", "86400")

    def _json(self, code, obj):
        raw = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self._cors()
        self.end_headers()
        self.wfile.write(raw)

    def _body(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0:
            return {}
        if length > MAX_BODY:
            raise ValueError("too large")
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            raise ValueError("bad json")
        return data if isinstance(data, dict) else {}

    def _authed(self):
        header = self.headers.get("Authorization") or ""
        if not header.startswith("Bearer "):
            return False
        return header[7:].strip() in TOKENS

    def _static(self, url_path):
        """网页版就挂在这个服务下面，和数据接口同源，省掉跨域和混合内容的麻烦"""
        base = os.path.realpath(STATIC_DIR)
        if not os.path.isdir(base):
            return False
        rel = url_path.lstrip("/") or "index.html"
        if rel.endswith("/"):
            rel += "index.html"
        full = os.path.realpath(os.path.join(base, rel))
        if not full.startswith(base + os.sep) and full != base:
            return False          # 挡住 ../ 穿越
        if not os.path.isfile(full):
            return False
        try:
            with open(full, "rb") as f:
                data = f.read()
        except OSError:
            return False
        ext = os.path.splitext(full)[1].lower()
        self.send_response(200)
        self.send_header("Content-Type", MIME.get(ext, "application/octet-stream"))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)
        return True

    # ---------- 路由 ----------

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self._cors()
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        maybe_backup()

        if path == "/health":
            with LOCK:
                con = connect()
                try:
                    n = con.execute("SELECT COUNT(*) FROM records").fetchone()[0]
                    seq = current_seq(con)
                finally:
                    con.close()
            self._json(200, {"ok": True, "records": n, "seq": seq, "ts": int(time.time() * 1000)})
            return

        if path != "/sync":
            if self._static(parsed.path):
                return
            self._json(404, {"ok": False, "error": "not found"})
            return

        if not self._authed():
            self._json(401, {"ok": False, "error": "unauthorized"})
            return

        qs = parse_qs(parsed.query)
        try:
            since = int((qs.get("since") or ["0"])[0])
        except ValueError:
            since = 0

        try:
            result = do_sync({"since": since, "changes": []})
        except Exception as e:
            log("sync failed: %r" % e)
            self._json(500, {"ok": False, "error": "internal"})
            return
        self._json(200, result)

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        maybe_backup()

        try:
            payload = self._body()
        except ValueError as e:
            code = 413 if str(e) == "too large" else 400
            self._json(code, {"ok": False, "error": str(e)})
            return

        # 允许不带 token 探活一次，但数据接口一律要鉴权
        if not self._authed():
            self._json(401, {"ok": False, "error": "unauthorized"})
            return

        if path != "/sync":
            self._json(404, {"ok": False, "error": "not found"})
            return

        try:
            result = do_sync(payload)
        except Exception as e:
            log("sync failed: %r" % e)
            self._json(500, {"ok": False, "error": "internal"})
            return
        self._json(200, result)


class Server(ThreadingHTTPServer):
    """客户端中途断开（关页面、切网络）不该打出整页堆栈污染日志"""
    daemon_threads = True

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionResetError, BrokenPipeError, ConnectionAbortedError)):
            return
        super().handle_error(request, client_address)


def main():
    global TOKENS, AES_KEY
    init_db()
    AES_KEY = load_key()
    TOKENS = load_tokens()
    if not TOKENS:
        log("警告：%s 为空，所有数据请求都会被拒绝" % TOKEN_PATH)

    httpd = Server((BIND, PORT), Handler)
    log("todo-sync 已启动 http://%s:%d  db=%s tokens=%d" % (BIND, PORT, DB_PATH, len(TOKENS)))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
