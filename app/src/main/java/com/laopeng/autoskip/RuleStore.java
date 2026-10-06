package com.laopeng.autoskip;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则仓库，分两层：
 * <ul>
 *   <li><b>订阅层</b> —— 由订阅地址拉取，整份覆盖式更新；</li>
 *   <li><b>本地层</b> —— 用户导入的文件 + 手动添加的规则，只增不减，订阅更新不影响。</li>
 * </ul>
 * 实际生效规则 = 本地层 ∪ 订阅层（本地排在前面）。
 */
public class RuleStore {

    public interface Callback {
        void done(boolean ok, String msg);
    }

    private static RuleStore sInstance;

    private final Context ctx;
    private final Prefs prefs;

    private volatile RuleSet remoteSet;
    private volatile RuleSet localSet;
    private volatile RuleSet builtinSet;
    private volatile RuleSet mergedSet;

    private RuleStore(Context c) {
        ctx = c.getApplicationContext();
        prefs = Prefs.get(ctx);
    }

    public static synchronized RuleStore get(Context c) {
        if (sInstance == null) sInstance = new RuleStore(c.getApplicationContext());
        return sInstance;
    }

    // ---------------- 层：订阅 / 本地 ----------------

    private RuleSet remote() {
        RuleSet s = remoteSet;
        if (s != null) return s;
        // 注意：这里**故意不在** rules_json 为空时回退到 BuiltinRules.JSON。
        // 出厂层（builtin()）已经是独立且永远参与匹配的一层，再回退一次会让内置规则
        // 被合并两遍 —— 同一个「跳过」按钮连点两下。空就是空。
        s = RuleSet.parse(prefs.getRulesJson());
        remoteSet = s;
        return s;
    }

    /**
     * 出厂兜底规则。和「订阅层」不同，它<b>永远参与匹配</b>，不会被订阅更新整份覆盖。
     *
     * <p>订阅层一拉就是几百个应用，`remote()` 只在订阅为空时才回退到内置规则，
     * 所以内置规则一旦写进 remote 就会被整份盖掉 —— 修一个通用缺陷（例如「跳过」
     * 按钮是纯图标、没有文本）就得靠改订阅文件，这在升级安装时是拿不到的。
     * 因此这里单独立一层，保证出厂修复在任何情况下都生效。
     */
    private RuleSet builtin() {
        RuleSet s = builtinSet;
        if (s != null) return s;
        s = RuleSet.parse(BuiltinRules.JSON);
        builtinSet = s;
        return s;
    }

    private RuleSet local() {
        RuleSet s = localSet;
        if (s != null) return s;
        s = RuleSet.parse(prefs.getLocalRulesJson());
        localSet = s;
        return s;
    }

    /** 合并视图：本地优先 + 出厂兜底 + 订阅，仅用于计数与展示。 */
    public synchronized RuleSet getRules() {
        RuleSet m = mergedSet;
        if (m != null) return m;
        m = new RuleSet();
        m.merge(local());
        m.merge(builtin());
        m.merge(remote());
        mergedSet = m;
        return m;
    }

    /**
     * 取某应用真正要跑的规则。
     * 本地独占打开且该应用有本地规则时，只返回本地规则 + 出厂兜底规则，订阅规则全部让位。
     * 非独占时直接走合并视图，省掉每次界面变化都要拼两次列表的开销。
     */
    public List<Rule> rulesFor(String pkg) {
        Set<String> off = prefs.getDisabledGroups();
        List<Rule> out = new ArrayList<>();
        if (!prefs.isLocalExclusive()) {
            addRules(out, local(), pkg, off);
            addRules(out, builtin(), pkg, off);
            addRules(out, remote(), pkg, off);
            return out;
        }
        RuleSet l = local();
        RuleSet.AppGroup localExact = l.findGroup(pkg);
        addRules(out, builtin(), pkg, off);
        addRules(out, l, pkg, off);
        if (localExact != null) return out;
        addRules(out, remote(), pkg, off);
        return out;
    }

    /**
     * 把某一层里适用于 {@code pkg} 的规则追加进 out，<b>跳过被关停的组</b>。
     *
     * <p>「关停」有两个来源，取「与」：规则文件里把组写成 {@code enable:false}
     * （订阅作者的意思），以及用户自己的关停记录（{@link Prefs#getDisabledGroups()}，
     * 键是 {@code 包名#组名}，包名位写 {@code *} 表示全局默认）。
     *
     * <p>{@code off} 由调用方传进来而不是每次现取 —— SharedPreferences 的
     * getStringSet 要拷一份 HashSet，而这里一趟要查十几个组。
     */
    private void addRules(List<Rule> out, RuleSet set, String pkg, Set<String> off) {
        RuleSet.AppGroup exact = set.findGroup(pkg);
        if (exact != null) appendGroups(out, exact, pkg, off);
        RuleSet.AppGroup any = set.globalNode();
        if (any != null && any != exact) appendGroups(out, any, pkg, off);
    }

    private void appendGroups(List<Rule> out, RuleSet.AppGroup node, String pkg, Set<String> off) {
        for (RuleGroup g : node.groups) {
            if (!g.enable) continue;
            if (!off.isEmpty()
                    && (off.contains(Prefs.groupKey(pkg, g.name))
                    || off.contains(Prefs.groupKey("*", g.name)))) {
                continue;
            }
            out.addAll(g.rules);
        }
    }

    public synchronized int ruleCount() {
        return getRules().ruleCount();
    }

    /** 合并视图的规则组总数。 */
    public synchronized int groupCount() {
        return getRules().groupCount();
    }

    /**
     * 某应用跑起来会碰到多少规则：{@code [组数, 条数]}。
     *
     * <p>口径必须和 {@link #groupsFor(String)}（应用详情页）一致，<b>含全局组的贡献</b>
     * —— 详情页里「开屏广告」这一行的条数是「这个应用自己的 + 全局同名的」，
     * 列表行要是只算前者，同一个应用在两处会显示两个数（实测 QQ：列表 19、详情 24），
     * 用户只会觉得是 bug。
     *
     * <p>所以这里按组名归并：三层 × （本应用节点 + 全局节点）里同名的组合成一条，
     * 条数相加、组数去重。同名合并的语义与 {@link #groupsFor} 的 {@code collect} 相同。
     *
     * <p>不走 {@code groupsFor} 是为了不碰 SharedPreferences —— 列表页每行都要问一次，
     * 而 {@code getStringSet} 每次都要拷一份 HashSet，三百行就是三百次白拷。
     */
    public int[] appRuleCounts(String pkg) {
        try {
            Map<String, Integer> byName = new LinkedHashMap<>();
            RuleSet[] layers = {local(), builtin(), remote()};
            for (RuleSet s : layers) {
                countInto(byName, s.findGroup(pkg));
                countInto(byName, s.globalNode());
            }
            int rules = 0;
            for (Integer v : byName.values()) rules += v;
            return new int[]{byName.size(), rules};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    private static void countInto(Map<String, Integer> byName, RuleSet.AppGroup node) {
        if (node == null) return;
        for (RuleGroup g : node.groups) {
            Integer c = byName.get(g.name);
            byName.put(g.name, (c == null ? 0 : c) + g.rules.size());
        }
    }

    /**
     * 该应用有没有「专属规则组」——注意通配组 {@code *} 不算。
     *
     * <p>三层里任意一层给它写了专属规则就算有。用来给「自动识别」划边界：
     * 只有上游没人管过的应用才轮到启发式引擎出手，避免和精调过的规则抢着点。
     */
    public boolean hasAppRules(String pkg) {
        if (pkg == null || pkg.length() == 0 || "*".equals(pkg)) return false;
        return local().findGroup(pkg) != null
                || builtin().findGroup(pkg) != null
                || remote().findGroup(pkg) != null;
    }

    public synchronized int appCount() {
        return getRules().apps.size();
    }

    // ---------------- 分层计数（界面摘要用，别拿合并数冒充某一层） ----------------

    /** 订阅层自身的应用数。 */
    public synchronized int remoteAppCount() {
        return remote().apps.size();
    }

    /** 订阅层自身的规则数。 */
    public synchronized int remoteRuleCount() {
        return remote().ruleCount();
    }

    /** 出厂层自身的规则数（应用数包含通配组 `*`，单独列出来没意义）。 */
    public synchronized int builtinRuleCount() {
        return builtin().ruleCount();
    }

    /** 订阅层自身的规则组数。 */
    public synchronized int remoteGroupCount() {
        return remote().groupCount();
    }

    /** 本地层自身的规则组数。 */
    public synchronized int localGroupCount() {
        return local().groupCount();
    }

    // ---------------- 分组视图（界面用） ----------------

    /**
     * 一个规则组在界面上的样子。
     *
     * <p>三层里同名同应用的组会折成一条 —— 用户关心的是「淘宝有哪些组、
     * 哪几个关着」，不关心这个组是订阅给的还是本地给的（来源只在脚注里提一句）。
     */
    public static final class GroupView {
        public String name = "";
        public int ruleCount;

        /** 规则文件里声明的开关；多层叠加，任一层写了 false 就是 false。 */
        public boolean fileEnabled = true;
        /** 用户自己关的（精确到该应用，不含全局默认）。 */
        public boolean userDisabled;
        /** 被全局默认（键 {@code *#组名}）关掉的。 */
        public boolean globalOff;

        /** 该组在应用节点上也有。 */
        public boolean inApp;
        /** 该组来自全局规则节点。 */
        public boolean inGlobal;

        /** 来源层，如「订阅+出厂」。 */
        public String sources = "";

        /** 最终是否生效 —— 与 {@link #rulesFor(String)} 的过滤口径保持一致。 */
        public boolean isOn() {
            return fileEnabled && !userDisabled && !globalOff;
        }
    }

    /**
     * 某应用看到的全部规则组：该应用的组 + 全局组，按类别顺序排。
     *
     * <p>应用详情页直接用这个列表画开关。
     */
    public synchronized List<GroupView> groupsFor(String pkg) {
        Map<String, GroupView> map = new LinkedHashMap<>();
        collect(map, local(), pkg, false, "本地");
        collect(map, builtin(), pkg, false, "出厂");
        collect(map, remote(), pkg, false, "订阅");
        collect(map, local(), "*", true, "本地");
        collect(map, builtin(), "*", true, "出厂");
        collect(map, remote(), "*", true, "订阅");

        List<GroupView> out = sort(map);
        for (GroupView v : out) {
            v.userDisabled = prefs.isGroupDisabledExact(pkg, v.name);
            v.globalOff = prefs.isGroupDisabled("*", v.name);
        }
        return out;
    }

    /**
     * 全局规则组：包名 {@code *} 的节点，对所有应用生效。
     *
     * <p>这里不标 {@code globalOff} —— 它自己就是全局默认，
     * 用户直接关它，不需要再叠一层。
     */
    public synchronized List<GroupView> globalGroups() {
        Map<String, GroupView> map = new LinkedHashMap<>();
        collect(map, local(), "*", true, "本地");
        collect(map, builtin(), "*", true, "出厂");
        collect(map, remote(), "*", true, "订阅");

        List<GroupView> out = sort(map);
        for (GroupView v : out) {
            v.userDisabled = prefs.isGroupDisabledExact("*", v.name);
            v.globalOff = false;
        }
        return out;
    }

    private void collect(Map<String, GroupView> map, RuleSet set, String nodePkg,
                         boolean global, String source) {
        RuleSet.AppGroup node = set.findGroup(nodePkg);
        if (node == null) return;
        for (RuleGroup g : node.groups) {
            GroupView v = map.get(g.name);
            if (v == null) {
                v = new GroupView();
                v.name = g.name;
                map.put(g.name, v);
            }
            v.ruleCount += g.rules.size();
            if (!g.enable) v.fileEnabled = false;
            if (global) {
                v.inGlobal = true;
            } else {
                v.inApp = true;
            }
            if (v.sources.indexOf(source) < 0) {
                v.sources = v.sources.length() == 0 ? source : v.sources + "+" + source;
            }
        }
    }

    private static List<GroupView> sort(Map<String, GroupView> map) {
        List<GroupView> out = new ArrayList<>(map.values());
        java.util.Collections.sort(out, new java.util.Comparator<GroupView>() {
            @Override
            public int compare(GroupView a, GroupView b) {
                int d = Category.rank(a.name) - Category.rank(b.name);
                return d != 0 ? d : a.name.compareTo(b.name);
            }
        });
        return out;
    }

    // ---------------- 订阅层 → 本地层 转存 ----------------

    public static final class MoveResult {
        /** 从订阅层搬过来的总量。 */
        public int movedApps, movedRules;
        /** 搬完之后本地层的总量。 */
        public int totalApps, totalRules;
        /** 本地层实际新增的条数（去重后可能小于 movedRules）。 */
        public int addedRules;
        /** 订阅层是否已被清空（正常情况下都是 true）。 */
        public boolean remoteCleared;
    }

    /**
     * 把当前订阅层整体转存为本地规则。
     *
     * <p>为什么必须连订阅层一起清空：{@code rulesFor()} 取的是三层合并视图
     * （本地 ∪ 出厂 ∪ 订阅），只搬不清等于同一份规则在两层各留一份，会被合并成
     * 双份 —— 引擎按条独立执行，表现就是同一个按钮连点两下。
     *
     * <p>合并走去重版（{@link RuleSet#mergeDedup}），所以本地层原有的规则不会被冲掉，
     * 重复的也不会叠成两份。
     *
     * @return 订阅层没有规则时返回 null。
     */
    public synchronized MoveResult localizeRemote() {
        RuleSet rem = RuleSet.parse(prefs.getRulesJson());
        if (rem.apps.isEmpty() || rem.ruleCount() == 0) return null;

        RuleSet cur = RuleSet.parse(prefs.getLocalRulesJson());
        int beforeRules = cur.ruleCount();
        cur.mergeDedup(rem);
        prefs.setLocalRulesJson(cur.toJson());
        prefs.setRulesJson("");

        invalidateLocal();
        remoteSet = null;
        mergedSet = null;

        MoveResult r = new MoveResult();
        r.movedApps = rem.apps.size();
        r.movedRules = rem.ruleCount();
        r.totalApps = cur.apps.size();
        r.totalRules = cur.ruleCount();
        r.addedRules = r.totalRules - beforeRules;
        r.remoteCleared = true;
        return r;
    }

    public synchronized int localAppCount() {
        return local().apps.size();
    }

    public synchronized int localRuleCount() {
        return local().ruleCount();
    }

    public synchronized List<RuleSet.AppGroup> localGroups() {
        return local().listGroups();
    }

    // ---------------- 本地层增删 ----------------

    /**
     * 导入一段规则 JSON，并入本地层。
     *
     * @return 解析成功返回这次导入的部分（应用数/规则数可读），解析失败返回 null。
     */
    public synchronized RuleSet importLocal(String rawJson) {
        RuleSet add = RuleSet.parse(rawJson);
        if (add.apps.isEmpty() || add.ruleCount() == 0) return null;
        RuleSet cur = RuleSet.parse(prefs.getLocalRulesJson());
        // 必须走 mergeDedup：merge 是无条件追加，同一份文件导入两次会让每条规则
        // 各留两份（引擎按条独立执行 → 同一个按钮连点两下）。这是历史 bug 的根因。
        cur.mergeDedup(add);
        prefs.setLocalRulesJson(cur.toJson());
        invalidateLocal();
        return add;
    }

    /** 手动添加一条规则到本地层。规则按名字归到对应的类别组里。 */
    public synchronized void addLocalRule(String pkg, String appName, Rule rule) {
        RuleSet one = new RuleSet();
        RuleSet.AppGroup g = new RuleSet.AppGroup();
        g.name = appName == null ? "" : appName;
        g.packageName = pkg;
        g.ensure(Category.of(rule.name)).rules.add(rule);
        one.apps.add(g);
        RuleSet cur = RuleSet.parse(prefs.getLocalRulesJson());
        cur.mergeDedup(one);
        prefs.setLocalRulesJson(cur.toJson());
        invalidateLocal();
    }

    public synchronized boolean removeLocalApp(String pkg) {
        RuleSet cur = RuleSet.parse(prefs.getLocalRulesJson());
        if (!cur.removeApp(pkg)) return false;
        prefs.setLocalRulesJson(cur.toJson());
        invalidateLocal();
        return true;
    }

    public synchronized void clearLocal() {
        prefs.setLocalRulesJson("");
        invalidateLocal();
    }

    /**
     * 把本地层导出为可复制 / 可落盘的 JSON 文本。
     *
     * <p>直接用缓存的解析结果而不是重新 parse 一遍 prefs：本地层动辄几百 KB、
     * 几千条规则，重新解析要几百毫秒，而 {@code localSet} 在任何一次增删后都会被
     * {@link #invalidateLocal()} 清掉，缓存与 prefs 必然一致。
     */
    public synchronized String exportLocal() {
        return local().toJson();
    }

    private void invalidateLocal() {
        localSet = null;
        mergedSet = null;
    }

    public synchronized void reload() {
        remoteSet = null;
        localSet = null;
        builtinSet = null;
        mergedSet = null;
        getRules();
    }

    // ---------------- 落盘格式升级 ----------------

    public static final class UpgradeResult {
        public boolean localUpgraded, remoteUpgraded;
        public int apps, groups, rules;
        /** 顺手清掉的组内重复规则条数（正常是 0）。 */
        public int dupesRemoved;

        public boolean changed() {
            return localUpgraded || remoteUpgraded;
        }
    }

    /**
     * 规范化盘上的规则文件：升到分组格式（{@code version: 2}）并清掉组内重复规则。
     *
     * <p>老的扁平文件本来就能正常读（解析时按类别派生组），所以「升级格式」这一步
     * <b>不影响功能</b>，只是让盘上存的东西和内存里的模型对齐 —— 导出的文件带显式
     * {@code groups}，组名、组开关都能完整往返，而不是每次靠规则名前缀猜。
     *
     * <p>真正修 bug 的是<b>去重</b>那一半：历史版本里 {@code importLocal()} 用的是
     * 非去重合并，同一份文件导入两次就会让每条规则各留两份，而引擎按条独立执行
     * ——表现是同一个按钮连点两下。「规则数翻倍」在界面上看着还挺正常，极难排查，
     * 所以这里顺手修掉，并在返回值里报出来让用户知道。
     *
     * <p>是否要跑由 {@link Prefs#getRuleFormat()} 决定，不靠猜文件内容：
     * 跑过一次就把版本号写死，之后启动不再 parse（本地层几百 KB，parse 一次几百毫秒）。
     *
     * <p>写入前做完整性校验，对不上就<b>放弃</b>并保持原文件 —— 宁可不升级，
     * 也不能把用户攒了几百个应用的规则写坏。
     */
    public synchronized UpgradeResult upgradeFormat() {
        UpgradeResult r = new UpgradeResult();
        boolean stale = prefs.getRuleFormat() < RuleSet.FORMAT_VERSION;

        String lj = prefs.getLocalRulesJson();
        if (lj.trim().length() > 0 && stale) {
            UpgradeResult one = rewrite(lj, true);
            if (one != null) {
                r.localUpgraded = true;
                r.apps = one.apps;
                r.groups = one.groups;
                r.rules = one.rules;
                r.dupesRemoved = one.dupesRemoved;
            }
        }
        String rj = prefs.getRulesJson();
        if (rj.trim().length() > 0 && stale) {
            UpgradeResult one = rewrite(rj, false);
            if (one != null) {
                r.remoteUpgraded = true;
                if (!r.localUpgraded) {
                    r.apps = one.apps;
                    r.groups = one.groups;
                    r.rules = one.rules;
                    r.dupesRemoved = one.dupesRemoved;
                }
            }
        }

        // 只有真的成功了才记账，否则下次启动还会重试（校验失败时文件没动过）
        if (!stale || r.changed() || (lj.trim().length() == 0 && rj.trim().length() == 0)) {
            prefs.setRuleFormat(RuleSet.FORMAT_VERSION);
        }
        if (r.changed()) {
            localSet = null;
            remoteSet = null;
            mergedSet = null;
        }
        return r;
    }

    /** @return 升级成功返回统计，校验不通过返回 null（原文件不动）。 */
    private UpgradeResult rewrite(String json, boolean localLayer) {
        RuleSet before = RuleSet.parse(json);
        if (before.apps.isEmpty()) return null;
        int dupes = before.duplicateRulesRemoved;

        String upgraded = before.toJson();
        RuleSet after = RuleSet.parse(upgraded);
        if (after.apps.size() != before.apps.size()
                || after.ruleCount() != before.ruleCount()
                || after.duplicateRulesRemoved != 0
                || after.findRules("*").size() != before.findRules("*").size()) {
            return null;
        }
        if (localLayer) {
            prefs.setLocalRulesJson(upgraded);
        } else {
            prefs.setRulesJson(upgraded);
        }
        UpgradeResult r = new UpgradeResult();
        r.apps = after.apps.size();
        r.groups = after.groupCount();
        r.rules = after.ruleCount();
        r.dupesRemoved = dupes;
        return r;
    }

    /**
     * 「恢复出厂内置规则」—— 把订阅层<b>清空</b>即可。
     *
     * <p>出厂层本来就永远参与匹配，所以清空后生效的正是内置那几条；
     * 反过来如果把 {@code BuiltinRules.JSON} 写进订阅层，内置规则就会被合并两遍。
     */
    public synchronized void restoreBuiltin() {
        prefs.setRulesJson("");
        remoteSet = null;
        mergedSet = null;
    }

    // ---------------- 订阅层更新 ----------------

    /** 后台线程拉取所有订阅地址并合并覆盖订阅层。本地规则不受影响。 */
    public void update(final List<String> urls, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<RuleSet> sets = new ArrayList<>();
                final List<String> errors = new ArrayList<>();
                if (urls != null) {
                    for (String u : urls) {
                        try {
                            String body = httpGet(u);
                            RuleSet s = RuleSet.parse(body);
                            if (s.apps.isEmpty() || s.ruleCount() == 0) {
                                errors.add(shortUrl(u) + " 未解析出规则");
                            } else {
                                sets.add(s);
                            }
                        } catch (Exception e) {
                            errors.add(shortUrl(u) + " " + e.getMessage());
                        }
                    }
                }

                final RuleSet merged = new RuleSet();
                for (RuleSet s : sets) merged.merge(s);

                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        if (!sets.isEmpty()) {
                            prefs.setRulesJson(merged.toJson());
                            prefs.setLastUpdate(System.currentTimeMillis());
                            remoteSet = null;
                            mergedSet = null;
                            int localN = localRuleCount();
                            String msg = "已更新 " + merged.apps.size() + " 个应用 / " + merged.ruleCount() + " 条规则";
                            if (localN > 0) msg += "（本地 " + localN + " 条保留）";
                            LogStore.get(ctx).add("规则更新成功：" + merged.apps.size() + " 应用 / "
                                    + merged.ruleCount() + " 规则");
                            if (cb != null) cb.done(true, msg);
                        } else {
                            String m = errors.isEmpty() ? "没有填写订阅地址" : errors.get(0);
                            LogStore.get(ctx).add("规则更新失败：" + m);
                            if (cb != null) cb.done(false, m);
                        }
                    }
                });
            }
        }, "autoskip-rule-update").start();
    }

    private static String shortUrl(String u) {
        if (u == null) return "";
        return u.length() > 36 ? u.substring(0, 36) + "…" : u;
    }

    public static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(25000);
            conn.setRequestProperty("User-Agent", "AutoSkip/1.0 (Android)");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = conn.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 读取 content:// 文件流的文本（导入本地规则文件用）。 */
    public static String readAll(InputStream in) throws Exception {
        if (in == null) throw new Exception("无法打开文件");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        byte[] data = bos.toByteArray();
        // 去掉 UTF-8 BOM，否则 JSON 解析会失败
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            byte[] t = new byte[data.length - 3];
            System.arraycopy(data, 3, t, 0, t.length);
            data = t;
        }
        return new String(data, "UTF-8");
    }
}
