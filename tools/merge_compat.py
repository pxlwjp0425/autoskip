# -*- coding: utf-8 -*-
"""把多份「快跳格式」规则合并成一份订阅（并集，不丢任何一条）。

为什么要并集：重跑干净源会修正一批应用（旧产物来自被逐字符拆坏的上游文件），
但同时有少量应用在新源里没有。并集能保证「只增不减」——新订阅永远是旧订阅的超集，
换订阅不会让任何应用反而变少。

用法：
    python tools/merge_compat.py 输出.json 输入1.json 输入2.json ...
"""
import io
import json
import os
import sys


def load(path):
    d = json.load(io.open(path, encoding="utf-8"))
    return d.get("apps") or []


def rule_key(r):
    # anchor 必须参与去重键：李跳跳转来的规则是「前提 A + 点击 B」，
    # 而 GKD 转来的规则没有前提。两者可能 matches 完全相同（比如都是「取消」），
    # 但语义截然不同，不能当成重复项互相吃掉。
    return (r.get("matchType"), tuple(r.get("matches") or []),
            tuple(r.get("anchor") or []), tuple(r.get("activityIds") or []))


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return
    out_path, srcs = sys.argv[1], sys.argv[2:]

    order, groups = [], {}
    for path in srcs:
        for a in load(path):
            pkg = a.get("packageName")
            if not pkg:
                continue
            if pkg not in groups:
                groups[pkg] = {"name": a.get("name") or pkg, "packageName": pkg,
                               "rules": [], "_seen": set()}
                order.append(pkg)
            g = groups[pkg]
            if not g["name"] or g["name"] == pkg:
                g["name"] = a.get("name") or g["name"]
            for r in a.get("rules") or []:
                k = rule_key(r)
                if k in g["_seen"]:
                    continue
                g["_seen"].add(k)
                g["rules"].append(r)

    apps = []
    for pkg in order:
        g = groups[pkg]
        g.pop("_seen", None)
        if g["rules"]:
            apps.append(g)

    # 全局组排最前，方便人工检查
    apps.sort(key=lambda x: (x["packageName"] != "*", x["packageName"]))

    result = {
        "version": 1,
        "name": "GKD + 李跳跳 转换规则（快跳兼容版）",
        "desc": "由 GKD 订阅与李跳跳规则集自动转换，仅保留快跳可表达的匹配条件（文本/描述/资源ID）",
        "apps": apps,
    }
    with io.open(out_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))

    n_rules = sum(len(a["rules"]) for a in apps)
    print("输出 %s" % out_path)
    print("应用 %d 个 / 规则 %d 条 / %.0f KB" % (len(apps), n_rules,
                                                 os.path.getsize(out_path) / 1024.0))
    for path in srcs:
        n = len({a.get("packageName") for a in load(path)})
        print("  源 %-28s %d 应用" % (os.path.basename(path), n))


if __name__ == "__main__":
    main()
