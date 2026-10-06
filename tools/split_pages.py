# -*- coding: utf-8 -*-
"""
把 v1.6.0 的单页仪表盘（activity_main.xml，892 行）按区块拆成四页，
并生成新的外壳布局（底部导航 + 页面容器）。

不手抄 XML：原文件里每个区块前面都有 `<!-- ① … -->` 这样的注释标记，
按标记切一刀即可，属性、id 原样保留。
"""
import io, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'layout')
SRC = os.path.join(BASE, 'activity_main.xml')

lines = io.open(SRC, encoding='utf-8').read().split('\n')   # 0-based


def L(n):
    """取 1-based 行号 n。"""
    return lines[n - 1]


def block(start, end):
    """取 [start, end] 闭区间的行，去掉尾部空行。"""
    out = lines[start - 1:end]
    while out and out[-1].strip() == '':
        out.pop()
    return out


# ---- 先断言锚点还在原位，避免行号漂移后静默切错 ----
ANCHORS = {
    7: '<ScrollView xmlns:android=',
    28: '<!-- 自绘标题',
    41: '<!-- ① 状态总览',
    60: '<!-- 未开启无障碍时露出的排查引导',
    103: '<!-- ② 运行设置',
    150: '<!-- ③ 跳过统计',
    162: '<!-- ④ 按应用开关',
    267: '<!-- ⑤ 后台保活',
    385: '<!-- ⑤ root 增强',
    504: '<!-- ⑥ 规则订阅',
    634: '<!-- ⑦ 本地规则',
    790: '<!-- ⑧ 最近跳过记录',
}
for n, needle in ANCHORS.items():
    actual = L(n)
    if needle not in actual:
        sys.exit('第 %d 行锚点不匹配：期望含 %r，实际 %r' % (n, needle, actual))

assert 'android:id="@+id/tvFooter"' in L(883), 'tvFooter 位置变了'

title = block(28, 39)
blk_status = block(41, 59)          # ① 状态总览
blk_tip = block(60, 102)            # 无障碍排查引导
blk_switch = block(103, 149)        # ② 运行设置
blk_stats = block(150, 161)         # ③ 跳过统计
blk_apps = block(162, 266)          # ④ 按应用开关（只取自动识别开关那两段）
blk_keep = block(267, 384)          # ⑤ 后台保活
blk_root = block(385, 503)          # ⑤ root 增强
blk_sub = block(504, 633)           # ⑥ 规则订阅
blk_local = block(634, 789)         # ⑦ 本地规则
blk_log = block(790, 880)           # ⑧ 最近跳过记录
blk_footer = block(882, 889)

# 从 ④ 里只捞出「自动识别」开关和它的说明，挪到设置页；其余（应用列表入口）废弃 ——
# 应用列表现在是独立的一页，不再需要入口卡片。
auto_switch = block(246, 254)
auto_help = block(256, 263)
assert 'swAutoDetect' in '\n'.join(auto_switch), '没捞到 swAutoDetect'
assert 'auto_help' in '\n'.join(auto_help), '没捞到 auto_help 说明'


def page(title_text, body_blocks):
    head = u'''<?xml version="1.0" encoding="utf-8"?>
<!--
  %s —— 由 activity_main.xml 拆分而来（见 tools/split_pages.py）。
  外壳（底部导航 + 页面容器）在 activity_main.xml，本文件只放这一页的内容。
-->
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/page_bg"
    android:descendantFocusability="beforeDescendants"
    android:fillViewport="true"
    android:focusableInTouchMode="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:focusable="true"
        android:focusableInTouchMode="true"
        android:orientation="vertical"
        android:paddingLeft="14dp"
        android:paddingRight="14dp"
        android:paddingTop="14dp"
        android:paddingBottom="24dp">
''' % title_text

    title_block = u'''        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:paddingLeft="2dp"
            android:paddingTop="6dp"
            android:paddingBottom="12dp"
            android:text="@string/%s"
            android:textColor="@color/text_main"
            android:textSize="21sp"
            android:textStyle="bold" />
''' % title_text

    tail = u'''
    </LinearLayout>
</ScrollView>
'''
    chunks = [head, title_block]
    for b in body_blocks:
        chunks.append('\n'.join(b))
        chunks.append('')
    chunks.append(tail)
    return '\n'.join(chunks)


def write(name, text):
    p = os.path.join(BASE, name)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(text)
    print('  生成 %-22s %d 行' % (name, text.count('\n')))


print('拆分页面：')
write('page_home.xml', page('app_name', [blk_status, blk_tip, blk_switch, blk_stats, blk_log, blk_footer]))
write('page_rules.xml', page('tab_rules', [blk_sub, blk_local]))
write('page_settings.xml', page('tab_settings', [blk_keep, blk_root]))
write('page_apps.xml', page('tab_apps', []))
print('OK')
