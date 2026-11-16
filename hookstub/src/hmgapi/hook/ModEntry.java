package hmgapi.hook;

/** Compile-only stub of the HMG-Seogaemo hook host API (the real class is in /system/framework/hmg-seogaemo-hook.jar). */
public class ModEntry {
    public void zygoteInit(ModContext ctx) { }
    public void appLoaded(ModContext ctx, LoadedApp app) { }
    public void onEnabled(ModContext ctx, boolean enabled) { }
    public void onSettingsChanged(ModContext ctx) { }
}
