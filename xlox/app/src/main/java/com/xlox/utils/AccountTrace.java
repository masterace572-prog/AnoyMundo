package com.xlox.utils;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Virtual account-call tracer. The Play Store's in-container sign-in can fail
 * with no visible error; there is no logcat on a user's phone. This wraps the
 * hooked account service (ServiceManager's cached "account" binder) with a
 * tracing IAccountManager that records every call — method name, argument
 * count, and the outcome (ok / exception class and message). Never argument
 * contents: no tokens, no passwords, no account names. The tail shows up in
 * Help → Copy support info, so a silent failure becomes a paste-able line.
 */
public final class AccountTrace {

    private static final String TAG = "AccountTrace";
    private static final String FILE = "account-trace.txt";
    private static final int MAX_CHARS = 6000;
    private static final Object LOCK = new Object();

    private static Context app;

    private AccountTrace() {
    }

    /** Wraps the cached account binder, once per process. No-op until the
     *  container has hooked the account service. */
    public static void install(Context context) {
        if (context != null) app = context.getApplicationContext();
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Field cacheField = serviceManager.getDeclaredField("sCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, IBinder> cache = (Map<String, IBinder>) cacheField.get(null);
            if (cache == null) return;
            IBinder current = cache.get("account");
            if (current == null || current instanceof TracingBinder) return;
            Class<?> iam = Class.forName("android.accounts.IAccountManager");
            Object proxy = Proxy.newProxyInstance(AccountTrace.class.getClassLoader(),
                    new Class<?>[]{iam}, new TracingHandler(current, iam));
            cache.put("account", new TracingBinder(current, (IInterface) proxy));
            Log.i(TAG, "Account service tracer installed");
            record("tracer installed");
        } catch (Throwable t) {
            Log.w(TAG, "Account tracer unavailable", t);
        }
    }

    /** The recorded tail, or null when empty. */
    public static String read(Context ctx) {
        File file = new File(ctx.getFilesDir(), FILE);
        if (!file.isFile()) return null;
        FileInputStream in = null;
        try {
            byte[] data = new byte[(int) Math.min(file.length(), MAX_CHARS + 16L)];
            in = new FileInputStream(file);
            int n = in.read(data);
            return n > 0 ? new String(data, 0, n, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void record(String line) {
        Context ctx = app;
        if (ctx == null) return;
        synchronized (LOCK) {
            FileOutputStream out = null;
            try {
                File file = new File(ctx.getFilesDir(), FILE);
                String existing = "";
                if (file.isFile()) {
                    FileInputStream in = new FileInputStream(file);
                    byte[] data = new byte[(int) Math.min(file.length(), MAX_CHARS)];
                    int n = in.read(data);
                    in.close();
                    if (n > 0) existing = new String(data, 0, n, StandardCharsets.UTF_8);
                }
                String text = existing + timestamp() + " " + line + "\n";
                if (text.length() > MAX_CHARS) text = text.substring(text.length() - MAX_CHARS);
                out = new FileOutputStream(file, false);
                out.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            } finally {
                try {
                    if (out != null) out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    /** Delegates real Binder transactions to the container's binder, but hands
     *  out our tracing IAccountManager as the local interface. */
    private static final class TracingBinder extends Binder {
        private final IBinder delegate;
        private final IInterface traced;

        TracingBinder(IBinder delegate, IInterface traced) {
            this.delegate = delegate;
            this.traced = traced;
            attachInterface(traced, "android.accounts.IAccountManager");
        }

        @Override
        public IInterface queryLocalInterface(String descriptor) {
            return traced;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            // Binder.transact is final; direct binder traffic flows through here.
            return delegate.transact(code, data, reply, flags);
        }
    }

    private static final class TracingHandler implements InvocationHandler {
        private final Object delegate; // the container's IAccountManager

        TracingHandler(IBinder binder, Class<?> iam) throws Exception {
            Class<?> stub = Class.forName("android.accounts.IAccountManager$Stub");
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            Object local = asInterface.invoke(null, binder);
            this.delegate = local != null ? local : binder;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }
            String name = method.getName() + "/" + (args == null ? 0 : args.length);
            try {
                Object result = delegate instanceof android.os.IBinder
                        ? invokeRaw(delegate, method, args)
                        : Proxy.isProxyClass(delegate.getClass())
                                ? Proxy.getInvocationHandler(delegate).invoke(proxy, method, args)
                                : invokeRaw(delegate, method, args);
                record(name + " ok");
                return result;
            } catch (Throwable t) {
                Throwable cause = t instanceof InvocationTargetException
                        ? ((InvocationTargetException) t).getTargetException() : t;
                record(name + " FAIL " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                throw cause;
            }
        }
    }

    private static Object invokeRaw(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
