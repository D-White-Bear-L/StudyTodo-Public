#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把旧版「每日待办清单」导出的 PDF 历史记录，转成新版 APP 可导入的备份 JSON。

旧版 PDF 有一个坑：字体是子集化的 Noto Sans CJK，Identity-H 编码，
但正文用的是自定义字形序号，Python / pdftotext 这类工具直接读会得到一堆
"456#$7869:6;<6=>?@" 之类的乱码。好在 PDF 里带了一张完整的 ToUnicode
映射表（GID -> Unicode），所以这里自己解析 CMap 再解码正文，文字就能还原。

用法：
    python scripts/legacy-pdf-import.py 任务数据.pdf -o backup.json
    python scripts/legacy-pdf-import.py 任务数据.pdf -o backup.json --no-checkins

输出格式与 APP 的「数据备份」一致（见 apps/web/app.js 的 buildBackup）。
"""

import argparse
import json
import re
import sys
import zlib
from datetime import datetime, timedelta

# 旧版 PDF 里的分类名 -> 新版学科标签
SUBJECT_MAP = [
    ("数学", "math", "#4C8DFF"),
    ("英语", "english", "#1677FF"),
    ("408", "cs408", "#FF9F2E"),
    ("政治", "politics", "#FF5B6E"),
    ("其他", "other", "#8A8F99"),
    ("未分类", "uncat", "#B0B6C0"),
]


# --------------------------------------------------------------------------
# 1. 从 PDF 里解码正文
# --------------------------------------------------------------------------

def read_pdf_bytes(path):
    """PDF 里的对象/流：一次解析，供解码和取 CMap 复用。"""
    data = open(path, "rb").read()

    cmap_src = None
    for m in re.finditer(rb"stream\r?\n", data):
        start = m.end()
        end = data.find(b"endstream", start)
        raw = data[start:end]
        try:
            dec = zlib.decompress(raw)
        except Exception:
            dec = raw
        if b"begincmap" in dec and b"beginbfrange" in dec:
            cmap_src = dec.decode("latin-1")
            break
    return data, cmap_src


def build_gid_map(cmap_src):
    if not cmap_src:
        raise SystemExit("这个 PDF 里没有 ToUnicode 映射表，无法可靠还原文字。")

    gid2uni = {}
    for block in re.findall(r"beginbfrange(.*?)endbfrange", cmap_src, re.S):
        for m in re.finditer(r"<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]+)>\s*\[(.*?)\]", block, re.S):
            lo = int(m.group(1), 16)
            for i, u in enumerate(re.findall(r"<([0-9a-fA-F]+)>", m.group(3))):
                gid2uni[lo + i] = chr(int(u, 16))
        for m in re.finditer(r"<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]+)>\s*$", block, re.M):
            lo, hi, st = int(m.group(1), 16), int(m.group(2), 16), int(m.group(3), 16)
            for i in range(hi - lo + 1):
                gid2uni[lo + i] = chr(st + i)
    for block in re.findall(r"beginbfchar(.*?)endbfchar", cmap_src, re.S):
        for m in re.finditer(r"<([0-9a-fA-F]+)>\s*<([0-9a-fA-F]+)>", block):
            gid2uni[int(m.group(1), 16)] = chr(int(m.group(2), 16))
    return gid2uni


def read_pdf_text(path):
    """解析 PDF：先取 ToUnicode CMap，再按 CMap 解码每页的文本绘制指令。"""
    data, cmap_src = read_pdf_bytes(path)
    gid2uni = build_gid_map(cmap_src)

    objs = {}
    for m in re.finditer(rb"(\d+)\s+(\d+)\s+obj\b", data):
        num = int(m.group(1))
        start = m.end()
        objs[num] = data[start:data.find(b"endobj", start)]

    def stream_of(num):
        body = objs.get(num, b"")
        m = re.search(rb"stream\r?\n", body)
        if not m:
            return b""
        raw = body[m.end():body.find(b"endstream", m.end())]
        try:
            return zlib.decompress(raw)
        except Exception:
            return raw

    pages = [(n, b) for n, b in objs.items()
             if b"/Type" in b and b"/Page" in b and b"/Contents" in b]
    pages.sort(key=lambda t: t[0])

    token = re.compile(rb"<([0-9A-Fa-f\s]+)>|(Td|TD|T\*|Tm|BT|ET)")
    lines = []
    for _, body in pages:
        cref = re.search(rb"/Contents\s+(\d+)\s+0\s+R", body)
        if not cref:
            continue
        content = stream_of(int(cref.group(1)))
        cur = []
        for t in token.finditer(content):
            if t.group(1) is not None:
                h = re.sub(rb"\s", b"", t.group(1))
                step = 4 if len(h) % 4 == 0 else 2
                for i in range(0, len(h), step):
                    cur.append(gid2uni.get(int(h[i:i + step], 16), ""))
            elif cur:
                lines.append("".join(cur))
                cur = []
        if cur:
            lines.append("".join(cur))
    return lines


# --------------------------------------------------------------------------
# 2. 把行文本拆成任务
# --------------------------------------------------------------------------

DATE_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})$")
TASK_RE = re.compile(r"^(\d+)\s*│")
NOISE = (
    "序号 │", "当日完成", "─────────", "总体统计", "任务总数",
    "已完成:", "未完成:", "总完成率", "数据截止", "导出日期",
    "每日待办清单", "以上为截至", "状态以文字",
)


def clean_note(s):
    """去掉表格换行时残留的竖线边框，以及行尾多余的空白。"""
    s = s.replace("\x00", " ")
    s = re.sub(r"(?:\s*│\s*)+$", "", s)
    return s.strip()


def parse_tasks(lines):
    tasks = []          # [(date, title, subjectName, done, note)]
    date = None
    cur = None

    for raw in lines:
        line = raw.replace("\x00", " ").rstrip()
        s = line.strip()
        if not s:
            continue

        m = DATE_RE.match(s)
        if m:
            date = s
            cur = None
            continue

        if TASK_RE.match(s):
            parts = [p.strip() for p in s.split("│")]
            # 1 │ 任务名 │ 分类 │ 状态 │ 备注...
            if len(parts) >= 4 and date:
                title = parts[1]
                subject = parts[2]
                status = parts[3]
                note = clean_note("│".join(parts[4:])) if len(parts) > 4 else ""
                if note in ("-", "—", "–"):
                    note = ""
                cur = {
                    "date": date,
                    "title": title,
                    "subject": subject,
                    "done": status.strip() == "已完成",
                    "note": note,
                }
                tasks.append(cur)
                continue

        if any(n in s for n in NOISE):
            cur = None
            continue

        # 其余是上一行备注折行后的续行
        if cur is not None:
            more = clean_note(s)
            if more:
                cur["note"] = (cur["note"] + more).strip()

    return tasks


# --------------------------------------------------------------------------
# 3. 解析 App 自己导出的「每日计划」文本（补录 PDF 之后的那几天）
# --------------------------------------------------------------------------

PLAN_HEAD = re.compile(r"^【(\d{1,2})月(\d{1,2})日学习任务】$")
PLAN_SUBJECT = re.compile(r"^\[(.+?)\][：:]$")
PLAN_ITEM = re.compile(r"^\d+[.、]\s*(.+)$")


def parse_plan_text(text, year):
    """
    解析这种格式（App「导出每日计划」的原样输出）：

        【11月4日学习任务】
        学习总时长：6 小时
        开始学习时间：10:00
        学习地点：图书馆
        复盘时间：22:00
        学习内容
        [数学]：
        1. 数学真题订正

    返回 (tasks, study)：tasks 用统一的 dict 结构，study 是 {日期: {...}}。
    文本里没有完成状态，这类补录一律按「已完成」写入（这些天都已经过完了）。
    """
    tasks, study = [], {}
    cur_date, cur_subject = None, None

    for raw in text.splitlines():
        s = raw.strip()
        if not s:
            continue

        m = PLAN_HEAD.match(s)
        if m:
            cur_date = "%04d-%02d-%02d" % (year, int(m.group(1)), int(m.group(2)))
            cur_subject = None
            study[cur_date] = {"hours": "", "start": "", "place": "", "review": ""}
            continue

        if cur_date is None:
            continue

        if s.startswith("学习总时长"):
            v = s.split("：", 1)[-1] if "：" in s else s.split(":", 1)[-1]
            num = re.search(r"([\d.]+)", v)
            study[cur_date]["hours"] = num.group(1).rstrip(".") if num else ""
            continue
        if s.startswith("开始学习时间"):
            study[cur_date]["start"] = s.split("：", 1)[-1].strip()
            continue
        if s.startswith("学习地点"):
            study[cur_date]["place"] = s.split("：", 1)[-1].strip()
            continue
        if s.startswith("复盘时间"):
            study[cur_date]["review"] = s.split("：", 1)[-1].strip()
            continue
        if s == "学习内容":
            continue

        m = PLAN_SUBJECT.match(s)
        if m:
            cur_subject = m.group(1).strip()
            continue

        m = PLAN_ITEM.match(s)
        if m and cur_subject:
            title = re.sub(r"\s{2,}", " ", m.group(1)).strip()
            tasks.append({
                "date": cur_date,
                "title": title,
                "subject": cur_subject,
                "done": True,
                "note": "",
            })

    return tasks, study


# --------------------------------------------------------------------------
# 4. 组装备份 JSON
# --------------------------------------------------------------------------

def b36(n):
    """和 App 的 `System.currentTimeMillis().toString(36)` 一样的 36 进制写法。"""
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    if n <= 0:
        return "0"
    out = ""
    while n:
        out = digits[n % 36] + out
        n //= 36
    return out


def build_backup(tasks, study=None, with_checkins=True):
    subjects = []
    seen = {}
    for name, key, color in SUBJECT_MAP:
        subjects.append({"key": key, "name": name, "color": color})
        seen[name] = key

    by_date = {}
    for t in tasks:
        key = seen.get(t["subject"])
        if key is None:
            key = "s" + re.sub(r"\W", "", t["subject"])[:6]
            seen[t["subject"]] = key
            subjects.append({"key": key, "name": t["subject"] or "未分类",
                             "color": "#8A8F99"})
        by_date.setdefault(t["date"], []).append((key, t))

    out_tasks = {}
    for date in sorted(by_date):
        base = datetime.strptime(date, "%Y-%m-%d").replace(hour=8)
        base_ms = int(base.timestamp() * 1000)
        arr = []
        for i, (key, t) in enumerate(by_date[date]):
            ts = base_ms + i * 1000
            arr.append({
                # 与 App 自己生成的 id 同款（36 进制时间戳 + 后缀），
                # 不要留 "pdf-" 这种标记，导入后看不出是外来的
                "id": b36(ts) + b36(i),
                "title": t["title"],
                "subject": key,
                "note": t["note"],
                "done": t["done"],
                "createdAt": ts,
            })
        out_tasks[date] = arr

    backup = {
        "app": "daily-task-list",
        "schema": 1,
        "exportedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
        "source": "legacy-pdf",
        "tasks": out_tasks,
        "study": {d: s for d, s in (study or {}).items() if s["hours"] or s["start"] or s["place"] or s["review"]},
        "checkins": sorted(out_tasks) if with_checkins else [],
        "subjects": subjects,
        "settings": {"theme": "#4BC85C", "showCompletion": True, "sort": "manual"},
    }
    return backup


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pdf")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--no-checkins", action="store_true",
                    help="不根据「当天有任务」生成打卡记录")
    ap.add_argument("--append", help="补录文本（App「导出每日计划」的格式），可多次指定",
                    action="append", default=[])
    ap.add_argument("--year", type=int, default=datetime.now().year,
                    help="补录文本里只有「几月几日」，用这个年份补全（默认今年）")
    args = ap.parse_args()

    lines = read_pdf_text(args.pdf)
    tasks = parse_tasks(lines)

    study = {}
    for path in args.append:
        more, st = parse_plan_text(open(path, encoding="utf-8").read(), args.year)
        tasks += more
        study.update(st)
        print("补录 %s: %d 条任务" % (path, len(more)))

    # 四个字段全空（或写「未填写」）的不入库，否则界面上会多出一块空白的「每日学习详情」
    study = {
        d: v for d, v in study.items()
        if any(x.strip() and x.strip() != "未填写" for x in
               (v.get("hours") or "", v.get("start") or "",
                v.get("place") or "", v.get("review") or ""))
    }

    backup = build_backup(tasks, study, with_checkins=not args.no_checkins)

    total = sum(len(v) for v in backup["tasks"].values())
    done = sum(1 for v in backup["tasks"].values() for t in v if t["done"])
    missing = [t for t in tasks if not t["title"]]

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(backup, f, ensure_ascii=False, indent=1)

    print("日期范围: %s ~ %s（%d 天）" % (
        min(backup["tasks"]), max(backup["tasks"]), len(backup["tasks"])))
    print("任务总数: %d  已完成: %d  未完成: %d  完成率: %d%%" % (
        total, done, total - done, round(done * 100 / total) if total else 0))
    print("学习详情: %d 天" % len(backup["study"]))
    print("有备注的任务: %d" % sum(
        1 for v in backup["tasks"].values() for t in v if t["note"]))
    if missing:
        print("警告: %d 条任务缺标题" % len(missing), file=sys.stderr)
    print("已写入: %s" % args.out)


if __name__ == "__main__":
    main()
