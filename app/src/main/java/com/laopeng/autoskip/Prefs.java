package com.laopeng.autoskip;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public class Prefs {

    private static final String FILE = "autoskip_cfg";
    private static Prefs sInstance;

    private final SharedPreferences sp;

    private Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static synchronized Prefs get(Context c) {
        if (sInstance == null) {
            sInstance = new Prefs(c.getApplicationContext());
        }
        return sInstance;
    }

    public boolean isRunning() {
        return sp.getBoolean("running", true);
    }

    public void setRunning(boolean v) {
        sp.edit().putBoolean("running", v).apply();
    }

    /**
     * 前台常驻（常驻通知 + 前台服务优先级）。
     * 默认开启 —— 跳广告 App 最常见的故障就是被系统杀后台，默认关掉反而是坑。
     */
    public boolean isKeepAlive() {
        return sp.getBoolean("keep_alive", true);
    }

    public void setKeepAlive(boolean v) {
        sp.edit().putBoolean("keep_alive", v).apply();
    }

    /**
     * root 增强（锁内存 + 无障碍自愈 + 免手动解受限设置 + 开机守护模块）。
     * 默认关闭 —— 不是每台机器都有 root，打开前要先确认 su 可用。
     */
    public boolean isRootEnhance() {
        return sp.getBoolean("root_enhance", false);
    }

    public void setRootEnhance(boolean v) {
        sp.edit().putBoolean("root_enhance", v).apply();
    }

    /** su 是否已授权成功过。用来区分「没 root」和「用户还没点授权窗」。 */
    public boolean isRootGranted() {
        return sp.getBoolean("root_granted", false);
    }

    public void setRootGranted(boolean v) {
        sp.edit().putBoolean("root_granted", v).apply();
    }

    /** 守护模块是否曾部署成功（真实是否存在以 RootGuard.status 为准）。 */
    public boolean isGuardInstalled() {
        return sp.getBoolean("guard_installed", false);
    }

    public void setGuardInstalled(boolean v) {
        sp.edit().putBoolean("guard_installed", v).apply();
    }

    public boolean isToastOnHit() {
        return sp.getBoolean("toast_on_hit", false);
    }

    public void setToastOnHit(boolean v) {
        sp.edit().putBoolean("toast_on_hit", v).apply();
    }

    public List<String> getSubUrls() {
        List<String> out = new ArrayList<>();
        String raw = sp.getString("sub_urls", "");
        if (raw == null || raw.length() == 0) return out;
        for (String s : raw.split("\n")) {
            s = s.trim();
            if (s.length() > 0) out.add(s);
        }
        return out;
    }

    public void saveSubUrls(List<String> urls) {
        StringBuilder sb = new StringBuilder();
        for (String s : urls) {
            if (s == null) continue;
            s = s.trim();
            if (s.length() == 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        sp.edit().putString("sub_urls", sb.toString()).apply();
    }

    // ---------------- 按应用开关 ----------------

    /**
     * 被用户单独关掉规则的应用。这些应用快跳<b>完全不动手</b> —— 规则层和下面的
     * 自动识别一起停，等于把该应用从快跳的管辖范围里摘出去。
     *
     * <p>存的是包名集合。注意 {@code getStringSet} 返回的是内部实例，直接改它
     * 会绕过 SharedPreferences 的变更通知，所以这里一律返回副本。
     */
    public java.util.Set<String> getDisabledApps() {
        java.util.Set<String> s = sp.getStringSet("disabled_apps", null);
        if (s == null || s.isEmpty()) return new java.util.HashSet<>();
        return new java.util.HashSet<>(s);
    }

    public boolean isAppDisabled(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        return getDisabledApps().contains(pkg);
    }

    public void setAppDisabled(String pkg, boolean disabled) {
        if (pkg == null || pkg.length() == 0) return;
        java.util.Set<String> s = getDisabledApps();
        if (disabled) {
            s.add(pkg);
        } else {
            s.remove(pkg);
        }
        sp.edit().putStringSet("disabled_apps", s).apply();
    }

    public int disabledAppCount() {
        return getDisabledApps().size();
    }

    public void clearDisabledApps() {
        sp.edit().remove("disabled_apps").apply();
    }

    // ---------------- 按规则组开关 ----------------

    /** 关停记录的键：{@code 包名#组名}。包名写 {@code *} 表示全局默认。 */
    public static String groupKey(String pkg, String group) {
        return (pkg == null || pkg.length() == 0 ? "*" : pkg) + "#" + (group == null ? "" : group);
    }

    /**
     * 被用户关掉的规则组。
     *
     * <p>比「按应用开关」细一档 —— 用户想说的常常是「这个应用的开屏广告别管，
     * 但更新提示还是要跳」，而不是把整个应用摘出去。
     *
     * <p>键是 {@code 包名#组名}；包名位写 {@code *} 表示全局默认，
     * 对该组在所有应用里生效。两者是「或」的关系，见 {@link #isGroupDisabled}。
     *
     * <p>组名会随规则集更新而变（比如上游把「开屏广告」并进「全屏广告」），
     * 对不上的旧键自然失效、不会误伤别的组 —— 代价是那条设置会被静默忽略，
     * 所以设置页会显示「已关停 N 组」让用户看得见。
     */
    public java.util.Set<String> getDisabledGroups() {
        java.util.Set<String> s = sp.getStringSet("disabled_groups", null);
        if (s == null || s.isEmpty()) return new java.util.HashSet<>();
        return new java.util.HashSet<>(s);
    }

    /** 该组对该应用是否被关停（应用级或全局默认任一命中即算关）。 */
    public boolean isGroupDisabled(String pkg, String group) {
        if (group == null || group.length() == 0) return false;
        java.util.Set<String> s = getDisabledGroups();
        if (s.isEmpty()) return false;
        return s.contains(groupKey(pkg, group)) || s.contains(groupKey("*", group));
    }

    /** 只看应用级记录，不看全局默认 —— 界面上的开关要反映用户自己勾的那一下。 */
    public boolean isGroupDisabledExact(String pkg, String group) {
        if (group == null || group.length() == 0) return false;
        return getDisabledGroups().contains(groupKey(pkg, group));
    }

    public void setGroupDisabled(String pkg, String group, boolean disabled) {
        if (group == null || group.length() == 0) return;
        java.util.Set<String> s = getDisabledGroups();
        String k = groupKey(pkg, group);
        if (disabled) {
            s.add(k);
        } else {
            s.remove(k);
        }
        if (s.isEmpty()) {
            sp.edit().remove("disabled_groups").apply();
        } else {
            sp.edit().putStringSet("disabled_groups", s).apply();
        }
    }

    public int disabledGroupCount() {
        return getDisabledGroups().size();
    }

    /** 清掉某个包名的全部组停用记录（包名写 "*" 只清全局默认那几条）。 */
    public void clearDisabledGroups(String pkg) {
        String prefix = (pkg == null || pkg.length() == 0 ? "*" : pkg) + "#";
        java.util.Set<String> s = getDisabledGroups();
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (String k : s) {
            if (!k.startsWith(prefix)) keep.add(k);
        }
        if (keep.isEmpty()) {
            sp.edit().remove("disabled_groups").apply();
        } else {
            sp.edit().putStringSet("disabled_groups", keep).apply();
        }
    }

    public void clearAllDisabledGroups() {
        sp.edit().remove("disabled_groups").apply();
    }

    // ---------------- 自动识别（规则库外的应用） ----------------

    /**
     * 自动识别：对规则库里<b>没有专属规则</b>的应用，用启发式在开屏那几秒里
     * 找「跳过」按钮。
     *
     * <p>默认开启 —— 这条链路的定位就是兜住「规则库里没有这个 App」的情况；
     * 但它是启发式的，所以有三重限流（只在启动窗口内、每屏最多一次、每次前台
     * 会话最多两次），并且日志里会单独标成「自动识别」，方便发现误点时关掉。
     */
    public boolean isAutoDetect() {
        return sp.getBoolean("auto_detect", true);
    }

    public void setAutoDetect(boolean v) {
        sp.edit().putBoolean("auto_detect", v).apply();
    }

    /** 自动识别累计命中次数，用来在界面上区分「规则命中」和「启发式命中」。 */
    public int getAutoCount() {
        return sp.getInt("auto_count", 0);
    }

    public void bumpAutoCount() {
        sp.edit().putInt("auto_count", getAutoCount() + 1).apply();
    }

    /** 订阅层规则（由订阅地址拉取得到，会被「更新全部规则」整份覆盖）。 */
    public String getRulesJson() {
        return sp.getString("rules_json", "");
    }

    public void setRulesJson(String v) {
        sp.edit().putString("rules_json", v).apply();
    }

    /** 本地层规则（导入的文件 + 手动添加），订阅更新不会动它。 */
    public String getLocalRulesJson() {
        return sp.getString("local_rules_json", "");
    }

    public void setLocalRulesJson(String v) {
        sp.edit().putString("local_rules_json", v).apply();
    }

    /**
     * 规则文件的落盘格式版本，用于「只规范化一次」。
     *
     * <p>0 = 从没规范化过（老版本装上来的）。见
     * {@link RuleStore#upgradeFormat()}。
     *
     * <p>不要改成「每次启动都解析一遍文件看看要不要升级」—— 本地层几百 KB、
     * 几千条规则，parse 一次几百毫秒，白白拖慢启动。存个数字最省事。
     */
    public int getRuleFormat() {
        return sp.getInt("rule_format", 0);
    }

    public void setRuleFormat(int v) {
        sp.edit().putInt("rule_format", v).apply();
    }

    /** 本地独占：某应用只要存在本地规则，就完全忽略它的订阅规则，避免两边同时点。 */
    public boolean isLocalExclusive() {
        return sp.getBoolean("local_exclusive", false);
    }

    public void setLocalExclusive(boolean v) {
        sp.edit().putBoolean("local_exclusive", v).apply();
    }

    public long getLastUpdate() {
        return sp.getLong("last_update", 0L);
    }

    public void setLastUpdate(long v) {
        sp.edit().putLong("last_update", v).apply();
    }

    public int getTotalCount() {
        return sp.getInt("total_count", 0);
    }

    public int bumpTotal() {
        int v = getTotalCount() + 1;
        sp.edit().putInt("total_count", v).apply();
        bumpToday();
        return v;
    }

    public int getTodayCount() {
        String today = DateUtil.today();
        if (!today.equals(sp.getString("today_key", ""))) return 0;
        return sp.getInt("today_count", 0);
    }

    private void bumpToday() {
        String today = DateUtil.today();
        int v;
        if (today.equals(sp.getString("today_key", ""))) {
            v = sp.getInt("today_count", 0) + 1;
        } else {
            v = 1;
        }
        sp.edit().putString("today_key", today).putInt("today_count", v).apply();
    }

    public void resetCounters() {
        sp.edit().putInt("total_count", 0).putInt("today_count", 0).putInt("auto_count", 0)
                .putString("today_key", DateUtil.today()).apply();
    }
}
