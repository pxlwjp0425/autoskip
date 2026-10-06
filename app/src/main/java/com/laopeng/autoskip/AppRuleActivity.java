package com.laopeng.autoskip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用详情页 —— 仿 GKD 的「应用规则」页：列出这个应用的全部规则组，逐组开关。
 *
 * <p>为什么要比「整应用开关」再细一档：用户想说的常常是「这个应用的开屏广告别管，
 * 但更新提示还是要跳」，一刀切把整个应用摘出去太粗。
 *
 * <p>两块内容分开画：
 * <ul>
 *   <li><b>专属规则</b> —— 这个应用自己节点下的组（含同名全局组的条数，见下）；</li>
 *   <li><b>全局规则</b> —— 只活在 {@code *} 节点里、这个应用没有专属组的那些组。</li>
 * </ul>
 *
 * <p>两块的开关写的都是同一个键 {@code 包名#组名}，也就是「在这个应用里关掉这个组」。
 * {@link RuleStore#rulesFor(String)} 把应用节点和 {@code *} 节点都按真实包名过滤，
 * 所以应用级的键确实能连带压住同名的全局组 —— 这是实测过的（淘宝开屏广告
 * 24 条 → 关掉「开屏广告」组 → 21 条，少的正是它自己的 1 条 + 全局的 2 条）。
 * 「对所有应用都关」那是「规则」页全局卡的事，写的是 {@code *#组名}，别混。
 */
public class AppRuleActivity extends Activity {

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_LABEL = "label";

    private String pkg = "";
    private String label = "";

    private TextView tvGroupInfo;
    private LinearLayout llAppGroups;
    private LinearLayout llGlobalGroups;
    private View cardGlobal;
    private Button btnResetGroups;

    private final GroupRows.Callback onSwitch = new GroupRows.Callback() {
        @Override
        public void onChanged() {
            reload();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_app_rule);

        pkg = getIntent().getStringExtra(EXTRA_PKG);
        if (pkg == null) pkg = "";
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (label == null || label.length() == 0) label = appLabel(pkg);

        ((TextView) findViewById(R.id.tvTitle)).setText(label);
        findViewById(R.id.tvBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        tvGroupInfo = (TextView) findViewById(R.id.tvGroupInfo);
        llAppGroups = (LinearLayout) findViewById(R.id.llAppGroups);
        llGlobalGroups = (LinearLayout) findViewById(R.id.llGlobalGroups);
        cardGlobal = findViewById(R.id.cardGlobal);

        btnResetGroups = (Button) findViewById(R.id.btnResetGroups);
        btnResetGroups.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        // 第一次画在 onResume 里做（它一定会跟在 onCreate 后面跑），
        // 这样从不重复画两遍，也让「从系统设置页回来」时能自动刷新开关状态。
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<RuleStore.GroupView> all = RuleStore.get(this).groupsFor(pkg);
        List<RuleStore.GroupView> own = new ArrayList<>();
        List<RuleStore.GroupView> glob = new ArrayList<>();
        for (RuleStore.GroupView v : all) {
            if (v.inApp) {
                own.add(v);
            } else if (v.inGlobal) {
                glob.add(v);
            }
        }

        if (own.isEmpty()) {
            llAppGroups.removeAllViews();
            llAppGroups.addView(GroupRows.hint(this, getString(R.string.app_rules_group_none)));
        } else {
            GroupRows.render(this, llAppGroups, own, pkg, onSwitch);
        }

        // 摘要按「两块加起来」算，不按上面那块算 —— 列表行的条数用的是同一个口径
        // （RuleStore.appRuleCounts 按组名归并三层 × 本应用 + 全局），两边必须对得上，
        // 否则同一个应用在列表里显示 24、点进来显示 21，用户只会当成 bug。
        if (!all.isEmpty()) {
            tvGroupInfo.setText(getString(R.string.app_rules_summary, all.size(), GroupRows.ruleSum(all))
                    + "\n" + getString(R.string.app_rules_intro));
        } else {
            tvGroupInfo.setText(getString(R.string.app_rules_intro));
        }

        // 只列这个应用没有专属组的那些全局组 —— 两边都有的组已经在上面列过一次了，
        // 它的开关本来就是应用级的，画两遍只会让人以为有两个开关。
        if (glob.isEmpty()) {
            cardGlobal.setVisibility(View.GONE);
        } else {
            cardGlobal.setVisibility(View.VISIBLE);
            GroupRows.render(this, llGlobalGroups, glob, pkg, onSwitch);
        }

        btnResetGroups.setVisibility(GroupRows.userOffCount(all) > 0 ? View.VISIBLE : View.GONE);
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.btn_reset_groups)
                .setMessage(R.string.dlg_reset_groups)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.btn_reset_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.get(AppRuleActivity.this).clearDisabledGroups(pkg);
                        reload();
                        toast(getString(R.string.toast_groups_reset));
                    }
                })
                .show();
    }

    /** 拿不到应用名就退回包名，别让标题空着。 */
    private String appLabel(String p) {
        if (p == null || p.length() == 0) return getString(R.string.title_app_list);
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(p, 0);
            String s = String.valueOf(ai.loadLabel(pm));
            if (s != null && s.length() > 0 && !"null".equals(s)) return s;
        } catch (Throwable ignored) {
        }
        return p;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
