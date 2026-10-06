package com.laopeng.autoskip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQ_PICK_FILE = 1001;
    private static final int REQ_NOTIF = 1002;

    /** 手动添加对话框里的「匹配方式」下拉项，顺序与 Spinner 一致。 */
    private static final String[] MATCH_TYPES = {"any", "text", "desc", "id", "className"};
    private static final String[] MATCH_LABELS = {"任意（文字 / 描述 / ID 都试）", "文字 text",
            "描述 desc", "控件 ID", "类名 className"};
    private static final String[] ACTION_VALUES = {Rule.ACTION_CLICK, Rule.ACTION_CLICK_SELF,
            Rule.ACTION_CLICK_CENTER, Rule.ACTION_BACK, Rule.ACTION_NONE};
    private static final String[] ACTION_LABELS = {"点击（推荐）", "点它自己", "点中心坐标", "返回键", "仅记录不点击"};

    private TextView tvServiceState;
    private TextView tvStats;
    private TextView tvRuleInfo;
    private Button btnOpenSettings;
    private Button btnUpdate;
    private Switch swRunning;
    private Switch swToast;
    private Switch swLocalExclusive;
    private EditText etUrl;
    private LinearLayout llSubs;
    private LinearLayout llLogs;
    private LinearLayout llLocals;
    private LinearLayout llRestrictTip;
    private TextView tvLocalInfo;
    private Button btnAppDetail;
    private Button btnCopyCmd;
    private Switch swKeepAlive;
    private TextView tvKeepState;
    private Button btnBattery;
    private TextView tvFooter;

    // ---- 可折叠卡片：标题栏常驻一行状态摘要，内容区按需展开 ----
    private TextView tvKeepSummary;
    private TextView tvRootSummary;
    private TextView tvSubSummary;
    private TextView tvLocalSummary;
    private TextView tvLogSummary;
    private TextView arrowKeep;
    private TextView arrowRoot;
    private TextView arrowSub;
    private TextView arrowLocal;
    private TextView arrowLog;
    private View bodyKeepAlive;
    private View bodyRoot;
    private View bodySub;
    private View bodyLocal;
    private View bodyLog;
    private Button btnLogMore;

    // ---- 自动识别（在「设置」页里） ----
    private Switch swAutoDetect;

    // ---- 底部导航：四页共用一个 Activity，靠 visibility 切换（见 activity_main.xml 的注释） ----
    private static final int PAGE_HOME = 0;
    private static final int PAGE_APPS = 1;
    private static final int PAGE_RULES = 2;
    private static final int PAGE_SETTINGS = 3;
    private static final int PAGE_COUNT = 4;

    private int page = PAGE_HOME;
    /** 「应用」页的控制器。它以前是个独立 Activity，v1.7.0 起降级成可嵌入的页面。 */
    private AppsPage appsPage;
    private View[] tabViews;
    private TextView[] tabIcons;
    private TextView[] tabLabels;

    // ---- 规则页：全局规则卡（对应 GKD 的 globalGroups，数据层是包名 "*" 的节点） ----
    private TextView tvGlobalSummary;
    private LinearLayout llGlobal;
    private Button btnResetGlobal;
    private TextView arrowGlobal;
    private View bodyGlobal;

    /** 日志默认只渲染这么多条 —— 全量铺出来会把整页撑成好几屏，正是界面凌乱的主因。 */
    private static final int LOG_PREVIEW = 8;
    /** 点「展开全部」后最多渲染这么多条，避免日志攒了几百条时卡顿。 */
    private static final int LOG_MAX = 200;
    private boolean logExpanded = false;
    /** 首次进入时把列表锁回顶部，只做一次，之后不再打扰用户的滚动位置。 */
    private boolean scrolledHomeOnce = false;

    private Switch swRootEnhance;
    private TextView tvRootState;
    private Button btnRootGuard;
    /** 守护模块当前是否已部署（由 refreshRootState 实测结果更新，按钮文案据此切换）。 */
    private boolean guardInstalled;
    /**
     * root 操作是串行的：su 调用会弹 KernelSU 的授权窗，两个线程同时来会打架。
     * 另外它也防止「状态刷新」和「用户点按钮」同时发起 su。
     */
    private boolean rootBusy;

    /** 程序化回填开关时置真，避免把"回填"当成"用户操作"而弹提示。 */
    private boolean loadingPrefs;

    /**
     * 取当前已安装包的版本名。以前底部文案把版本号写死在 strings.xml 里，
     * 每次发版都得记得手改，漏一次界面就一直显示旧版本号，改用运行时读取根治。
     * 读不到时回退为 "?"，避免界面露出 %1$s 占位符。
     */
    private String appVersionName() {
        try {
            String vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return (vn == null || vn.isEmpty()) ? "?" : vn;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 把标题栏做成开关：点一下展开 / 收起内容区，箭头跟着翻。 */
    private void bindSection(View header, final View body, final TextView arrow, boolean open) {
        applySection(body, arrow, open);
        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applySection(body, arrow, body.getVisibility() != View.VISIBLE);
            }
        });
    }

    private void applySection(View body, TextView arrow, boolean open) {
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        if (arrow != null) arrow.setText(open ? "▾" : "▸");
    }

    // ---------------- 底部导航（四页共用一个 Activity） ----------------

    /**
     * 接上底部导航。
     *
     * <p>零依赖工程，所以没有 BottomNavigationView —— 四个 LinearLayout 自己画。
     * 选中态＝图标与文字用品牌色、文字加粗；未选中＝次要色。
     * 图标是纯文本字形（⌂ ▦ ☰ ⚙），不再额外塞 vector 资源。
     */
    private void bindTabBar() {
        tabViews = new View[]{
                findViewById(R.id.tabHome), findViewById(R.id.tabApps),
                findViewById(R.id.tabRules), findViewById(R.id.tabSettings)};
        tabIcons = new TextView[]{
                (TextView) findViewById(R.id.icHome), (TextView) findViewById(R.id.icApps),
                (TextView) findViewById(R.id.icRules), (TextView) findViewById(R.id.icSettings)};
        tabLabels = new TextView[]{
                (TextView) findViewById(R.id.lbHome), (TextView) findViewById(R.id.lbApps),
                (TextView) findViewById(R.id.lbRules), (TextView) findViewById(R.id.lbSettings)};

        for (int i = 0; i < PAGE_COUNT; i++) {
            final int p = i;
            View v = tabViews[i];
            if (v == null) continue;
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    showPage(p);
                }
            });
        }
        showPage(PAGE_HOME);
    }

    /**
     * 切页。只动 visibility，不重建任何东西。
     *
     * <p>四页是一起 inflate 的（零依赖，没有 Fragment / ViewPager），副作用是
     * 各页的滚动位置会各自保留 —— 在「规则」页翻到一半切去「应用」再回来，
     * 不会被打回顶部。
     */
    private void showPage(int p) {
        if (p < 0 || p >= PAGE_COUNT) return;
        page = p;
        final int[] pages = {R.id.pageHome, R.id.pageApps, R.id.pageRules, R.id.pageSettings};
        for (int i = 0; i < PAGE_COUNT; i++) {
            View v = findViewById(pages[i]);
            if (v != null) v.setVisibility(i == p ? View.VISIBLE : View.GONE);
        }
        for (int i = 0; i < PAGE_COUNT; i++) {
            boolean on = (i == p);
            int c = getColor(on ? R.color.brand : R.color.text_sub);
            if (tabIcons[i] != null) tabIcons[i].setTextColor(c);
            if (tabLabels[i] != null) {
                tabLabels[i].setTextColor(c);
                tabLabels[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
        // 角标要在选中态之后画，否则非选中页的警示色会被上面的 text_sub 盖掉
        refreshTabBadges();
        if (p == PAGE_APPS && appsPage != null) appsPage.refresh();
        if (p == PAGE_RULES) refreshGlobalGroups();
    }

    /**
     * 返回键：不在首页就先回首页，在首页才退出。
     *
     * <p>四页共用一个 Activity，直接 finish 的话用户在「设置」页按返回会整个 App 退掉，
     * 和他在首页按返回没区别 —— 不符合直觉。
     */
    @Override
    public void onBackPressed() {
        if (page != PAGE_HOME) {
            showPage(PAGE_HOME);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvServiceState = (TextView) findViewById(R.id.tvServiceState);
        tvStats = (TextView) findViewById(R.id.tvStats);
        tvRuleInfo = (TextView) findViewById(R.id.tvRuleInfo);
        btnOpenSettings = (Button) findViewById(R.id.btnOpenSettings);
        btnUpdate = (Button) findViewById(R.id.btnUpdate);
        swRunning = (Switch) findViewById(R.id.swRunning);
        swToast = (Switch) findViewById(R.id.swToast);
        etUrl = (EditText) findViewById(R.id.etUrl);
        llSubs = (LinearLayout) findViewById(R.id.llSubs);
        llLogs = (LinearLayout) findViewById(R.id.llLogs);
        llRestrictTip = (LinearLayout) findViewById(R.id.llRestrictTip);
        btnAppDetail = (Button) findViewById(R.id.btnAppDetail);
        btnCopyCmd = (Button) findViewById(R.id.btnCopyCmd);

        tvLocalInfo = (TextView) findViewById(R.id.tvLocalInfo);
        llLocals = (LinearLayout) findViewById(R.id.llLocals);
        swLocalExclusive = (Switch) findViewById(R.id.swLocalExclusive);

        swKeepAlive = (Switch) findViewById(R.id.swKeepAlive);
        tvKeepState = (TextView) findViewById(R.id.tvKeepState);
        btnBattery = (Button) findViewById(R.id.btnBattery);

        swRootEnhance = (Switch) findViewById(R.id.swRootEnhance);
        tvRootState = (TextView) findViewById(R.id.tvRootState);
        btnRootGuard = (Button) findViewById(R.id.btnRootGuard);

        tvFooter = (TextView) findViewById(R.id.tvFooter);
        tvFooter.setText(getString(R.string.footer, appVersionName()));

        // 老版本盘上存的是扁平规则文件（app → rules），v1.7.0 起改用分组格式
        // （app → groups → rules）。读的时候两种都认，所以升级只是为了把显式组名落盘，
        // 升不上去也不影响功能 —— 后台做，失败静默。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final RuleStore.UpgradeResult ur = RuleStore.get(MainActivity.this).upgradeFormat();
                    if (!ur.changed() || isFinishing()) return;
                        runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            refreshAll();
                            String msg = "规则文件规范化：" + ur.apps + " 应用 / "
                                    + ur.groups + " 组 / " + ur.rules + " 条";
                            if (ur.dupesRemoved > 0) {
                                msg += "（清掉组内重复规则 " + ur.dupesRemoved + " 条）";
                            }
                            LogStore.get(MainActivity.this).add(msg);
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }, "autoskip-format-upgrade").start();

        // 可折叠卡片：默认只展开「后台保活」和「最近跳过记录」，其余收起 —— 首屏就能看全所有区块。
        tvKeepSummary = (TextView) findViewById(R.id.tvKeepSummary);
        tvRootSummary = (TextView) findViewById(R.id.tvRootSummary);
        tvSubSummary = (TextView) findViewById(R.id.tvSubSummary);
        tvLocalSummary = (TextView) findViewById(R.id.tvLocalSummary);
        tvLogSummary = (TextView) findViewById(R.id.tvLogSummary);
        arrowKeep = (TextView) findViewById(R.id.arrowKeep);
        arrowRoot = (TextView) findViewById(R.id.arrowRoot);
        arrowSub = (TextView) findViewById(R.id.arrowSub);
        arrowLocal = (TextView) findViewById(R.id.arrowLocal);
        arrowLog = (TextView) findViewById(R.id.arrowLog);
        bodyKeepAlive = findViewById(R.id.bodyKeepAlive);
        bodyRoot = findViewById(R.id.bodyRoot);
        bodySub = findViewById(R.id.bodySub);
        bodyLocal = findViewById(R.id.bodyLocal);
        bodyLog = findViewById(R.id.bodyLog);
        btnLogMore = (Button) findViewById(R.id.btnLogMore);

        swAutoDetect = (Switch) findViewById(R.id.swAutoDetect);

        tvGlobalSummary = (TextView) findViewById(R.id.tvGlobalSummary);
        arrowGlobal = (TextView) findViewById(R.id.arrowGlobal);
        bodyGlobal = findViewById(R.id.bodyGlobal);
        llGlobal = (LinearLayout) findViewById(R.id.llGlobal);
        btnResetGlobal = (Button) findViewById(R.id.btnResetGlobal);

        // 四页同时在视图层级里，各页的折叠状态因此互不影响。默认值按「这一页打开时
        // 最想先看见什么」定：首页的保活与日志展开，规则页三张卡都收起。
        bindSection(findViewById(R.id.hdrKeepAlive), bodyKeepAlive, arrowKeep, true);
        bindSection(findViewById(R.id.hdrRoot), bodyRoot, arrowRoot, false);
        bindSection(findViewById(R.id.hdrSub), bodySub, arrowSub, false);
        bindSection(findViewById(R.id.hdrLocal), bodyLocal, arrowLocal, false);
        bindSection(findViewById(R.id.hdrLog), bodyLog, arrowLog, true);
        bindSection(findViewById(R.id.hdrGlobal), bodyGlobal, arrowGlobal, false);

        btnResetGlobal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm(getString(R.string.btn_reset_groups),
                        "把被你关掉的全局规则组全部恢复为启用？\n这些组对所有应用生效，「恢复」之后所有应用都会重新跑它们的规则。",
                        new Runnable() {
                            @Override
                            public void run() {
                                Prefs.get(MainActivity.this).clearDisabledGroups("*");
                                refreshGlobalGroups();
                                toast(getString(R.string.toast_groups_reset));
                            }
                        });
            }
        });

        bindTabBar();
        appsPage = new AppsPage(this);
        appsPage.bind(findViewById(R.id.pageApps));

        swAutoDetect.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.get(MainActivity.this).setAutoDetect(checked);
                if (loadingPrefs) return;
                toast(checked ? "已开启自动识别：规则库外的应用会用启发式找开屏「跳过」"
                        : "已关闭自动识别，只跑规则库里的规则");
            }
        });

        btnLogMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logExpanded = !logExpanded;
                refreshLogs();
            }
        });

        swKeepAlive.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.get(MainActivity.this).setKeepAlive(checked);
                SkipService.applyKeepAlive(MainActivity.this);
                if (loadingPrefs) return;
                if (checked) {
                    ensureNotifPermission();
                    toast(KeepAlive.hasNotifPermission(MainActivity.this)
                            ? getString(R.string.toast_keepalive_on)
                            : "已开启，但通知权限被拒 —— 到系统设置里允许通知更稳");
                } else {
                    toast(getString(R.string.toast_keepalive_off));
                }
                tvKeepState.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshKeepAliveState();
                    }
                }, 400);
            }
        });

        btnBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                KeepAlive.requestBatteryWhitelist(MainActivity.this);
                toast("在弹窗里选「允许」，再回来这里看状态");
            }
        });

        ((Button) findViewById(R.id.btnAutoStart)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean ok = KeepAlive.openAutoStart(MainActivity.this, getString(R.string.app_name));
                toast(ok ? "找到「快跳」并允许自启动 / 设为「无限制」"
                        : "你的系统没有公开的自启动设置页，请手动到「设置 → 应用管理 → 快跳」里打开");
            }
        });

        ((Button) findViewById(R.id.btnAppDetail2)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppDetail();
            }
        });

        btnOpenSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    toast("在列表里找到「快跳」并打开开关");
                } catch (Exception e) {
                    toast("无法打开系统设置，请手动进入：设置 → 无障碍");
                }
            }
        });

        btnAppDetail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppDetail();
            }
        });

        btnCopyCmd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAdbCmd();
            }
        });

        swLocalExclusive.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.get(MainActivity.this).setLocalExclusive(checked);
            }
        });

        ((Button) findViewById(R.id.btnImportFile)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickLocalFile();
            }
        });

        ((Button) findViewById(R.id.btnPasteImport)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteImportDialog();
            }
        });

        ((Button) findViewById(R.id.btnAddRule)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addRuleDialog();
            }
        });

        ((Button) findViewById(R.id.btnLocalize)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                localizeDialog();
            }
        });

        ((Button) findViewById(R.id.btnExportLocal)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportLocalFile();
            }
        });

        ((Button) findViewById(R.id.btnClearLocal)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm("清空本地规则", "将删除全部本地导入与手动添加的规则。\n订阅规则不受影响。\n此操作不可撤销。",
                        new Runnable() {
                            @Override
                            public void run() {
                                RuleStore.get(MainActivity.this).clearLocal();
                                refreshLocal();
                                refreshRules();
                                toast("本地规则已清空");
                            }
                        });
            }
        });

        swRunning.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.get(MainActivity.this).setRunning(checked);
            }
        });

        swToast.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                Prefs.get(MainActivity.this).setToastOnHit(checked);
            }
        });

        ((Button) findViewById(R.id.btnAddSub)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addSub();
            }
        });

        btnUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateRules();
            }
        });

        ((Button) findViewById(R.id.btnRestoreBuiltin)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm(getString(R.string.btn_builtin), "将清空订阅得到的规则，恢复为出厂内置规则。\n本地规则不受影响。", new Runnable() {
                    @Override
                    public void run() {
                        RuleStore.get(MainActivity.this).restoreBuiltin();
                        refreshRules();
                        refreshLogs();
                        toast("已恢复内置规则");
                    }
                });
            }
        });

        ((Button) findViewById(R.id.btnClearLog)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LogStore.get(MainActivity.this).clear();
                refreshLogs();
            }
        });

        tvStats.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                confirm("重置统计", "把今日与累计的跳过次数清零？", new Runnable() {
                    @Override
                    public void run() {
                        Prefs.get(MainActivity.this).resetCounters();
                        refreshStats();
                        toast("统计已清零");
                    }
                });
                return true;
            }
        });

        swRootEnhance.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                if (loadingPrefs) return;
                if (checked) {
                    enableRootEnhance();
                } else {
                    disableRootEnhance();
                }
            }
        });

        ((Button) findViewById(R.id.btnRootHarden)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hardenOnce();
            }
        });

        btnRootGuard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleGuard();
            }
        });

        ((Button) findViewById(R.id.btnOpenKsu)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openKernelSU();
            }
        });

        // 权限已经开过、保活也开着，那第一次进界面就把通知权限要了（之前没要过的话）
        if (Prefs.get(this).isKeepAlive() && isAccEnabled()) ensureNotifPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
        // 页面深处的订阅地址输入框（EditText）会抢走 ScrollView 的初始焦点，
        // 系统随即把列表滚到那一行 —— 结果一进 App 顶部的状态卡就被顶出屏幕，
        // 用户以为「没开启」。布局里已用 descendantFocusability 抢回焦点，这里再补一次兜底。
        if (!scrolledHomeOnce) {
            scrolledHomeOnce = true;
            final ScrollView sv = (ScrollView) findViewById(R.id.scrollRoot);
            if (sv != null) {
                sv.post(new Runnable() {
                    @Override
                    public void run() {
                        sv.scrollTo(0, 0);
                    }
                });
            }
        }
    }

    private void refreshAll() {
        loadingPrefs = true;
        refreshServiceState();
        swRunning.setChecked(Prefs.get(this).isRunning());
        swToast.setChecked(Prefs.get(this).isToastOnHit());
        swLocalExclusive.setChecked(Prefs.get(this).isLocalExclusive());
        swKeepAlive.setChecked(Prefs.get(this).isKeepAlive());
        swRootEnhance.setChecked(Prefs.get(this).isRootEnhance());
        swAutoDetect.setChecked(Prefs.get(this).isAutoDetect());
        loadingPrefs = false;
        refreshStats();
        refreshTabBadges();
        // Android 12+ 禁止应用在后台启动前台服务。服务刚连上时进程还在后台，可能被拒；
        // 趁现在 App 在前台，补一次。
        if (Prefs.get(this).isKeepAlive() && SkipService.isConnected() && !SkipService.isForeground()) {
            SkipService.applyKeepAlive(this);
        }
        refreshKeepAliveState();
        refreshRootState();
        refreshSubs();
        refreshRules();
        refreshLocal();
        refreshLogs();
        // 顺便把常驻通知上的次数刷成最新（内部有节流 + 只在前台时生效）
        KeepAlive.refresh(this);
    }

    /**
     * 保活状态的体检结论。按严重程度排：服务没在跑 &gt; 未豁免电池优化 &gt; 一切正常。
     */
    private void refreshKeepAliveState() {
        boolean accOn = isAccEnabled();
        boolean alive = SkipService.isConnected();
        boolean whitelisted = KeepAlive.isBatteryWhitelisted(this);

        String text;
        int color;
        if (accOn && !alive) {
            text = getString(R.string.keepalive_state_dead);
            color = R.color.danger;
        } else if (!whitelisted) {
            text = getString(R.string.keepalive_state_bad);
            color = R.color.danger;
        } else {
            text = SkipService.isForeground()
                    ? getString(R.string.keepalive_state_fg)
                    : getString(R.string.keepalive_state_ok);
            color = R.color.ok;
        }
        tvKeepState.setText(text);
        tvKeepState.setTextColor(getColor(color));

        // 标题栏摘要：和上面同一套判定，不展开也能一眼看出保活正常不正常
        int keepSum;
        if (accOn && !alive) {
            keepSum = R.string.sum_keep_dead;
        } else if (!whitelisted) {
            keepSum = R.string.sum_keep_bad;
        } else {
            keepSum = R.string.sum_keep_ok;
        }
        tvKeepSummary.setText(keepSum);
        tvKeepSummary.setTextColor(getColor(color));

        btnBattery.setEnabled(!whitelisted);
        btnBattery.setText(whitelisted ? "已在电池优化白名单中 ✓" : getString(R.string.btn_battery));
    }

    /** Android 13+ 通知权限。拒了也不影响功能，只是常驻通知不显示。 */
    private void ensureNotifPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (KeepAlive.hasNotifPermission(this)) return;
        try {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_NOTIF) return;
        if (results != null && results.length > 0
                && results[0] != PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.toast_no_notif));
        }
    }

    private boolean isAccEnabled() {
        try {
            ComponentName cn = new ComponentName(this, SkipService.class);
            String flat = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) return false;
            return flat.contains(cn.flattenToString()) || flat.contains(cn.flattenToShortString());
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshServiceState() {
        boolean on = isAccEnabled();
        tvServiceState.setText(on ? R.string.state_on : R.string.state_off);
        tvServiceState.setTextColor(getColor(on ? R.color.ok : R.color.danger));
        btnOpenSettings.setText(on ? "打开系统无障碍设置" : getString(R.string.btn_open_settings));
        // 未开启时把「受限制的设置」引导卡片露出来：国产 ROM 上九成卡在这一步
        llRestrictTip.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    /**
     * 跳到本应用的系统详情页。国产 ROM 的「允许受限制的设置」就藏在这里的右上角 ⋮ 菜单。
     */
    private void openAppDetail() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            toast("点右上角 ⋮ →「允许受限制的设置」");
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_SETTINGS));
                toast("手动找到「快跳」→ 右上角 ⋮");
            } catch (Exception e2) {
                toast("请手动进入：设置 → 应用管理 → 快跳");
            }
        }
    }

    /**
     * 复制绕过受限设置的 ADB 命令，给那些详情页里没有 ⋮ 菜单的机型兜底。
     */
    private void copyAdbCmd() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("剪贴板不可用");
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("adb", getString(R.string.adb_cmd)));
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            toast("复制失败：" + e.getMessage());
        }
    }

    private void refreshStats() {
        Prefs p = Prefs.get(this);
        String text = "今日跳过  " + p.getTodayCount() + " 次\n累计跳过  " + p.getTotalCount()
                + " 次   ·   长按可清零";
        int auto = p.getAutoCount();
        if (auto > 0) text = text + "\n其中自动识别（无规则应用）  " + auto + " 次";
        tvStats.setText(text);
    }

    /**
     * 底部导航的角标：有东西被关停时把数字缀在标签后面，并把标签染成警示色。
     *
     * <p>拆成四页之后，用户关掉的东西最容易「看不见」—— 站在首页上完全感觉不到
     * 「应用」页里停用了几个、「规则」页里关了几组。角标就是给这两页留的一张便条。
     */
    private void refreshTabBadges() {
        if (tabLabels == null) return;
        Prefs p = Prefs.get(this);
        setTabBadge(PAGE_APPS, p.disabledAppCount());
        setTabBadge(PAGE_RULES, p.disabledGroupCount());
    }

    private void setTabBadge(int idx, int n) {
        TextView lb = tabLabels[idx];
        TextView ic = tabIcons[idx];
        if (lb == null || ic == null) return;
        int base = (idx == PAGE_APPS) ? R.string.tab_apps : R.string.tab_rules;
        lb.setText(n > 0 ? getString(R.string.tab_badge, getString(base), n) : getString(base));
        if (n <= 0) return;   // 没角标时颜色归 showPage 的选中态逻辑管，别在这里覆盖
        int c = getColor(page == idx ? R.color.brand : R.color.warn);
        lb.setTextColor(c);
        ic.setTextColor(c);
    }

    /**
     * 规则页的「全局规则」卡：包名 {@code *} 的节点，对所有应用生效。
     *
     * <p>为什么单独一张卡而不是并进下面的「本地规则」列表 —— 全局组的开关影响面是
     * 全部应用，用户必须一眼看清自己在关什么。对应 GKD 的全局规则组。
     *
     * <p>开关写的是 {@code *#组名}（真正的全局默认）；如果只想关某个应用里的这一组，
     * 去「应用」页点进那个应用，那里写的是 {@code 包名#组名}。
     */
    private void refreshGlobalGroups() {
        if (llGlobal == null) return;
        List<RuleStore.GroupView> gs = RuleStore.get(this).globalGroups();

        if (gs.isEmpty()) {
            llGlobal.removeAllViews();
            llGlobal.addView(GroupRows.hint(this, getString(R.string.global_empty)));
            tvGlobalSummary.setText(R.string.sum_none);
            tvGlobalSummary.setTextColor(getColor(R.color.text_sub));
            btnResetGlobal.setVisibility(View.GONE);
            return;
        }

        GroupRows.render(this, llGlobal, gs, "*", new GroupRows.Callback() {
            @Override
            public void onChanged() {
                refreshGlobalGroups();
                refreshTabBadges();
            }
        });

        int off = GroupRows.userOffCount(gs);
        tvGlobalSummary.setText(off > 0
                ? getString(R.string.sum_off_groups, off)
                : getString(R.string.sum_global_groups, gs.size(), GroupRows.ruleSum(gs)));
        tvGlobalSummary.setTextColor(getColor(off > 0 ? R.color.warn : R.color.text_sub));
        btnResetGlobal.setVisibility(off > 0 ? View.VISIBLE : View.GONE);
    }

    private void refreshRules() {
        RuleStore rs = RuleStore.get(this);
        Prefs p = Prefs.get(this);
        int ra = rs.remoteAppCount();
        int rr = rs.remoteRuleCount();
        String sub;
        if (!p.getSubUrls().isEmpty()) {
            sub = p.getSubUrls().size() + " 个订阅地址";
        } else if (rr > 0) {
            // 订阅层里有东西、但没有订阅地址 —— 是外部导入/刷入的规则集。
            // 不区分会让界面显示"使用内置规则"，用户误点「恢复出厂内置规则」就把规则清没了。
            sub = "已载入规则集（无订阅地址）";
        } else {
            sub = "无订阅（使用内置规则）";
        }
        tvRuleInfo.setText("当前生效：" + rs.appCount() + " 个应用 / " + rs.ruleCount() + " 条\n"
                + "订阅层 " + ra + " 应用 / " + rr + " 条　·　本地层 "
                + rs.localAppCount() + " 应用 / " + rs.localRuleCount() + " 条\n"
                + sub + "   ·   上次更新：" + DateUtil.full(p.getLastUpdate()));
        // 摘要必须是订阅层自己的数：这张卡片挂着「规则订阅」的标题，
        // 拿三层合并数会让人以为订阅里有 900 多个应用 —— 实际可能全在本地层。
        tvSubSummary.setText(rr == 0
                ? getString(R.string.sum_none)
                : getString(R.string.sum_rules, ra, rr));
    }

    /** 刷新本地规则区块：计数 + 分组列表。 */
    private void refreshLocal() {
        final RuleStore rs = RuleStore.get(this);
        int na = rs.localAppCount();
        int nr = rs.localRuleCount();
        tvLocalInfo.setText(na == 0
                ? getString(R.string.local_info_empty)
                : ("本地规则：" + na + " 个应用 / " + nr + " 条"));
        tvLocalSummary.setText(na == 0
                ? getString(R.string.sum_none)
                : getString(R.string.sum_local, na, nr));

        // 订阅层空着就没得转存，把按钮置灰（点下去也只会弹一句提示）
        Button bl = (Button) findViewById(R.id.btnLocalize);
        if (bl != null) {
            boolean can = rs.remoteRuleCount() > 0;
            bl.setEnabled(can);
            bl.setAlpha(can ? 1f : 0.45f);
        }

        llLocals.removeAllViews();
        final List<RuleSet.AppGroup> groups = rs.localGroups();
        if (groups.isEmpty()) {
            llLocals.addView(hint(getString(R.string.local_empty)));
            return;
        }
        for (final RuleSet.AppGroup g : groups) {
            final String label = (g.name != null && g.name.length() > 0) ? g.name : g.packageName;
            TextView tv = new TextView(this);
            tv.setText("· " + label + "（" + g.groupCount() + " 组 / " + g.ruleCount() + " 条）\n   " + g.packageName);
            tv.setTextSize(12f);
            tv.setLineSpacing(dp(2), 1f);
            tv.setTextColor(getColor(R.color.text_main));
            tv.setPadding(0, dp(6), 0, dp(6));
            tv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    localGroupMenu(g, label);
                    return true;
                }
            });
            llLocals.addView(tv);
            llLocals.addView(divider());
        }
        llLocals.addView(hint(getString(R.string.local_tip)));
    }

    private void localGroupMenu(final RuleSet.AppGroup g, String label) {
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(new String[]{"查看 / 复制 JSON", "删除「" + label + "」的本地规则"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            showJsonDialog(g);
                        } else {
                            confirm("删除本地规则", "移除「" + g.packageName + "」的全部本地规则？", new Runnable() {
                                @Override
                                public void run() {
                                    RuleStore.get(MainActivity.this).removeLocalApp(g.packageName);
                                    refreshLocal();
                                    refreshRules();
                                    toast("已删除");
                                }
                            });
                        }
                    }
                })
                .show();
    }

    // ---------------- 本地规则：转存 / 导出 ----------------

    /**
     * 把订阅层整体转存为本地规则。
     *
     * <p>必须连订阅层一起清空：三层是叠加生效的（本地 ∪ 出厂 ∪ 订阅），只搬不清等于
     * 同一份规则在两层各留一份，合并后变成双份 —— 引擎按条独立执行，表现就是同一
     * 个按钮连点两下。清空订阅层后生效总数不变，规则只是换了归属层。
     */
    private void localizeDialog() {
        RuleStore rs = RuleStore.get(this);
        final int ra = rs.remoteAppCount();
        final int rr = rs.remoteRuleCount();
        if (rr == 0) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.localize_title)
                    .setMessage(R.string.localize_none)
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.localize_title)
                .setMessage(getString(R.string.localize_msg, ra, rr))
                .setNegativeButton("取消", null)
                .setPositiveButton("转存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        doLocalize();
                    }
                })
                .show();
    }

    /**
     * 真正执行转存。解析 + 序列化三百多万字符的 JSON 要几百毫秒到一两秒，
     * 放主线程会卡住界面（用户以为没反应就会再点一次），所以挪到后台线程，
     * 前台给一个不可取消的进度框。
     */
    private void doLocalize() {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(R.string.localize_title)
                .setMessage("正在转存…")
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final RuleStore.MoveResult r = RuleStore.get(MainActivity.this).localizeRemote();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            progress.dismiss();
                        } catch (Exception ignored) {
                        }
                        if (r == null) {
                            toast(getString(R.string.localize_none));
                            return;
                        }
                        refreshLocal();
                        refreshRules();
                        LogStore.get(MainActivity.this).add("订阅规则转存为本地规则："
                                + r.movedApps + " 应用 / " + r.movedRules + " 条");
                        toast(getString(R.string.localize_ok, r.movedApps, r.movedRules,
                                r.totalApps, r.totalRules, r.movedRules - r.addedRules));
                    }
                });
            }
        }, "autoskip-localize").start();
    }

    /** 把本地层规则导成一个真文件（公共下载目录，无需存储权限）。 */
    private void exportLocalFile() {
        final RuleStore rs = RuleStore.get(this);
        if (rs.localRuleCount() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.export_title)
                    .setMessage(R.string.export_empty)
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(R.string.export_title)
                .setMessage("正在导出…")
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final RuleExport.Result res =
                        RuleExport.write(MainActivity.this, rs.exportLocal(), "快跳-本地规则");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            progress.dismiss();
                        } catch (Exception ignored) {
                        }
                        showExportResult(res);
                    }
                });
            }
        }, "autoskip-export").start();
    }

    private void showExportResult(final RuleExport.Result res) {
        if (res.error != null || res.uri == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.export_title)
                    .setMessage(getString(R.string.export_failed, String.valueOf(res.error)))
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        LogStore.get(this).add("导出本地规则：" + res.path + "（" + fmtSize(res.size) + "）");
        String msg = getString(R.string.export_ok, res.path, fmtSize(res.size));
        if (!res.shareable) msg = msg + "\n\n" + getString(R.string.export_no_share);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.export_title)
                .setMessage(msg)
                .setNegativeButton(R.string.btn_copy_path, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copyToClipboard("path", res.path);
                    }
                });
        if (res.shareable) {
            b.setPositiveButton(R.string.btn_share_file, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    shareFile(res.uri);
                }
            });
        } else {
            b.setPositiveButton("知道了", null);
        }
        b.show();
    }

    private void shareFile(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.export_title)));
        } catch (Exception e) {
            toast("无法分享：" + e.getMessage());
        }
    }

    private static String fmtSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return (n / 1024) + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0);
    }

    /** 把单个应用组的规则序列化成 JSON，方便复制出来备份或分享。 */
    private void showJsonDialog(RuleSet.AppGroup g) {
        RuleSet one = new RuleSet();
        one.apps.add(g);
        final String json = one.toJson();
        new AlertDialog.Builder(this)
                .setTitle("规则 JSON")
                .setMessage(json)
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copyToClipboard("rules", json);
                    }
                })
                .show();
    }

    private void copyToClipboard(String tag, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("剪贴板不可用");
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText(tag, text));
            toast("已复制到剪贴板");
        } catch (Exception e) {
            toast("复制失败：" + e.getMessage());
        }
    }

    // ---------------- 本地规则的三个入口 ----------------

    /** 入口一：系统文件选择器挑一个 json / json5 / txt 规则文件。 */
    private void pickLocalFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_FILE);
        } catch (Exception e) {
            try {
                Intent i2 = new Intent(Intent.ACTION_GET_CONTENT);
                i2.addCategory(Intent.CATEGORY_OPENABLE);
                i2.setType("*/*");
                startActivityForResult(i2, REQ_PICK_FILE);
            } catch (Exception e2) {
                toast("系统没有可用的文件选择器，请改用「粘贴 JSON」");
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK_FILE) return;
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            String text = RuleStore.readAll(in);
            doImportLocal(text, "文件");
        } catch (Exception e) {
            toast("读取失败：" + e.getMessage());
        }
    }

    /** 入口二：直接粘贴规则文本。 */
    private void pasteImportDialog() {
        final EditText et = new EditText(this);
        et.setHint("{\"apps\":[{\"packageName\":\"com.example.app\",\"rules\":[{\"matchType\":\"text\",\"matches\":[\"跳过\"]}]}]}");
        et.setTextSize(12f);
        et.setMinLines(6);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        ScrollView sv = new ScrollView(this);
        int p = dp(16);
        sv.setPadding(p, dp(8), p, 0);
        sv.addView(et);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_import_title)
                .setView(sv)
                .setNegativeButton("取消", null)
                .setNeutralButton(R.string.btn_fill_clip, null)
                .setPositiveButton("导入", null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String clip = readClipboard();
                        if (clip == null || clip.trim().length() == 0) {
                            toast("剪贴板是空的");
                        } else {
                            et.setText(clip);
                        }
                    }
                });
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String text = et.getText().toString();
                        if (text.trim().length() == 0) {
                            toast("先粘贴规则内容");
                            return;
                        }
                        if (doImportLocal(text, "粘贴")) dlg.dismiss();
                    }
                });
            }
        });
        dlg.show();
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) return null;
            ClipData.Item it = cm.getPrimaryClip().getItemAt(0);
            return it == null || it.getText() == null ? null : it.getText().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把文本并入本地规则层。
     *
     * @return 是否导入成功（失败时已弹提示）
     */
    private boolean doImportLocal(String text, String source) {
        RuleStore rs = RuleStore.get(this);
        RuleSet added = rs.importLocal(text);
        if (added == null) {
            toast("没解析出规则。需含 packageName 与 matches 字段");
            return false;
        }
        refreshLocal();
        refreshRules();
        String msg = "已导入 " + added.apps.size() + " 个应用 / " + added.ruleCount() + " 条规则";
        LogStore.get(this).add("导入本地规则（" + source + "）：" + added.apps.size() + " 应用 / "
                + added.ruleCount() + " 规则");
        toast(msg);
        return true;
    }

    /** 入口三：手动填一条规则。 */
    private void addRuleDialog() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, dp(8), p, 0);

        final EditText etPkg = field(box, getString(R.string.hint_pkg), InputType.TYPE_CLASS_TEXT);
        Button btnCur = new Button(this);
        btnCur.setText(R.string.btn_fill_current);
        box.addView(btnCur);

        final EditText etName = field(box, getString(R.string.hint_rule_name), InputType.TYPE_CLASS_TEXT);

        box.addView(label("匹配方式"));
        final Spinner spType = spinner(box, MATCH_LABELS);

        final EditText etMatch = field(box, getString(R.string.hint_matches), InputType.TYPE_CLASS_TEXT);

        final CheckBox cbRegex = new CheckBox(this);
        cbRegex.setText(R.string.cb_regex);
        cbRegex.setTextSize(13f);
        box.addView(cbRegex);

        final CheckBox cbActRegex = new CheckBox(this);
        cbActRegex.setText(R.string.cb_activity_regex);
        cbActRegex.setTextSize(13f);

        box.addView(label("动作"));
        final Spinner spAction = spinner(box, ACTION_LABELS);

        final EditText etAct = field(box, getString(R.string.hint_activity), InputType.TYPE_CLASS_TEXT);
        box.addView(cbActRegex);

        final EditText etMax = field(box, getString(R.string.hint_maxclick),
                InputType.TYPE_CLASS_NUMBER);
        etMax.setText("2");

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_add_rule_title)
                .setView(sv)
                .setNegativeButton("取消", null)
                .setPositiveButton("添加", null)
                .create();

        btnCur.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String pkg = SkipService.lastPackage();
                if (pkg == null || pkg.length() == 0) {
                    toast("还没捕捉到应用。先去目标应用里停留一下再回来");
                    return;
                }
                etPkg.setText(pkg);
                String lbl = appLabel(pkg);
                if (lbl != null && etName.getText().toString().trim().length() == 0) {
                    etName.setText(lbl);
                }
            }
        });

        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String pkg = etPkg.getText().toString().trim();
                        if (pkg.length() == 0) {
                            toast("请填写目标应用包名");
                            return;
                        }
                        List<String> ms = splitList(etMatch.getText().toString());
                        if (ms.isEmpty()) {
                            toast("请填写关键词");
                            return;
                        }
                        boolean regex = cbRegex.isChecked();
                        if (regex) {
                            for (String m : ms) {
                                try {
                                    Pattern.compile(m);
                                } catch (Exception e) {
                                    toast("正则写错了：" + m);
                                    return;
                                }
                            }
                        }
                        int ti = spType.getSelectedItemPosition();
                        if (ti < 0 || ti >= MATCH_TYPES.length) ti = 0;
                        int ai = spAction.getSelectedItemPosition();
                        if (ai < 0 || ai >= ACTION_VALUES.length) ai = 0;

                        Rule r = new Rule();
                        String nm = etName.getText().toString().trim();
                        r.name = nm.length() > 0 ? nm : ms.get(0);
                        r.matchType = MATCH_TYPES[ti];
                        r.regex = regex;
                        r.matches = ms;
                        r.action = ACTION_VALUES[ai];
                        r.activityIds = splitList(etAct.getText().toString());
                        r.maxClick = parseInt(etMax.getText().toString(), 2);
                        r.cooldown = 900;
                        r.delay = 0;
                        r.enabled = true;

                        RuleStore.get(MainActivity.this).addLocalRule(pkg, nm, r);
                        refreshLocal();
                        refreshRules();
                        toast("已添加，回到目标应用试试");
                        dlg.dismiss();
                    }
                });
            }
        });
        dlg.show();
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(getColor(R.color.text_sub));
        tv.setPadding(0, dp(10), 0, dp(2));
        return tv;
    }

    private EditText field(LinearLayout parent, String hint, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(13f);
        et.setSingleLine(true);
        et.setInputType(inputType);
        parent.addView(et);
        return et;
    }

    private Spinner spinner(LinearLayout parent, String[] items) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        parent.addView(sp);
        return sp;
    }

    /** 按中英文逗号 / 分号 / 换行切分成关键词列表。 */
    private static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String t : s.split("[,，;；\\n\\r]+")) {
            t = t.trim();
            if (t.length() > 0) out.add(t);
        }
        return out;
    }

    private static int parseInt(String s, int def) {
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    /** 尽力解析应用名；拿不到就返回 null（某些系统对包可见性有限制）。 */
    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            return cs == null ? null : cs.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void refreshSubs() {
        llSubs.removeAllViews();
        List<String> urls = Prefs.get(this).getSubUrls();
        if (urls.isEmpty()) {
            llSubs.addView(hint(getString(R.string.sub_empty)));
            return;
        }
        for (final String u : urls) {
            TextView tv = new TextView(this);
            tv.setText("· " + u);
            tv.setTextSize(12f);
            tv.setTextColor(getColor(R.color.text_main));
            tv.setPadding(0, dp(6), 0, dp(6));
            tv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirm("删除订阅", "移除这个订阅地址？\n" + u, new Runnable() {
                        @Override
                        public void run() {
                            List<String> list = Prefs.get(MainActivity.this).getSubUrls();
                            list.remove(u);
                            Prefs.get(MainActivity.this).saveSubUrls(list);
                            refreshSubs();
                            refreshRules();
                        }
                    });
                    return true;
                }
            });
            llSubs.addView(tv);
        }
        llSubs.addView(hint(getString(R.string.sub_tip)));
    }

    private void refreshLogs() {
        llLogs.removeAllViews();
        List<String> logs = LogStore.get(this).list();

        tvLogSummary.setText(getString(R.string.sum_logs, logs.size()));

        if (logs.isEmpty()) {
            llLogs.addView(hint(getString(R.string.log_empty)));
            btnLogMore.setVisibility(View.GONE);
            return;
        }
        // 默认只铺最近几条 —— 早些时候一次铺 60 条，整页被撑成好几屏，
        // 上面的保活 / 订阅区块全被埋掉，这才是「界面凌乱」的最大来源。
        int show = logExpanded
                ? Math.min(logs.size(), LOG_MAX)
                : Math.min(logs.size(), LOG_PREVIEW);
        for (int i = 0; i < show; i++) {
            TextView tv = new TextView(this);
            tv.setText(logs.get(i));
            tv.setTextSize(12f);
            tv.setTextColor(getColor(R.color.text_main));
            tv.setLineSpacing(dp(2), 1f);
            tv.setPadding(0, dp(6), 0, dp(6));
            llLogs.addView(tv);
            if (i != show - 1) llLogs.addView(divider());
        }

        if (logs.size() > LOG_PREVIEW) {
            btnLogMore.setVisibility(View.VISIBLE);
            btnLogMore.setText(logExpanded
                    ? getString(R.string.btn_log_less, LOG_PREVIEW)
                    : getString(R.string.btn_log_more, logs.size()));
        } else {
            btnLogMore.setVisibility(View.GONE);
        }
    }

    private void addSub() {
        String url = etUrl.getText().toString().trim();
        if (url.length() == 0) {
            toast("请先粘贴规则订阅地址");
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("地址需要以 http:// 或 https:// 开头");
            return;
        }
        List<String> urls = Prefs.get(this).getSubUrls();
        if (urls.contains(url)) {
            toast("这个地址已经在列表里了");
            return;
        }
        urls.add(url);
        Prefs.get(this).saveSubUrls(urls);
        etUrl.setText("");
        refreshSubs();
        refreshRules();
        toast("已添加，点「更新全部规则」开始拉取");
    }

    private void updateRules() {
        final List<String> urls = Prefs.get(this).getSubUrls();
        if (urls.isEmpty()) {
            toast("先添加一个订阅地址");
            return;
        }
        btnUpdate.setEnabled(false);
        btnUpdate.setText("更新中…");
        RuleStore.get(this).update(urls, new RuleStore.Callback() {
            @Override
            public void done(boolean ok, String msg) {
                btnUpdate.setEnabled(true);
                btnUpdate.setText(R.string.btn_update);
                toast(msg);
                refreshRules();
                refreshLogs();
            }
        });
    }

    // ---------------- root 增强 ----------------

    /**
     * 打开 root 增强：探一次授权 → 加固 → 部署守护模块。
     *
     * <p>全程在子线程 —— 第一次会弹 KernelSU 的授权窗，用户不点就一直阻塞，
     * 放主线程必 ANR。
     */
    private void enableRootEnhance() {
        if (rootBusy) return;
        rootBusy = true;
        tvRootState.setText(R.string.toast_root_granting);
        toast(getString(R.string.toast_root_granting));

        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean granted = RootShell.probe();
            RootShell.Result harden = granted ? RootGuard.harden(app) : null;
            boolean hardened = harden != null && harden.ok;
            RootShell.Result guard = hardened ? RootGuard.installGuard(app) : null;

            Prefs.get(app).setRootGranted(granted);
            Prefs.get(app).setGuardInstalled(guard != null && guard.ok);
            // 加固没成功就不算开启，开关弹回去，免得给用户"已经打开了"的错觉
            Prefs.get(app).setRootEnhance(hardened);

            runOnUiThread(() -> {
                rootBusy = false;
                loadingPrefs = true;
                swRootEnhance.setChecked(hardened);
                loadingPrefs = false;
                if (hardened) {
                    toast(getString(R.string.toast_root_done));
                } else {
                    // 失败几乎总是同一个原因：KernelSU 的授权窗被系统拦了。
                    // 与其只给一句 toast，不如直接把该做的事摆出来。
                    showKsuGuide();
                }
                refreshRootState();
            });
        }, "autoskip-root-enable").start();
    }

    /** 关掉 root 增强：移除守护模块 + 解除内存锁。 */
    private void disableRootEnhance() {
        Prefs.get(this).setRootEnhance(false);
        if (rootBusy) return;
        rootBusy = true;
        tvRootState.setText("正在移除开机守护…");

        final Context app = getApplicationContext();
        new Thread(() -> {
            RootShell.Result r = RootGuard.removeGuard(app);
            if (r.ok) Prefs.get(app).setGuardInstalled(false);
            runOnUiThread(() -> {
                rootBusy = false;
                toast(getString(r.ok ? R.string.toast_guard_off : R.string.toast_root_fail));
                refreshRootState();
            });
        }, "autoskip-root-disable").start();
    }

    /** 手动补一刀加固（oom 锁会被系统在前后台切换时改回去）。 */
    private void hardenOnce() {
        if (rootBusy) return;
        rootBusy = true;
        tvRootState.setText(R.string.toast_root_granting);

        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean granted = Prefs.get(app).isRootGranted() || RootShell.probe();
            RootShell.Result h = granted ? RootGuard.harden(app) : null;
            boolean ok = h != null && h.ok;
            Prefs.get(app).setRootGranted(granted);
            runOnUiThread(() -> {
                rootBusy = false;
                toast(getString(ok ? R.string.toast_root_done : R.string.toast_root_fail));
                refreshRootState();
            });
        }, "autoskip-root-harden").start();
    }

    /** 按当前部署状态，装上或卸掉 KernelSU 守护模块。 */
    private void toggleGuard() {
        if (rootBusy) return;
        final boolean installed = guardInstalled;
        rootBusy = true;
        tvRootState.setText(installed ? "正在移除开机守护…" : "正在部署开机守护…");

        final Context app = getApplicationContext();
        new Thread(() -> {
            RootShell.Result r = installed ? RootGuard.removeGuard(app) : RootGuard.installGuard(app);
            if (r.ok) Prefs.get(app).setGuardInstalled(!installed);
            runOnUiThread(() -> {
                rootBusy = false;
                toast(getString(r.ok
                        ? (installed ? R.string.toast_guard_off : R.string.toast_guard_on)
                        : R.string.toast_root_fail));
                refreshRootState();
            });
        }, "autoskip-root-guard").start();
    }

    /** 引导用户去 KernelSU 管理器手动授权（首次必须做一次，弹窗会被国产 ROM 拦）。 */
    private void showKsuGuide() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.ksu_help_title)
                .setMessage(R.string.ksu_help_body)
                .setNegativeButton("知道了", null)
                .setPositiveButton(R.string.btn_open_ksu, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        openKernelSU();
                    }
                })
                .show();
    }

    /** 打开 KernelSU 管理器。直接指组件比 getLaunchIntentForPackage 稳。 */
    private void openKernelSU() {
        Intent i = new Intent();
        i.setComponent(new ComponentName("me.weishu.kernelsu", "me.weishu.kernelsu.ui.MainActivity"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            toast("底部切到「超级用户」，把「快跳」设为允许 ROOT");
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i2 = getPackageManager().getLaunchIntentForPackage("me.weishu.kernelsu");
            if (i2 != null) {
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i2);
                toast("底部切到「超级用户」，把「快跳」设为允许 ROOT");
                return;
            }
        } catch (Throwable ignored) {
        }
        toast("没能打开 KernelSU 管理器，请手动打开 → 超级用户");
    }

    /**
     * 刷新 root 区块的显示。
     *
     * <p>只在「确实用过 root」时才去跑 su —— 没 root 的机器上每次进界面都弹一句
     * {@code su: not found} 毫无意义，也白等一次超时。
     */
    private void refreshRootState() {
        if (rootBusy) return;
        boolean enhance = Prefs.get(this).isRootEnhance();
        boolean granted = Prefs.get(this).isRootGranted();
        if (!enhance && !granted) {
            applyRootState(false, null);
            return;
        }
        new Thread(() -> {
            final boolean ok = Prefs.get(getApplicationContext()).isRootGranted();
            RootGuard.Status st = ok ? RootGuard.status(getApplicationContext()) : null;
            // 发现内存锁掉了就当场补一刀。守护脚本 20 秒才轮一次，而用户正盯着这一行
            // 看结果 —— 与其显示「未锁定，稍后自动重试」，不如这会儿就修好。
            if (st != null && !"none".equals(st.oomAdj) && !st.oomLocked()) {
                RootGuard.harden(getApplicationContext());
                st = RootGuard.status(getApplicationContext());
            }
            final RootGuard.Status out = st;
            runOnUiThread(() -> applyRootState(ok, out));
        }, "autoskip-root-state").start();
    }

    private void applyRootState(boolean granted, RootGuard.Status st) {
        boolean enhance = Prefs.get(this).isRootEnhance();

        if (!enhance) {
            String text = getString(R.string.root_state_off);
            if (granted) text += "\n（这台机器上 root 可用，可直接开启）";
            tvRootState.setText(text);
            tvRootState.setTextColor(getColor(R.color.text_main));
            tvRootSummary.setText(R.string.sum_root_off);
            tvRootSummary.setTextColor(getColor(R.color.text_sub));
            guardInstalled = false;
            btnRootGuard.setEnabled(false);
            btnRootGuard.setText(R.string.btn_root_guard_on);
            return;
        }

        if (!granted) {
            tvRootState.setText(R.string.root_state_none);
            tvRootState.setTextColor(getColor(R.color.danger));
            tvRootSummary.setText(R.string.sum_root_none);
            tvRootSummary.setTextColor(getColor(R.color.danger));
            guardInstalled = false;
            btnRootGuard.setEnabled(false);
            btnRootGuard.setText(R.string.btn_root_guard_on);
            return;
        }

        StringBuilder sb = new StringBuilder(getString(R.string.root_state_on));
        sb.append("\n· 内存优先级：");
        if (st == null || "none".equals(st.oomAdj)) {
            sb.append("进程不在");
        } else if (st.oomLocked()) {
            sb.append(st.oomAdj).append("（已锁定）");
        } else {
            sb.append(st.oomAdj).append("（未锁定，稍后自动重试）");
        }
        sb.append("\n· 守护模块：").append(st != null && st.moduleInstalled ? "已部署" : "未部署");
        sb.append("\n· 守护进程：").append(st != null && st.guardRunning() ? "运行中" : "未运行");

        boolean allGood = st != null && st.oomLocked() && st.moduleInstalled && st.guardRunning();
        tvRootState.setText(sb.toString());
        tvRootState.setTextColor(getColor(allGood ? R.color.ok : R.color.text_main));
        tvRootSummary.setText(R.string.sum_root_on);
        tvRootSummary.setTextColor(getColor(allGood ? R.color.ok : R.color.warn));

        guardInstalled = st != null && st.moduleInstalled;
        btnRootGuard.setEnabled(true);
        btnRootGuard.setText(guardInstalled ? R.string.btn_root_guard_off : R.string.btn_root_guard_on);
    }

    private void confirm(String title, String msg, final Runnable onOk) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        onOk.run();
                    }
                })
                .show();
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setLineSpacing(dp(2), 1f);
        tv.setTextColor(getColor(R.color.text_sub));
        tv.setPadding(0, dp(4), 0, dp(4));
        return tv;
    }

    private View divider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        v.setBackgroundColor(getColor(R.color.divider));
        return v;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
