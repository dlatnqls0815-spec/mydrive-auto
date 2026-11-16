package io.github.k8ecs.hook;

import hmgapi.hook.LoadedApp;
import hmgapi.hook.ModContext;
import hmgapi.hook.ModEntry;
import io.github.k8ecs.car.CarMain;

import java.io.File;

/**
 * HMG-Seogaemo hook entry (card k8-mydrive, verb hook_apps): runs the switching engine inside one persistent
 * system-uid process, so the card works without the NEMESIS package. When the NEMESIS package k8ecs.damping
 * is installed it does the work and this entry stays idle, so there is never a second engine.
 */
public class K8Entry extends ModEntry {
    /** Persistent head unit process (uid system) the engine lives in; also listed in the card's apply.sh. */
    public static final String HOST = "com.tcc.IpodUsbdeviceManager";
    static final String NEMESIS_PKG = "/data/nemesis/apps/k8ecs.damping";
    private static boolean started;

    @Override
    public void appLoaded(ModContext ctx, LoadedApp app) {
        try {
            if (!HOST.equals(app.processName) && !HOST.equals(app.packageName)) return;
            synchronized (K8Entry.class) {
                if (started) return;
                started = true;
            }
            if (new File(NEMESIS_PKG).isDirectory()) {
                ctx.log("k8ecs: NEMESIS package present, engine runs there");
                return;
            }
            CarMain.launch(true);
            ctx.log("k8ecs: engine started in " + app.processName);
        } catch (Throwable t) {
            try { ctx.error("k8ecs: engine not started", t); } catch (Throwable ignored) { }
        }
    }
}
