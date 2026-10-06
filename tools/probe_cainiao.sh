#!/system/bin/sh
# 【已弃用，保留作反例参考】
# uiautomator dump 是 UiAutomation，会要求独占无障碍连接，一执行就把快跳的服务
# 顶下线重连 —— 抓到的树不是快跳看到的，还会打乱它跳过的时机。
# 排查「某 App 跳不过」请改用 App 内置诊断模式（写 files/diag.txt），
# 见 autoskip/README.md 第十一节「怎么看清快跳到底看到了什么」。
#
# 菜鸟裹裹开屏广告探测 v2：高密度抓 UI 树，不夹 dumpsys
PKG=com.cainiao.wireless
OUT=/data/local/tmp/cn_probe
rm -rf $OUT; mkdir -p $OUT

logcat -b events -c 2>/dev/null
logcat -c 2>/dev/null

am force-stop $PKG
sleep 3

monkey -p $PKG -c android.intent.category.LAUNCHER 1 > $OUT/start.txt 2>&1

i=0
while [ $i -lt 12 ]; do
  i=$((i+1))
  S=$(date +%H:%M:%S)
  uiautomator dump --compressed $OUT/u$i.xml > /dev/null 2>&1
  E=$(date +%H:%M:%S)
  sz=$(ls -l $OUT/u$i.xml 2>/dev/null | awk '{print $5}')
  act=$(dumpsys activity activities 2>/dev/null | grep -m1 -oE 'com\.cainiao\.wireless/[A-Za-z0-9._]+')
  echo "$i start=$S end=$E size=$sz act=$act" >> $OUT/idx.txt
done

logcat -b events -d > $OUT/events.txt 2>/dev/null
logcat -d > $OUT/logcat.txt 2>/dev/null
cat $OUT/idx.txt
echo PROBE_DONE
