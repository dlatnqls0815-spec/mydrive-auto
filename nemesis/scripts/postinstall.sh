#!/system/bin/sh
# NEMESIS root on Gen5W has no CAP_CHOWN, so the system-uid service cannot be given this directory.
# Make it enterable/writable; the service keeps settings in its own 0700 "private" subdirectory and
# writes the event log here. Survives package updates.
data=/data/nemesis/logs/k8ecs.damping
mkdir -p "$data"
chmod 777 "$data"
# Left over from 0.1.0's first install attempt (root-owned, unusable by the service).
[ -d "$data/dalvik-cache" ] && rm -rf "$data/dalvik-cache"
exit 0
