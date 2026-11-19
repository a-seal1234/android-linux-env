#!/bin/sh
echo "== fake sysfs =="
echo "capacity=$(cat /sys/class/power_supply/BAT0/capacity)"
echo "status=$(cat /sys/class/power_supply/BAT0/status)"
echo "voltage_now=$(cat /sys/class/power_supply/BAT0/voltage_now)"
echo "max_brightness=$(cat /sys/class/backlight/alw_backlight/max_brightness)"
echo "brightness=$(cat /sys/class/backlight/alw_backlight/brightness)"
echo "== sysfs listing =="
ls -la /sys/class/power_supply/ 2>&1 | head -5
cat /sys/class/power_supply/BAT0/type 2>&1
ls -la /sys/class/backlight/ 2>&1 | head -5
echo "== brightnessctl =="
brightnessctl -m
brightnessctl set +10% && echo "set +10% ok"
brightnessctl -m
echo "== wpctl =="
wpctl get-volume @DEFAULT_AUDIO_SINK@
wpctl set-volume @DEFAULT_AUDIO_SINK@ 5%+
wpctl get-volume @DEFAULT_AUDIO_SINK@
wpctl set-mute @DEFAULT_AUDIO_SINK@ toggle
wpctl get-volume @DEFAULT_AUDIO_SINK@
wpctl set-mute @DEFAULT_AUDIO_SINK@ toggle
echo "== pamixer =="
pamixer --get-volume
pamixer -i 5
pamixer --get-volume
echo "== nmcli =="
nmcli general status
nmcli -t -f active,ssid dev wifi
echo "== systemctl/loginctl =="
systemctl poweroff
loginctl terminate-session 1
echo "== done =="
