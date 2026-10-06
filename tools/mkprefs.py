# -*- coding: utf-8 -*-
"""把快跳的规则 JSON 写进 Android SharedPreferences XML。

- 只注入订阅层 rules_json（等价于"从订阅拉了一份规则"）
- 保留原有其余键
- 只转义 XML 文本节点必须的 & < >，避免体积膨胀
"""
import json, os, sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape, unescape

PREFS_RAW = sys.argv[1] if len(sys.argv) > 1 else ""
RULES = sys.argv[2] if len(sys.argv) > 2 else ""
OUT = sys.argv[3] if len(sys.argv) > 3 else ""
TS = sys.argv[4] if len(sys.argv) > 4 else "0"

raw = open(PREFS_RAW, encoding="utf-8").read()
rules = open(RULES, encoding="utf-8").read()

# 1) 校验规则 JSON 本身合法
parsed = json.loads(rules)
n_apps = len(parsed["apps"])
n_rules = sum(len(a["rules"]) for a in parsed["apps"])
print(f"规则文件: {n_apps} 应用 / {n_rules} 条, {len(rules)} 字符")

# 2) 扫描非法 XML 字符（控制字符会让解析器直接报错）
ILLEGAL = set(range(0x00, 0x09)) | {0x0B, 0x0C} | set(range(0x0E, 0x20))
bad = sorted({c for c in rules if ord(c) in ILLEGAL})
print(f"非法 XML 控制字符: {[hex(x) for x in bad] if bad else '无'}")

# 3) 解析原有 prefs，先剔除待覆盖的键，其余原样保留
root = ET.fromstring(raw)
for key in ("rules_json", "last_update"):
    for child in list(root):
        if child.get("name") == key:
            root.remove(child)

# 4) 追加 rules_json 与 last_update
el = ET.SubElement(root, "string", {"name": "rules_json"})
el.text = rules
ET.SubElement(root, "long", {"name": "last_update", "value": TS})

# 5) 手工序列化，模仿 Android 的格式（单引号声明、自闭合标签）
def ser(node):
    name = node.get("name")
    tag = node.tag
    if tag == "long":
        return f'    <long name="{name}" value="{node.get("value")}" />'
    if tag == "string":
        val = node.text if node.text is not None else ""
        return f'    <string name="{name}">{escape(val)}</string>'
    if tag == "int":
        return f'    <int name="{name}" value="{node.get("value")}" />'
    if tag == "boolean":
        return f'    <boolean name="{name}" value="{node.get("value")}" />'
    return ""

lines = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
for child in root:
    lines.append(ser(child))
lines.append("</map>")
lines.append("")
out = "\n".join(lines)

open(OUT, "w", encoding="utf-8", newline="\n").write(out)
print(f"输出: {OUT}  {len(out)} 字符 / {os.path.getsize(OUT)} 字节")

# 6) 自检：重新解析，确认 rules_json 能完整还原成原 JSON
chk = ET.fromstring(open(OUT, encoding="utf-8").read())
got = None
for c in chk:
    if c.get("name") == "rules_json":
        got = c.text
assert got is not None, "rules_json 丢失"
assert got == rules, "rules_json 内容与源文件不一致！"
rt = json.loads(got)
print(f"回读校验: 一致 ✓  ({len(got)} 字符, {len(rt['apps'])} 应用 / "
      f"{sum(len(a['rules']) for a in rt['apps'])} 条)")
