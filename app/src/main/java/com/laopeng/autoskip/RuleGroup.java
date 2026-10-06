package com.laopeng.autoskip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则组 —— 快跳的三层结构里「应用」与「规则」之间的那一层，对应 GKD 的 RawGroup。
 *
 * <p>存在的意义是让开关的粒度对得上人的直觉：用户想说的是「这个应用的开屏广告别管」，
 * 而不是「第 3 条和第 7 条别跑」。
 *
 * <p>组有两个来源：
 * <ul>
 *   <li><b>显式</b> —— 规则文件里就写了 {@code groups}（GKD 原生格式），组名原样保留；</li>
 *   <li><b>派生</b> —— 规则文件是扁平的，按 {@link Category#of(String)} 从规则名前缀还原。</li>
 * </ul>
 * 两种在内存里长得一样，上层不用关心。
 */
public class RuleGroup {

    /** 组在应用内的序号，随 {@link RuleSet.AppGroup#sortGroups()} 重排。 */
    public int key;

    public String name = "";

    /** GKD 的组描述，可空。 */
    public String desc = "";

    /**
     * 规则文件里声明的默认开关。
     *
     * <p>注意这<b>不是</b>最终是否生效 —— 用户自己的关停记录存在
     * {@code Prefs.disabled_groups} 里，两者是「与」的关系，
     * 见 {@link RuleStore#rulesFor(String)}。
     */
    public boolean enable = true;

    public final List<Rule> rules = new ArrayList<>();

    public RuleGroup() {
    }

    public RuleGroup(String name) {
        this.name = name == null ? Category.OTHER : name;
    }

    /** 浅拷贝：规则对象共享，只有组容器是新的。 */
    public RuleGroup copy() {
        RuleGroup g = new RuleGroup(name);
        g.key = key;
        g.desc = desc;
        g.enable = enable;
        g.rules.addAll(rules);
        return g;
    }

    public int ruleCount() {
        return rules.size();
    }

    @Override
    public String toString() {
        return name + "（" + rules.size() + " 条）";
    }

    // ---------------- 序列化 ----------------

    public static RuleGroup fromJson(JSONObject o) {
        RuleGroup g = new RuleGroup();
        g.key = o.optInt("key", o.optInt("id", 0));
        g.name = o.optString("name", "");
        if (g.name.length() == 0) g.name = Category.OTHER;
        g.desc = o.optString("desc", o.optString("description", ""));
        g.enable = o.optBoolean("enable", o.optBoolean("enabled", true));

        JSONArray rs = o.optJSONArray("rules");
        if (rs != null) {
            for (int i = 0; i < rs.length(); i++) {
                JSONObject ro = rs.optJSONObject(i);
                if (ro == null) continue;
                Rule r = Rule.fromJson(ro);
                // 既没有点击目标也没有活动页约束的规则是废条，收进来只会白占扫描时间
                if (r.matches.isEmpty() && r.activityIds.isEmpty()) continue;
                g.rules.add(r);
            }
        }
        return g;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("key", key);
            o.put("name", name);
            if (desc != null && desc.length() > 0) o.put("desc", desc);
            o.put("enable", enable);
            JSONArray rs = new JSONArray();
            for (Rule r : rules) rs.put(RuleSet.ruleToJson(r));
            o.put("rules", rs);
        } catch (Exception ignored) {
        }
        return o;
    }
}
