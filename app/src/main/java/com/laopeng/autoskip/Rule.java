package com.laopeng.autoskip;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Rule {

    public static final String ACTION_CLICK = "click";
    public static final String ACTION_CLICK_SELF = "clickSelf";
    public static final String ACTION_CLICK_CENTER = "clickCenter";
    public static final String ACTION_BACK = "back";
    public static final String ACTION_NONE = "none";

    public String name = "";
    public String action = ACTION_CLICK;
    public String matchType = "any";
    public boolean regex = false;

    public List<String> matches = new ArrayList<>();

    /**
     * 前提条件：界面里必须先存在匹配这些关键词的节点，本规则才生效。
     *
     * <p>空列表 = 无前提，老规则全部走这条路，行为完全不变。
     *
     * <p>存在的意义是承载「李跳跳」那类「出现 A 时点击 B」的规则：A 放这里当锚点，
     * B 放 matches 当点击目标。没有它就只能拿 B 单独去点，而 B 往往是「取消」
     * 「我知道了」这种满屏都有的通用文案，必然误点。
     *
     * <p>写法与上游一致：元素之间是「或」；单个元素内部再用 "|" 分「或组」、
     * "&" 分「与项」，AND 优先级高于 OR，即 {@code A&B|C} = (A 且 B) 或 C。
     */
    public List<String> anchor = new ArrayList<>();

    public List<String> excludes = new ArrayList<>();
    public List<String> activityIds = new ArrayList<>();
    public List<String> excludeActivityIds = new ArrayList<>();
    public List<String> className = new ArrayList<>();

    public int delay = 0;
    public int maxClick = 3;
    public int cooldown = 900;
    public boolean enabled = true;

    public static Rule fromJson(JSONObject o) {
        Rule r = new Rule();
        r.name = o.optString("name", "");
        r.action = o.optString("action", ACTION_CLICK);
        r.matchType = o.optString("matchType", "any");
        r.regex = o.optBoolean("regex", false);
        r.enabled = o.optBoolean("enabled", true);

        r.matches.addAll(JsonUtil.strList(o.opt("matches")));
        if (r.matches.isEmpty()) {
            r.matches.addAll(JsonUtil.strList(o.opt("match")));
        }
        r.anchor.addAll(JsonUtil.strList(o.opt("anchor")));
        r.excludes.addAll(JsonUtil.strList(o.opt("excludes")));
        if (r.excludes.isEmpty()) {
            r.excludes.addAll(JsonUtil.strList(o.opt("excludeMatches")));
        }
        r.activityIds.addAll(JsonUtil.strList(o.opt("activityIds")));
        r.excludeActivityIds.addAll(JsonUtil.strList(o.opt("excludeActivityIds")));
        r.className.addAll(JsonUtil.strList(o.opt("className")));

        r.delay = o.optInt("delay", 0);
        r.maxClick = o.optInt("maxClick", 3);
        r.cooldown = o.optInt("cooldown", 900);
        return r;
    }

    public boolean matchActivity(String act) {
        if (act == null) act = "";
        for (String e : excludeActivityIds) {
            if (act.contains(e)) return false;
        }
        if (activityIds.isEmpty()) return true;
        for (String a : activityIds) {
            if (act.contains(a)) return true;
        }
        return false;
    }

    public boolean matchText(String src) {
        if (src == null || src.length() == 0) return false;
        for (String e : excludes) {
            if (src.contains(e)) return false;
        }
        for (String m : matches) {
            if (JsonUtil.contains(src, m, regex)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        String n = name.length() > 0 ? name : String.valueOf(matches);
        return n + " [" + action + "]";
    }
}
