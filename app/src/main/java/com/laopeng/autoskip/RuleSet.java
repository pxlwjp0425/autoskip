package com.laopeng.autoskip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则集 —— 层级：<b>应用 → 规则组 → 规则</b>（对齐 GKD 的 RawApp / RawGroup / RawRule）。
 *
 * <p>顶层永远是「一批应用节点」，每个节点下挂若干规则组，组里才是规则。
 * 全局规则是包名为 {@code *} 的那个节点，它的组对所有应用生效 ——
 * 对应 GKD 的 {@code globalGroups}。
 *
 * <p>两条兼容策略：
 * <ul>
 *   <li><b>读</b>：节点里有 {@code groups} 就按组读；只有扁平的 {@code rules} 就按
 *       {@link Category} 从规则名前缀派生组。老文件不需要任何转换就能用。</li>
 *   <li><b>写</b>：一律写成分组格式（{@code version: 2}）。第一次写会把老的扁平
 *       文件就地升级成分组格式，规则内容不变。</li>
 * </ul>
 */
public class RuleSet {

    /** 一个「应用节点」。 */
    public static class AppGroup {
        public int id;
        public String name = "";
        public String packageName = "";
        public final List<RuleGroup> groups = new ArrayList<>();

        public int ruleCount() {
            int n = 0;
            for (RuleGroup g : groups) n += g.rules.size();
            return n;
        }

        public int groupCount() {
            return groups.size();
        }

        /**
         * 去掉组内<b>内容完全相同</b>的规则，返回去掉的条数。
         *
         * <p>为什么必须做：引擎是按条独立执行的，同一条规则留两份不会报错，
         * 只会让同一个按钮被点两下 —— 这是最难排查的一类故障，因为界面上「规则数」
         * 只多了一倍，看起来还挺正常。
         *
         * <p>指纹见 {@link RuleSet#signature(Rule)}，比对的是全部参与判定的字段，
         * 所以「过滤条件不同」的两条规则不会被误判成重复。
         */
        public int dedupRules() {
            int removed = 0;
            for (RuleGroup g : groups) {
                int n = g.rules.size();
                if (n < 2) continue;
                Set<String> seen = new HashSet<>(n * 2);
                List<Rule> keep = new ArrayList<>(n);
                for (Rule r : g.rules) {
                    if (seen.add(RuleSet.signature(r))) {
                        keep.add(r);
                    } else {
                        removed++;
                    }
                }
                if (keep.size() != n) {
                    g.rules.clear();
                    g.rules.addAll(keep);
                }
            }
            return removed;
        }

        /** 所有组的规则拍平。引擎按条匹配，拿到的就是这个。 */
        public List<Rule> allRules() {
            List<Rule> out = new ArrayList<>();
            for (RuleGroup g : groups) out.addAll(g.rules);
            return out;
        }

        /** 按组名取组，没有返回 null。 */
        public RuleGroup find(String groupName) {
            if (groupName == null) return null;
            for (RuleGroup g : groups) {
                if (groupName.equals(g.name)) return g;
            }
            return null;
        }

        /** 按组名取组，没有就建一个（建完按类别顺序归位）。 */
        public RuleGroup ensure(String groupName) {
            RuleGroup g = find(groupName);
            if (g != null) return g;
            g = new RuleGroup(groupName);
            groups.add(g);
            sortGroups();
            return g;
        }

        /** 按 {@link Category#rank(String)} 排序并重新编号 —— 保证界面里组序稳定。 */
        public void sortGroups() {
            Collections.sort(groups, new Comparator<RuleGroup>() {
                @Override
                public int compare(RuleGroup a, RuleGroup b) {
                    int d = Category.rank(a.name) - Category.rank(b.name);
                    if (d != 0) return d;
                    return a.name.compareTo(b.name);
                }
            });
            for (int i = 0; i < groups.size(); i++) groups.get(i).key = i;
        }

        /** 空壳拷贝：每个组都复制一份容器，规则对象共享。 */
        public AppGroup copyShell() {
            AppGroup g = new AppGroup();
            g.id = id;
            g.name = name;
            g.packageName = packageName;
            for (RuleGroup gr : groups) g.groups.add(gr.copy());
            return g;
        }

        @Override
        public String toString() {
            String n = name.length() > 0 ? name : packageName;
            return n + "（" + groupCount() + " 组 / " + ruleCount() + " 条规则）";
        }
    }

    public final List<AppGroup> apps = new ArrayList<>();
    private final Map<String, AppGroup> index = new HashMap<>();

    /**
     * 最近一次 {@link #reindex()} 清掉了多少条组内重复规则。
     *
     * <p>正常数据永远是 0。不是 0 说明输入里本来就有重复（重复导入、重复合并、
     * 上游订阅自己写重了），值得让用户看见 —— 静默修掉虽然也能跑，但用户会以为
     * 「我明明只导入了一次，怎么规则数翻倍了」。
     */
    public int duplicateRulesRemoved;

    // ---------------- 计数 ----------------

    public int ruleCount() {
        int n = 0;
        for (AppGroup g : apps) n += g.ruleCount();
        return n;
    }

    public int groupCount() {
        int n = 0;
        for (AppGroup g : apps) n += g.groupCount();
        return n;
    }

    public boolean isEmpty() {
        return apps.isEmpty();
    }

    // ---------------- 查询 ----------------

    /**
     * 取某包名适用的全部规则：精确包名的规则 + 通配 "*" 的通用规则。
     *
     * <p>这里不做过组开关的过滤 —— 过滤在 {@link RuleStore#rulesFor(String)}，
     * 因为「哪些组被用户关掉了」这件事属于设置，不属于规则集。
     */
    public List<Rule> findRules(String pkg) {
        List<Rule> out = new ArrayList<>();
        AppGroup exact = pkg == null ? null : index.get(pkg);
        if (exact != null) out.addAll(exact.allRules());
        AppGroup any = index.get("*");
        if (any != null && any != exact) out.addAll(any.allRules());
        return out;
    }

    /** 取某包名的应用节点（不含通配节点）。 */
    public AppGroup findGroup(String pkg) {
        return pkg == null ? null : index.get(pkg);
    }

    /** 全局规则节点（包名 "*"），没有返回 null。 */
    public AppGroup globalNode() {
        return index.get("*");
    }

    public List<AppGroup> listGroups() {
        return new ArrayList<>(apps);
    }

    /** 删除某个包名的全部规则组，返回是否删掉了东西。 */
    public boolean removeApp(String pkg) {
        if (pkg == null) return false;
        boolean hit = false;
        for (int i = apps.size() - 1; i >= 0; i--) {
            if (pkg.equals(apps.get(i).packageName)) {
                apps.remove(i);
                hit = true;
            }
        }
        if (hit) reindex();
        return hit;
    }

    /**
     * 合并同一包名的重复节点、按组去重、重建索引。
     * 只在 parse() 之后（以及删除应用之后）调用 —— 重复调用是安全的（去重是幂等的），
     * 但会重新统计 {@link #duplicateRulesRemoved}。
     */
    private void reindex() {
        index.clear();
        List<AppGroup> compact = new ArrayList<>();
        for (AppGroup g : apps) {
            if (g.packageName == null || g.packageName.length() == 0) continue;
            AppGroup exist = index.get(g.packageName);
            if (exist == null) {
                index.put(g.packageName, g);
                compact.add(g);
            } else if (exist != g) {
                mergeGroupsInto(exist, g, true);
                if (exist.name == null || exist.name.length() == 0) exist.name = g.name;
            }
        }
        apps.clear();
        apps.addAll(compact);

        int dupes = 0;
        for (AppGroup g : compact) dupes += g.dedupRules();
        duplicateRulesRemoved = dupes;
    }

    /**
     * 把 src 的组并进 dst，同名组合并。
     *
     * @param dedup true = 逐条去重。同一份规则在两层各留一份会合并成双份，
     *              而引擎按条独立执行 → 同一个按钮连点两下，所以跨层合并必须去重。
     */
    private static void mergeGroupsInto(AppGroup dst, AppGroup src, boolean dedup) {
        for (RuleGroup sg : src.groups) {
            if (sg.name == null || sg.name.length() == 0) continue;
            RuleGroup dg = dst.find(sg.name);
            if (dg == null) {
                dst.groups.add(sg.copy());
                continue;
            }
            if (!dedup) {
                dg.rules.addAll(sg.rules);
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (Rule r : dg.rules) seen.add(signature(r));
            for (Rule r : sg.rules) {
                if (seen.add(signature(r))) dg.rules.add(r);
            }
        }
        dst.sortGroups();
    }

    // ---------------- 解析 ----------------

    /** 解析一份规则文件。支持 {"apps":[...]}、{"groups":[...]} 与 [{...}] 三种顶层写法。 */
    public static RuleSet parse(String rawJson) {
        RuleSet set = new RuleSet();
        if (rawJson == null || rawJson.trim().length() == 0) return set;
        String clean = JsonUtil.stripJson5(rawJson);
        JSONArray arr = null;
        try {
            String t = clean.trim();
            if (t.startsWith("[")) {
                arr = new JSONArray(t);
            } else {
                JSONObject root = new JSONObject(t);
                arr = root.optJSONArray("apps");
                if (arr == null) arr = root.optJSONArray("groups");
                if (arr == null) arr = root.optJSONArray("rules");
            }
        } catch (Exception e) {
            return set;
        }
        if (arr == null) return set;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            AppGroup g = new AppGroup();
            g.id = o.optInt("id", i);
            g.name = o.optString("name", "");
            g.packageName = o.optString("packageName", o.optString("app", o.optString("package", "")));
            if (g.packageName.length() == 0) continue;

            JSONArray gs = o.optJSONArray("groups");
            if (looksLikeGroups(gs)) {
                // 显式分组（GKD 原生格式）：组名原样保留
                for (int j = 0; j < gs.length(); j++) {
                    JSONObject go = gs.optJSONObject(j);
                    if (go == null) continue;
                    RuleGroup gr = RuleGroup.fromJson(go);
                    if (gr.rules.isEmpty()) continue;
                    g.groups.add(gr);
                }
                g.sortGroups();
            } else {
                // 扁平格式：按规则名的类别前缀派生组
                JSONArray rs = o.optJSONArray("rules");
                if (rs == null) rs = gs;   // 老代码把应用级 groups 当规则数组用
                if (rs != null) {
                    for (int j = 0; j < rs.length(); j++) {
                        JSONObject ro = rs.optJSONObject(j);
                        if (ro == null) continue;
                        Rule r = Rule.fromJson(ro);
                        if (r.matches.isEmpty() && r.activityIds.isEmpty()) continue;
                        g.ensure(Category.of(r.name)).rules.add(r);
                    }
                }
            }
            if (!g.groups.isEmpty()) set.apps.add(g);
        }
        set.reindex();
        return set;
    }

    /**
     * 判断应用节点下的 {@code groups} 数组装的是「组对象」还是「规则对象」。
     *
     * <p>靠特征字段区分：组对象一定带 {@code rules} 数组；规则对象带
     * {@code matches} / {@code match} / {@code activityIds}。两者都不像时按「组」处理
     * （宁可少读几条，也不要把组当规则读成空规则）。
     */
    private static boolean looksLikeGroups(JSONArray gs) {
        if (gs == null || gs.length() == 0) return false;
        for (int i = 0; i < gs.length(); i++) {
            JSONObject o = gs.optJSONObject(i);
            if (o == null) continue;
            if (o.optJSONArray("rules") != null) return true;
            if (o.has("matches") || o.has("match") || o.has("activityIds")) return false;
            return o.has("name");
        }
        return false;
    }

    // ---------------- 合并 ----------------

    /**
     * 把另一份规则集并入当前集合。
     *
     * <p>会为每个包名新建容器（规则对象共享），因此对同一目标集合重复调用不会让规则翻倍，
     * 也不会污染来源集合 —— 这是「订阅层 + 本地层」能安全反复合并的前提。
     *
     * <p>同包名同名组会合并到一起（而不是并列成两个「开屏广告」）。
     */
    public void merge(RuleSet other) {
        if (other == null) return;
        for (AppGroup g : other.apps) {
            if (g.packageName == null || g.packageName.length() == 0) continue;
            AppGroup exist = index.get(g.packageName);
            if (exist == null) {
                AppGroup ng = g.copyShell();
                apps.add(ng);
                index.put(ng.packageName, ng);
            } else {
                mergeGroupsInto(exist, g, false);
                if (exist.name == null || exist.name.length() == 0) exist.name = g.name;
            }
        }
    }

    /**
     * 把另一份规则集并入当前集合，<b>并按规则指纹去重</b>。
     *
     * <p>和 {@link #merge} 的区别：merge 是无条件追加，同一应用两边都有规则时会把两份
     * 规则首尾相接 —— 内容一样的两条规则叠在一起不会报错，但引擎是<b>按条独立执行</b>的，
     * 结果就是同一个按钮连点两下。所以凡是「把一整层搬进另一层」的场景都必须走去重版本。
     */
    public void mergeDedup(RuleSet other) {
        if (other == null) return;
        for (AppGroup g : other.apps) {
            if (g.packageName == null || g.packageName.length() == 0) continue;
            AppGroup exist = index.get(g.packageName);
            if (exist == null) {
                AppGroup ng = g.copyShell();
                apps.add(ng);
                index.put(ng.packageName, ng);
            } else {
                mergeGroupsInto(exist, g, true);
                if (exist.name == null || exist.name.length() == 0) exist.name = g.name;
            }
        }
    }

    /**
     * 规则指纹：内容一致即视为同一条规则。用于 {@link #mergeDedup} 判重。
     *
     * <p>分隔符用 U+0001 / U+0002 这类不可能出现在规则文本里的控制字符，
     * 避免 ["a|b"] 和 ["a","b"] 拼出同一个字符串而误判为重复。
     */
    public static String signature(Rule r) {
        if (r == null) return "";
        StringBuilder sb = new StringBuilder(96);
        sb.append(r.name).append('\u0001')
                .append(r.action).append('\u0001')
                .append(r.matchType).append('\u0001')
                .append(r.regex ? '1' : '0').append('\u0001')
                .append(r.enabled ? '1' : '0');
        appendList(sb, r.matches);
        appendList(sb, r.anchor);
        appendList(sb, r.excludes);
        appendList(sb, r.activityIds);
        appendList(sb, r.excludeActivityIds);
        appendList(sb, r.className);
        sb.append('\u0001').append(r.delay).append('\u0001')
                .append(r.maxClick).append('\u0001').append(r.cooldown);
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, List<String> l) {
        sb.append('\u0001');
        if (l == null) return;
        for (String s : l) sb.append(s).append('\u0002');
    }

    // ---------------- 序列化 ----------------

    /** 当前落盘格式版本。1 = 扁平（只读兼容），2 = 分组。 */
    public static final int FORMAT_VERSION = 2;

    private static JSONArray toArray(List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) {
            if (s != null) a.put(s);
        }
        return a;
    }

    /** 单条规则 → JSON。RuleGroup 序列化时也走这里，保证两处字段一致。 */
    public static JSONObject ruleToJson(Rule r) {
        JSONObject ro = new JSONObject();
        try {
            ro.put("name", r.name);
            ro.put("action", r.action);
            ro.put("matchType", r.matchType);
            ro.put("regex", r.regex);
            ro.put("matches", toArray(r.matches));
            if (!r.anchor.isEmpty()) ro.put("anchor", toArray(r.anchor));
            if (!r.excludes.isEmpty()) ro.put("excludes", toArray(r.excludes));
            if (!r.activityIds.isEmpty()) ro.put("activityIds", toArray(r.activityIds));
            if (!r.excludeActivityIds.isEmpty())
                ro.put("excludeActivityIds", toArray(r.excludeActivityIds));
            if (!r.className.isEmpty()) ro.put("className", toArray(r.className));
            ro.put("delay", r.delay);
            ro.put("maxClick", r.maxClick);
            ro.put("cooldown", r.cooldown);
            ro.put("enabled", r.enabled);
        } catch (Exception ignored) {
        }
        return ro;
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", FORMAT_VERSION);
            JSONArray arr = new JSONArray();
            for (AppGroup g : apps) {
                if (g.groups.isEmpty()) continue;
                JSONObject go = new JSONObject();
                go.put("id", g.id);
                go.put("name", g.name);
                go.put("packageName", g.packageName);
                JSONArray gs = new JSONArray();
                for (RuleGroup gr : g.groups) gs.put(gr.toJson());
                go.put("groups", gs);
                arr.put(go);
            }
            root.put("apps", arr);
            return root.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
