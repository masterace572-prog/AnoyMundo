package com.xlox.utils;

/**
 * Google login setup decisions, kept free of the container API so they run in
 * plain JVM tests. MAct probes the container/phone and feeds the booleans in;
 * the strings and sheet actions follow the resolved state.
 */
public final class GoogleLoginSetup {

    /** What provides Google sign-in inside the container right now. */
    public enum Status {
        NOT_SET_UP,
        GOOGLE_SERVICES,
        MICROG,
        /** microG present but not registered as an internal app: Google login fails until repaired. */
        MICROG_NEEDS_REPAIR
    }

    /** One sheet action. The phone button maps to INSTALL_PHONE or SWITCH_TO_PHONE. */
    public enum Task { INSTALL_PHONE, SWITCH_TO_PHONE, INSTALL_MICROG, REPAIR }

    private GoogleLoginSetup() { }

    /**
     * microG wins over a stock install: it replaces the same three package
     * names, so a stock core detected beside it is the leftover of an
     * interrupted switch. microG is only usable as an internal app.
     */
    public static Status resolveStatus(boolean microgInstalled, boolean microgInternal,
                                       boolean stockCoreInstalled) {
        if (microgInstalled) return microgInternal ? Status.MICROG : Status.MICROG_NEEDS_REPAIR;
        return stockCoreInstalled ? Status.GOOGLE_SERVICES : Status.NOT_SET_UP;
    }

    /** The phone path clones the phone's own apps; GMS + GSF must exist there. */
    public static boolean phoneAvailable(boolean phoneHasGms, boolean phoneHasGsf) {
        return phoneHasGms && phoneHasGsf;
    }

    /** Repair is only meaningful while microG sits as a non-internal install. */
    public static boolean showRepair(Status status) {
        return status == Status.MICROG_NEEDS_REPAIR;
    }

    /** The phone action is pointless while a healthy stock install is already live. */
    public static boolean showPhoneAction(Status status, boolean phoneAvailable) {
        return status != Status.GOOGLE_SERVICES && phoneAvailable;
    }

    /** microG is offered unless it is already the (healthy) provider. */
    public static boolean showMicrogAction(Status status) {
        return status != Status.MICROG;
    }

    /** True once either provider can answer the game's Google sign-in. */
    public static boolean isReady(Status status) {
        return status == Status.GOOGLE_SERVICES || status == Status.MICROG;
    }

    /** The phone button's task: a switch away from microG, else a fresh stock install. */
    public static Task phoneTask(Status status) {
        return status == Status.MICROG || status == Status.MICROG_NEEDS_REPAIR
                ? Task.SWITCH_TO_PHONE : Task.INSTALL_PHONE;
    }
}
