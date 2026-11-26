#!/bin/bash
# Runs INSIDE Ubuntu (proot). Starts Xvnc on localhost:5901 and the XFCE session.
GEOMETRY="${1:-1280x720}"
export DISPLAY=:1
pkill -f 'Xvnc :1' 2>/dev/null
rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
mkdir -p /tmp/.X11-unix /root/.vnc /root/Android

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
# Lighter desktop over VNC: no compositing, shadows or animations; windows move as outlines.
xf -c xfwm4 -p /general/use_compositing -n -t bool -s false
xf -c xfwm4 -p /general/show_dock_shadow -n -t bool -s false
xf -c xfwm4 -p /general/show_frame_shadow -n -t bool -s false
xf -c xfwm4 -p /general/show_popup_shadow -n -t bool -s false
xf -c xfwm4 -p /general/box_move -n -t bool -s true
xf -c xfwm4 -p /general/box_resize -n -t bool -s true
xf -c xsettings -p /Gtk/EnableAnimations -n -t bool -s false
pkill -x xfce4-power-manager 2>/dev/null
pkill -x xfce4-screensaver 2>/dev/null
pkill -x light-locker 2>/dev/null
exit 0
EOF

# Session script is rewritten on every start so updates to it reach existing installs.
# vncconfig bridges the X clipboard with the VNC clipboard. The sandbox variables let browsers
# (WebKit/Epiphany, Firefox) run under proot, where the usual sandboxes are not available.
cat > /root/.vnc/xstartup <<'EOF'
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
export XDG_RUNTIME_DIR=/tmp/runtime-root
export WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1
export WEBKIT_DISABLE_COMPOSITING_MODE=1
export MOZ_FAKE_NO_SANDBOX=1
export MOZ_DISABLE_CONTENT_SANDBOX=1
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"
(vncconfig -nowin >/tmp/vncconfig.log 2>&1 &)
exec dbus-launch --exit-with-session sh -c '(sleep 8; sh /root/cyberorg-theme.sh; sleep 25; sh /root/cyberorg-theme.sh) >/tmp/theme.log 2>&1 & exec startxfce4'
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
