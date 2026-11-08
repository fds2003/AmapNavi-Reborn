#!/usr/bin/env bash
# ============================================================================
# 车机「百度地图 / 高德 ↔ HSAE 仪表」数据交互监控
#
# 用途：长期抓取车机日志与广播状态，落盘成结构化文件，供离线分析
#       —— 尤其是复现"仪表投影闪动 / 罗盘与导航交替 / 左右方向相反"这类问题时，
#       需要同时看到 百度(codriver/mapauto)、高德(amapauto)、HSAE 系统、Reborn 四方行为。
#
# 在 PC 端运行（Git Bash / WSL / Linux），通过 adb 驱动车机：
#
#   ./scripts/monitor_cluster_baidu.sh                        # 默认 10 分钟
#   ./scripts/monitor_cluster_baidu.sh -d 192.168.144.253:5555 -m 30
#   ./scripts/monitor_cluster_baidu.sh --live                 # 边抓边打印关键行
#
# 产物（$OUT 目录）：
#   meta.txt          设备/应用版本/进程快照
#   logcat_full.txt   完整线程时间戳日志
#   baidu.txt         百度系（codriver / mapauto / carlife）
#   amap.txt          高德系（amapauto + Reborn 收到的高德广播）
#   hsae.txt          HSAE 系统（仪表 Binder / 服务 / 权限）
#   reborn.txt        Reborn 自身（含 ClusterDbg 发帧诊断）
#   broadcasts.txt    运行期间广播状态快照（每 15s 一次）
#   errors.txt        异常/崩溃/W/Bundle 类型告警
#   summary.md        自动生成的统计摘要
#
# 说明：车机 Android 4.4 无法直接 hook Binder，仪表交互只能通过各方日志间接还原；
#       本脚本把"同一时间轴上"的四方日志一次抓全，分析时按时间戳对齐即可。
# ============================================================================

set -u

DEV="${ADB_DEVICE:-}"
OUT=""
MINUTES=10
LIVE=0
INTERVAL=15
REMOTE_LOG="/data/local/tmp/cluster_mon_$(date +%s).log"

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    -d|--device)   DEV="${2:?}"; shift 2 ;;
    -o|--out)      OUT="${2:?}"; shift 2 ;;
    -m|--minutes)  MINUTES="${2:?}"; shift 2 ;;
    -i|--interval) INTERVAL="${2:?}"; shift 2 ;;
    --live)        LIVE=1; shift ;;
    -h|--help)     usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
  esac
done

log()  { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die()  { printf '[ERROR] %s\n' "$*" >&2; exit 1; }

ADB="adb"
[ -n "$DEV" ] && ADB="adb -s $DEV"

# ---------- 0. 前置 ----------
command -v adb >/dev/null 2>&1 || die "找不到 adb"
[ -n "$DEV" ] || die "请用 -d 指定车机地址，例如 -d 192.168.144.253:5555"

if [ -n "$DEV" ]; then
  adb connect "$DEV" >/dev/null 2>&1 || true
fi
STATE="$($ADB get-state 2>/dev/null | tr -d '\r')"
[ "$STATE" = "device" ] || die "车机不可用（adb state=$STATE），请确认车机在线：adb connect $DEV"

STAMP="$(date +%Y%m%d_%H%M%S)"
[ -z "$OUT" ] && OUT="./cluster_mon_$STAMP"
mkdir -p "$OUT" || die "无法创建输出目录 $OUT"
log "输出目录：$OUT"

# ---------- 1. 环境快照 ----------
log "采集设备与应用快照…"
{
  echo "== 采集时间 =="; date
  echo; echo "== 设备 =="
  $ADB shell "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.display.id" 2>/dev/null
  echo; echo "== 相关包版本 =="
  for p in com.baidu.mapauto com.baidu.che.codriver cn.baidu.carlifevehicle \
           com.autonavi.amapauto tech.touka.amapnavi; do
    echo "-- $p"
    $ADB shell "dumpsys package $p | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime'" 2>/dev/null
  done
  echo; echo "== Reborn 定位权限 =="
  $ADB shell "dumpsys package tech.touka.amapnavi | grep -E 'LOCATION'" 2>/dev/null
  echo; echo "== 进程 =="
  $ADB shell "ps | grep -iE 'baidu|hsae|amapnavi|amapauto|codriver|carlife'" 2>/dev/null
  echo; echo "== 前台 Activity =="
  $ADB shell "dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|mFocusedActivity'" 2>/dev/null
  echo; echo "== Reborn 仪表设置 =="
  $ADB shell "run-as tech.touka.amapnavi cat shared_prefs/amapnavi_settings.xml" 2>/dev/null | tr '>' '>\n' | grep -iE 'cluster_'
} > "$OUT/meta.txt" 2>&1
log "  → meta.txt"

# ---------- 2. 打开 Reborn 发帧诊断 ----------
$ADB shell "setprop log.tag.ClusterDbg DEBUG" >/dev/null 2>&1 && log "已开启 Reborn 发帧诊断（ClusterDbg）"

# ---------- 3. 开始抓日志 ----------
# 清空一次缓冲，避免混入历史；随后用 -f 写设备文件（WiFi 抖动也不丢数据）
$ADB logcat -c >/dev/null 2>&1 || true
$ADB shell "logcat -v threadtime -f $REMOTE_LOG" >/dev/null 2>&1 &
LOGCAT_PID=$!
sleep 2
if ! kill -0 "$LOGCAT_PID" 2>/dev/null; then
  die "日志采集进程启动失败"
fi
log "日志采集中，计划 ${MINUTES} 分钟（Ctrl-C 可提前结束）"

# ---------- 4. 后台采样广播/服务状态 ----------
(
  END=$(( $(date +%s) + MINUTES * 60 ))
  while [ "$(date +%s)" -lt "$END" ]; do
    echo "===== $(date +%F' '%T) =====" >> "$OUT/broadcasts.txt"
    $ADB shell "dumpsys activity broadcasts 2>/dev/null" 2>/dev/null | grep -iE -A4 'baidu|hsae|amap|touka' | head -80 >> "$OUT/broadcasts.txt" 2>/dev/null
    echo >> "$OUT/broadcasts.txt"
    sleep "$INTERVAL"
  done
) &
SAMPLER_PID=$!

# ---------- 5. 可选：实时打印关键行 ----------
if [ "$LIVE" -eq 1 ]; then
  log "实时视图（仅关键行）——Ctrl-C 结束采集"
  $ADB logcat -v time 2>/dev/null | grep -iE 'ClusterDbg|codriver|mapauto|amapauto|hsae|amapnavi' | \
    grep -iE 'RX keyType|TBT|ACTIVE|RELEASE|navi|cluster|transact|Exception' &
  LIVE_PID=$!
else
  LIVE_PID=""
fi

# ---------- 6. 等待结束 ----------
STOP=0
trap 'STOP=1; log "收到中断，提前结束"' INT
END_TS=$(( $(date +%s) + MINUTES * 60 ))
while [ "$STOP" -eq 0 ] && [ "$(date +%s)" -lt "$END_TS" ]; do
  sleep 5
done

# ---------- 7. 收尾 ----------
log "采集结束，正在回收数据…"
[ -n "${LIVE_PID:-}" ] && kill "$LIVE_PID" 2>/dev/null || true
kill "$SAMPLER_PID" 2>/dev/null || true
kill "$LOGCAT_PID" 2>/dev/null || true
$ADB shell "logcat -c" >/dev/null 2>&1 || true   # 触发 logcat 关闭输出文件
sleep 1

MSYS_NO_PATHCONV=1 $ADB pull "$REMOTE_LOG" "$OUT/logcat_full.txt" >/dev/null 2>&1 \
  || $ADB shell "cat $REMOTE_LOG" > "$OUT/logcat_full.txt" 2>/dev/null
$ADB shell "rm -f $REMOTE_LOG" >/dev/null 2>&1 || true

LINES=$(wc -l < "$OUT/logcat_full.txt" 2>/dev/null || echo 0)
[ "${LINES:-0}" -gt 0 ] || die "日志为空，采集失败"
log "  → logcat_full.txt（$LINES 行）"

# ---------- 8. 分类拆分（时间轴对齐用） ----------
grep -iE 'baidu|codriver|carlife'            "$OUT/logcat_full.txt" > "$OUT/baidu.txt"  2>/dev/null
grep -iE 'amapauto|amap_|AUTONAVI|AmapAuto'  "$OUT/logcat_full.txt" > "$OUT/amap.txt"   2>/dev/null
grep -iE 'hsae|IBINDER_|autosdk|INavigation' "$OUT/logcat_full.txt" > "$OUT/hsae.txt"   2>/dev/null
grep -iE 'amapnavi|ClusterDbg'               "$OUT/logcat_full.txt" > "$OUT/reborn.txt" 2>/dev/null
grep -iE 'FATAL|ANR|ClassCastException|W/Bundle|Exception|signal 11|SIGSEGV' \
                                             "$OUT/logcat_full.txt" > "$OUT/errors.txt" 2>/dev/null

# ---------- 9. 摘要 ----------
{
  echo "# 采集摘要 $STAMP"
  echo
  echo "- 设备：$DEV"
  echo "- 时长：${MINUTES} 分钟"
  echo "- 总日志行：$LINES"
  echo
  echo "## 分类行数"
  for f in baidu amap hsae reborn errors; do
    printf -- '- %-8s %s\n' "$f" "$(wc -l < "$OUT/$f.txt" 2>/dev/null || echo 0)"
  done
  echo
  echo "## Reborn 仪表发帧统计"
  if [ -s "$OUT/reborn.txt" ]; then
    echo '```'
    grep -oE '\[(TBT|SPEED|EST|TEXT|ACTIVE|RELEASE|IDLE|NAVI)\]' "$OUT/reborn.txt" 2>/dev/null | sort | uniq -c | sort -rn
    echo '--- TBT displayType 分布（8=罗盘）---'
    grep -oE '\[TBT\] type=[0-9]+' "$OUT/reborn.txt" 2>/dev/null | sort | uniq -c | sort -rn | head -10
    echo '--- TBT 触发原因 ---'
    grep -oE 'reason=(changed|keepalive)' "$OUT/reborn.txt" 2>/dev/null | sort | uniq -c
    echo '```'
  else
    echo "（无 Reborn 发帧日志：确认仪表总开关与 log.tag.ClusterDbg）"
  fi
  echo
  echo "## 高德广播 KEY_TYPE 分布"
  if [ -s "$OUT/reborn.txt" ]; then
    echo '```'
    grep -oE 'RX keyType=[0-9]+' "$OUT/reborn.txt" 2>/dev/null | sort | uniq -c | sort -rn | head -10
    echo '```'
  fi
  echo
  echo "## 异常摘要（前 15 条）"
  echo '```'
  head -15 "$OUT/errors.txt" 2>/dev/null
  echo '```'
} > "$OUT/summary.md"

# ---------- 10. 打包 ----------
TARBALL="${OUT%/}.tar.gz"
tar -czf "$TARBALL" -C "$(dirname "$OUT")" "$(basename "$OUT")" 2>/dev/null && \
  log "  → $TARBALL"

log "完成。分析入口：$OUT/summary.md"
echo
echo "提示："
echo "  1) 复现问题时，请在同一时间点分别用 百度地图 / 高德地图 导航一次，便于对比两条链路。"
echo "  2) 仪表侧现象（闪动/交替/左右相反）请连同时间点一起记录，可对照 reborn.txt 的 TBT 行定位。"
