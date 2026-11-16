# The engine starts after the head unit restarts (hooked process) or with the k8ecs.damping NEMESIS package.
if not prop_is sys.k8ecs.alive 1
then
warn @need_engine
fi
