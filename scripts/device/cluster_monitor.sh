#!/system/bin/sh
# ============================================================================
# 车机端「百度/高德 ↔ HSAE 仪表」全链路监控（Android 4.4.3 + root）
#
# 采集四类证据，全部落到 /sdcard/cluster_mon/<时间戳>/ ：
#   1. logcat.txt                main/system/events 三缓冲全量线程时间戳日志
#   2. binder_transactions.txt   内核 Binder 事务日志（真实调用链：谁→谁、size）
#   3. binder_pids.txt           事务采集期间的 pid → 进程名映射
#   4. dumpsys.txt               周期性广播/前台/窗口焦点快照
#   +  meta.txt / errors.txt / summary.md / 分类文件（baidu/amap/hsae/reborn）
#
# 用法（车机端，需 root）：
#   su -c 'sh /data/local/tmp/cluster_monitor.sh start [分钟] [时间戳]'
#   su -c 'sh /data/local/tmp/cluster_monitor.sh stop'
#   su -c 'sh /data/local/tmp/cluster_monitor.sh status'
#
# 注意：后台任务用 setsid 脱离会话，否则 adb/su 退出时会被 SIGHUP 杀掉。
# ============================================================================

BB=/system/bin/busybox
ROOT_DIR=/sdcard/cluster_mon
STATE=/data/local/tmp/cluster_mon.state
SELF=/data/local/tmp/cluster_monitor.sh

log() { echo "[$($BB date +%H:%M:%S)] $*"; }

ensure_root() {
  U=$($BB id -u 2>/dev/null)
  if [ "$U" != "0" ]; then
    echo "[ERROR] 需要 root（请用 su -c 运行）当前 uid=${U:-未知}" >&2
    exit 1
  fi
}

binder_dump() { $BB cat /sys/kernel/debug/binder/transaction_log 2>/dev/null; }

# ---------------- 子任务：logcat ----------------
task_logcat() {
  OUT="$1"
  exec logcat -b main -b system -b events -v threadtime -f "$OUT/logcat.txt"
}

# ---------------- 子任务：周期采样 ----------------
task_poll() {
  OUT="$1"; MINUTES="$2"
  SEQ_F="$STATE.seq"
  END=$(( $($BB date +%s) + MINUTES * 60 ))
  N=0
  TMP=/data/local/tmp/.binder_tmp
  while [ "$($BB date +%s)" -lt "$END" ]; do
    binder_dump > "$TMP" 2>/dev/null
    LAST=$($BB cat "$SEQ_F" 2>/dev/null || echo 0)
    $BB awk -F: -v last="$LAST" '{ s=$1+0; if (s > last) { print; last=s } }' "$TMP" >> "$OUT/binder_transactions.txt" 2>/dev/null
    $BB awk -F: 'NF>0 { s=$1+0; if (s>m) m=s } END { print m+0 }' "$TMP" > "$SEQ_F" 2>/dev/null

    N=$((N + 1))
    if [ $((N % 5)) -eq 0 ]; then
      { echo "===== $($BB date +%F' '%T) ====="; ps; } >> "$OUT/binder_pids.txt" 2>/dev/null
      {
        echo "===== $($BB date +%F' '%T) ====="
        echo "-- 前台"
        dumpsys activity activities 2>/dev/null | $BB grep -E 'mResumedActivity|mFocusedActivity'
        echo "-- 窗口焦点"
        dumpsys window windows 2>/dev/null | $BB grep -E 'mCurrentFocus|mFocusedApp'
        echo "-- 相关广播"
        dumpsys activity broadcasts 2>/dev/null | $BB grep -iE -A4 'baidu|hsae|amap|touka' | $BB head -60
        echo
      } >> "$OUT/dumpsys.txt" 2>/dev/null
    fi

    SZ=$($BB stat -c %s "$OUT/logcat.txt" 2>/dev/null || echo 0)
    if [ "$SZ" -gt 629145600 ]; then
      log "日志超过 600MB，自动停止采集"
      break
    fi
    $BB sleep 5
  done
  # 到点自动收尾（无需人工 stop）
  do_finalize "$OUT"
}

# ---------------- start ----------------
do_start() {
  ensure_root
  MINUTES="${1:-30}"
  # 用 PC 传入的时间戳（车机时钟可能是 UTC），未传则用本机时间
  STAMP="${2:-$($BB date +%Y%m%d_%H%M%S)}"
  OUT="$ROOT_DIR/$STAMP"
  $BB mkdir -p "$OUT" || { echo "[ERROR] 无法创建 $OUT" >&2; exit 1; }

  echo "$OUT" > "$STATE.dir"
  echo 0 > "$STATE.seq"
  $BB rm -f "$STATE.finalized"

  {
    echo "== 采集开始（车机时间 $($BB date)）=="
    echo; echo "== 设备 =="
    getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.display.id
    echo; echo "== 相关包 =="
    for p in com.baidu.mapauto com.baidu.che.codriver cn.baidu.carlifevehicle \
             com.autonavi.amapauto tech.touka.amapnavi com.hsae.core com.hsae.gasservice; do
      echo "-- $p"
      dumpsys package "$p" 2>/dev/null | $BB grep -E 'versionName|versionCode|lastUpdateTime'
    done
    echo; echo "== 进程（pid 用于对照 binder 日志）=="
    ps
    echo; echo "== 前台 =="
    dumpsys activity activities 2>/dev/null | $BB grep -E 'mResumedActivity|mFocusedActivity'
    echo; echo "== Reborn 仪表设置 =="
    $BB cat /data/data/tech.touka.amapnavi/shared_prefs/amapnavi_settings.xml 2>/dev/null
  } > "$OUT/meta.txt" 2>&1

  setprop log.tag.ClusterDbg DEBUG 2>/dev/null
  # ClusterDbg 开关在 RelayEngine 构造时读取，必须重启进程才生效
  am force-stop tech.touka.amapnavi 2>/dev/null
  $BB sleep 1
  am start -n tech.touka.amapnavi/.ui.main.MainActivity > /dev/null 2>&1

  # 脱离会话启动（setsid + nohup + 重定向，避免 SIGHUP）
  $BB setsid sh "$SELF" _task_logcat "$OUT" < /dev/null >> "$OUT/task.log" 2>&1 &
  $BB setsid sh "$SELF" _task_poll "$OUT" "$MINUTES" < /dev/null >> "$OUT/task.log" 2>&1 &

  $BB sleep 2
  if $BB pgrep -f "logcat -b main" > /dev/null 2>&1; then
    log "已启动：$OUT"
  else
    log "警告：logcat 子任务未存活，请查看 $OUT/task.log"
  fi
  log "  自动停止：${MINUTES} 分钟后；也可随时执行 stop"
}

# ---------------- 收尾（幂等，可被 poll 或 stop 调用）----------------
do_finalize() {
  OUT="$1"
  [ -n "$OUT" ] || return 0
  [ -f "$STATE.finalized" ] && return 0
  echo 1 > "$STATE.finalized"

  log "收尾中…"
  $BB pkill -f "logcat -b main" 2>/dev/null
  $BB sleep 2

  # 收尾：补一次 binder 增量
  TMP=/data/local/tmp/.binder_tmp
  binder_dump > "$TMP" 2>/dev/null
  LAST=$($BB cat "$STATE.seq" 2>/dev/null || echo 0)
  $BB awk -F: -v last="$LAST" '{ s=$1+0; if (s > last) { print; last=s } }' "$TMP" >> "$OUT/binder_transactions.txt" 2>/dev/null
  $BB rm -f "$TMP"

  L="$OUT/logcat.txt"
  [ -f "$L" ] || L=/dev/null
  $BB grep -iE 'baidu|codriver|carlife'            "$L" > "$OUT/baidu.txt"  2>/dev/null
  $BB grep -iE 'amapauto|AUTONAVI|AmapAuto'        "$L" > "$OUT/amap.txt"   2>/dev/null
  $BB grep -iE 'hsae|IBINDER_|autosdk|INavigation' "$L" > "$OUT/hsae.txt"   2>/dev/null
  $BB grep -iE 'amapnavi|ClusterDbg'               "$L" > "$OUT/reborn.txt" 2>/dev/null
  $BB grep -iE 'FATAL|ANR|ClassCastException|W/Bundle|signal 11' "$L" > "$OUT/errors.txt" 2>/dev/null

  # ---- pid -> 进程名，并把 Binder 事务翻译成可读调用链 ----
  ps 2>/dev/null | $BB awk 'NF>=9 { name[$2]=$9 } END { for (p in name) print p" "name[p] }' | $BB sort -n > "$OUT/pid_map.txt"
  $BB awk '
    NR==FNR { name[$1]=$2; next }
    / from / {
      split($4, a, ":"); split($6, b, ":")
      s=a[1]; d=b[1]
      ns=(s in name)?name[s]:"?"
      nd=(d in name)?name[d]:"?"
      printf "%s  %s(%s) -> %s(%s)  %s  size=%s\n", $1, s, ns, d, nd, $2, $12
    }
  ' "$OUT/pid_map.txt" "$OUT/binder_transactions.txt" > "$OUT/binder_chain.txt" 2>/dev/null

  {
    echo "# 采集摘要（$($BB basename "$OUT")）"
    echo
    echo "## 体量"
    for f in logcat binder_transactions binder_pids baidu amap hsae reborn errors; do
      CNT=$($BB wc -l < "$OUT/$f.txt" 2>/dev/null | $BB tr -d ' ')
      [ -n "$CNT" ] || CNT=0
      echo "- $f: $CNT 行"
    done
    echo
    echo "## Reborn 仪表发帧"
    $BB grep -oE '\[(TBT|SPEED|EST|TEXT|ACTIVE|RELEASE|IDLE|NAVI)\]' "$OUT/reborn.txt" 2>/dev/null | $BB sort | $BB uniq -c | $BB sort -rn
    echo
    echo "### TBT 通道分布（8 = 罗盘）"
    $BB grep -oE '\[TBT\] type=[0-9]+' "$OUT/reborn.txt" 2>/dev/null | $BB sort | $BB uniq -c | $BB sort -rn
    echo
    echo "### 高德广播 KEY_TYPE"
    $BB grep -oE 'RX keyType=[0-9]+' "$OUT/reborn.txt" 2>/dev/null | $BB sort | $BB uniq -c | $BB sort -rn
    echo
    echo "## Binder 调用链 Top 20（已解析进程名）"
    $BB awk -F'  ' 'NF>=2 { split($2, p, " "); print p[1] }' "$OUT/binder_chain.txt" 2>/dev/null | $BB sort | $BB uniq -c | $BB sort -rn | $BB head -20
    echo
    echo "> 完整逐条链路见 binder_chain.txt；原始序号见 binder_transactions.txt"
  } > "$OUT/summary.md" 2>/dev/null

  $BB tar -czf "$OUT.tar.gz" -C "$($BB dirname "$OUT")" "$($BB basename "$OUT")" 2>/dev/null
  log "完成：$OUT"
}

# ---------------- stop ----------------
do_stop() {
  ensure_root
  OUT=$($BB cat "$STATE.dir" 2>/dev/null)
  [ -n "$OUT" ] || { echo "[ERROR] 未找到采集目录记录" >&2; exit 1; }

  log "停止采集…"
  $BB pkill -f "cluster_monitor.sh _task_poll" 2>/dev/null
  $BB sleep 1
  do_finalize "$OUT"
  echo "分析入口：$OUT/summary.md"
  echo "真实调用链：$OUT/binder_chain.txt"
}

# ---------------- status ----------------
do_status() {
  OUT=$($BB cat "$STATE.dir" 2>/dev/null)
  echo "采集目录：${OUT:-（无）}"
  if $BB pgrep -f "logcat -b main" > /dev/null 2>&1; then
    echo "  logcat 采集中"
  else
    echo "  logcat 未运行"
  fi
  if $BB pgrep -f "cluster_monitor.sh _task_poll" > /dev/null 2>&1; then
    echo "  binder/dumpsys 采集中"
  else
    echo "  binder/dumpsys 未运行"
  fi
  [ -n "$OUT" ] && [ -f "$OUT/logcat.txt" ] && \
    echo "  日志大小：$($BB du -h "$OUT/logcat.txt" 2>/dev/null | $BB awk '{print $1}')" && \
    echo "  binder 事务：$($BB wc -l < "$OUT/binder_transactions.txt" 2>/dev/null || echo 0) 条"
}

case "${1:-status}" in
  start)        shift; do_start "$@" ;;
  stop)         do_stop ;;
  status)       do_status ;;
  _task_logcat) shift; task_logcat "$@" ;;
  _task_poll)   shift; task_poll "$@" ;;
  *) echo "用法: $0 {start [分钟] [时间戳] | stop | status}" >&2; exit 2 ;;
esac
