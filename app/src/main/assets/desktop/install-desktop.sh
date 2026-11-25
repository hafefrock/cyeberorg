#!/bin/bash
# Runs INSIDE Ubuntu (proot). Installs a light XFCE desktop and a VNC server.
set -e
export DEBIAN_FRONTEND=noninteractive
echo "== Cyberorg: installing desktop (this takes a while, keep the app open) =="
apt-get update
apt-get install -y --no-install-recommends \
  tigervnc-standalone-server dbus-x11 xfce4 xfce4-terminal thunar \
  xfonts-base fonts-dejavu-core x11-xserver-utils

mkdir -p /root/.vnc
cat > /root/.vnc/xstartup <<'EOF'
#!/bin/sh
unset SESSION_MANAGER
unset DBUS_SESSION_BUS_ADDRESS
export XDG_RUNTIME_DIR=/tmp/runtime-root
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"
exec dbus-launch --exit-with-session startxfce4
EOF
chmod +x /root/.vnc/xstartup
touch /root/.cyberorg-desktop-installed
echo "== Done. Go back to Settings and tap 'Start desktop'. =="
