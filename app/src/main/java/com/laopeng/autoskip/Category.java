package com.laopeng.autoskip;

import java.util.HashMap;
import java.util.Map;

/**
 * 规则类别 —— 快跳「规则组」这一层的来源。
 *
 * <p>GKD 的层级是「订阅 → 应用 → 组 → 规则」，组名（开屏广告 / 全屏广告 / 更新提示…）
 * 是这套结构里最有用的东西：它让用户能按「这类广告我很烦」而不是按「第几条正则」
 * 去关规则。快跳早期把组这一层压平了，只剩应用 → 规则两级。
 *
 * <p>好在压平是<b>可逆</b>的：GKD 的规则命名约定是 {@code 组名-规则名}，
 * 而实测真机上 100% 的规则都带 name，所以直接按前缀就能把组还原回来 ——
 * 不需要重做规则集，也不需要用户重新导入。
 *
 * <p>类别表取 GKD 官方订阅规范的分类，是<b>封闭集合</b>：
 * 认不出的前缀一律进「其他」。这样做是为了组名可预期 ——
 * 否则「首页弹窗广告」「课程广告」这类长尾前缀会散成一堆单条规则的组，
 * 界面上比不分还乱。要加类别，改 {@link #ALL} 一行即可。
 */
public class Category {

    /** 兜底类别。 */
    public static final String OTHER = "其他";

    /**
     * 已知类别，顺序即界面里的展示顺序（按 GKD 社区的常见程度排）。
     * 出现新类别时加在这里，{@link #of(String)} 会自动认。
     */
    public static final String[] ALL = {
            "开屏广告",
            "全屏广告",
            "局部广告",
            "分段广告",
            "更新提示",
            "通知提示",
            "评价提示",
            "权限提示",
            "功能类",
            "青少年模式",
            OTHER
    };

    private static final Map<String, Integer> RANK = new HashMap<>();

    static {
        for (int i = 0; i < ALL.length; i++) RANK.put(ALL[i], i);
    }

    private Category() {
    }

    /**
     * 从规则名推断所属类别。
     *
     * <p>取 {@code '-'} 之前的前缀；<b>没有 '-' 时整个名字就是候选</b> ——
     * 这第二种情况不是边角料：真机上有 1506 条规则的名字干脆就叫「开屏广告」
     * 「更新提示」，是转换时就没带第二段。
     *
     * <p>前缀认不出就归「其他」。
     */
    public static String of(String ruleName) {
        if (ruleName == null) return OTHER;
        String n = ruleName.trim();
        if (n.length() == 0) return OTHER;
        int i = n.indexOf('-');
        String cand = (i > 0) ? n.substring(0, i).trim() : n;
        return RANK.containsKey(cand) ? cand : OTHER;
    }

    /** 是不是已知类别。 */
    public static boolean isKnown(String name) {
        return name != null && RANK.containsKey(name);
    }

    /** 排序权重：已知类别按 {@link #ALL} 的顺序，未知的排在「其他」之后。 */
    public static int rank(String name) {
        Integer r = name == null ? null : RANK.get(name);
        return r == null ? ALL.length : r;
    }
}
