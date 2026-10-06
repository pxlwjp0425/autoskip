# -*- coding: utf-8 -*-
"""
GKD 订阅(json5) -> 快跳 AutoSkip 规则(json) 转换器

设计原则：**宁可少转，不可误触**。
GKD 的选择器引擎支持层级(> < + -)、索引、属性断言(clickable/childCount/width...)、
正则等，快跳只有「文本/描述/资源ID」三个维度的匹配，所以：

  1. 只转换能提取出明确 text / desc / vid 关键词的规则
  2. 纯靠结构定位（clickable=true、childCount=5、index=0 之类）的规则一律丢弃
  3. 精确匹配 text="跳过" -> 正则 ^跳过$，完整复刻语义
  4. 低置信度关键词（"关闭""取消""X"）必须带 activityIds 才保留
  5. 系统通用 ID（android:id/content）直接拉黑
  6. 无 activityIds 的规则 maxClick 收紧到 1
  7. **只从「点击目标」那一段取关键词**。GKD 选择器是多段拼的，只有 '@' 标记的那段
     是目标；没有 '@' 时按搜索方向推断（'>' 目标在右，'<<' 目标在左）。
     祖先/兄弟段落里的 vid 只是定位约束，拿来当点击目标就会点中整个容器 ——
     微博的 view_recycler（信息流列表，占屏 84%）就是这么被误转成点击目标的。
  8. 容器型资源 ID（recycler/list/layout/container/pager…）不当点击目标，
     除非 ID 里带 close/btn/skip 这类动作词
  9. 「推荐」「广告」「荐读」「关注」这类内容/导航词不转：
     GKD 靠 preKeys 多步流程才安全，快跳只有单步点击
"""
import json5, json, re, collections, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
# 用法: python gkd2skip.py [源.json5] [输出.json]
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "merged_gkd.json5")
OUTDIR = os.path.join(HERE, "..", "rules")
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(OUTDIR, "gkd-compat.json")
REPORT = OUT + ".report.txt"

MAX_RULES_PER_APP = 80
MAX_PATTERNS_PER_RULE = 6

# ---------------- 选择器解析 ----------------
RE_ID         = re.compile(r'''(?<![\w.])(?:vid|id)\s*=\s*["']([^"']*)["']''')
RE_TEXT_EQ    = re.compile(r'''\btext\s*=\s*["']([^"']*)["']''')
RE_TEXT_STAR  = re.compile(r'''\btext\s*\*\s*=\s*["']([^"']*)["']''')
RE_TEXT_CARET = re.compile(r'''\btext\s*\^\s*=\s*["']([^"']*)["']''')
RE_TEXT_DOL   = re.compile(r'''\btext\s*\$\s*=\s*["']([^"']*)["']''')
RE_TEXT_RE    = re.compile(r'''\btext\s*~\s*=\s*["']([^"']*)["']''')
RE_DESC_EQ    = re.compile(r'''\bdesc\s*=\s*["']([^"']*)["']''')
RE_DESC_ANY   = re.compile(r'''\bdesc\s*[*^$~]\s*=\s*["']([^"']*)["']''')

HIGH_CONF = ("跳过", "跳過", "skip", "关闭广告", "关闭此广告", "跳过广告", "跳過廣告",
             "略过", "略過", "跳过片头", "跳过按钮")

TOO_BROAD = {"x", "×", "✕", "✖", "关闭", "取消", "忽略", "否", "no", "close",
             "确定", "好的", "知道了", "以后再说", "暂不", "下次", "设置", "更多"}

BAD_WORDS = {"jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "null", "true",
             "false", "none", "undefined"}

BAD_ID_PREFIX = ("android:", "com.android.internal")

# 以「动作词」结尾的资源 ID 即便很短，语义也是明确的（xx_skip / xx_close / xx_btn），
# 而且快跳的规则本身按包名隔离，不存在跨应用误匹配。
ID_ACTION_TAILS = ("skip", "close", "btn", "button", "exit", "dismiss", "cancel")


def has_action_word(i):
    low = (i or "").lower()
    return any(("_" + t) in low or low.endswith(t) for t in ID_ACTION_TAILS)

# 以这些词结尾的资源 ID 是「容器」：列表、布局、翻页器……它们是用来定位的，
# 不是用来点的。真按下去只会点中容器里的内容 —— 用户看到的现象就是「乱跳转」。
CONTAINER_ID_TAIL = ("recycler", "list", "pager", "grid", "scroll", "flipper",
                     "drawer", "adapter", "wrapper", "holder", "container",
                     "layout", "root", "content", "item")

# ID 里带这些词说明它是「可操作控件」，优先按控件处理，不套容器黑名单。
ACTION_ID_HINT = ("close", "btn", "button", "exit", "skip", "cancel", "dismiss",
                  "del", "delete", "fold", "collapse", "back")

# 这些词在任何 App 里都更像是「内容标签 / 导航项」而不是关闭按钮。
# GKD 多半把它们放在带 preKeys 的多步流程里（先点广告标记、再点这个），
# 快跳只有单步语义，命中即点，必须整体拉黑。
VAGUE_CONTENT = {"推荐", "广告", "荐读", "推荐阅读", "相关推荐", "热门", "热点",
                 "发现", "视频", "直播", "话题", "评论", "转发", "收藏", "分享",
                 "赞", "详情", "更多", "关注", "订阅", "客服", "举报"}


def to_list(x):
    if x is None:
        return []
    if isinstance(x, str):
        return [x]
    if isinstance(x, (list, tuple)):
        return [i for i in x if isinstance(i, str)]
    return []


def esc(s):
    return re.escape(s)


def ok_id(x):
    x = (x or "").strip()
    if len(x) < 4:
        return False
    for p in BAD_ID_PREFIX:
        if x.startswith(p):
            return False
    name = x.lower().rsplit("/", 1)[-1]
    if len(x) < 8 and name in ("content", "compose_view", "root", "container"):
        return False
    # 带动作语义的控件 ID 优先放行（如 iv_close / btn_skip），
    # 剩下的再按「容器后缀」拉黑：view_recycler / tweet_list / xxx_container 都是这一类。
    if any(h in name for h in ACTION_ID_HINT):
        return True
    if any(name.endswith(t) for t in CONTAINER_ID_TAIL):
        return False
    return True


def id_pattern(x):
    """完整资源名直接用；短名锚定到 '/' 之后，避免 'arg' 命中 'large_button'"""
    if ":" in x:
        return esc(x)
    return "/" + esc(x) + "$"


def is_high_conf(kw):
    k = kw.lower()
    return any(h in k for h in HIGH_CONF)


def meaningful(kind, kw, acts):
    """关键词是否值得保留"""
    if kw.strip().lower() in BAD_WORDS:
        return False
    if len(kw) >= 2:
        return True
    # 单字符（如 "X"）：只有精确匹配 + 限定了界面才敢用
    return kind == "eq" and bool(acts)


def text_ok(kind, kw, acts):
    if not meaningful(kind, kw, acts):
        return False
    if kw.strip() in VAGUE_CONTENT:
        # 「推荐」「广告」「关注」这类词在界面里是内容/导航，不是关闭按钮。
        # 它们原本靠 GKD 的 preKeys 多步流程才安全，快跳单步点击等于乱点。
        return False
    if is_high_conf(kw):
        return True
    if kw.lower() in TOO_BROAD:
        return bool(acts)
    if acts:
        return len(kw) >= 2
    return len(kw) >= 4


def text_pattern(kind, kw):
    if kind == "eq":
        return "^" + esc(kw) + "$"
    if kind == "star":
        return esc(kw)
    if kind == "caret":
        return "^" + esc(kw)
    if kind == "dollar":
        return esc(kw) + "$"
    if kind == "re":
        return kw
    return esc(kw)


# 顶层关系操作符：' ' 后代 / '>' 子 / '<' 父 / '+' 后继兄弟 / '-' 前驱兄弟，
# 可带索引(>7)与任意层级(n)。形如 'A >2 B <<n C'。
RE_REL = re.compile(r"\s*(?:<<|>>|<|>|\+|-|~)\s*\d*\s*n?\s*|\s+")


def split_selector(s):
    """把 GKD 选择器按顶层关系操作符切成片段，返回 (fragments, ops)。

    ops[i] 表示 fragments[i-1] 与 fragments[i] 之间的操作符。

    关键：'[' ']' 和引号内部的 '>' '-' 等字符是属性值的一部分，不能当分隔符。
    """
    parts, ops, buf = [], [], []
    pending = None
    i, n = 0, len(s)
    depth, quote = 0, None
    while i < n:
        ch = s[i]
        if quote:
            buf.append(ch)
            if ch == quote:
                quote = None
            i += 1
            continue
        if ch in ('"', "'"):
            quote = ch
            buf.append(ch)
            i += 1
            continue
        if ch == "[":
            depth += 1
            buf.append(ch)
            i += 1
            continue
        if ch == "]":
            depth = max(0, depth - 1)
            buf.append(ch)
            i += 1
            continue
        if depth == 0:
            m = RE_REL.match(s, i)
            if m:
                frag = "".join(buf).strip()
                token = m.group(0).strip()
                if frag:
                    parts.append(frag)
                    ops.append(pending)
                    pending = token
                    buf = []
                elif token:
                    pending = token
                i = m.end()
                continue
        buf.append(ch)
        i += 1
    frag = "".join(buf).strip()
    if frag:
        parts.append(frag)
        ops.append(pending)
    return parts, ops


def target_index(parts, ops):
    """定位「真正会被点击」的那一段。

    GKD 是自顶向下搜索的：`A > B` 先找 A、再在 A 的子节点里找 B，目标是 B；
    而 `A <<n B` 先找祖先 B、再在 B 的后代里找 A，**目标是 A（左边那个）**。
    所以凡是最右侧那段是通过祖先关系（'<' / '<<'）挂上去的，都要往左退。

    老实现无脑取最后一段，于是 `[id$="tt_splash_skip_btn"] <<n [vid="rlAdView"]`
    这种开屏跳过规则会被理解成「点 rlAdView（整个广告视图）」。
    """
    i = len(parts) - 1
    while i > 0 and (ops[i] or "").startswith("<"):
        i -= 1
    return i


def target_fragments(raw):
    """取出所有可能被点击的片段（@ 标记的，或按方向推断出的那一段）。"""
    out = []
    for s in to_list(raw):
        parts, ops = split_selector(s)
        if not parts:
            continue
        at = [p for p in parts if p.startswith("@")]
        out.extend(at if at else [parts[target_index(parts, ops)]])
    return out


def grab_texts(frag):
    out = []
    for rx, kind in ((RE_TEXT_EQ, "eq"), (RE_TEXT_STAR, "star"),
                     (RE_TEXT_CARET, "caret"), (RE_TEXT_DOL, "dollar"),
                     (RE_TEXT_RE, "re")):
        for m in rx.finditer(frag):
            out.append((kind, m.group(1)))
    return out


def grab_descs(frag):
    out = [("eq", m.group(1)) for m in RE_DESC_EQ.finditer(frag)]
    out += [("star", m.group(1)) for m in RE_DESC_ANY.finditer(frag)]
    return out


def extract(raw):
    ids, texts, descs = [], [], []
    for s in to_list(raw):
        parts, ops = split_selector(s)
        if not parts:
            continue
        at = [i for i, p in enumerate(parts) if p.startswith("@")]
        ti = at if at else [target_index(parts, ops)]

        # 目标片段：id / text / desc 三个维度的关键词都可以用
        for i in ti:
            frag = parts[i]
            ids += RE_ID.findall(frag)
            texts += grab_texts(frag)
            descs += grab_descs(frag)

        # 上下文片段：只放行「跳过/关闭广告」这类高置信词。
        # 其余（^广告$、^立即、view_recycler…）都是「识别广告用的特征」，
        # 拿来当点击目标要么点开广告、要么点中整个列表。
        for i, frag in enumerate(parts):
            if i in ti:
                continue
            for kind, val in grab_texts(frag) + grab_descs(frag):
                if is_high_conf(val):
                    texts.append((kind, val))

    def clean(lst):
        out = []
        for k, v in lst:
            v = (v or "").strip()
            if v and v.lower() != "null":
                out.append((k, v))
        return out

    texts, descs = clean(texts), clean(descs)
    seen, uids = set(), []
    for i in ids:
        i = i.strip()
        if i and i not in seen and ok_id(i):
            seen.add(i)
            uids.append(i)
    return uids, texts, descs


def norm_rules(g):
    """把 group 里的 rules 规范成「字典列表」。

    GKD 的 json5 里 rules 有三种合法写法：
      rules:[{matches:'…'}, {matches:'…'}]   # 常规
      rules:{matches:'…'}                    # 单条规则的简写
      rules:'…'                              # 单条规则且只有 matches 的极简写法

    旧版只按 list 处理，遇到后两种会 **把字符串当列表遍历** ——
    于是 `matches:'FrameLayout > [childCount=2]'` 被拆成一堆单字符规则
    （F / r / a / m / e …），既废掉了这批应用的全部规则，也让统计彻底失真。
    """
    rs = g.get("rules")
    if rs is None:
        return []
    if isinstance(rs, str):
        return [{"matches": rs}]
    if isinstance(rs, dict):
        return [rs]
    out = []
    for r in rs:
        if isinstance(r, str):
            out.append({"matches": r})
        elif isinstance(r, dict):
            out.append(r)
    return out


def convert_rule(r, gname, acts_ctx, stats, force_global=False):
    raw = r.get("matches")
    if raw is None:
        stats["无matches"] += 1
        return None

    acts = to_list(r.get("activityIds")) or acts_ctx
    if force_global:
        acts = []
    ex_acts = to_list(r.get("excludeActivityIds"))

    act = r.get("action") or "click"
    if act in ("longClick", "swipe", "clickNode"):
        stats["不支持的动作:" + act] += 1
        return None
    if act == "back":
        if not acts:
            stats["back但无activityIds"] += 1
            return None
        actj = "back"
    elif act == "clickCenter":
        actj = "clickCenter"
    else:
        actj = "click"

    ids, texts, descs = extract(raw)
    good_texts = [(k, v) for k, v in texts if text_ok(k, v, acts)]
    good_desc = [(k, v) for k, v in descs if text_ok(k, v, acts)]

    if good_texts:
        kind, kws = "text", good_texts
    elif ids:
        kind, kws = "id", [("raw", i) for i in ids]
    elif good_desc:
        kind, kws = "desc", good_desc
    else:
        if texts or descs or ids:
            stats["关键词过于宽泛"] += 1
        else:
            stats["仅结构定位(无关键词)"] += 1
        return None

    if not acts:
        if kind == "id":
            # 只剔除太短的 ID，不让它连坐整条规则。
            # 旧实现是 all(len>=8)，于是 `[vid="tv_main_splash_skip" || vid="tv_skip"]`
            # 因为 tv_skip 只有 7 个字符，把 20 字符的 tv_main_splash_skip 一起拖死了 ——
            # 12306 的开屏跳过规则就这么没了。
            keep = [kv for kv in kws if len(kv[1]) >= 8 or has_action_word(kv[1])]
            if not keep:
                stats["ID过短且无activityIds"] += 1
                return None
            kws = keep
        else:
            if not any(is_high_conf(v) for _, v in kws):
                stats["低置信度且无activityIds"] += 1
                return None

    if kind == "text":
        pats = []
        for k, v in kws[:MAX_PATTERNS_PER_RULE]:
            p = text_pattern(k, v)
            if p not in pats:
                pats.append(p)
        mtype, regex = "text", True
    elif kind == "id":
        pats = [id_pattern(v) for _, v in kws[:MAX_PATTERNS_PER_RULE]]
        mtype, regex = "id", True
    else:
        pats = [text_pattern(k, v) for k, v in kws[:MAX_PATTERNS_PER_RULE]]
        mtype, regex = "desc", True

    ex_ids, ex_texts, ex_descs = extract(r.get("excludeMatches"))
    excludes = []
    for _, v in (ex_texts + ex_descs)[:8]:
        if len(v) >= 2 and v not in excludes:
            excludes.append(v)

    name = r.get("name") or gname or "自动跳过"
    out = {
        "name": str(name)[:40],
        "matchType": mtype,
        "regex": regex,
        "matches": pats,
        "activityIds": acts[:4],
        "action": actj,
        # 没限定界面的规则单屏只点一次，把误触概率压到最低
        "maxClick": 1 if not acts else int(r.get("actionMaximum") or 2),
        "cooldown": int(r.get("actionCd") or 1000),
        "enabled": True,
    }
    if excludes:
        out["excludes"] = excludes
    if ex_acts:
        out["excludeActivityIds"] = ex_acts[:4]
    if r.get("actionDelay"):
        out["delay"] = int(r["actionDelay"])

    stats["转换成功"] += 1
    return out


def main():
    data = json5.load(open(SRC, encoding="utf-8"))
    apps = data.get("apps") or []
    gg = data.get("globalGroups") or []

    stats = collections.Counter()
    out_apps = []
    total_rules = 0

    # ---- 全局规则 -> packageName "*"（只留高置信度的，否则任意界面都可能误触）----
    global_rules = []
    for g in gg:
        if g.get("enable") is False:
            continue
        for r in norm_rules(g):
            if r.get("enable") is False:
                continue
            total_rules += 1
            rr = convert_rule(r, g.get("name"), [], stats, force_global=True)
            if not rr:
                continue
            if not any(is_high_conf(m) for m in rr["matches"]):
                stats["全局规则不够通用被丢弃"] += 1
                stats["转换成功"] -= 1
                continue
            rr["maxClick"] = 1
            global_rules.append(rr)
    if global_rules:
        out_apps.append({"name": "通用（所有应用）", "packageName": "*", "rules": global_rules})

    # ---- 各应用 ----
    for a in apps:
        pkg = a.get("id")
        if not isinstance(pkg, str) or not pkg:
            stats["跳过无包名应用"] += 1
            continue
        rules = []
        for g in (a.get("groups") or []):
            if g.get("enable") is False:
                continue
            gacts = to_list(g.get("activityIds"))
            for r in norm_rules(g):
                if r.get("enable") is False:
                    continue
                total_rules += 1
                rr = convert_rule(r, g.get("name"), gacts, stats)
                if rr:
                    rules.append(rr)

        if not rules:
            continue

        uniq, seen = [], set()
        for r in rules:
            key = (r["matchType"], tuple(r["matches"]))
            if key in seen:
                continue
            seen.add(key)
            uniq.append(r)

        if len(uniq) > MAX_RULES_PER_APP:
            stats["超出上限被截断的应用"] += 1
            uniq = uniq[:MAX_RULES_PER_APP]

        out_apps.append({"name": a.get("name") or pkg, "packageName": pkg, "rules": uniq})

    result = {
        "version": 1,
        "name": "GKD 转换规则（快跳兼容版）",
        "desc": "由 GKD 订阅自动转换，仅保留快跳可表达的匹配条件（文本/描述/资源ID）",
        "apps": out_apps,
    }
    os.makedirs(OUTDIR, exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))

    # ---- 报告 ----
    n_apps = len([x for x in out_apps if x["packageName"] != "*"])
    n_rules = sum(len(x["rules"]) for x in out_apps)
    per = sorted((len(x["rules"]) for x in out_apps), reverse=True)
    mtypes = collections.Counter(r["matchType"] for x in out_apps for r in x["rules"])
    withacts = sum(1 for x in out_apps for r in x["rules"] if r.get("activityIds"))

    L = []
    L.append("GKD -> 快跳 AutoSkip 转换报告")
    L.append("=" * 50)
    L.append("源规则总数        : %d" % total_rules)
    L.append("成功转换          : %d  (%.1f%%)" % (stats["转换成功"], stats["转换成功"] * 100.0 / max(1, total_rules)))
    L.append("输出应用数        : %d 个（另有 1 组全局通用规则）" % n_apps)
    L.append("输出规则总数      : %d 条" % n_rules)
    L.append("匹配类型          : text=%d  id=%d  desc=%d" % (mtypes["text"], mtypes["id"], mtypes["desc"]))
    L.append("带界面限定的比例  : %.1f%%  (%d/%d)" % (withacts * 100.0 / max(1, n_rules), withacts, n_rules))
    L.append("平均/最多每应用   : %.1f / %d 条" % (n_rules / max(1, n_apps), per[0] if per else 0))
    L.append("输出体积          : %.0f KB" % (os.path.getsize(OUT) / 1024.0))
    L.append("")
    L.append("丢弃原因明细")
    L.append("-" * 50)
    for k, v in stats.most_common():
        if k == "转换成功":
            continue
        L.append("  %-30s %6d" % (k, v))
    txt = "\n".join(L)
    open(REPORT, "w", encoding="utf-8").write(txt)
    print(txt)
    print("\n输出:", OUT)


if __name__ == "__main__":
    main()
