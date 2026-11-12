#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把库里已有的明文记录就地加密（幂等，可重复执行）。

    python3 migrate_encrypt.py [--dry-run]

- 只改 body 字段，不动 seq / updated_at / deleted，客户端感觉不到变化
- 已经是密文的记录跳过
- 完成后逐条解密回读校验，确保没有不可逆的损坏
"""
import json
import os
import shutil
import sqlite3
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sync_server as S  # noqa: E402


def main():
    dry = "--dry-run" in sys.argv
    no_backup = "--no-backup" in sys.argv

    if not os.path.exists(S.DB_PATH):
        print("数据库不存在: %s" % S.DB_PATH)
        return 2

    S.AES_KEY = S.load_key()
    print("密钥: %s" % S.SECRET_PATH)
    print("目标: %s" % S.DB_PATH)

    if not dry and not no_backup:
        ts = time.strftime("%Y%m%d%H%M%S")
        bak = "%s.before-encrypt-%s" % (S.DB_PATH, ts)
        shutil.copy2(S.DB_PATH, bak)
        os.chmod(bak, 0o600)
        print("已备份: %s" % bak)

    con = sqlite3.connect(S.DB_PATH)
    rows = con.execute("SELECT kind, id, body FROM records").fetchall()
    plain = [r for r in rows if not r[2].startswith(S.ENC_PREFIX)]
    cipher = len(rows) - len(plain)
    print("总计 %d 条：已加密 %d，待加密 %d" % (len(rows), cipher, len(plain)))

    if dry:
        print("[dry-run] 不做修改")
        return 0

    done = 0
    for kind, rid, body in plain:
        try:
            json.loads(body)
        except ValueError:
            print("跳过非法 JSON: %s/%s" % (kind, rid))
            continue
        con.execute(
            "UPDATE records SET body=? WHERE kind=? AND id=?",
            (S.enc_body(body), kind, rid),
        )
        done += 1
    con.commit()

    print("已加密 %d 条" % done)

    # 回读校验：每条都必须能解回合法 JSON
    bad = 0
    for kind, rid, body in con.execute("SELECT kind, id, body FROM records").fetchall():
        if not body.startswith(S.ENC_PREFIX):
            bad += 1
            print("仍是明文: %s/%s" % (kind, rid))
            continue
        try:
            json.loads(S.dec_body(body))
        except Exception as e:
            bad += 1
            print("解密失败: %s/%s %r" % (kind, rid, e))

    con.close()

    if bad:
        print("校验未通过：%d 条有问题" % bad)
        return 1

    print("校验通过：全部 %d 条均可正常解密" % len(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
