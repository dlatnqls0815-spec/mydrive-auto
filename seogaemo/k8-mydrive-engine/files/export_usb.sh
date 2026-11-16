#!/system/bin/sh
# Copies the k8ecs.damping logs to a plugged-in USB stick. Started by the car module (uid system) through
# "nemesis root" when the user presses the button in the head unit app; vold mounts the sticks as vfat
# with group sdcard_rw, which the NEMESIS root context has. Reads the head unit only, writes only the USB.
# Last output line: "OK <folder> files=<n>" or "ERR <reason>".
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

src=/data/nemesis/logs/k8ecs.damping
stamp=$(date +%Y%m%d-%H%M%S) || { echo "ERR date"; exit 1; }

found=0
out=
while read -r dev mp rest; do
 case "$mp" in
  /storage/usb[0-9]*|/mnt/media_rw/usb*) ;;
  *) continue ;;
 esac
 case "$rest" in
  *' ro,'*|*' ro '*) found=1; continue ;;
 esac
 found=1
 if mkdir "$mp/K8ECS-LOG-$stamp" 2>/dev/null; then
  out="$mp/K8ECS-LOG-$stamp"
  break
 fi
done < /proc/mounts

if [ -z "$out" ]; then
 if [ "$found" -eq 0 ]; then echo "ERR no_usb"; else echo "ERR usb_not_writable"; fi
 exit 2
fi

copied=0
for f in events.1.log events.log damping.log imu.1.csv imu.csv imu2.1.csv imu2.csv; do
 if [ -f "$src/$f" ] && [ -r "$src/$f" ]; then
  cp "$src/$f" "$out/$f" && copied=$((copied+1))
 fi
done

desc=/data/nemesis/apps/k8ecs.damping/NEMESIS.data
{
 echo "K8 suspension log export"
 echo "time=$stamp"
 echo "product=$(getprop ro.build.product 2>/dev/null)"
 echo "android=$(getprop ro.build.version.release 2>/dev/null)"
 if [ -f "$desc" ]; then
  while IFS= read -r line; do
   case "$line" in package-version=*) echo "module_$line" ;; esac
  done < "$desc"
 fi
 echo "copied_files=$copied"
} > "$out/info.txt"

sync
if [ "$copied" -gt 0 ]; then
 echo "OK ${out##*/} files=$copied"
else
 echo "ERR no_logs"
 exit 3
fi
