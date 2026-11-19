#!/bin/sh
# Optional ALW session hook (TASK-025): diagnostics + rice demo bar.
[ -x /root/alw-session-diagnostics.sh ] && /root/alw-session-diagnostics.sh
if command -v waybar >/dev/null 2>&1 && [ -f /root/.config/waybar/config.jsonc ]; then
    (waybar -c /root/.config/waybar/config.jsonc -s /root/.config/waybar/style.css >/tmp/rt/waybar.log 2>&1 &)
fi
exit 0
