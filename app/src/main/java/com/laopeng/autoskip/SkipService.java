package com.laopeng.autoskip;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SkipService extends AccessibilityService {

    public static final String TAG = "AutoSkip";

    private static volatile SkipService sInstance;

    /** 最近一个「别人的」前台包名 —— 给手动加规则时「填入当前应用」用。 */
    private static volatile String sLastPkg = "";

    /** 是否已成功进入前台服务状态（保活的关键）。 */
    private static volatile boolean sForeground = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> lastHit = new HashMap<>();
    private final Map<String, Integer> screenCount = new HashMap<>();
    /** 诊断模式：每个包最近一次导出界面树的时间，用来限流。 */
    private final Map<String, Long> diagLast = new HashMap<>();

    // ---- 自动识别（规则库外的应用）----

    /** 当前前台的包名，以及它「成为前台」的时刻。 */
    private String fgPkg = "";
    private long fgAt = 0L;
    /**
     * 自动识别的时间窗口。开屏广告只在应用启动那一瞬间出现，过了这个窗口
     * 还在扫，只可能扫到正常界面上的「跳过」（教程、引导页之类）而误点。
     */
    private static final long AUTO_WINDOW_MS = 6500L;
    /** 每次前台会话最多自动点几次；同一个界面最多几次。 */
    private static final int AUTO_MAX_PER_SESSION = 2;
    private static final int AUTO_MAX_PER_SCREEN = 1;
    /**
     * 「不在启动 Activity 上」时的收紧窗口。开屏广告要么就挂在启动 Activity 上，
     * 要么在它跳转后立刻出现；再往后才冒出来的「跳过」基本都是引导页/教程，
     * 不该点。所以认不出启动 Activity 时只认头 3.5 秒。
     */
    private static final long AUTO_STRICT_MS = 3500L;
    private int autoUsed = 0;
    /** 本次前台会话是否已经留过一条「没命中」的判定记录（命中时每次都记）。 */
    private boolean autoNoteFresh = true;
    private final Map<String, Integer> autoScreen = new HashMap<>();
    /** 包名 → 启动 Activity 类名（取不到存空串）。启动 Activity 不会变，可以长期缓存。 */
    private final Map<String, String> launcherAct = new HashMap<>();
    /** 桌面包名，懒加载一次。 */
    private String homePkg = null;

    private String curScreenKey = "none";

    private static final int[] RETRY_DELAYS = {0, 250, 600, 1200, 2000, 3200};

    public static SkipService get() {
        return sInstance;
    }

    public static boolean isConnected() {
        return sInstance != null;
    }

    public static String lastPackage() {
        return sLastPkg;
    }

    public static boolean isForeground() {
        return sForeground;
    }

    /**
     * 供界面上的「前台常驻」开关实时生效 —— 不用等用户去关开无障碍开关。
     */
    public static void applyKeepAlive(android.content.Context c) {
        SkipService s = sInstance;
        if (s == null) return;
        if (Prefs.get(c).isKeepAlive()) {
            s.goForeground();
        } else {
            s.leaveForeground();
        }
    }

    private void goForeground() {
        if (sForeground) {
            KeepAlive.refresh(this);
            return;
        }
        sForeground = KeepAlive.start(this);
        if (sForeground) LogStore.get(this).add("已进入前台常驻（保活中）");
    }

    private void leaveForeground() {
        if (!sForeground) return;
        KeepAlive.stop(this);
        sForeground = false;
        LogStore.get(this).add("已退出前台常驻");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            sInstance = this;
            LogStore.get(this).add("无障碍服务已连接");
            if (Prefs.get(this).isKeepAlive()) goForeground();
            hardenWithRoot();
        } catch (Throwable t) {
            Log.w(TAG, "onServiceConnected failed: " + t);
        }
    }

    /**
     * root 增强开着的话，服务每连上一次就顺手加固一次。
     *
     * <p>oom_score_adj 会被系统在状态切换（切前后台、进出 Doze）时改回去，
     * 只靠守护脚本那 20 秒的巡检会有空窗期，这里补一刀能填上。
     *
     * <p>su 是阻塞调用，必须另开线程 —— 在 onServiceConnected 里同步跑会直接
     * 把主线程卡住（第一次还要等 KernelSU 的授权窗，那就更久了）。
     */
    private void hardenWithRoot() {
        Prefs p = Prefs.get(this);
        if (!p.isRootEnhance() || !p.isRootGranted()) return;
        new Thread(() -> {
            try {
                RootGuard.harden(SkipService.this);
            } catch (Throwable t) {
                Log.w(TAG, "root harden failed: " + t);
            }
        }, "autoskip-root-harden").start();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        sForeground = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 整个回调兜底：无障碍服务一旦抛未捕获异常，系统会把「快跳」的无障碍开关
        // 直接关掉（用户看到的就是"开了又自己关了"）。这里必须连 Error 一起挡。
        try {
            handleEvent(event);
        } catch (Throwable t) {
            Log.w(TAG, "onAccessibilityEvent failed: " + t);
        }
    }

    private void handleEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        final String pkg = pkgCs.toString();
        if (pkg.equals(getPackageName())) return;
        if ("com.android.systemui".equals(pkg)) return;

        sLastPkg = pkg;

        // 切到新应用 = 新的一次前台会话。自动识别只在这之后的一小段窗口内出手，
        // 顺带把它的限流计数清零。
        if (!pkg.equals(fgPkg)) {
            fgPkg = pkg;
            fgAt = SystemClock.uptimeMillis();
            autoUsed = 0;
            autoNoteFresh = true;
            autoScreen.clear();
        }

        if (!Prefs.get(this).isRunning()) return;

        final String cls = event.getClassName() == null ? "" : event.getClassName().toString();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            curScreenKey = pkg + "#" + cls + "#" + SystemClock.uptimeMillis();
            screenCount.remove(curScreenKey);
            for (int delay : RETRY_DELAYS) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        scan(pkg, cls, curScreenKey);
                    }
                }, delay);
            }
        } else {
            scan(pkg, cls, curScreenKey);
        }
    }

    private void scan(String pkg, String cls, String screenKey) {
        try {
            Prefs prefs = Prefs.get(this);
            if (!prefs.isRunning()) return;
            // 用户单独关掉的应用：规则层和自动识别一起停，等于不归快跳管。
            if (prefs.isAppDisabled(pkg)) return;

            List<Rule> rules = RuleStore.get(this).rulesFor(pkg);

            // 自动识别的准入条件：开关开着、还在启动窗口内、这个应用在三层规则里
            // 都没有「专属规则组」（通配 `*` 组不算 —— 那个每个应用都有），
            // 而且当前不在桌面上（桌面上没有开屏广告这回事）。
            boolean autoOk = prefs.isAutoDetect()
                    && withinAutoWindow()
                    && !isHomePkg(pkg)
                    && !RuleStore.get(this).hasAppRules(pkg);

            // 既没有规则可跑、又轮不到自动识别，就没必要去取节点树（跨进程，很贵）。
            if ((rules == null || rules.isEmpty()) && !autoOk) return;

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            // 关键校验：事件里的包名，必须和真正拿到焦点的窗口是同一个应用。
            //
            // 切应用的过渡期里，事件可能来自 A 而活动窗口已经是 B（或者反过来，
            // 事件来自 B 而窗口还停在上一个应用上）。不校验就会拿着 A 的规则去扫 B 的
            // 界面 —— 实测后果是：快跳会拿「跳过」这条通用规则，把快跳自己界面上的
            // 「启用自动跳过」开关当成开屏广告的「跳过」按钮点掉，
            // 用户看到的现象就是「刚把自动跳过打开，它自己又关了」。
            CharSequence rootPkgCs = null;
            try {
                rootPkgCs = root.getPackageName();
            } catch (Throwable t) {
                return;
            }
            if (rootPkgCs == null) return;
            String rootPkg = rootPkgCs.toString();
            if (!pkg.equals(rootPkg)) return;
            if (getPackageName().equals(rootPkg)) return;

            List<RuleEngine.Hit> hits = (rules == null || rules.isEmpty())
                    ? new ArrayList<RuleEngine.Hit>()
                    : RuleEngine.match(rules, cls, root);
            diagDump(pkg, cls, rules, hits, root);
            if (hits.isEmpty()) {
                if (autoOk) autoSkip(pkg, cls, screenKey, root, prefs);
                return;
            }

            long now = SystemClock.uptimeMillis();
            for (final RuleEngine.Hit h : hits) {
                final Rule r = h.rule;
                final String ruleKey = pkg + "|" + r.name + "|" + r.matches.hashCode();
                final String countKey = screenKey + "|" + ruleKey;

                Long last = lastHit.get(ruleKey);
                if (last != null && now - last < Math.max(0, r.cooldown)) continue;

                Integer used = screenCount.get(countKey);
                int usedN = used == null ? 0 : used;
                if (usedN >= Math.max(1, r.maxClick)) continue;

                if (r.delay > 0) {
                    final int snapshot = usedN;
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            fire(pkg, r, h, ruleKey, countKey, snapshot);
                        }
                    }, r.delay);
                } else {
                    fire(pkg, r, h, ruleKey, countKey, usedN);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "scan failed: " + t);
        }
    }

    /** 诊断模式：编译期写死的待诊断包名，排查完清空（空数组 = 关闭）。 */
    private static final String[] DIAG_PKGS = {};
    /** 运行期诊断开关的缓存：getFilesDir()/diag.txt 的内容与读取时间。 */
    private volatile String diagTrig = "";
    private volatile long diagTrigAt = 0L;

    /**
     * 诊断模式：把「窗口树 + 规则命中情况」写到 {@code getFilesDir()/diag/}，
     * 供 adb 拉取分析。看到的就是快跳真正读到的节点树。
     *
     * <p>为什么不用 uiautomator dump：它是 UiAutomation，会要求独占无障碍连接，
     * 一执行就把快跳的服务顶下线重连，抓到的树跟快跳看到的两码事。
     *
     * <p>写内部私有目录而不是外部存储：外部目录由 root 预先创建时属主/权限不在
     * App 手里，mkdirs/write 会静默失败；内部目录一定是 App 自己的，稳。
     *
     * <p>开启方式二选一：① 把包名加进 {@link #DIAG_PKGS}；② 往
     * {@code /data/data/<pkg>/files/diag.txt} 写要诊断的包名（root，需 restorecon）。
     */
    private void diagDump(String pkg, String cls, List<Rule> rules,
                          List<RuleEngine.Hit> hits, AccessibilityNodeInfo root) {
        try {
            if (!diagWanted(pkg)) return;

            long now = System.currentTimeMillis();
            Long last = diagLast.get(pkg);
            if (last != null && now - last < 700) return;
            diagLast.put(pkg, now);

            java.io.File dir = new java.io.File(getFilesDir(), "diag");
            if (!dir.exists() && !dir.mkdirs()) return;
            java.io.File out = new java.io.File(dir, pkg + "_" + now + ".txt");

            StringBuilder sb = new StringBuilder();
            sb.append("pkg=").append(pkg).append('\n');
            sb.append("cls=").append(cls).append('\n');
            sb.append("ruleCount=").append(rules == null ? 0 : rules.size()).append('\n');
            sb.append("hitCount=").append(hits == null ? 0 : hits.size()).append('\n');
            if (hits != null) {
                for (RuleEngine.Hit h : hits) {
                    sb.append("HIT: ").append(h.rule.name).append(" | ").append(h.rule.matchType)
                            .append(" | ").append(h.detail).append('\n');
                }
            }
            sb.append("---- nodes ----\n");
            sb.append(RuleEngine.dumpTree(root));
            writeSmall(out, sb.toString());
        } catch (Throwable t) {
            Log.w(TAG, "diagDump failed: " + t);
        }
    }

    /**
     * 这个包名是否在诊断名单里。名单来源两处：编译期数组 {@link #DIAG_PKGS}，
     * 以及运行期文件 {@code files/diag.txt}（root 写入，改完立即生效，不用重编译）。
     */
    private boolean diagWanted(String pkg) {
        for (String p : DIAG_PKGS) {
            if (p.equals(pkg)) return true;
        }
        long t = System.currentTimeMillis();
        if (t - diagTrigAt > 5000L) {
            diagTrigAt = t;
            java.io.File trig = new java.io.File(getFilesDir(), "diag.txt");
            diagTrig = trig.exists() ? readSmall(trig) : "";
        }
        String s = diagTrig;
        return s != null && s.indexOf(pkg) >= 0;
    }

    /** 诊断模式：把某次自动识别的判定过程单独落一个文件，用于排查「为什么没自动跳过」。 */
    private void diagAuto(String pkg, String note) {
        try {
            if (!diagWanted(pkg)) return;
            java.io.File dir = new java.io.File(getFilesDir(), "diag");
            if (!dir.exists() && !dir.mkdirs()) return;
            java.io.File out = new java.io.File(dir, pkg + "_auto_" + System.currentTimeMillis() + ".txt");
            writeSmall(out, "pkg=" + pkg + "\n" + note + "\n");
        } catch (Throwable ignored) {
        }
    }

    private static String readSmall(java.io.File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void writeSmall(java.io.File f, String s) {
        java.io.FileOutputStream out = null;
        try {
            out = new java.io.FileOutputStream(f);
            out.write(s.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 现在还在「刚进这个 App」的时间窗口里吗。 */
    private boolean withinAutoWindow() {
        return fgAt > 0 && SystemClock.uptimeMillis() - fgAt <= AUTO_WINDOW_MS;
    }

    /** 当前界面是不是这个应用的「启动 Activity」。取不到就当成不是（走收紧窗口）。 */
    private boolean isLaunchActivity(String pkg, String cls) {
        if (cls == null || cls.length() == 0) return false;
        String cached = launcherAct.get(pkg);
        if (cached == null) {
            cached = "";
            try {
                Intent it = getPackageManager().getLaunchIntentForPackage(pkg);
                if (it != null && it.getComponent() != null) {
                    String c = it.getComponent().getClassName();
                    if (c != null) cached = c;
                }
            } catch (Throwable ignored) {
            }
            if (launcherAct.size() > 256) launcherAct.clear();
            launcherAct.put(pkg, cached);
        }
        return cached.length() > 0 && cls.equals(cached);
    }

    /** 桌面包名（懒加载一次）。桌面上不存在开屏广告，直接排除掉最省心。 */
    private boolean isHomePkg(String pkg) {
        if (homePkg == null) {
            String p = "";
            try {
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(home, 0);
                if (ri != null && ri.activityInfo != null && ri.activityInfo.packageName != null) {
                    p = ri.activityInfo.packageName;
                }
            } catch (Throwable ignored) {
            }
            homePkg = p;
        }
        return homePkg.length() > 0 && homePkg.equals(pkg);
    }

    /**
     * 自动识别：规则库里没有这个应用时，用启发式找开屏跳过按钮并点击。
     *
     * <p>它跟规则链路的区别只有一个 ——「凭什么认为是跳过按钮」：这边靠
     * {@link SplashAuto} 的形状启发式，那边靠人工/上游写好的规则。点下去之后
     * 的安全闸（大面积不点、可点祖先、手势兜底）和限流是完全共用的一套；
     * 日志里也会明确写成「自动识别」，方便事后判断是不是它点错了。
     */
    private void autoSkip(String pkg, String cls, String screenKey,
                          AccessibilityNodeInfo root, Prefs prefs) {
        try {
            if (autoUsed >= AUTO_MAX_PER_SESSION) return;
            Integer used = autoScreen.get(screenKey);
            if (used != null && used >= AUTO_MAX_PER_SCREEN) return;

            // 收紧闸：不是启动 Activity 的话只认头 3.5 秒（见 AUTO_STRICT_MS）。
            if (!isLaunchActivity(pkg, cls)
                    && SystemClock.uptimeMillis() - fgAt > AUTO_STRICT_MS) {
                return;
            }

            StringBuilder why = new StringBuilder();
            SplashAuto.Result res = SplashAuto.detect(root, why);
            boolean hit = res != null && res.node != null;
            // 诊断模式下，每个前台会话只留一条「没命中」的判定记录 —— 内容变化太快
            // （fg_ms 每次都不同），不限流的话一次启动就能写出上百个文件。
            if (hit || autoNoteFresh) {
                diagAuto(pkg, "hit=" + hit + "\ncls=" + cls
                        + "\nfg_ms=" + (SystemClock.uptimeMillis() - fgAt)
                        + "\nlaunchAct=" + isLaunchActivity(pkg, cls)
                        + "\nautoUsed=" + autoUsed + "\n" + why);
                autoNoteFresh = false;
            }
            if (!hit) return;

            // 点击前复核，和规则链路同一套：窗口还是这个应用、节点还可见。
            try {
                AccessibilityNodeInfo nowRoot = getRootInActiveWindow();
                if (nowRoot == null) return;
                CharSequence nowPkg = nowRoot.getPackageName();
                if (nowPkg == null || !pkg.equals(nowPkg.toString())) return;
                if (!res.node.isVisibleToUser()) return;
            } catch (Throwable t) {
                return;
            }

            if (!RuleEngine.clickNode(this, res.node)) return;

            autoUsed++;
            autoScreen.put(screenKey, (used == null ? 0 : used) + 1);
            prefs.bumpTotal();
            prefs.bumpAutoCount();
            LogStore.get(this).add("自动识别 " + pkg + " · 开屏跳过 · 「" + res.detail + "」");
            KeepAlive.refresh(this);
            if (prefs.isToastOnHit()) {
                final String msg = "自动识别并跳过：" + res.detail;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(SkipService.this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "autoSkip failed: " + t);
        }
    }

    private void fire(String pkg, Rule r, RuleEngine.Hit h, String ruleKey, String countKey, int usedN) {
        try {
            if (!Prefs.get(this).isRunning()) return;
            if (h.node == null) return;

            // 命中到真正点击之间隔了 delay + 重试延迟，界面可能早就换掉了。
            // 点击前再确认一次「窗口还是那个应用」「按钮还在屏幕上」，
            // 否则很容易点到新界面上恰好位置相同的元素。
            try {
                AccessibilityNodeInfo nowRoot = getRootInActiveWindow();
                if (nowRoot == null) return;
                CharSequence nowPkg = nowRoot.getPackageName();
                if (nowPkg == null || !pkg.equals(nowPkg.toString())) return;
                if (!h.node.isVisibleToUser()) return;
            } catch (Throwable t) {
                return;
            }

            Integer cur = screenCount.get(countKey);
            int now = cur == null ? 0 : cur;
            if (now >= Math.max(1, r.maxClick)) return;

            boolean ok = RuleEngine.execute(this, r, h.node);
            if (ok) {
                lastHit.put(ruleKey, SystemClock.uptimeMillis());
                screenCount.put(countKey, now + 1);
                Prefs.get(this).bumpTotal();
                LogStore.get(this).add("跳过 " + pkg + " · " + r.name + " · 「" + h.detail + "」");
                KeepAlive.refresh(this);
                if (Prefs.get(this).isToastOnHit()) {
                    final String msg = "已跳过：" + h.detail;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(SkipService.this, msg, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "fire failed: " + t);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        if (sForeground) KeepAlive.stop(this);
        sForeground = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
