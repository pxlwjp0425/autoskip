package com.laopeng.autoskip;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** 统一在主线程弹 Toast，免得在服务/回调线程里踩 "Can't create handler" 的坑。 */
public class ToastHelper {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void show(final Context c, final String msg) {
        if (c == null || msg == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
