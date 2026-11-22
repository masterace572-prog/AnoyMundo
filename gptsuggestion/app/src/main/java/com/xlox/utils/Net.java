package com.xlox.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;

import com.xlox.R;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

import javax.net.ssl.SSLException;

/** Connectivity checks and user-friendly network error messages. */
public final class Net {

    private static final String TAG = "Net";

    private Net() {
    }

    /** True when the device has a network that claims internet access. */
    public static boolean isOnline(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true; // unknown: let the request decide
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                return caps == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            @SuppressWarnings("deprecation")
            NetworkInfo info = cm.getActiveNetworkInfo();
            //noinspection deprecation
            return info != null && info.isConnected();
        } catch (RuntimeException e) {
            // Missing ACCESS_NETWORK_STATE on a modified ROM: assume online.
            Log.w(TAG, "Connectivity state unavailable", e);
            return true;
        }
    }

    /** True when the active network is a VPN. */
    public static boolean isVpn(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (RuntimeException e) {
            Log.w(TAG, "VPN state unavailable", e);
            return false;
        }
    }

    /** Readable explanation for a low-level I/O failure. */
    public static String friendly(Context context, Throwable error) {
        if (error instanceof UnknownHostException) {
            return context.getString(isOnline(context) ? R.string.net_no_host : R.string.net_offline);
        }
        if (error instanceof SocketTimeoutException) return context.getString(R.string.net_timeout);
        if (error instanceof SSLException) return context.getString(R.string.net_ssl);
        if (error instanceof ConnectException || error instanceof NoRouteToHostException
                || error instanceof PortUnreachableException) {
            return context.getString(isOnline(context) ? R.string.net_refused : R.string.net_offline);
        }
        if (isNoSpace(error)) return context.getString(R.string.update_install_failed);
        if (error instanceof SocketException || error instanceof EOFException
                || error instanceof ProtocolException || error instanceof InterruptedIOException) {
            return context.getString(R.string.net_interrupted);
        }
        if (!isOnline(context)) return context.getString(R.string.net_offline);
        return context.getString(R.string.net_generic);
    }

    /** Whether repeating the same request can reasonably succeed. */
    public static boolean isRetryable(Throwable error) {
        if (error instanceof SSLException) return false;
        if (isNoSpace(error)) return false;
        return error instanceof IOException;
    }

    public static boolean isNoSpace(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.US);
        return lower.contains("enospc") || lower.contains("no space left");
    }
}
