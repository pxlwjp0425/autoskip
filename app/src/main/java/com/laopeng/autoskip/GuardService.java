package com.laopeng.autoskip;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * 空壳前台服务，唯一的作用是给 root 守护脚本一个「能把进程拉起来、又不打扰用户」的入口。
 *
 * <p>无障碍服务是系统绑定的，没法用 {@code am} 直接启动。进程一旦被系统彻底清掉，
 * 只剩「启动 Activity」这一条重建进程的路 —— 那会直接把界面糊到用户脸上。
 * 所以额外挂这个前台服务，守护脚本用
 * {@code am start-foreground-service -n <pkg>/.GuardService} 静默拉起即可。
 *
 * <p>它复用保活那条常驻通知（同一个通知 id），不会多出一条。
 */
public class GuardService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            KeepAlive.start(this);
        } catch (Throwable ignored) {
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
