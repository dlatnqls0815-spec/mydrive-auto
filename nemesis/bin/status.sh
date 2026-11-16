#!/system/bin/sh
# Shows the last event log line (NEMESIS Manager shows script output as a toast).
log=/data/nemesis/logs/k8ecs.damping/events.log
last=
if [ -f "$log" ]; then
    while read -r line; do
        last=$line
    done < "$log"
fi
echo "${last:-log empty}"
