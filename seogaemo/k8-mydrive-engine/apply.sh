# Engine of the "K8 My Drive" card: runs inside the persistent head unit process com.tcc.IpodUsbdeviceManager
# (hook_apps; it starts after the head unit restarts). Kept apart from the card, which has the settings:
# HMG-Seogaemo reverts and re-applies a module on every setting change, and that must not touch the hook.
# When the k8ecs.damping NEMESIS package is installed the engine runs there and this copy stays idle.
hook_apps files/k8ecs-hook.jar io.github.k8ecs.hook.K8Entry com.tcc.IpodUsbdeviceManager
put_file /data/nemesis/backup/k8ecs/export_usb.sh files/export_usb.sh 755
