package com.laopeng.autoskip;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 极简 root 命令执行器（KernelSU / Magisk 通用）。
 *
 * 两个必须遵守的前提：
 * <p>
 * 1. <b>只能在子线程调用</b>。su 是阻塞的，主线程调用直接 ANR。
 * 2. <b>第一次调用会弹 KernelSU 的授权窗</b>，用户没点之前进程一直挂着，
 * 所以首次超时要给足（见 {@link #FIRST_TIMEOUT_MS}）。
 * <p>
 * 走的是 {@code sh -c "su -c '<脚本>'"} 这条最通用的路径。KernelSU 与 Magisk
 * 都会把 su 挂到 /system/bin/su，不用去猜 /data/adb 下的实现路径。
 */
public final class RootShell {

    public static final String TAG = "AutoSkip";

    /** 首次授权要等用户点弹窗，给足一分钟。 */
    public static final long FIRST_TIMEOUT_MS = 60_000L;
    /** 授权过之后 su 是毫秒级的，超时只是兜底。 */
    public static final long TIMEOUT_MS = 15_000L;

    public static final class Result {
        public final boolean ok;
        public final String out;
        public final boolean timedOut;

        Result(boolean ok, String out, boolean timedOut) {
            this.ok = ok;
            this.out = out;
            this.timedOut = timedOut;
        }

        public boolean has(String needle) {
            return out != null && out.contains(needle);
        }

        @Override
        public String toString() {
            return (ok ? "ok" : (timedOut ? "timeout" : "fail")) + ": " + out;
        }
    }

    private RootShell() {
    }

    public static Result run(String script) {
        return run(script, TIMEOUT_MS);
    }

    /**
     * 以 root 身份跑一段 shell 脚本。脚本里可以自由用换行、$变量、for 循环。
     *
     * @return 执行结果；{@code ok} 为 true 表示进程正常退出且退出码为 0
     */
    public static Result run(String script, long timeoutMs) {
        if (script == null || script.trim().length() == 0) {
            return new Result(false, "empty script", false);
        }

        final Process proc;
        try {
            proc = new ProcessBuilder("/system/bin/sh", "-c", "su -c " + quote(script))
                    .redirectErrorStream(true)
                    .start();
        } catch (Throwable t) {
            Log.w(TAG, "exec su failed: " + t);
            return new Result(false, "exec failed: " + t, false);
        }

        // su 的输出必须边跑边读，否则缓冲区满了子进程会卡死在写上面
        final StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()), 8192)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() < 65536) sb.append(line).append('\n');
                }
            } catch (Throwable ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            finished = false;
        }
        if (!finished) {
            try {
                proc.destroy();
            } catch (Throwable ignored) {
            }
        }
        try {
            reader.join(1500);
        } catch (Throwable ignored) {
        }

        int code = -1;
        try {
            code = proc.exitValue();
        } catch (Throwable ignored) {
        }

        String out = sb.toString().trim();
        return new Result(finished && code == 0, out, !finished);
    }

    /**
     * 探一次 root 是否真的可用（会触发 KernelSU 授权窗）。
     * 只用来做开关状态判断，真正的权限结论以实际执行加固脚本为准。
     */
    public static boolean probe() {
        Result r = run("id", FIRST_TIMEOUT_MS);
        return r.ok && r.out.contains("uid=0");
    }

    /**
     * 把脚本整个塞进单引号里。脚本里若出现单引号，用 {@code '\''} 收尾再续，
     * 这样任何内容都不会破掉外层引号。
     */
    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
