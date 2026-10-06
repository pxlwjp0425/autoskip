# -*- coding: utf-8 -*-
"""由 tools/builtin_rules.json 生成 BuiltinRules.java。

为什么要生成而不是手写：出厂规则是一大坨 JSON，手写进 Java 字符串要么反斜杠漏转义、
要么引号漏转义，改一次炸一次。这里统一由 JSON 生成，改规则只动 JSON。

用法：
    python tools/gen_builtin.py
输出：
    app/src/main/java/com/laopeng/autoskip/BuiltinRules.java
"""
import json
import os
import io

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "builtin_rules.json")
OUT = os.path.join(HERE, "..", "app", "src", "main", "java",
                   "com", "laopeng", "autoskip", "BuiltinRules.java")

# 只保留 Rule.fromJson 认识的字段；note 是给人看的，不进产物。
KNOWN = {"name", "matchType", "matches", "match", "excludes", "excludeMatches",
         "activityIds", "excludeActivityIds", "className", "action",
         "regex", "delay", "maxClick", "cooldown", "enabled"}
TOP = {"version", "apps", "id", "packageName", "rules", "groups"}


def strip_notes(node):
    if isinstance(node, dict):
        return {k: strip_notes(v) for k, v in node.items() if k in KNOWN or k in TOP}
    if isinstance(node, list):
        return [strip_notes(x) for x in node]
    return node


def java_literal(text):
    lines = text.split("\n")
    out = []
    for i, ln in enumerate(lines):
        esc = ln.replace("\\", "\\\\").replace('"', '\\"')
        suffix = "\\n" if i < len(lines) - 1 else ""
        out.append('        "%s%s"' % (esc, suffix))
    return " +\n".join(out) + ";"


def main():
    data = json.load(io.open(SRC, encoding="utf-8"))
    clean = strip_notes(data)
    text = json.dumps(clean, ensure_ascii=False, indent=2)
    apps = clean.get("apps", [])
    global_rules = next((len(a["rules"]) for a in apps if a.get("packageName") == "*"), 0)
    app_groups = [a for a in apps if a.get("packageName") != "*"]
    total = sum(len(a["rules"]) for a in apps)

    java = (
        "package com.laopeng.autoskip;\n"
        "\n"
        "/**\n"
        " * 出厂内置规则。**本文件由 tools/gen_builtin.py 从 tools/builtin_rules.json 生成，\n"
        " * 不要手改** —— 要改规则请改 JSON 再跑一次生成器。\n"
        " *\n"
        " * <p>这层永远参与匹配（见 {@link RuleStore}），所以它同时也是「订阅拉不到 /\n"
        " * 订阅里没有这个应用 / 上游把规则默认关闭」时的兜底。\n"
        " *\n"
        " * <p>当前：%d 条通用规则（packageName=\"*\"）+ %d 个应用专属组，共 %d 条。\n"
        " */\n"
        "public class BuiltinRules {\n"
        "\n"
        "    public static final String JSON =\n"
        "%s\n"
        "}\n"
    ) % (global_rules, len(app_groups), total, java_literal(text))

    with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(java)
    print("wrote", os.path.abspath(OUT))
    print("全局规则 %d 条 / 应用组 %d 个 / 合计 %d 条" % (global_rules, len(app_groups), total))
    for a in app_groups:
        print("   %-34s %s（%d 条）" % (a["packageName"], a.get("name", ""), len(a["rules"])))


if __name__ == "__main__":
    main()
