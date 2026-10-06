package com.laopeng.autoskip;

import android.app.Activity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/**
 * 规则组行的渲染器 —— 应用详情页与规则页的全局规则卡共用同一套画法。
 *
 * <p>两组场景只是「这一行属于谁」不同：
 * <ul>
 *   <li>应用详情页：{@code pkg} 传真实包名，开关写「这个应用的这个组」；</li>
 *   <li>规则页全局卡：{@code pkg} 传 {@code "*"}，开关写全局默认。</li>
 * </ul>
 * 除此之外「什么算关着、什么算不可点」的口径必须一致 —— 所以抽到这里，
 * 而不是两边各写一遍再慢慢长歪。
 *
 * <p>三层开关的口径（与 {@link RuleStore#rulesFor(String)} 的过滤保持一致）：
 * 生效 = 文件里 {@code enable:true} <b>且</b> 用户没关这个应用的这个组
 * <b>且</b> 没关这个组的全局默认。任一不成立就是关。
 */
final class GroupRows {

    private GroupRows() {
    }

    /** 用户在某一行上动了开关之后的回调，用来触发重画与摘要刷新。 */
    interface Callback {
        void onChanged();
    }

    /**
     * 把 {@code views} 逐行画进 {@code box}（会先清空）。
     *
     * @param views 已按类别排好序的组视图；调用方负责过滤（哪几组属于这一块）
     */
    static void render(Activity act, LinearLayout box, List<RuleStore.GroupView> views,
                       String pkg, Callback cb) {
        box.removeAllViews();
        for (final RuleStore.GroupView v : views) {
            View row = act.getLayoutInflater().inflate(R.layout.item_group, box, false);
            TextView name = (TextView) row.findViewById(R.id.tvGroupName);
            TextView tag = (TextView) row.findViewById(R.id.tvGroupTag);
            TextView sub = (TextView) row.findViewById(R.id.tvGroupSub);
            Switch sw = (Switch) row.findViewById(R.id.swGroup);

            name.setText(v.name);

            // 小标签只说「为什么它现在不是你想的样子」，正常生效的组只标一个「全局」
            String tagText = "";
            int tagColor = R.color.text_sub;
            if (v.userDisabled) {
                tagText = act.getString(R.string.group_off_tag);
                tagColor = R.color.warn;
            } else if (v.globalOff) {
                tagText = act.getString(R.string.group_global_off_tag);
                tagColor = R.color.warn;
            } else if (!v.fileEnabled) {
                tagText = act.getString(R.string.group_file_off_tag);
                tagColor = R.color.warn;
            } else if (v.inGlobal) {
                tagText = act.getString(R.string.group_global_tag);
            }
            tag.setText(tagText);
            tag.setTextColor(act.getColor(tagColor));

            sub.setText(act.getString(R.string.group_sub, v.ruleCount, v.sources));
            name.setTextColor(act.getColor(v.isOn() ? R.color.text_main : R.color.text_sub));

            // 上游 enable:false、或已经被全局默认关掉的组，这一层的开关点了也不会生效
            // —— 置灰而不是藏起来，让用户看得见「这里有一组规则，但不是在这一层管的」。
            boolean editable = v.fileEnabled && !v.globalOff;
            sw.setEnabled(editable);
            sw.setAlpha(editable ? 1f : 0.45f);
            // 先摘监听器再回填，否则程序化 setChecked 会被当成用户操作（老坑，AppListActivity 那版踩过）
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(v.isOn());
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean checked) {
                    Prefs.get(act).setGroupDisabled(pkg, v.name, !checked);
                    if (cb != null) cb.onChanged();
                }
            });
            box.addView(row);
        }
    }

    /** 这些组一共多少条规则。 */
    static int ruleSum(List<RuleStore.GroupView> views) {
        int n = 0;
        for (RuleStore.GroupView v : views) n += v.ruleCount;
        return n;
    }

    /** 这些组里有几组还生效着（{@code onOnly} 为假时就是总数）。 */
    static int count(List<RuleStore.GroupView> views, boolean onOnly) {
        int n = 0;
        for (RuleStore.GroupView v : views) {
            if (!onOnly || v.isOn()) n++;
        }
        return n;
    }

    /** 这些组里被用户自己关掉的有几组（用于决定「全部恢复」按钮露不露）。 */
    static int userOffCount(List<RuleStore.GroupView> views) {
        int n = 0;
        for (RuleStore.GroupView v : views) {
            if (v.userDisabled) n++;
        }
        return n;
    }

    /** 一块区域没有组时铺的一行灰字。 */
    static TextView hint(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setLineSpacing(dp(act, 3), 1f);
        tv.setTextColor(act.getColor(R.color.text_sub));
        tv.setPadding(0, dp(act, 10), 0, dp(act, 10));
        return tv;
    }

    private static int dp(Activity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }
}
