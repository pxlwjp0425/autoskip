package com.laopeng.autoskip;

import android.content.Context;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * root 增强（KernelSU / Magisk）。把免 root 做不到的四件事补上：
 *
 * <ol>
 *   <li><b>锁内存</b>：{@code oom_score_adj = -1000}，LMK 基本不会再回收它</li>
 *   <li><b>免手动解锁受限设置</b>：{@code ACCESS_RESTRICTED_SETTINGS allow}，
 *       重装 / 清数据后也不用再去点「⋮ → 允许受限制的设置」</li>
 *   <li><b>无障碍开关自愈</b>：开关记录被系统清掉就补回</li>
 *   <li><b>开机守护</b>：KernelSU 模块在开机后把上面这些重新做一遍，并长期巡检</li>
 * </ol>
 *
 * <p>免 root 那条路（前台常驻 + 电池白名单）已经走到顶了 —— 白名单对应的是
 * standby bucket 里的 EXEMPTED 档，再往上没有档位了。所以这里不是「锦上添花」，
 * 而是「要更强只有这一条路」。
 *
 * <p>所有方法都是阻塞的（要跑 su），<b>只能在子线程调用</b>。
 */
public final class RootGuard {

    public static final String TAG = "AutoSkip";

    /** -1000 是内存里最高优先级，LMK 不会再回收这个进程。 */
    public static final int OOM_ADJ_LOCKED = -1000;
    /** 解除锁定时写回的默认值（系统给「可见应用」的档位）。 */
    public static final int OOM_ADJ_DEFAULT = 100;

    private RootGuard() {
    }

    /** 无障碍服务在系统里的完整组件名。 */
    public static String serviceComponent(Context c) {
        return c.getPackageName() + "/" + SkipService.class.getName();
    }

    // ---------------- 加固 ----------------

    /**
     * 一次性加固。幂等，可以反复跑。
     * 首次调用会弹 KernelSU 授权窗，所以超时给到一分钟。
     */
    public static RootShell.Result harden(Context c) {
        String pkg = c.getPackageName();
        String svc = serviceComponent(c);

        StringBuilder sb = new StringBuilder();
        sb.append("PKG=").append(pkg).append('\n');
        sb.append("SVC=").append(svc).append('\n');

        // 1) 解锁「受限设置」。做完这一条，以后重装、清数据都不用手动去点 ⋮ 菜单
        sb.append("cmd appops set $PKG ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null\n");
        // 2) 后台运行相关的 appop（部分 ROM 默认会收紧）
        sb.append("cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow 2>/dev/null\n");
        sb.append("cmd appops set $PKG WAKE_LOCK allow 2>/dev/null\n");
        // 3) 电池白名单（幂等，已在里面也不会出错）
        sb.append("dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1\n");
        // 4) 无障碍开关记录保底。注意是「追加」不是「覆盖」——
        //    机器上可能还跑着别的无障碍服务，覆盖会把它们关掉。
        sb.append("CUR=$(settings get secure enabled_accessibility_services)\n");
        sb.append("case \"$CUR\" in\n");
        sb.append("  *\"$PKG\"*) ;;\n");
        sb.append("  \"\"|null) settings put secure enabled_accessibility_services \"$SVC\" ;;\n");
        sb.append("  *) settings put secure enabled_accessibility_services \"$CUR:$SVC\" ;;\n");
        sb.append("esac\n");
        sb.append("settings put secure accessibility_enabled 1\n");
        // 5) 锁内存
        sb.append("for p in $(pidof $PKG); do echo ").append(OOM_ADJ_LOCKED)
                .append(" > /proc/$p/oom_score_adj 2>/dev/null; done\n");
        sb.append("echo HARDEN_OK\n");

        return RootShell.run(sb.toString(), RootShell.FIRST_TIMEOUT_MS);
    }

    // ---------------- 守护模块 ----------------

    /**
     * 把 KernelSU 模块写进 {@code /data/adb/modules/autoskip_guard/} 并立刻启动一次。
     * 不重启也有效；重启后由模块的 service.sh 自动接上。
     */
    public static RootShell.Result installGuard(Context c) {
        String dir = GuardModule.MODULE_DIR;
        StringBuilder sb = new StringBuilder();

        sb.append("mkdir -p ").append(dir).append("/bin\n");
        // 内容用 base64 传，脚本里的引号、$、换行都不会被外层 shell 解释掉
        for (Map.Entry<String, String> e : GuardModule.files().entrySet()) {
            sb.append("echo '").append(b64(e.getValue())).append("' | base64 -d > ")
                    .append(dir).append('/').append(e.getKey()).append('\n');
        }
        sb.append("chmod 755 ").append(dir).append('\n');
        sb.append("chmod 755 ").append(dir).append("/bin\n");
        sb.append("chmod 755 ").append(dir).append("/service.sh\n");
        sb.append("chmod 755 ").append(dir).append("/bin/guard.sh\n");
        sb.append("chmod 644 ").append(dir).append("/module.prop\n");

        // 立刻跑一遍 —— 不用等用户重启
        sb.append("nohup sh ").append(dir)
                .append("/bin/guard.sh </dev/null >/dev/null 2>&1 &\n");
        sb.append("sleep 1\n");
        sb.append("echo MODULE_OK\n");

        return RootShell.run(sb.toString(), 90_000L);
    }

    /**
     * 移除守护模块并解除内存锁。
     *
     * <p>不去翻进程表杀守护脚本 —— 各家 ROM 的 ps 参数不一致，太脆。
     * 脚本自己每轮会检查模块目录，目录没了就退出。
     */
    public static RootShell.Result removeGuard(Context c) {
        String pkg = c.getPackageName();
        StringBuilder sb = new StringBuilder();
        sb.append("rm -rf ").append(GuardModule.MODULE_DIR).append('\n');
        sb.append("rm -f ").append(GuardModule.GUARD_LOG).append('\n');
        sb.append("for p in $(pidof ").append(pkg).append("); do echo ")
                .append(OOM_ADJ_DEFAULT).append(" > /proc/$p/oom_score_adj 2>/dev/null; done\n");
        sb.append("echo REMOVED\n");
        return RootShell.run(sb.toString());
    }

    // ---------------- 状态 ----------------

    public static final class Status {
        /** 进程当前的内存优先级；进程不在时是 "none"。 */
        public String oomAdj = "";
        public boolean moduleInstalled;
        public int guardProcs;

        public boolean oomLocked() {
            return String.valueOf(OOM_ADJ_LOCKED).equals(oomAdj);
        }

        public boolean guardRunning() {
            return guardProcs > 0;
        }

        static Status parse(String out) {
            Status s = new Status();
            if (out == null) return s;
            for (String line : out.split("\n")) {
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1).trim();
                switch (k) {
                    case "adj":
                        s.oomAdj = v;
                        break;
                    case "module":
                        s.moduleInstalled = "yes".equals(v);
                        break;
                    case "guard":
                        try {
                            s.guardProcs = Integer.parseInt(v);
                        } catch (Throwable ignored) {
                        }
                        break;
                    default:
                        break;
                }
            }
            return s;
        }
    }

    /** 查一次当前状态。阻塞，需在子线程调用。 */
    public static Status status(Context c) {
        String pkg = c.getPackageName();
        StringBuilder sb = new StringBuilder();
        sb.append("P=$(pidof ").append(pkg).append(" | head -n 1)\n");
        sb.append("if [ -n \"$P\" ]; then echo \"adj=$(cat /proc/$P/oom_score_adj 2>/dev/null)\"; "
                + "else echo adj=none; fi\n");
        sb.append("[ -d ").append(GuardModule.MODULE_DIR).append(" ] "
                + "&& echo module=yes || echo module=no\n");
        // 注意不能用 `ps -A | grep` 数守护进程：Android 的 ps 默认只显示被截断的进程名，
        // 看不到完整命令行，永远数出 0（这个坑踩过一次）。pgrep -f 看的是完整命令行，
        // 再用 $ 锚定结尾，就不会把「执行这条查询的 sh 自己」也算进去。
        sb.append("echo guard=$(pgrep -f 'bin/guard.sh$' 2>/dev/null | wc -l)\n");
        return Status.parse(RootShell.run(sb.toString()).out);
    }

    private static String b64(String s) {
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }
}
