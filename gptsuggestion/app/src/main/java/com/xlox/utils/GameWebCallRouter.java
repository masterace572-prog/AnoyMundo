package com.xlox.utils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Chains the SDK's existing activity handler, replacing only an outbound web Intent. */
public final class GameWebCallRouter implements InvocationHandler {
    public interface Redirector {
        /** null means unchanged; non-Intent parameters must always return null. */
        Object redirect(Object argument);
    }

    public interface Registrar {
        InvocationHandler register(InvocationHandler handler);
    }

    private final Redirector redirector;
    private InvocationHandler previous;

    public GameWebCallRouter(Redirector redirector) {
        this.redirector = redirector;
    }

    /** Invocation cannot race the assignment of the returned previous handler. */
    public synchronized boolean install(Registrar registrar) {
        if (previous != null) return true;
        previous = registrar.register(this);
        return previous != null;
    }

    @Override
    public Object invoke(Object base, Method method, Object[] arguments) throws Throwable {
        InvocationHandler delegate;
        synchronized (this) {
            delegate = previous;
        }
        if (delegate == null) throw new IllegalStateException("Game web routing is not ready");
        Object[] forwarded = arguments;
        if (arguments != null) {
            for (int i = 0; i < arguments.length; i++) {
                Object replacement = redirector.redirect(arguments[i]);
                if (replacement != null) {
                    forwarded = arguments.clone();
                    forwarded[i] = replacement;
                    break;
                }
            }
        }
        try {
            // Preserve binder/resultTo, request code, options, return type and
            // every other argument. The existing SDK still does its wrapping.
            return delegate.invoke(base, method, forwarded);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
