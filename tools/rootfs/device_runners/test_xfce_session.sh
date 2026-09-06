#!/bin/sh
export PATH="/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin:$PATH"
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
Xvnc :1 -SecurityTypes None -geometry 1024x768 >/tmp/xvnc1.log 2>&1 &
VNC_PID=$!
sleep 2

export DISPLAY=:1
dbus-launch --exit-with-session startxfce4 >/tmp/xfce4_start.log 2>&1 &
XFCE_PID=$!
sleep 5

echo "=== RUNNING DESKTOP PROCESSES ==="
ps aux | grep -E 'Xvnc|xfce4-session|glycin' | grep -v grep

kill $XFCE_PID 2>/dev/null || true
pkill -f xfce4 2>/dev/null || true
pkill -f xfwm4 2>/dev/null || true
kill $VNC_PID 2>/dev/null || true
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
