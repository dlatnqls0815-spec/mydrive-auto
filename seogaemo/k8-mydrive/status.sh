# Values published by the k8ecs.damping NEMESIS package, and the tab switch for the screen:
# info.s / info.h / info.e is "1" for the selected tab (ui.txt shows blocks with when=s|h|e).
info st prop sys.k8ecs.st
info now prop sys.k8ecs.now
info sp prop sys.k8ecs.sp
info cf prop sys.k8ecs.cfg
if setting_is tab susp
then
info s prop sys.k8ecs.alive
fi
if setting_is tab steer
then
info h prop sys.k8ecs.alive
fi
if setting_is tab eng
then
info e prop sys.k8ecs.alive
fi
if not prop_is sys.k8ecs.alive 1
then
fail @need_engine
fi
