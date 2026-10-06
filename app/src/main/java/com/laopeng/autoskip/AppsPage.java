package com.laopeng.autoskip;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「应用」页的控制器 —— 把它从独立的 Activity 改成可嵌进主界面的页面。
 *
 * <p>为什么要这么改：底部导航的四页是同一个 Activity 里的四个容器
 * （零依赖，没有 Fragment），所以「应用列表」不能再是一个 Activity，
 * 得变成一个只管自己那块 View 的控制器。逻辑跟原来一模一样，只是
 * {@code findViewById} 从 Activity 换成页面根 View。
 *
 * <p>两个开关的分工：
 * <ul>
 *   <li>行右侧的 <b>Switch</b> —— 整应用摘出去（规则 + 自动识别一起停）；</li>
 *   <li>点整行 → {@link AppRuleActivity} —— 进到这个应用内部，按规则组逐组关。</li>
 * </ul>
 *
 * <p>列表口径：有启动图标的应用（用户能点开的才谈得上开屏广告）+ 任何在三层规则里
 * 有专属规则组的应用（即使没图标，上游也可能写了规则，要能关掉）。
 */
class AppsPage {

    static final int MODE_ALL = 0;
    static final int MODE_RULES = 1;
    static final int MODE_OFF = 2;

    /** 一行 = 一个应用。图标在后台线程就取好，避免滑动时主线程解码。 */
    static final class Row {
        String pkg = "";
        String label = "";
        String sub = "";
        Drawable icon;
        boolean hasRules;
        boolean disabled;
    }

    private final Activity act;
    private final List<Row> all = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();

    private Adapter adapter;
    private TextView tvInfo;
    private TextView tvEmpty;
    private Button btnReset;
    private Button btnAll;
    private Button btnRules;
    private Button btnOff;

    private int mode = MODE_ALL;
    private String query = "";
    /**
     * 列表正在建。默认 false —— 这一页**不在启动时加载**（见 {@link #bind}）。
     */
    private boolean loading;
    private boolean bound;
    private int loadSeq;

    AppsPage(Activity a) {
        act = a;
    }

    /**
     * 把这一页的控件接起来。root 传 page_apps 的根 View。
     *
     * <p>只接控件、不建列表：这一页要遍历全部已安装应用取名字和图标（三百来个，一两秒），
     * 而底部导航的另外三页完全用不到它。所以推迟到用户第一次真点进「应用」页时才建，
     * 由 {@link #refresh()} 触发。代价是初次进来会看到一瞬间的「正在读取应用列表…」。
     */
    void bind(View root) {
        tvInfo = (TextView) root.findViewById(R.id.tvAppListInfo);
        tvEmpty = (TextView) root.findViewById(R.id.tvAppListEmpty);
        EditText etSearch = (EditText) root.findViewById(R.id.etSearch);
        btnReset = (Button) root.findViewById(R.id.btnResetApps);
        btnAll = (Button) root.findViewById(R.id.btnFilterAll);
        btnRules = (Button) root.findViewById(R.id.btnFilterRules);
        btnOff = (Button) root.findViewById(R.id.btnFilterOff);

        adapter = new Adapter();
        ListView lv = (ListView) root.findViewById(R.id.lvApps);
        lv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString();
                applyFilter();
            }
        });

        btnAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(MODE_ALL);
            }
        });
        btnRules.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(MODE_RULES);
            }
        });
        btnOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode(MODE_OFF);
            }
        });

        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });

        bound = true;
        styleFilters();
    }

    /**
     * 拉列表：第一次是建，之后再调就是重建（比如从详情页关了几组回来）。
     *
     * <p>正在建的时候直接返回，别把同一份活儿排两遍 —— 切页会连着触发好几次。
     */
    void refresh() {
        if (!bound || loading) return;
        loadAsync();
    }

    private void setMode(int m) {
        mode = m;
        styleFilters();
        applyFilter();
    }

    private void styleFilters() {
        Button[] bs = {btnAll, btnRules, btnOff};
        int[] ms = {MODE_ALL, MODE_RULES, MODE_OFF};
        for (int i = 0; i < bs.length; i++) {
            boolean on = mode == ms[i];
            bs[i].setTextColor(act.getColor(on ? R.color.brand : R.color.text_sub));
            bs[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    // ---------------- 数据加载 ----------------

    private void loadAsync() {
        loading = true;
        final int seq = ++loadSeq;
        tvInfo.setText(R.string.app_list_loading);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Row> rows = buildRows();
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 连着刷新两次时，只认最后一次的结果，避免旧结果盖掉新的
                        if (seq != loadSeq) return;
                        all.clear();
                        all.addAll(rows);
                        loading = false;
                        applyFilter();
                    }
                });
            }
        }, "autoskip-applist").start();
    }

    private List<Row> buildRows() {
        List<Row> out = new ArrayList<>();
        PackageManager pm = act.getPackageManager();
        Prefs prefs = Prefs.get(act);
        Set<String> disabled = prefs.getDisabledApps();
        RuleStore store = RuleStore.get(act);
        String self = act.getPackageName();

        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            apps = new ArrayList<>();
        }
        for (ApplicationInfo ai : apps) {
            if (ai == null || ai.packageName == null) continue;
            if (self.equals(ai.packageName)) continue;

            boolean launchable;
            try {
                launchable = pm.getLaunchIntentForPackage(ai.packageName) != null;
            } catch (Throwable t) {
                launchable = false;
            }
            boolean hasRules = store.hasAppRules(ai.packageName);
            if (!launchable && !hasRules) continue;

            Row r = new Row();
            r.pkg = ai.packageName;
            try {
                r.label = String.valueOf(ai.loadLabel(pm));
            } catch (Throwable t) {
                r.label = ai.packageName;
            }
            r.hasRules = hasRules;
            r.disabled = disabled.contains(ai.packageName);

            if (hasRules) {
                int[] c = store.appRuleCounts(ai.packageName);
                r.sub = act.getString(R.string.app_rules_n, c[1]);
            } else {
                r.sub = act.getString(R.string.app_rules_none);
            }
            if (!launchable) r.sub = r.sub + " · " + act.getString(R.string.app_no_icon);

            try {
                r.icon = ai.loadIcon(pm);
            } catch (Throwable ignored) {
            }
            out.add(r);
        }

        // 已停用的排最前（回来就能看见自己关掉了谁），其次是有规则的，最后按应用名。
        final Collator col = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                if (a.disabled != b.disabled) return a.disabled ? -1 : 1;
                if (a.hasRules != b.hasRules) return a.hasRules ? -1 : 1;
                return col.compare(a.label, b.label);
            }
        });
        return out;
    }

    private void applyFilter() {
        if (adapter == null) return;
        shown.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (Row r : all) {
            if (mode == MODE_RULES && !r.hasRules) continue;
            if (mode == MODE_OFF && !r.disabled) continue;
            if (q.length() > 0
                    && !r.label.toLowerCase(Locale.ROOT).contains(q)
                    && !r.pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(r);
        }
        adapter.notifyDataSetChanged();

        int off = Prefs.get(act).disabledAppCount();
        tvInfo.setText(act.getString(R.string.app_list_info, all.size(), off));
        tvEmpty.setVisibility((!loading && shown.isEmpty()) ? View.VISIBLE : View.GONE);
        btnReset.setVisibility(off > 0 ? View.VISIBLE : View.GONE);
    }

    private void setDisabled(Row r, boolean disabled) {
        if (r.disabled == disabled) return;
        r.disabled = disabled;
        Prefs.get(act).setAppDisabled(r.pkg, disabled);
        toast(act.getString(disabled ? R.string.toast_app_off : R.string.toast_app_on, r.label));
        applyFilter();
    }

    private void confirmReset() {
        new AlertDialog.Builder(act)
                .setTitle(R.string.btn_reset_apps)
                .setMessage(R.string.dlg_reset_apps)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.btn_reset_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.get(act).clearDisabledApps();
                        for (Row r : all) r.disabled = false;
                        applyFilter();
                        toast(act.getString(R.string.toast_apps_reset));
                    }
                })
                .show();
    }

    private void openDetail(Row r) {
        Intent i = new Intent(act, AppRuleActivity.class);
        i.putExtra(AppRuleActivity.EXTRA_PKG, r.pkg);
        i.putExtra(AppRuleActivity.EXTRA_LABEL, r.label);
        act.startActivity(i);
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }

    /** 列表适配器。开关状态在每次绑定时先摘掉监听器再设置，避免被当成用户操作。 */
    private final class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int i) {
            return shown.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = act.getLayoutInflater().inflate(R.layout.item_app_rule, parent, false);
            }
            final Row r = shown.get(pos);

            ImageView iv = (ImageView) v.findViewById(R.id.ivIcon);
            TextView name = (TextView) v.findViewById(R.id.tvAppName);
            TextView sub = (TextView) v.findViewById(R.id.tvAppSub);
            Switch sw = (Switch) v.findViewById(R.id.swApp);

            iv.setImageDrawable(r.icon);
            name.setText(r.label);
            sub.setText(r.sub);
            name.setTextColor(act.getColor(r.disabled ? R.color.text_sub : R.color.text_main));

            sw.setOnCheckedChangeListener(null);
            sw.setChecked(!r.disabled);
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean checked) {
                    setDisabled(r, !checked);
                }
            });

            // 点整行进详情页。Switch 自己消费点击，不会冒泡到这里，不会误开详情。
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    openDetail(r);
                }
            });
            return v;
        }
    }
}
