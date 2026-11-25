#!/bin/bash
# Runs INSIDE Ubuntu (proot). Starts Xvnc on localhost:5901 and the XFCE session.
GEOMETRY="${1:-1280x720}"
export DISPLAY=:1
pkill -f 'Xvnc :1' 2>/dev/null
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
mkdir -p /tmp/.X11-unix
echo "== Cyberorg desktop ${GEOMETRY} - keep this session open; open the viewer from Settings =="
Xvnc :1 -geometry "$GEOMETRY" -depth 24 -rfbport 5901 -localhost \
  -SecurityTypes None -AlwaysShared >/tmp/xvnc.log 2>&1 &
sleep 2
if ! kill -0 $! 2>/dev/null; then
  echo "Xvnc failed to start:"
  cat /tmp/xvnc.log
  exit 1
fi
sh /root/.vnc/xstartup >/tmp/desktop.log 2>&1 &
wait
