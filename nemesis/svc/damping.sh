#!/system/bin/sh
# K8 suspension auto-switch (NEMESIS service, uid=system, mode=restart).
# Reads speed, drive mode and the GPS module gyro; changes only the custom-mode suspension item
# (the same call as the stock settings screen) when the user enabled it. Event log: events.log (damping.log is the service stdout kept by NEMESIS).

case "$0" in
    /*) pkg_dir=${0%/svc/*} ;;
    *) pkg_dir=/data/nemesis/apps/k8ecs.damping ;;
esac
dex="$pkg_dir/bin/k8-ecs-car.dex"
data=/data/nemesis/logs/k8ecs.damping
[ -f "$dex" ] || { echo "dex missing"; exit 1; }

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 5
done
sleep 10

# Own private directory (settings, learned sensor scale, dalvik cache); the parent is world-writable.
priv="$data/private"
mkdir -p "$priv/dalvik-cache" 2>/dev/null
chmod 700 "$priv" 2>/dev/null
[ -w "$priv" ] || { echo "private dir not writable: $priv"; sleep 60; exit 1; }

K8ECS_PKG="$pkg_dir" ANDROID_DATA="$priv" CLASSPATH="$dex" exec app_process /system/bin io.github.k8ecs.car.CarMain
