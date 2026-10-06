package com.laopeng.autoskip;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/**
 * 开屏广告的「启发式」识别 —— 规则库里没有这个应用时的兜底。
 *
 * <h3>解决什么问题</h3>
 * 规则库（订阅 + 本地 + 出厂）覆盖不到的应用，以前是彻底没人管：开屏广告
 * 照样要点。而开屏广告的跳过按钮在所有 App 上长得都差不多，是可以靠
 * 「形状」认出来的，不需要知道包名。
 *
 * <h3>识别依据（四条必须同时成立）</h3>
 * <ol>
 *   <li><b>词</b>：文字的 text / contentDescription / 资源 ID 里带强跳过词
 *       （跳过 / 跳過 / 略过 / skip）。ID 也参与匹配是有意的 —— 阿里系「美数」
 *       SDK 的跳过按钮就是 {@code id=.../ms_skipView} 且 text/desc 全为空，
 *       只认文字的规则永远命中不了它；</li>
 *   <li><b>小</b>：命中节点占屏面积 ≤ {@link #MAX_AREA_PERCENT}%。跳过按钮都是
 *       角上的小控件，超过这个比例基本都是列表/容器碰巧带了同样的文案；</li>
 *   <li><b>角</b>：节点中心必须落在<u>右上角</u>或<u>右下角</u>。开屏跳过按钮
 *       的位置是行业惯例，而「跳过」这两个字本身在别处也常见（引导页的跳过、
 *       教程的跳过）—— 那些通常在底部中间，靠这条能挡掉；</li>
 *   <li><b>短</b>：文字长度 ≤ {@link #MAX_TEXT_LEN} 字。「点击跳过广告，开启
 *       精彩内容」这种长句是文案不是按钮。</li>
 * </ol>
 *
 * <h3>另外三重时间/次数闸门在调用方（{@link SkipService}）</h3>
 * 只在「刚进这个 App 的几秒内」生效、每屏最多一次、每次前台会话最多两次。
 * 开屏广告只出现在启动瞬间，这三条能挡掉绝大多数「用着用着突然被点一下」。
 *
 * <h3>为什么默认不去点纯倒计时数字</h3>
 * {@code "3"} {@code "5s"} 这种倒计时按钮很常见，但一个孤零零的「3」在别的
 * 界面里可能是页码、数量、评分。没有足够的上下文，宁可漏也不能点错。
 */
public class SplashAuto {

    /**
     * 强跳过词。刻意不收录「关闭」「取消」这类词：它们在任何界面都满屏都是，
     * 收进来就等于给自己埋雷，而它们在开屏场景里本来也少见（开屏清一色是「跳过」）。
     */
    private static final String[] SKIP_WORDS = {
            "跳过广告", "跳过本次广告", "跳过此广告", "点击跳过", "我要跳过", "跳过",
            "跳過", "略过", "skip"
    };

    /** 命中节点占屏面积的绝对上限（%）。比规则引擎的 40% 严格得多。 */
    private static final int MAX_AREA_PERCENT = 12;
    /** 文字/描述的长度上限，超过就认定是文案而不是按钮。 */
    private static final int MAX_TEXT_LEN = 12;
    /** 向上找可点祖先的最大层数。 */
    private static final int MAX_CLICK_UP = 4;

    /** 命中结果。 */
    public static final class Result {
        public AccessibilityNodeInfo node;
        /** 「文字「跳过」」「ID「…/ms_skipView」」这种，直接写进日志。 */
        public String detail = "";
    }

    /**
     * 在当前窗口里找开屏跳过按钮。找不到返回 {@code null}。
     *
     * <p>只做「认按钮」这一件事，时间窗口 / 次数限流都由调用方掌握 ——
     * 这样引擎保持无状态，好测也好读。
     */
    public static Result detect(AccessibilityNodeInfo root) {
        return detect(root, null);
    }

    /**
     * @param outDesc 非空时把「为什么没命中」写进去，供诊断用（手机上看不到返回值）。
     */
    public static Result detect(AccessibilityNodeInfo root, StringBuilder outDesc) {
        if (root == null) return null;

        int w = 0, h = 0;
        try {
            android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        } catch (Throwable ignored) {
        }
        if (w <= 0 || h <= 0) return null;

        List<RuleEngine.Candy> nodes = RuleEngine.collect(root);
        if (nodes.isEmpty()) return null;

        int wordHit = 0, areaRej = 0, posRej = 0, clickRej = 0;
        Rect r = new Rect();

        for (RuleEngine.Candy c : nodes) {
            String why = matchWord(c);
            if (why == null) continue;
            wordHit++;

            if (RuleEngine.isBigArea(c.node, MAX_AREA_PERCENT)) {
                areaRej++;
                continue;
            }
            boolean visible;
            try {
                visible = c.node.isVisibleToUser();
            } catch (Throwable t) {
                continue;
            }
            if (!visible) continue;

            try {
                c.node.getBoundsInScreen(r);
            } catch (Throwable t) {
                continue;
            }
            if (r.width() <= 0 || r.height() <= 0) continue;

            // 面积再算一遍：这里用的是屏幕绝对像素，比 isBigArea 更严，
            // 顺便挡掉「bounds 拿到了但明显是整个页面」的情况。
            long screenArea = (long) w * h;
            if (screenArea <= 0) continue;
            if ((long) r.width() * r.height() * 100L / screenArea > MAX_AREA_PERCENT) {
                areaRej++;
                continue;
            }

            float cx = r.exactCenterX(), cy = r.exactCenterY();
            boolean topRight = cy < h * 0.30f && cx > w * 0.55f;
            boolean bottomRight = cy > h * 0.72f && cx > w * 0.55f;
            if (!topRight && !bottomRight) {
                posRej++;
                continue;
            }

            if (!hasClickable(c.node, MAX_CLICK_UP)) {
                clickRej++;
                continue;
            }

            Result res = new Result();
            res.node = c.node;
            res.detail = why;
            if (outDesc != null) {
                outDesc.append("命中 ").append(why).append(" @ ")
                        .append(r.left).append(',').append(r.top)
                        .append(' ').append(r.width()).append('x').append(r.height());
            }
            return res;
        }

        if (outDesc != null) {
            outDesc.append("无命中（含跳过词 ").append(wordHit)
                    .append(" 个：面积挡 ").append(areaRej)
                    .append(" / 位置挡 ").append(posRej)
                    .append(" / 不可点挡 ").append(clickRej).append("）");
        }
        return null;
    }

    /**
     * 节点的 text / desc / id 里有没有强跳过词。命中返回一句人话描述，否则 null。
     *
     * <p>顺序按「可信度」排：文字 &gt; 描述 &gt; ID。文字和描述要过长度闸，
     * ID 不用 —— 资源 ID 天生就长，{@code com.x.y:id/tv_main_splash_skip} 是正常长度。
     */
    private static String matchWord(RuleEngine.Candy c) {
        if (c.text != null) {
            String w = pickWord(c.text);
            if (w != null && c.text.trim().length() <= MAX_TEXT_LEN) return "文字「" + w + "」";
        }
        if (c.desc != null) {
            String w = pickWord(c.desc);
            if (w != null && c.desc.trim().length() <= MAX_TEXT_LEN + 4) return "描述「" + w + "」";
        }
        if (c.id != null) {
            String w = pickWord(c.id);
            if (w != null) return "ID「" + w + "」";
        }
        return null;
    }

    /** 单串文本里找第一个命中的强跳过词。 */
    private static String pickWord(String s) {
        if (s == null || s.length() == 0) return null;
        String lower = null;
        for (String w : SKIP_WORDS) {
            if ("skip".equals(w)) {
                // 英文必须忽略大小写：SKIP / Skip / skip 都出现过
                if (lower == null) lower = s.toLowerCase(Locale.ROOT);
                if (lower.contains("skip")) return "skip";
            } else if (s.contains(w)) {
                return w;
            }
        }
        return null;
    }

    /** 节点自己或近祖先是可点的（且祖先不能是那种一大片的外层容器）。 */
    private static boolean hasClickable(AccessibilityNodeInfo node, int maxUp) {
        AccessibilityNodeInfo cur = node;
        int guard = 0;
        while (cur != null && guard++ < maxUp) {
            try {
                if (cur.isClickable() && !RuleEngine.isBigArea(cur, 70)) return true;
                cur = cur.getParent();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }
}
