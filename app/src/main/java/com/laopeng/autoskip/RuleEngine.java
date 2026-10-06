package com.laopeng.autoskip;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.res.Resources;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RuleEngine {

    /** 单次扫描最多收集的节点数，防止超长列表把一次扫描拖到几百毫秒。 */
    private static final int MAX_NODES = 3000;
    /** 单次扫描最多访问的节点数（含被丢弃的容器节点），硬上限。 */
    private static final int MAX_VISIT = 12000;
    /** 最大下探层级。正常界面树二三十层就到头了，超过基本可以断定树有问题。 */
    private static final int MAX_DEPTH = 40;

    /**
     * 命中节点占屏幕面积超过这个比例，就认定它是「容器」而不是「按钮」，拒绝点击。
     *
     * 信息流列表（微博的 view_recycler）、页面根布局这类节点永远不是关闭按钮，
     * 拿规则去点它，performAction 会命中容器里当前的内容，手势兜底则直接落在
     * 正中间的那条内容上 —— 用户看到的现象就是「进页面之后自己乱跳转」。
     * 真正的广告关闭/跳过按钮都是小控件，这个阈值不会伤到正常规则。
     */
    private static final int MAX_HIT_AREA_PERCENT = 40;
    /** 向上找到的可点祖先超过这个比例就放弃它，改用节点自身中心。 */
    private static final int MAX_ANCESTOR_AREA_PERCENT = 70;

    public static class Hit {
        public Rule rule;
        public AccessibilityNodeInfo node;
        public String detail = "";
    }

    /** 遍历栈里的一帧。 */
    private static final class Frame {
        final AccessibilityNodeInfo node;
        final int depth;

        Frame(AccessibilityNodeInfo node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }

    /**
     * 节点属性快照。
     *
     * 提前把四个属性取出来存好，一是避免「规则数 × 节点数」次重复取值，
     * 二是节点在后续点击时可能已失效，文本留个副本便于记日志。
     *
     * <p>包内可见：{@link SplashAuto} 的启发式识别要复用同一套节点快照，
     * 保证「引擎看到的树」和「自动识别看到的树」是同一棵。
     */
    static final class Candy {
        final AccessibilityNodeInfo node;
        final String text;
        final String desc;
        final String id;
        final String cls;

        Candy(AccessibilityNodeInfo node, String text, String desc, String id, String cls) {
            this.node = node;
            this.text = text;
            this.desc = desc;
            this.id = id;
            this.cls = cls;
        }
    }

    /**
     * 在节点树里为每条规则找第一个命中的节点。
     *
     * 这里刻意不用递归：部分 ROM（尤其 MIUI）的 AccessibilityCache 偶发会返回成环的
     * 节点树，老实现会在这种树上无限递归，把 main 线程 8MB 的栈打爆。
     * StackOverflowError 属于 Error 不属于 Exception，外层 catch(Exception) 兜不住，
     * 结果是无障碍服务进程直接崩掉、系统随即把无障碍开关关掉 ——
     * 用户看到的现象就是「开了自动跳过，开关自己又关上了」。
     *
     * 现在改成「显式栈 + 深度上限 + 访问去重 + 节点总数上限」四道闸。
     */
    public static List<Hit> match(List<Rule> rules, String activity, AccessibilityNodeInfo root) {
        List<Hit> hits = new ArrayList<>();
        if (rules == null || rules.isEmpty() || root == null) return hits;

        List<Rule> active = new ArrayList<>();
        for (Rule r : rules) {
            if (r == null || !r.enabled) continue;
            try {
                if (r.matchActivity(activity)) active.add(r);
            } catch (Throwable ignored) {
            }
        }
        if (active.isEmpty()) return hits;

        List<Candy> nodes = collect(root);
        if (nodes.isEmpty()) return hits;

        for (Rule r : active) {
            for (Candy c : nodes) {
                boolean ok;
                try {
                    ok = hitNode(c, r);
                } catch (Throwable t) {
                    ok = false;
                }
                if (!ok) continue;
                // 找到点击目标之后，再回头确认「前提条件」在同一帧里也成立。
                // 顺序刻意放后面：anchor 检查要扫全树，先跑就等于每条规则都白扫一遍。
                if (!anchorHit(nodes, r)) break;
                Hit h = new Hit();
                h.rule = r;
                h.node = c.node;
                h.detail = describe(c);
                hits.add(h);
                break;
            }
        }
        return hits;
    }

    /**
     * 前序遍历整棵树，一次扫描收集所有候选节点。
     * 原实现是「每条规则遍历一次全树」，命中 N 条规则就要走 N 遍，
     * 而 getChild 每次都是跨进程调用，代价极高；现在全树只走一遍。
     *
     * <p>包内可见：自动识别（{@link SplashAuto}）也走这一步，共用一套遍历上限。
     */
    static List<Candy> collect(AccessibilityNodeInfo root) {
        List<Candy> out = new ArrayList<>();
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        Set<AccessibilityNodeInfo> seen = new HashSet<>();
        int visited = 0;

        stack.push(new Frame(root, 0));

        while (!stack.isEmpty() && out.size() < MAX_NODES && visited < MAX_VISIT) {
            Frame f = stack.pop();
            AccessibilityNodeInfo n = f.node;
            if (n == null) continue;

            // AccessibilityNodeInfo.equals 比较的是「连接 id + 窗口 id + 节点 id」，
            // 正好用来识别同一个节点。一旦重复出现，说明这棵树成环了，丢掉即可。
            boolean first;
            try {
                first = seen.add(n);
            } catch (Throwable t) {
                first = true;
            }
            if (!first) continue;

            visited++;

            String text = null, desc = null, id = null, cls = null;
            try {
                text = str(n.getText());
                desc = str(n.getContentDescription());
                id = n.getViewIdResourceName();
                cls = str(n.getClassName());
            } catch (Throwable t) {
                // 取属性失败就当这个节点没有属性，仍然保留，子节点继续走
            }
            out.add(new Candy(n, text, desc, id, cls));

            if (f.depth >= MAX_DEPTH) continue;

            int count;
            try {
                count = n.getChildCount();
            } catch (Throwable t) {
                continue;
            }
            // 反向压栈：这样弹出来的顺序仍是前序，和最老的递归版一致，
            // 保证「越靠前的节点越优先命中」这个既有行为不变。
            for (int i = count - 1; i >= 0; i--) {
                AccessibilityNodeInfo c;
                try {
                    c = n.getChild(i);
                } catch (Throwable t) {
                    continue;
                }
                if (c != null) stack.push(new Frame(c, f.depth + 1));
            }
        }
        return out;
    }

    /**
     * 规则的前提条件是否成立。空 anchor 视为「无前提」，直接放行。
     *
     * <p>用来承载「李跳跳」那种「出现 A 时点击 B」的规则：A 是锚点，B 是点击目标。
     * 上游写法里 anchor 元素之间是「或」，单个元素内部 "|" 分「或组」、"&" 分「与项」，
     * 也就是标准的析取范式 {@code (a 且 b) 或 c}。
     *
     * <p>这里把「与项」理解为「树里存在某个节点包含它」而不是「同一个节点同时包含全部」——
     * 上游用 {@code &} 描述的是「弹窗里同时出现了这几段文字」，同一节点并不一定共存。
     */
    private static boolean anchorHit(List<Candy> nodes, Rule r) {
        if (r.anchor == null || r.anchor.isEmpty()) return true;
        for (String expr : r.anchor) {
            if (dnfHit(nodes, expr, r.regex)) return true;
        }
        return false;
    }

    /** 解析 {@code "A&B|C"} 形式的条件串：或组之间取「任一」，与项之间取「全部」。 */
    private static boolean dnfHit(List<Candy> nodes, String expr, boolean regex) {
        if (expr == null || expr.length() == 0) return false;
        for (String group : expr.split("\\|")) {
            // 像 "| 跳过" 这种写法拆出来会带一个空组，直接忽略空组即可。
            if (group.trim().length() == 0) continue;
            boolean all = true;
            for (String kw : group.split("&")) {
                kw = kw.trim();
                if (kw.length() == 0) {
                    all = false;
                    break;
                }
                if (!anyNodeHas(nodes, kw, regex)) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    private static boolean anyNodeHas(List<Candy> nodes, String kw, boolean regex) {
        for (Candy c : nodes) {
            if (JsonUtil.contains(c.text, kw, regex)
                    || JsonUtil.contains(c.desc, kw, regex)
                    || JsonUtil.contains(c.id, kw, regex)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hitNode(Candy c, Rule r) {        String text = c.text;
        String desc = c.desc;
        String id = c.id;

        boolean ok;
        switch (r.matchType) {
            case "text":
                ok = r.matchText(text);
                break;
            case "desc":
                ok = r.matchText(desc);
                break;
            case "id":
                ok = id != null && r.matchText(id);
                break;
            case "className":
                ok = r.matchText(c.cls);
                break;
            default:
                ok = r.matchText(text) || r.matchText(desc) || (id != null && r.matchText(id));
                break;
        }
        if (!ok) return false;

        // 关键词对上了，但节点是一大片区域 —— 那多半是列表/页面容器碰巧带了
        // 相同文案，不是可点的按钮。直接判为不命中，连尝试都不尝试。
        if (isBigArea(c.node, MAX_HIT_AREA_PERCENT)) return false;

        if (!r.className.isEmpty()) {
            boolean cm = false;
            for (String want : r.className) {
                if (c.cls != null && c.cls.contains(want)) {
                    cm = true;
                    break;
                }
            }
            if (!cm) return false;
        }
        return true;
    }

    /**
     * 节点是不是「大块区域」。
     *
     * 面积算不出来时一律按大块处理 —— 宁可漏点一条规则，也不能乱点一下。
     */
    static boolean isBigArea(AccessibilityNodeInfo node, int percent) {
        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
        } catch (Throwable t) {
            return true;
        }
        if (r.width() <= 0 || r.height() <= 0) return true;
        try {
            DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
            long screen = (long) dm.widthPixels * dm.heightPixels;
            if (screen <= 0) return false;
            return (long) r.width() * r.height() * 100L / screen > percent;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 执行规则动作。返回是否成功。 */
    public static boolean execute(AccessibilityService svc, Rule r, AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (r.action == null) r.action = Rule.ACTION_CLICK;

        if (Rule.ACTION_BACK.equals(r.action)) {
            return svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        }
        if (Rule.ACTION_NONE.equals(r.action)) {
            return false;
        }
        if (Rule.ACTION_CLICK_CENTER.equals(r.action)) {
            return tapCenter(svc, node);
        }

        // 最后一道闸：哪怕是别处漏进来的规则、或本地导入的规则，只要命中的
        // 是一大片区域就不点。多一层保险，代价只是偶尔漏过一次跳过。
        return clickNode(svc, node);
    }

    /**
     * 直接点一个节点，不套规则。
     *
     * <p>给自动识别用：它判定命中的不是规则，但还是必须走同一套安全闸
     * （大面积不点 → 找可点祖先 → 手势兜底），否则启发式一旦判错就是直接乱点。
     */
    public static boolean clickNode(AccessibilityService svc, AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (isBigArea(node, MAX_HIT_AREA_PERCENT)) return false;
        AccessibilityNodeInfo clickable = findClickable(node, 8);
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        return tapCenter(svc, node);
    }

    private static AccessibilityNodeInfo findClickable(AccessibilityNodeInfo node, int maxUp) {
        AccessibilityNodeInfo cur = node;
        int guard = 0;
        while (cur != null && guard++ < maxUp) {
            try {
                // 祖先可点但面积太大（例如整个列表项之外的外层容器）时不用它，
                // 退回点命中节点自身的中心，避免「点一下跳一屏」。
                if (cur.isClickable() && !isBigArea(cur, MAX_ANCESTOR_AREA_PERCENT)) return cur;
                cur = cur.getParent();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 用系统手势点击节点中心，兜底方案。 */
    public static boolean tapCenter(AccessibilityService svc, AccessibilityNodeInfo node) {
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() <= 0 || rect.height() <= 0) return false;
        Path p = new Path();
        p.moveTo(rect.exactCenterX(), rect.exactCenterY());
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 50);
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        try {
            return svc.dispatchGesture(gd, null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String str(CharSequence cs) {
        return cs == null ? null : cs.toString();
    }

    /**
     * 诊断用：把当前窗口里所有「带文本/描述/资源ID」的节点导成多行文本。
     *
     * <p>排查「某个 App 跳不过」时，靠 uiautomator dump 是不行的 —— 它会抢占
     * 无障碍连接，把快跳的服务顶掉重连，抓到的树和快跳真正看到的不是一回事。
     * 这里直接用快跳自己的 root 节点导，看到的就是它真正读到的东西。
     */
    public static String dumpTree(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        if (root == null) return sb.toString();
        Rect r = new Rect();
        for (Candy c : collect(root)) {
            String t = c.text, d = c.desc, i = c.id;
            boolean hasT = t != null && t.length() > 0;
            boolean hasD = d != null && d.length() > 0;
            boolean hasI = i != null && i.length() > 0;
            if (!hasT && !hasD && !hasI) continue;

            boolean clickable = false, visible = true;
            try { clickable = c.node.isClickable(); } catch (Throwable ignored) {}
            try { visible = c.node.isVisibleToUser(); } catch (Throwable ignored) {}
            String b = "?";
            try {
                c.node.getBoundsInScreen(r);
                b = r.left + "," + r.top + " " + r.width() + "x" + r.height();
            } catch (Throwable ignored) {}

            sb.append(c.cls == null ? "?" : c.cls)
                    .append(" | t=").append(t)
                    .append(" | d=").append(d)
                    .append(" | id=").append(i)
                    .append(" | click=").append(clickable)
                    .append(" | vis=").append(visible)
                    .append(" | big=").append(isBigArea(c.node, MAX_HIT_AREA_PERCENT))
                    .append(" | ").append(b)
                    .append('\n');
        }
        return sb.toString();
    }

    private static String describe(Candy c) {
        if (c.text != null && c.text.length() > 0) return c.text;
        if (c.desc != null && c.desc.length() > 0) return c.desc;
        if (c.id != null && c.id.length() > 0) return c.id;
        return c.cls == null ? "" : c.cls;
    }
}
