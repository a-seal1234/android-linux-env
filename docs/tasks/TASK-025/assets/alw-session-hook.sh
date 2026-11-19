#!/bin/sh
# Optional ALW session hook (TASK-025): diagnostics only; the Sekai Pop rice
# (wallpaper + Waybar) is launched by the synthesized Hyprland session config.
[ -x /root/alw-session-diagnostics.sh ] && /root/alw-session-diagnostics.sh
exit 0
