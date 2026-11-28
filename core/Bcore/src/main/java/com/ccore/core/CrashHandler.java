package com.ccore.core;

import com.ccore.CCoreCore;

/**
 * Created by Milk on 4/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private Thread.UncaughtExceptionHandler mDefaultHandler;

    public static void create() {
        new CrashHandler();
    }

    public CrashHandler() {
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if (CCoreCore.get().getExceptionHandler() != null) {
            CCoreCore.get().getExceptionHandler().uncaughtException(t, e);
        }
        mDefaultHandler.uncaughtException(t, e);
    }
}
