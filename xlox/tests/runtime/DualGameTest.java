package com.xlox.tests;

import android.content.Context;
import com.xlox.utils.ConcurrentLaunchGuard;
import com.xlox.utils.GameLibraryFiles;
import com.xlox.utils.GameLoginRouter;
import com.xlox.utils.GameRunState;
import com.xlox.utils.GameTarget;
import com.xlox.utils.LibManager;
import com.xlox.utils.StorageAccess;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Production classes with test-only platform adapters; never a device/linker test. */
public final class DualGameTest {
    private static int passed;
    private static File root;

    interface Test { void run() throws Exception; }

    private static void test(String name, Test test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Expectation failed");
    }

    private static File dir(String name) throws IOException {
        File dir = new File(root, name);
        Files.createDirectories(dir.toPath());
        return dir;
    }

    private static File elf(File parent, String name, int marker) throws IOException {
        File file = new File(parent, name);
        Files.createDirectories(file.getParentFile().toPath());
        byte[] data = new byte[128];
        data[0] = 0x7f; data[1] = 'E'; data[2] = 'L'; data[3] = 'F';
        data[4] = 2; data[5] = 1; data[18] = (byte) 183;
        data[120] = (byte) marker;
        Files.write(file.toPath(), data);
        return file;
    }

    private static int marker(File file) throws IOException {
        return Files.readAllBytes(file.toPath())[120] & 0xff;
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("dual-game-fixtures-").toFile();
        try {
            test("game packages and library filenames are distinct and exact", () -> {
                require(GameTarget.fromPackage("com.pubg.imobile") == GameTarget.BGMI);
                require(GameTarget.fromPackage("com.krafton.bgmilite") == GameTarget.BGMI_AIR);
                require(GameTarget.fromPackage("com.krafton.bgmilite.fake") == null);
                require(GameTarget.BGMI.libraryName.equals("libbgmi.so"));
                require(GameTarget.BGMI_AIR.libraryName.equals("libbgmilite.so"));
            });
            test("normal, service and rewritten processes select only their own game", () -> {
                for (GameTarget game : GameTarget.values()) {
                    for (String process : new String[]{game.packageName, game.packageName + ":service",
                            "virtual/" + game.packageName + ":worker", "user0:" + game.packageName.substring(4)}) {
                        require(GameTarget.forProcess(process) == game);
                    }
                    require(GameTarget.forProcess(game.packageName + ".fake") == null);
                    require(GameTarget.forProcess(game.packageName + "x") == null);
                }
                require(GameTarget.forProcess(null) == null);
                require(GameTarget.forProcess("fixture.loader") == null);
                require(GameTarget.forProcess("virtual/" + GameTarget.BGMI.packageName + ":" + GameTarget.BGMI_AIR.packageName) == null);
                require(GameTarget.forProcess(GameTarget.BGMI.packageName, GameTarget.BGMI_AIR.packageName) == null);
                require(GameTarget.forProcess(null, GameTarget.BGMI_AIR.packageName) == GameTarget.BGMI_AIR);
            });
            test("ambiguous legacy provider returns cannot deliver Air login data to BGMI", () -> {
                final int[] calls = {0};
                GameLoginRouter.Container both = new GameLoginRouter.Container() {
                    public boolean isRunning() { return true; }
                    public boolean isOtherGameRunning() { return true; }
                    public GameLoginRouter.Target resolve(String uri) { calls[0]++; return null; }
                    public void launch(GameLoginRouter.Target target, String uri) { calls[0]++; }
                };
                require(GameLoginRouter.route(GameLoginRouter.VIEW, "twittersdk://callback?fixture=1", both)
                        == GameLoginRouter.Result.AMBIGUOUS_GAME);
                require(calls[0] == 0);
                require(GameLoginRouter.route(GameLoginRouter.VIEW, "fbconnect://fixture", both)
                        == GameLoginRouter.Result.LEGACY);
            });
            test("a BGMI-only release never unlocks Air", () -> {
                File release = dir("only-bgmi");
                File main = elf(release, "libbgmi.so", 11);
                require(GameLibraryFiles.find(release, GameTarget.BGMI).equals(main));
                require(GameLibraryFiles.find(release, GameTarget.BGMI_AIR) == null);
                Context context = new Context(dir("only-bgmi-installed"));
                require(LibManager.installRelease(context, release));
                require(LibManager.isUsableLib(context));
                require(!LibManager.isUsableLib(context, GameTarget.BGMI_AIR));
            });
            test("both root-level libraries retain their content and filenames", () -> {
                File release = dir("both");
                elf(release, "libbgmi.so", 21);
                elf(release, "libbgmilite.so", 22);
                elf(release, "libhelper.so", 23);
                Context context = new Context(dir("both-installed"));
                require(LibManager.installRelease(context, release));
                require(marker(LibManager.libFile(context, GameTarget.BGMI)) == 21);
                require(marker(LibManager.libFile(context, GameTarget.BGMI_AIR)) == 22);
                require(marker(new File(LibManager.loaderDir(context), "libhelper.so")) == 23);
            });
            test("wrapped libraries are found independently, even in different folders", () -> {
                File release = dir("wrapped");
                elf(release, "bgmi/arm64/libbgmi.so", 31);
                elf(release, "air/arm64/libbgmilite.so", 32);
                Context context = new Context(dir("wrapped-installed"));
                require(LibManager.installRelease(context, release));
                require(marker(LibManager.libFile(context, GameTarget.BGMI)) == 31);
                require(marker(LibManager.libFile(context, GameTarget.BGMI_AIR)) == 32);
            });
            test("Air-only ZIPs and their dependencies cannot replace BGMI", () -> {
                File release = dir("air-only");
                File air = elf(release, "libbgmilite.so", 41);
                elf(release, "libhelper.so", 42);
                Context context = new Context(dir("air-only-installed"));
                elf(LibManager.loaderDir(context), "libbgmi.so", 40);
                require(GameLibraryFiles.find(release, GameTarget.BGMI) == null);
                require(!LibManager.installRelease(context, release));
                require(!LibManager.installLib(context, air));
                require(marker(LibManager.libFile(context)) == 40);
                require(!LibManager.isUsableLib(context, GameTarget.BGMI_AIR));
            });
            test("legacy BGMI aliases work without borrowing the Air payload", () -> {
                File legacy = dir("legacy");
                File main = elf(legacy, "wrapper/lib.so", 51);
                elf(legacy, "wrapper/libbgmilite.so", 52);
                require(GameLibraryFiles.find(legacy, GameTarget.BGMI).equals(main));
                File anonymous = dir("anonymous");
                File renamed = elf(anonymous, "renamed.so", 53);
                require(GameLibraryFiles.find(anonymous, GameTarget.BGMI).equals(renamed));
                require(GameLibraryFiles.find(anonymous, GameTarget.BGMI_AIR) == null);
            });
            test("a later BGMI-only bundle withdraws old Air support", () -> {
                Context context = new Context(dir("withdraw-installed"));
                File both = dir("withdraw-both");
                elf(both, "libbgmi.so", 61); elf(both, "libbgmilite.so", 62);
                require(LibManager.installRelease(context, both));
                File next = dir("withdraw-next"); elf(next, "libbgmi.so", 63);
                require(LibManager.installRelease(context, next));
                require(marker(LibManager.libFile(context)) == 63);
                require(!LibManager.isUsableLib(context, GameTarget.BGMI_AIR));
            });
            test("empty, truncated or non-ELF Air files do not unlock it", () -> {
                File release = dir("invalid-air"); elf(release, "libbgmi.so", 71);
                File air = new File(release, "libbgmilite.so");
                for (byte[] bad : new byte[][]{new byte[0], new byte[]{0x7f,'E','L','F'}, new byte[128]}) {
                    Files.write(air.toPath(), bad);
                    require(GameLibraryFiles.find(release, GameTarget.BGMI_AIR) == null);
                    Context context = new Context(dir("invalid-air-installed-" + bad.length));
                    require(LibManager.installRelease(context, release));
                    require(!LibManager.isUsableLib(context, GameTarget.BGMI_AIR));
                }
            });
            test("Air load success cannot clear a BGMI failure (or vice versa)", () -> {
                Context context = new Context(dir("errors"));
                LibManager.recordLoadError(context, GameTarget.BGMI, "BGMI failure");
                LibManager.recordLoadError(context, GameTarget.BGMI_AIR, "Air failure");
                LibManager.recordLoadSuccess(context, GameTarget.BGMI_AIR, "fixture");
                require(LibManager.lastLoadError(context).equals("BGMI failure"));
                require(LibManager.lastLoadError(context, GameTarget.BGMI_AIR).isEmpty());
                LibManager.clearLoadError(context, GameTarget.BGMI);
                require(LibManager.lastLoadError(context).isEmpty());
            });
            test("two games run independently; stopping either leaves the other running", () -> {
                GameRunState bgmi = new GameRunState(); GameRunState air = new GameRunState();
                bgmi.launched(1000); air.launched(2000);
                require(bgmi.isRunning() && air.isRunning());
                air.stopped(3000);
                require(bgmi.isRunning() && !air.isRunning());
                air.launched(4000); bgmi.stopped(5000);
                require(!bgmi.isRunning() && air.isRunning());
            });
            test("container reconciliation keeps two-hit/two-miss and first-seen grace", () -> {
                GameRunState state = new GameRunState();
                require(!state.observe(true, 1000));
                require(state.observe(true, 4000) && state.isRunning());
                require(!state.observe(false, 7000));
                require(state.observe(false, 10000) && !state.isRunning());
                state.launched(11000);
                require(!state.observe(false, 12000));
                require(!state.observe(false, 30000) && state.isRunning());
                require(!state.observe(false, 42000));
                require(state.observe(false, 45000) && !state.isRunning());
            });
            test("warning is locked for ten full monotonic seconds, including rounding", () -> {
                ConcurrentLaunchGuard guard = new ConcurrentLaunchGuard(1000);
                require(guard.secondsRemaining(1000) == 10);
                require(guard.secondsRemaining(1001) == 10);
                require(guard.secondsRemaining(10000) == 1);
                require(guard.secondsRemaining(10999) == 1);
                require(!guard.confirm(10999));
                require(guard.secondsRemaining(11000) == 0);
                require(guard.confirm(11000));
                require(!guard.confirm(11001));
            });
            test("Cancel remains final even after the timer; a new warning starts from ten", () -> {
                ConcurrentLaunchGuard guard = new ConcurrentLaunchGuard(1000);
                guard.cancel(); require(!guard.confirm(50000));
                ConcurrentLaunchGuard next = new ConcurrentLaunchGuard(50000);
                require(next.secondsRemaining(50000) == 10 && !next.confirm(50000));
                require(next.secondsRemaining(40000) == 10 && !next.confirm(40000));
            });
            test("restart continuation keeps Air identity and is consumed exactly once", () -> {
                Context context = new Context(dir("restart-air"));
                StorageAccess.markLaunchAfterRestart(context, GameTarget.BGMI_AIR);
                require(StorageAccess.consumeGameAfterRestart(context) == GameTarget.BGMI_AIR);
                require(StorageAccess.consumeGameAfterRestart(context) == null);
                StorageAccess.markLaunchAfterRestart(context);
                require(StorageAccess.consumeGameAfterRestart(context) == GameTarget.BGMI);
            });
            test("legacy, expired, future and unknown-game restart markers are handled safely", () -> {
                Context context = new Context(dir("restart-invalid"));
                android.content.SharedPreferences prefs = context.getSharedPreferences("anoy_permissions", 0);
                prefs.edit().putLong("launch_after_restart_at", System.currentTimeMillis()).commit();
                require(StorageAccess.consumeGameAfterRestart(context) == GameTarget.BGMI);
                for (long time : new long[]{System.currentTimeMillis() - 700000, System.currentTimeMillis() + 60000}) {
                    prefs.edit().putLong("launch_after_restart_at", time).putString("launch_after_restart_game", GameTarget.BGMI_AIR.packageName).commit();
                    require(StorageAccess.consumeGameAfterRestart(context) == null);
                }
                prefs.edit().putLong("launch_after_restart_at", System.currentTimeMillis()).putString("launch_after_restart_game", "outside.game").commit();
                require(StorageAccess.consumeGameAfterRestart(context) == null);
                require(prefs.getLong("launch_after_restart_at", 0) == 0);
            });
            System.out.println(passed + " dual-game production-class JVM regressions passed (test Android adapters; not a device test)");
        } finally {
            LibManager.deleteRecursive(root);
        }
    }
}
