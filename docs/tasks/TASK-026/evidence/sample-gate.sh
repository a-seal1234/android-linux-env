#!/bin/sh
# TASK-026 GPU Blocker Gate — coupled CPU / GPU / FPS sampler.
# Samples while the caller keeps the desktop animating.
D=192.168.51.161:44021
P=$(adb -s $D shell pidof com.alw.workstation | tr -d '\r')
HP=$(adb -s $D shell pidof Hyprland | tr -d '\r')
PROOT=$(adb -s $D shell "ps -A -o PID,NAME 2>/dev/null | grep lib_proot" | awk '{print $1}' | head -1)
echo "app=$P hyprland=$HP proot=$PROOT"

sample() {
  TS=$(date +%H:%M:%S)
  TOP=$(adb -s $D shell top -b -n 1 -o PID,%CPU,ARGS 2>/dev/null)
  CPU_APP=$(echo "$TOP" | awk -v p="$P" '$1==p {print $2}')
  CPU_HYP=$(echo "$TOP" | awk -v p="$HP" '$1==p {print $2}')
  CPU_PRT=$(echo "$TOP" | awk -v p="$PROOT" '$1==p {print $2}')
  LOAD=$(adb -s $D shell cat /proc/loadavg 2>/dev/null | awk '{print $1}')
  GPU=$(adb -s $D shell dumpsys gpu 2>/dev/null | awk -v p="$P" -v h="$HP" '
    /^Proc /{pid=$2; tot=$4} pid==p{a=tot} pid==h{hy=tot}
    END{printf "%d/%d", a/1048576, hy/1048576}')
  TZ=$(adb -s $D shell "cat /sys/class/thermal/thermal_zone0/temp" 2>/dev/null)
  echo "$TS cpu_app=$CPU_APP cpu_hypr=$CPU_HYP cpu_proot=$CPU_PRT load1=$LOAD gpuMB(app/hypr)=$GPU tz0=$TZ"
}

echo "--- sampling start ---"
i=0
while [ $i -lt "$1" ]; do
  sample
  i=$((i+1))
done
