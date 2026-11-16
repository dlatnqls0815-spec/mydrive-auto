package hmgapi.hook;

/** Compile-only stub (see ModEntry). */
public class ModContext {
    public final String id = null;
    public final java.io.File dir = null;
    public void log(String msg) { }
    public void error(String msg, Throwable t) { }
    public String settingString(String name, String def) { return def; }
    public boolean settingBoolean(String name, boolean def) { return def; }
}
