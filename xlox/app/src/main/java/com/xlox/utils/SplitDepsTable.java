package com.xlox.utils;

/**
 * The split dependency table SplitDepsFix synthesizes, kept free of Android
 * types so the layout logic runs in plain JVM tests. Row 0 is the base; row
 * i + 1 is splitNames[i]; a null row belongs to a pure config split (it gets
 * no entry of its own). Row layout matches AOSP's SplitDependencyLoader:
 * the first element is the parent (-1 for none), the rest are that split's
 * config splits. Play's delivery naming decides what configures what:
 * "config.<abi/density>" configures the base, "<feature>.config.<...>"
 * configures the feature split, and every other split depends on the base.
 */
public final class SplitDepsTable {

    private SplitDepsTable() {
    }

    public static int[][] synthesize(String[] splitNames) {
        int[][] table = new int[splitNames.length + 1][];
        int[] baseConfig = configIndices(splitNames, null);
        int[] base = new int[baseConfig.length + 1];
        base[0] = -1; // the base depends on nothing
        System.arraycopy(baseConfig, 0, base, 1, baseConfig.length);
        table[0] = base;
        for (int i = 0; i < splitNames.length; i++) {
            String name = splitNames[i];
            if (name == null || isBaseConfig(name) || isFeatureConfig(splitNames, name)) continue;
            int[] config = configIndices(splitNames, name);
            int[] entry = new int[config.length + 1];
            entry[0] = 0; // feature splits depend on the base
            System.arraycopy(config, 0, entry, 1, config.length);
            table[i + 1] = entry;
        }
        return table;
    }

    private static boolean isBaseConfig(String name) {
        return name.startsWith("config.");
    }

    /** "<feature>.config.<...>" where <feature> is itself a split: no own entry. */
    private static boolean isFeatureConfig(String[] splitNames, String name) {
        int at = name.indexOf(".config.");
        if (at <= 0) return false;
        String owner = name.substring(0, at);
        for (String split : splitNames) {
            if (owner.equals(split)) return true;
        }
        return false;
    }

    /** 1-based indices of the config splits of {@code feature} (null = base). */
    private static int[] configIndices(String[] splitNames, String feature) {
        int count = 0;
        for (String name : splitNames) {
            if (matches(name, feature)) count++;
        }
        int[] indices = new int[count];
        int at = 0;
        for (int i = 0; i < splitNames.length; i++) {
            if (matches(splitNames[i], feature)) indices[at++] = i + 1;
        }
        return indices;
    }

    private static boolean matches(String name, String feature) {
        if (name == null) return false;
        return feature == null ? isBaseConfig(name) : name.startsWith(feature + ".config.");
    }
}
