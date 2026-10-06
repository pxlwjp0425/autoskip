package com.laopeng.autoskip;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * KernelSU 守护模块的文件内容。
 *
 * <p>装到 {@code /data/adb/modules/autoskip_guard/} 之后：
 * <ul>
 *   <li>{@code module.prop} —— 模块描述，KernelSU 管理器据此识别</li>
 *   <li>{@code service.sh} —— 开机 late_start 阶段执行一次，等系统起来后把守护脚本挂到后台</li>
 *   <li>{@code bin/guard.sh} —— 真正干活的长驻循环</li>
 * </ul>
 *
 * <p>守护脚本的核心原则：<b>只在真的坏了的时候才动手</b>。
 * 因为 {@code settings put} 会让系统重新绑定无障碍服务（有短暂闪断），
 * 没事就写一遍反而更不稳定 —— 所以正常状态下每轮只做「看一眼、锁一下内存」。
 */
final class GuardModule {

    static final String MODULE_DIR = "/data/adb/modules/autoskip_guard";
    static final String GUARD_LOG = "/data/adb/autoskip_guard.log";

    private GuardModule() {
    }

    static Map<String, String> files() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("module.prop", MODULE_PROP);
        m.put("service.sh", SERVICE_SH);
        m.put("bin/guard.sh", GUARD_SH);
        return m;
    }

    private static final String MODULE_PROP = """
            id=autoskip_guard
            name=快跳守护
            version=v1.1
            versionCode=2
            author=laopeng
            description=守护「快跳」的无障碍开关与后台进程：开关记录被系统清掉自动补回、服务掉绑定自动重连、进程被杀静默拉起、内存优先级持续锁定（含守护自身）。单实例锁防止重复守护。卸载本模块不影响快跳本体。
            """;

    private static final String SERVICE_SH = """
            #!/system/bin/sh
            # KernelSU 在 late_start 阶段以 root 身份执行本脚本。
            MODDIR=${0%/*}

            # 等系统真的起来。无障碍服务属于系统服务，起太早写了设置也会被系统覆盖回去。
            i=0
            while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 90 ]; do
              sleep 2
              i=$((i+1))
            done
            sleep 12

            nohup sh $MODDIR/bin/guard.sh </dev/null >/dev/null 2>&1 &
            """;

    /**
     * 注意：脚本里用到的反斜杠（如 {@code tr '\n'}）在 Java 文本块里必须写成 {@code \\n}，
     * 否则会被 Java 先解释掉。改这个常量时务必留意。
     */
    private static final String GUARD_SH = """
            #!/system/bin/sh
            # 快跳守护脚本（KernelSU 模块 autoskip_guard）v1.1
            #
            # 只在真的坏了的时候才动手：settings put 会让系统重新绑定无障碍服务，
            # 有几百毫秒的闪断，正常状态下反复写反而更不稳定。

            PKG=com.laopeng.autoskip
            SVC=$PKG/$PKG.SkipService
            # 自适应模块目录：首次安装会落在 modules_update/，重启后才转正到 modules/
            BINDIR=${0%/*}
            MODDIR=${BINDIR%/*}
            LOG=/data/adb/autoskip_guard.log
            LOCK=/data/adb/autoskip_guard.lock
            INTERVAL=20

            # 单实例锁：开机脚本被重复触发时不会起出第二个守护
            if [ -f $LOCK ]; then
              old=$(cat $LOCK 2>/dev/null)
              if [ -n "$old" ] && [ -r /proc/$old/cmdline ]; then
                if tr '\\0' ' ' < /proc/$old/cmdline 2>/dev/null | grep -q 'autoskip_guard/bin/guard.sh'; then
                  exit 0
                fi
              fi
            fi
            echo $$ > $LOCK

            # 守护自身也锁最高优先级：它一旦被回收，就没人再看管快跳了
            printf '%s\\n' -1000 > /proc/$$/oom_score_adj 2>/dev/null

            log() {
              echo "$(date '+%m-%d %H:%M:%S') $*" >> $LOG
              n=$(wc -l < $LOG 2>/dev/null)
              if [ -n "$n" ] && [ "$n" -gt 300 ]; then
                tail -n 120 $LOG > $LOG.tmp 2>/dev/null && mv $LOG.tmp $LOG
              fi
            }

            # 无障碍服务当前是否已被系统绑定（Bound services 段里能看到自己的 label）
            is_bound() {
              dumpsys accessibility 2>/dev/null | grep 'Bound services:' | grep -q '快跳'
            }

            # 强制系统重新绑定：先把自己从开关列表里摘掉，再写回去，逼系统走一遍 updateServices。
            # 只动自己那一段，不影响机器上其它无障碍服务（GKD、TalkBack 之类）。
            rebind() {
              CUR=$(settings get secure enabled_accessibility_services)
              [ "$CUR" = "null" ] && CUR=""
              REST=$(echo "$CUR" | tr ':' '\\n' | grep -v "^$SVC$" | tr '\\n' ':')
              REST=${REST%:}
              if [ -n "$REST" ]; then
                settings put secure enabled_accessibility_services "$REST"
              else
                settings put secure enabled_accessibility_services ""
              fi
              sleep 1
              if [ -n "$REST" ]; then
                settings put secure enabled_accessibility_services "$REST:$SVC"
              else
                settings put secure enabled_accessibility_services "$SVC"
              fi
              settings put secure accessibility_enabled 1
            }

            # 内存优先级被系统改回来就重新锁上（含守护自己）
            lock_oom() {
              for p in $(pidof $PKG) $$; do
                [ -z "$p" ] && continue
                cur=$(cat /proc/$p/oom_score_adj 2>/dev/null)
                [ "$cur" = "-1000" ] && continue
                printf '%s\\n' -1000 > /proc/$p/oom_score_adj 2>/dev/null
                [ "$p" != "$$" ] && log "重新锁定内存优先级 pid=$p"
              done
            }

            # ── 开机后的一次性加固 ──
            cmd appops set $PKG ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null
            cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow 2>/dev/null
            cmd appops set $PKG WAKE_LOCK allow 2>/dev/null
            dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1
            log "守护启动 (pid=$$)，已完成开机加固"

            # ── 主循环 ──
            while true; do
              # 模块目录被删掉 = 用户在 App 里关了 root 增强，自己退出
              if [ ! -d $MODDIR ]; then
                rm -f $LOCK
                exit 0
              fi

              # 1) 无障碍开关记录被系统清掉 → 补回（HyperOS 在 force-stop 后会清）
              CUR=$(settings get secure enabled_accessibility_services)
              case "$CUR" in
                *"$PKG"*) ;;
                *)
                  log "无障碍开关记录被清，补回"
                  rebind
                  ;;
              esac

              # 2) 进程不在 → 用前台服务静默拉起（不弹界面）
              #    注意：补回开关记录后系统绑定无障碍服务时通常已把进程带起来，
              #    这一步主要是兜底。
              if [ -z "$(pidof $PKG)" ]; then
                am start-foreground-service -n $PKG/.GuardService >/dev/null 2>&1
                sleep 3
                log "进程不在，已静默拉起"
              fi

              # 3) 服务掉绑定 → 强制重连
              if ! is_bound; then
                log "无障碍服务未绑定，强制重连"
                rebind
                sleep 5
              fi

              # 4) 内存优先级掉了就重新锁上
              lock_oom

              sleep $INTERVAL
            done
            """;
}
