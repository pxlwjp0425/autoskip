#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线回放：拿真机抓下来的节点树，验证规则集会不会误命中。

快跳内置的诊断模式会把「它自己看到的」节点树落到设备私有目录，这里把那些
dump 拉回本地，用与 RuleEngine 相同的判定顺序重跑一遍：

    1. activityIds 过滤
    2. matches 命中节点（text / desc / id 三者任一，子串包含）
    3. anchor 前提（& 与、| 或，AND 优先）
    4. 命中节点占屏 >40% 视为容器，拒绝点击

用途是**安全审计**：正常界面（没有弹窗）上不该有任何规则命中。命中了就是误点。

用法：
    python tools/ltt_verify.py <dump目录> [规则json]
"""
import io
import json
import os
import re
import sys

LINE_RE = re.compile(
    r"^(?P<cls>.*?) \| t=(?P<t>.*?) \| d=(?P<d>.*?) \| id=(?P<i>.*?)"
    r" \| click=(?P<c>[a-z]+) \| vis=(?P<v>[a-z]+) \| big=(?P<b>[a-z]+)"
    r" \| (?P<bounds>\d+,\d+ \d+x\d+)$"
)


def parse_dump(path):
    """返回 (pkg, activityClass, ruleCount, hitCount, [节点...])。"""
    text = io.open(path, encoding="utf-8", errors="replace").read()
    head, _, body = text.partition("---- nodes ----")
    meta = {}
    hits = []
    for line in head.splitlines():
        if line.startswith("pkg="):
            meta["pkg"] = line[4:].strip()
        elif line.startswith("cls="):
            meta["cls"] = line[4:].strip()
        elif line.startswith("ruleCount="):
            meta["ruleCount"] = int(line[10:].strip() or 0)
        elif line.startswith("hitCount="):
            meta["hitCount"] = int(line[10:].strip() or 0)
        elif line.startswith("HIT: "):
            hits.append(line[5:].strip())
    meta["hits"] = hits

    nodes = []
    for line in body.splitlines():
        m = LINE_RE.match(line.strip())
        if not m:
            continue
        nodes.append({
            "cls": m.group("cls"),
            "text": None if m.group("t") == "null" else m.group("t"),
            "desc": None if m.group("d") == "null" else m.group("d"),
            "id": None if m.group("i") == "null" else m.group("i"),
            "big": m.group("b") == "true",
            "vis": m.group("v") == "true",
            "bounds": m.group("bounds"),
        })
    return meta, nodes


def has(node, kw):
    for v in (node["text"], node["desc"], node["id"]):
        if v and (kw in v or kw.lower() in v.lower()):
            return True
    return False


def dnf_ok(nodes, expr):
    """anchor 条件：或组之间取任一，与项之间取全部。"""
    for group in str(expr).split("|"):
        if not group.strip():
            continue
        ok = True
        for kw in group.split("&"):
            kw = kw.strip()
            if not kw or not any(has(n, kw) for n in nodes):
                ok = False
                break
        if ok:
            return True
    return False


def anchor_ok(nodes, rule):
    anchors = rule.get("anchor") or []
    if not anchors:
        return True
    return any(dnf_ok(nodes, a) for a in anchors)


def match_rule(nodes, rule):
    """返回第一个命中且可点的节点；(None, 原因) 表示不命中。"""
    for n in nodes:
        if not any(has(n, m) for m in rule.get("matches") or []):
            continue
        excl = rule.get("excludes") or []
        if any(x in (n["text"] or "") or x in (n["desc"] or "") or x in (n["id"] or "")
               for x in excl):
            continue
        if not anchor_ok(nodes, rule):
            return None, "anchor不满足"
        if n["big"]:
            return None, "命中节点过大(容器)"
        return n, None
    return None, None


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    dump_dir = sys.argv[1]
    rule_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(os.path.abspath(__file__)), "..", "rules", "gkd-compat.json")

    if not os.path.exists(rule_path):
        print("找不到规则文件：%s" % os.path.normpath(rule_path))
        print("")
        print("规则数据不随仓库分发（授权原因，见 rules/README.md「授权说明」）。")
        print("请先用 tools/gkd2skip.py 生成，或把已有规则文件的路径作为第二个参数传入：")
        print("    python tools/ltt_verify.py <dump目录> <规则.json>")
        return

    ruleset = json.load(io.open(rule_path, encoding="utf-8"))
    by_pkg = {a["packageName"]: a["rules"] for a in ruleset["apps"]}
    glob = by_pkg.get("*", [])

    files = sorted(f for f in os.listdir(dump_dir) if f.endswith(".txt")
                   and f != "autoskip_log.xml")
    print("回放 dump 文件:", len(files))

    total_frames = 0
    total_fire = 0
    offenders = []
    lt_fire = 0

    for fn in files:
        meta, nodes = parse_dump(os.path.join(dump_dir, fn))
        pkg = meta.get("pkg", "")
        if not nodes:
            continue
        total_frames += 1
        for rule in by_pkg.get(pkg, []) + glob:
            if not rule.get("enabled", True):
                continue
            node, why = match_rule(nodes, rule)
            if node is None:
                continue
            total_fire += 1
            is_ltt = bool(rule.get("anchor"))
            if is_ltt:
                lt_fire += 1
            offenders.append((fn, pkg, rule.get("name"), rule.get("anchor"),
                              rule.get("matches"), node["bounds"], (node["text"] or node["desc"] or node["id"] or "")[:40]))

    print("有效帧:", total_frames, " 规则命中次数:", total_fire, " 其中李跳跳式(带anchor):", lt_fire)
    print()
    if not offenders:
        print("没有任何规则在抓到的界面上命中 —— 无潜在误点。")
        return

    print("命中明细：")
    seen = set()
    for fn, pkg, name, anc, mts, bounds, shown in offenders:
        key = (pkg, name, shown)
        dup = "  (重复)" if key in seen else ""
        seen.add(key)
        print("  %-34s %-28s anchor=%s matches=%s → 「%s」 %s%s"
              % (pkg, (name or "")[:28], anc, mts, shown, bounds, dup))


if __name__ == "__main__":
    main()
