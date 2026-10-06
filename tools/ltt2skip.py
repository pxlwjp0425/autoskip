#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""李跳跳规则 → 快跳规则 转换器。

## 上游形态

李跳跳规则文件是一串「哈希 → 规则 JSON 字符串」的对象，多个应用排成数组：

    [ {"<包名.hashCode()>": "{\\"popup_rules\\":[{\\"id\\":..,\\"action\\":..}]}"} , ... ]

哈希是 Java `String.hashCode()`（可为负），**不可逆**，所以必须靠外部包名清单
反算回来。本脚本按优先级合并多个包名来源（见 load_pkgs）。

顺带一提，从各处收集来的规则文件经常是**多段数组首尾直接拼接**的（没有逗号分隔），
严格 `json.loads` 会报 "Extra data"，所以这里用 raw_decode 扫描式解析。

## 语义映射（关键）

    李跳跳  {"id": A, "action": B}
      = 界面里出现特征 A 时，点击按钮 B

    快跳    {"anchor": [A], "matches": [B]}
      = 前提 anchor 成立，才允许点击 matches 命中的节点

A/B 都可以是 text / desc / resourceId 里的任意一种，两边都是「子串包含」，
这一点快跳的 matchType="any" 天然对齐。

## 语法映射

"&" = 与（同时包含）、"|" = 或（任一满足），AND 优先级高于 OR。
统计显示 "&" 只出现在 id 侧、action 侧干净，所以 anchor 原样透传给引擎解析，
matches 则把 "|" 拆成多项（快跳的 matches 数组本身就是「或」）。

用法：
    python tools/ltt2skip.py
"""
import io
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.normpath(os.path.join(HERE, "..", "rules", "_src"))

# 上游偶尔会用这些前缀表示「精确匹配」，快跳没有对应语义，直接剥掉。
MARK_RE = re.compile(r"^[=|!><~\s]+")

# 会被扫描的规则源（存在哪个读哪个）
SOURCES = ["LTT_Snoopy.json", "LTT_user.txt", "LTT_ExtendedRules.json"]


def jhash(s):
    """Java String.hashCode()：32 位有符号。"""
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    if h >= 2 ** 31:
        h -= 2 ** 32
    return h


# --------------------------------------------------------------------------- #
# 包名反查池
# --------------------------------------------------------------------------- #

def load_pkgs():
    """收集所有可能出现在规则里的包名，用于把 hashCode 反算回包名。

    池子越大，能还原的应用越多。按「专用清单 → 通用池」的顺序合并。
    """
    out = set()

    # 1) 上游自带的已适配列表
    p = os.path.join(SRC, "LTT_AppList.md")
    if os.path.exists(p):
        txt = io.open(p, encoding="utf-8").read()
        for m in re.finditer(r"\]\(\./([^)]*?)/readme\.md\)", txt):
            out.add(m.group(1).split("/")[-1])

    # 2) 其它脚本预先攒好的大池子（手机已装 + GKD 订阅 + 各规则源）
    p = os.path.join(SRC, "pkg_pool.txt")
    if os.path.exists(p):
        for line in io.open(p, encoding="utf-8"):
            line = line.strip()
            if line:
                out.add(line)

    return {x for x in out if x and x != "*" and "." in x}


# --------------------------------------------------------------------------- #
# 规则源解析
# --------------------------------------------------------------------------- #

def scan_json_arrays(text):
    """扫出文本里所有 JSON 数组，容忍多段拼接、段间夹杂垃圾字符。

    返回 [(hashCode字符串, 规则对象), ...]，同一应用可能重复出现多次。
    """
    dec = json.JSONDecoder()
    out = []
    idx = 0
    n = len(text)
    while idx < n:
        while idx < n and text[idx] in " \r\n\t,":
            idx += 1
        if idx >= n:
            break
        try:
            obj, end = dec.raw_decode(text, idx)
        except Exception:
            # 段间残留字符（拼接产物），跳过继续找
            idx += 1
            continue
        if isinstance(obj, list):
            for item in obj:
                if isinstance(item, dict):
                    out.extend(item.items())
        idx = end
    return out


def load_sources():
    """读取全部规则源，返回 {hashCode: {popup_rule, ...}}（同一规则去重）。"""
    merged = {}
    for name in SOURCES:
        path = os.path.join(SRC, name)
        if not os.path.exists(path):
            continue
        text = io.open(path, encoding="utf-8", errors="replace").read()
        pairs = scan_json_arrays(text)
        kept = 0
        for k, v in pairs:
            try:
                obj = json.loads(v)
            except Exception:
                continue
            rules = obj.get("popup_rules")
            if not isinstance(rules, list):
                continue
            bucket = merged.setdefault(k, {})
            for r in rules:
                if not isinstance(r, dict):
                    continue
                # 同一条规则在多个快照里会重复出现，按 (id, action) 去重
                key = (str(r.get("id")), str(r.get("action")), r.get("times"))
                if key in bucket:
                    continue
                bucket[key] = r
                kept += 1
        print("源 %-26s 条目 %-6d 新增规则 %d" % (name, len(pairs), kept))
    return merged


# --------------------------------------------------------------------------- #
# 转换
# --------------------------------------------------------------------------- #

def clean_kw(s):
    """清洗单个关键词：去首尾标记符号与空白。"""
    if not isinstance(s, str):
        return ""
    return MARK_RE.sub("", s.strip()).strip()


def split_or(s):
    """按 "|" 拆「或组」并清洗，丢掉空项。"""
    out = []
    for part in str(s).split("|"):
        p = clean_kw(part)
        if p:
            out.append(p)
    return out


def clean_anchor(s):
    """anchor 要保留 & / | 结构，只逐项清洗后重组。"""
    groups = []
    for group in str(s).split("|"):
        kws = [clean_kw(k) for k in group.split("&")]
        kws = [k for k in kws if k]
        if kws:
            groups.append("&".join(kws))
    return "|".join(groups)


def convert_rule(r, stats):
    """李跳跳的一条 popup_rule → 快跳 Rule dict；转不了返回 None。"""
    if not isinstance(r, dict):
        stats["非字典"] += 1
        return None

    anchor = clean_anchor(r.get("id") or "")
    matches = split_or(r.get("action") or "")

    if not anchor:
        stats["无anchor"] += 1
        return None
    if not matches:
        stats["无action"] += 1
        return None

    # anchor 太短就失去「前提」的意义：'完成'、'更新' 这种满屏都可能出现，
    # 拿它当锚点等于没锚点，反而放大了误点风险。
    parts = [a for a in anchor.replace("&", "|").split("|") if a]
    if min(len(a) for a in parts) < 2:
        stats["anchor过短"] += 1
        return None

    times = r.get("times")
    if isinstance(times, int) and times > 0:
        max_click = min(max(times, 1), 5)
    else:
        # 上游绝大多数规则不带 times，李跳跳的行为是「能点就点」。
        # 快跳这边取 2：一次为正常关闭，留一次余量应对按钮分两步弹出的弹窗。
        max_click = 2

    return {
        "name": "李跳跳:%s → %s" % (anchor, "/".join(matches)),
        "action": "click",
        "matchType": "any",
        "anchor": [anchor],
        "matches": matches,
        "maxClick": max_click,
        "cooldown": 1500,
    }


def load_names():
    """从已适配列表里取「包名 → 中文名」，只用于展示。"""
    out = {}
    p = os.path.join(SRC, "LTT_AppList.md")
    if not os.path.exists(p):
        return out
    txt = io.open(p, encoding="utf-8").read()
    for m in re.finditer(r"\]\(\./([^)]*?)/readme\.md\)（([^）]*)）", txt):
        out[m.group(1).split("/")[-1]] = m.group(2)
    return out


def main():
    pkgs = load_pkgs()
    names = load_names()
    hmap = {}
    for p in pkgs:
        hmap.setdefault(jhash(p), []).append(p)
    print("包名反查池        : %d 个" % len(pkgs))
    print()

    raw = load_sources()
    print()
    print("规则源里的应用数  : %d" % len(raw))

    stats = {"总规则": 0, "转换成功": 0, "无anchor": 0, "无action": 0,
             "anchor过短": 0, "非字典": 0}
    apps = {}
    unmapped = []

    for k, bucket in raw.items():
        try:
            ki = int(k)
        except (TypeError, ValueError):
            unmapped.append(k)
            continue
        cand = hmap.get(ki)
        if not cand:
            unmapped.append(k)
            continue
        pkg = cand[0]
        slot = apps.setdefault(pkg, {"name": names.get(pkg, pkg), "rules": []})
        for r in bucket.values():
            stats["总规则"] += 1
            conv = convert_rule(r, stats)
            if conv:
                stats["转换成功"] += 1
                slot["rules"].append(conv)

    out_apps = []
    for i, (pkg, b) in enumerate(sorted(apps.items())):
        if not b["rules"]:
            continue
        out_apps.append({
            "id": i,
            "name": b["name"],
            "packageName": pkg,
            "rules": b["rules"],
        })

    out = {"version": 1, "apps": out_apps}
    dst = os.path.join(SRC, "from_LTT.json")
    io.open(dst, "w", encoding="utf-8").write(
        json.dumps(out, ensure_ascii=False, indent=1))

    print("未能反查包名的应用: %d  %s" % (len(unmapped), unmapped[:6]))
    print()
    print("规则总数          : %d" % stats["总规则"])
    print("  转换成功        : %d" % stats["转换成功"])
    print("  丢弃-无anchor   : %d" % stats["无anchor"])
    print("  丢弃-无action   : %d" % stats["无action"])
    print("  丢弃-anchor过短 : %d" % stats["anchor过短"])
    print("  丢弃-非字典     : %d" % stats["非字典"])
    print()
    print("输出应用数        : %d" % len(out_apps))
    print("输出规则数        : %d" % sum(len(a["rules"]) for a in out_apps))
    print("写入              : %s" % dst)


if __name__ == "__main__":
    main()
