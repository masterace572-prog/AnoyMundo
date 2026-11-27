package com.xlox.tests;

import com.xlox.utils.SplitDepsTable;

/**
 * Exercises the split dependency table synthesis on the JVM: the table must
 * match the layout AOSP's SplitDependencyLoader expects, or Android 16+
 * virtual-app launches NPE in collectConfigSplitIndices.
 */
public final class SplitDepsTableTest {
    private static int passed;

    private interface Test { void run() throws Exception; }

    private static void test(String name, Test body) throws Exception {
        body.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        test("base with abi and density config splits attaches both to the base", () -> {
            int[][] table = SplitDepsTable.synthesize(new String[] {
                    "config.arm64_v8a", "config.xxhdpi"});
            require(java.util.Arrays.equals(table[0], new int[] {-1, 1, 2}),
                    "base row must list its config splits");
            require(table[1] == null && table[2] == null, "config splits get no own entry");
        });
        test("a feature split depends on base and owns its config splits", () -> {
            int[][] table = SplitDepsTable.synthesize(new String[] {
                    "hd_pack", "hd_pack.config.arm64_v8a", "config.arm64_v8a"});
            require(java.util.Arrays.equals(table[0], new int[] {-1, 3}),
                    "base keeps only its own config split");
            require(java.util.Arrays.equals(table[1], new int[] {0, 2}),
                    "feature split depends on base and carries its config split");
            require(table[2] == null, "feature config split gets no own entry");
        });
        test("a lone feature split depends only on the base", () -> {
            int[][] table = SplitDepsTable.synthesize(new String[] {"hd_pack"});
            require(java.util.Arrays.equals(table[0], new int[] {-1}), "base without configs");
            require(java.util.Arrays.equals(table[1], new int[] {0}), "feature -> base");
        });
        test("no splits yields only the base row", () -> {
            int[][] table = SplitDepsTable.synthesize(new String[0]);
            require(table.length == 1 && java.util.Arrays.equals(table[0], new int[] {-1}),
                    "base row only");
        });
        System.out.println(passed + " split dependency table groups passed");
    }
}
