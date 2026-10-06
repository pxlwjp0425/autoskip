package com.laopeng.autoskip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * 后台保活（免 root 路线）。
 *
 * 核心招式：让无障碍服务自己调用 startForeground() —— 同一个进程被提到前台服务优先级，
 * 系统内存回收时几乎排到最后，国产 ROM 的"后台清理"也基本带不走它。
 * 配套两件事：电池优化白名单、开机自启/省电策略引导。
 *
 * 这些都是"降低概率"，不是绝对。真要 100% 抗杀只有 root 装成系统应用那条路。
 */
public class KeepAlive {

    public static final String TAG = "AutoSkip";

    public static final int NOTIF_ID = 1001;
    public static final String CHANNEL_ID = "autoskip_alive";

    /** 通知刷新节流：两次刷新至少间隔这么久，免得每跳一次都 notify。 */
    private static final long REFRESH_GAP = 60_000L;
    private static long sLastRefresh = 0L;

    private KeepAlive() {
    }

    private static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "快跳 · 后台守护",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("保持快跳在后台运行所需的常驻通知，可静音但请勿屏蔽");
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private static Notification build(Context c) {
        ensureChannel(c);

        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = "今日已跳过 " + Prefs.get(c).getTodayCount() + " 次 · 点此打开设置";

        return new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("快跳正在守护")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    /** 把服务提到前台。失败不影响跳广告功能，只是少了保活加成。 */
    public static boolean start(Service s) {
        try {
            Notification n = build(s);
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14 起必须显式声明前台服务类型，否则直接抛异常
                s.startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                // 29~33 用两参版本即可，类型从清单里取，避免类型位不认识的机型报错
                s.startForeground(NOTIF_ID, n);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed: " + t);
            return false;
        }
    }

    public static void stop(Service s) {
        try {
            s.stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (Throwable t) {
            Log.w(TAG, "stopForeground failed: " + t);
        }
    }

    /**
     * 刷新常驻通知上的统计数字（节流）。
     * 只在服务确实处于前台时刷新，否则会凭空多出一条永久通知。
     */
    public static void refresh(Context c) {
        if (!Prefs.get(c).isKeepAlive()) return;
        if (!SkipService.isForeground()) return;
        long now = SystemClock.uptimeMillis();
        if (now - sLastRefresh < REFRESH_GAP) return;
        sLastRefresh = now;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(NOTIF_ID, build(c));
        } catch (Throwable t) {
            Log.w(TAG, "refresh notification failed: " + t);
        }
    }

    // ---------------- 电池优化白名单 ----------------

    public static boolean isBatteryWhitelisted(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestBatteryWhitelist(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i2 = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i2);
        } catch (Throwable t) {
            ToastHelper.show(c, "系统没有公开电池优化设置页，请手动到「设置 → 电池」里把快跳设为不限制");
        }
    }

    // ---------------- 自启动 / 省电策略 ----------------

    /** 各家的自启动管理页。按热门程度排的，逐个试，第一个能打开的就用。 */
    private static final String[][] AUTOSTART_PAGES = {
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
            {"com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"},
            {"com.samsung.android.sm_cn", "com.samsung.android.sm_cn.ui.policy.PolicyActivity"},
            {"com.lenovo.safecenter", "com.lenovo.safecenter.permission.PermissionManagerActivity"},
    };

    /**
     * 打开自启动设置。国产 ROM 各家页面都不一样，只能挨个试。
     *
     * @return 是否成功打开了某个厂商页面（false 表示只能退到应用详情页）
     */
    public static boolean openAutoStart(Context c, String appLabel) {
        String pkg = c.getPackageName();

        // 小米/澎湃：「应用权限编辑」一页里同时有自启动和后台弹出界面，最省事
        if (tryStart(c, customIntent("miui.intent.action.APP_PERM_EDITOR",
                "com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", pkg))) {
            return true;
        }

        // 小米/澎湃：省电策略（无限制 / 智能限制）
        if (tryStart(c, customIntent(null, "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                .putExtra("package_name", pkg)
                .putExtra("package_label", appLabel))) {
            return true;
        }

        for (String[] page : AUTOSTART_PAGES) {
            if (tryStart(c, customIntent(null, page[0], page[1]))) return true;
        }

        // 兜底：应用详情页。用户自己能在里面找到「后台运行 / 电池」相关项
        tryStart(c, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + pkg)));
        return false;
    }

    private static Intent customIntent(String action, String pkg, String cls) {
        Intent i = new Intent();
        if (action != null) i.setAction(action);
        i.setComponent(new ComponentName(pkg, cls));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static boolean tryStart(Context c, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 通知权限（Android 13+）。没给也不影响功能，只是常驻通知不显示。 */
    public static boolean hasNotifPermission(Context c) {
        if (Build.VERSION.SDK_INT < 33) return true;
        try {
            return c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;
        }
    }
}
