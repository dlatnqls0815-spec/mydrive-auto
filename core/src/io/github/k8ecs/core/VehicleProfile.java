package io.github.k8ecs.core;

/**
 * What a car model sends and answers for the three My Drive items. One profile per model; add yours to
 * {@link #ALL} (see docs/CONTRIBUTING.md). The car module picks the first profile whose {@code products}
 * glob matches the head unit's {@code ro.build.product} (the same value Seogaemo matches {@code cars=} against);
 * {@link #GENERIC} is used when none matches.
 *
 * Values come from the stock settings call {@code setIndividualDriveMode(item, value)} and the answer
 * {@code mIndividualModeFeedback: [0] a [1] b [2] c} in logcat. The car module still learns feedback numbers
 * on the first change, so a profile only has to be right about the item numbers and the values sent.
 */
public final class VehicleProfile {

    /** One My Drive item: its number, the values sent for each step and the feedback the car answers. */
    public static final class Item {
        public final boolean supported;
        public final int item;
        public final int lowValue, sportValue;   // sent with setIndividualDriveMode
        public final int lowFeedback, sportFeedback;
        public final String lowName, sportName;  // log names

        public Item(boolean supported, int item, int lowValue, int lowFeedback, String lowName,
                    int sportValue, int sportFeedback, String sportName) {
            this.supported = supported; this.item = item;
            this.lowValue = lowValue; this.lowFeedback = lowFeedback; this.lowName = lowName;
            this.sportValue = sportValue; this.sportFeedback = sportFeedback; this.sportName = sportName;
        }

        public static Item none(int item) {
            return new Item(false, item, 0, -1, "-", 1, -1, "-");
        }
    }

    public final String id;
    /** Comma-separated globs on ro.build.product ({@code *} = any run of characters). */
    public final String products;
    public final String note;
    public final Item suspension, steering;
    /** Powertrain low step: Eco (hybrid) or Normal (gasoline, Settings.engNormal), and Sport. */
    public final Item engineEco, engineNormal;

    public VehicleProfile(String id, String products, String note, Item suspension, Item steering,
                          Item engineEco, Item engineNormal) {
        this.id = id; this.products = products; this.note = note;
        this.suspension = suspension; this.steering = steering;
        this.engineEco = engineEco; this.engineNormal = engineNormal;
    }

    public Item engine(boolean normal) {
        return normal ? engineNormal : engineEco;
    }

    // ---------- known cars ----------

    /** Kia K8 (GL3) hybrid, Gen5W. Confirmed on the car 2026-11-15..17 (docs/DESIGN.md). */
    public static final VehicleProfile K8_GL3 = new VehicleProfile("k8-gl3", "GL3*",
            "Kia K8 GL3 hybrid (confirmed)",
            new Item(true, CarLog.SUSPENSION_ITEM, 0, 2, "soft", 1, 5, "firm"),
            new Item(true, CarLog.STEERING_ITEM, 0, 2, "normal", 1, 5, "sport"),
            new Item(true, CarLog.POWERTRAIN_ITEM, 2, 4, "eco", 1, 5, "sport"),
            new Item(true, CarLog.POWERTRAIN_ITEM, 0, 2, "normal", 1, 5, "sport"));

    /**
     * Any other Gen5W car: the K8 numbering as a first guess. Feedback numbers are learned on the first
     * change, and an item the car lacks gets no answer and halts on its own.
     */
    public static final VehicleProfile GENERIC = new VehicleProfile("generic", "*",
            "Unknown car: K8 numbering, feedback learned on the car",
            K8_GL3.suspension, K8_GL3.steering, K8_GL3.engineEco, K8_GL3.engineNormal);

    /** Checked in order; GENERIC last. Add new cars above it. */
    public static final VehicleProfile[] ALL = {K8_GL3, GENERIC};

    public static VehicleProfile forProduct(String product) {
        String p = product == null ? "" : product.trim();
        for (VehicleProfile v : ALL) {
            for (String glob : v.products.split(",")) {
                if (globMatch(glob.trim(), p)) return v;
            }
        }
        return GENERIC;
    }

    static boolean globMatch(String glob, String s) {
        if (glob.isEmpty()) return false;
        StringBuilder re = new StringBuilder();
        for (char ch : glob.toCharArray()) {
            if (ch == '*') re.append(".*");
            else if (ch == '?') re.append('.');
            else re.append(java.util.regex.Pattern.quote(String.valueOf(ch)));
        }
        return s.matches(re.toString());
    }
}
