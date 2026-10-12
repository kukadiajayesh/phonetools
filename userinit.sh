#!/system/bin/sh
# Run early in boot by LineageOS's sysinit service. When that service exits, init kills every process it started
# (its process group, /acct/uid_0/pid_*), so the settings below would be lost. Instead the script starts a copy of
# itself that first moves to the root group, out of reach, and then waits for boot to finish.
if [ "$1" != "late" ]; then
  /system/bin/sh /data/local/userinit.sh late &
  # Let the copy move out before this script (and the service) exits.
  sleep 1
  exit 0
fi
echo $$ > /acct/tasks

# Resize zram0 to 2 GiB once boot has finished
(
  while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 5; done
  sleep 20
  Z=/sys/block/zram0
  if [ "$(cat $Z/disksize)" != "2147483648" ]; then
    swapoff /dev/block/zram0
    echo 1 > $Z/reset
    echo 2147483648 > $Z/disksize
    mkswap /dev/block/zram0
    swapon /dev/block/zram0
    log -p i -t userinit "zram0 resized to $(cat $Z/disksize)"
  fi
  echo 100 > /proc/sys/vm/swappiness
  # Always at the highest clocks: all 4 cores online at max frequency, memory bus and GPU at max.
  # thermal-engine keeps running, so the phone still throttles when hot.
  stop mpdecision
  for C in /sys/devices/system/cpu/cpu[0-3]; do
    echo 1 > $C/online
    echo performance > $C/cpufreq/scaling_governor
    cat $C/cpufreq/cpuinfo_max_freq > $C/cpufreq/scaling_min_freq
  done
  D=/sys/class/devfreq
  cat $D/qcom,cpubw.62/max_freq > $D/qcom,cpubw.62/min_freq
  cat $D/fdb00000.qcom,kgsl-3d0/max_freq > $D/fdb00000.qcom,kgsl-3d0/min_freq
  log -p i -t userinit "clocks pinned to max"
)
