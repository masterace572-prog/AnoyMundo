package com.xlox.tests;

import com.xlox.utils.GoogleLoginSetup;

/**
 * Executes the production Google-login decision layer on the JVM: no Android
 * device, no container, no Google account. The container/phone probes stay in
 * MAct and the installer classes; every choice they drive is covered here.
 */
public final class GoogleLoginSetupTest {
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
        test("status prefers a healthy microG over every other probe", () -> {
            require(GoogleLoginSetup.resolveStatus(true, true, true) == GoogleLoginSetup.Status.MICROG,
                    "microG internal install must win");
            require(GoogleLoginSetup.resolveStatus(true, true, false) == GoogleLoginSetup.Status.MICROG,
                    "microG alone must win");
        });
        test("microG without the internal registration needs repair, not reinstall", () -> {
            require(GoogleLoginSetup.resolveStatus(true, false, false)
                            == GoogleLoginSetup.Status.MICROG_NEEDS_REPAIR,
                    "non-internal microG must need repair");
            require(GoogleLoginSetup.showRepair(GoogleLoginSetup.Status.MICROG_NEEDS_REPAIR),
                    "repair is offered");
            require(!GoogleLoginSetup.showRepair(GoogleLoginSetup.Status.MICROG),
                    "healthy microG needs no repair");
            require(!GoogleLoginSetup.showRepair(GoogleLoginSetup.Status.GOOGLE_SERVICES),
                    "stock never shows microG repair");
            require(!GoogleLoginSetup.showRepair(GoogleLoginSetup.Status.NOT_SET_UP),
                    "nothing installed: no repair");
        });
        test("stock core services read as Google services only without microG", () -> {
            require(GoogleLoginSetup.resolveStatus(false, false, true) == GoogleLoginSetup.Status.GOOGLE_SERVICES,
                    "GMS+GSF inside the container is Google services");
            require(GoogleLoginSetup.resolveStatus(false, false, false) == GoogleLoginSetup.Status.NOT_SET_UP,
                    "nothing installed is not set up");
        });
        test("the phone path requires both GMS and GSF on the phone itself", () -> {
            require(GoogleLoginSetup.phoneAvailable(true, true), "both present");
            require(!GoogleLoginSetup.phoneAvailable(true, false), "GSF missing");
            require(!GoogleLoginSetup.phoneAvailable(false, true), "GMS missing");
            require(!GoogleLoginSetup.phoneAvailable(false, false), "neither present");
        });
        test("the phone action hides only for a healthy stock install", () -> {
            require(!GoogleLoginSetup.showPhoneAction(GoogleLoginSetup.Status.GOOGLE_SERVICES, true),
                    "already stock: pointless");
            require(GoogleLoginSetup.showPhoneAction(GoogleLoginSetup.Status.MICROG, true),
                    "switch away from microG");
            require(GoogleLoginSetup.showPhoneAction(GoogleLoginSetup.Status.NOT_SET_UP, true),
                    "fresh install");
            require(!GoogleLoginSetup.showPhoneAction(GoogleLoginSetup.Status.NOT_SET_UP, false),
                    "no Google apps on the phone: not offered");
        });
        test("microG is offered unless it is the healthy provider", () -> {
            require(!GoogleLoginSetup.showMicrogAction(GoogleLoginSetup.Status.MICROG), "already microG");
            require(GoogleLoginSetup.showMicrogAction(GoogleLoginSetup.Status.MICROG_NEEDS_REPAIR),
                    "reinstall remains available beside repair");
            require(GoogleLoginSetup.showMicrogAction(GoogleLoginSetup.Status.GOOGLE_SERVICES),
                    "switch away from stock");
            require(GoogleLoginSetup.showMicrogAction(GoogleLoginSetup.Status.NOT_SET_UP), "fresh install");
        });
        test("only a healthy provider counts as ready for the game's sign-in", () -> {
            require(GoogleLoginSetup.isReady(GoogleLoginSetup.Status.MICROG), "microG ready");
            require(GoogleLoginSetup.isReady(GoogleLoginSetup.Status.GOOGLE_SERVICES), "stock ready");
            require(!GoogleLoginSetup.isReady(GoogleLoginSetup.Status.MICROG_NEEDS_REPAIR),
                    "broken microG is not ready");
            require(!GoogleLoginSetup.isReady(GoogleLoginSetup.Status.NOT_SET_UP), "nothing is not ready");
        });
        test("the phone button switches away from microG and installs fresh otherwise", () -> {
            require(GoogleLoginSetup.phoneTask(GoogleLoginSetup.Status.MICROG)
                            == GoogleLoginSetup.Task.SWITCH_TO_PHONE, "microG -> stock switch");
            require(GoogleLoginSetup.phoneTask(GoogleLoginSetup.Status.MICROG_NEEDS_REPAIR)
                            == GoogleLoginSetup.Task.SWITCH_TO_PHONE, "broken microG -> stock switch");
            require(GoogleLoginSetup.phoneTask(GoogleLoginSetup.Status.NOT_SET_UP)
                            == GoogleLoginSetup.Task.INSTALL_PHONE, "fresh stock install");
            require(GoogleLoginSetup.phoneTask(GoogleLoginSetup.Status.GOOGLE_SERVICES)
                            == GoogleLoginSetup.Task.INSTALL_PHONE, "reinstall stock");
        });
        System.out.println(passed + " Google login decision groups passed");
    }
}
