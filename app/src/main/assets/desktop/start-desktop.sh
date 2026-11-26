#!/bin/bash
# Runs INSIDE Ubuntu (proot). Starts Xvnc on localhost:5901 and the XFCE session.
GEOMETRY="${1:-1280x720}"
export DISPLAY=:1
pkill -f 'Xvnc :1' 2>/dev/null
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
mkdir -p /tmp/.X11-unix /root/.vnc

# Theme helper (best effort): dark background and dark GTK theme once the session is up.
cat > /root/cyberorg-theme.sh <<'EOF'
#!/bin/sh
xf() { xfconf-query "$@" 2>/dev/null; }
paths=$(xf -c xfce4-desktop -l | grep 'last-image$' | sed 's/last-image$//')
[ -z "$paths" ] && paths="/backdrop/screen0/monitorVNC-0/workspace0/"
for b in $paths; do
  xf -c xfce4-desktop -p "${b}image-style" -n -t int -s 0
  xf -c xfce4-desktop -p "${b}color-style" -n -t int -s 2
  xf -c xfce4-desktop -p "${b}rgba1" -n -t double -s 0.04 -t double -s 0.06 -t double -s 0.12 -t double -s 1
  xf -c xfce4-desktop -p "${b}rgba2" -n -t double -s 0.00 -t double -s 0.22 -t double -s 0.28 -t double -s 1
done
xf -c xsettings -p /Net/ThemeName -n -t string -s Adwaita-dark
xf -c xsettings -p /Net/IconThemeName -n -t string -s Adwaita
exit 0
EOF

# Session script is rewritten on every start so updates to it reach existing installs.
cat > /root/.vnc/xstartup <<'EOF'
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
export XDG_RUNTIME_DIR=/tmp/runtime-root
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"
exec dbus-launch --exit-with-session sh -c '(sleep 10; sh /root/cyberorg-theme.sh) >/tmp/theme.log 2>&1 & exec startxfce4'
EOF
chmod +x /root/.vnc/xstartup

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
