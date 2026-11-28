package com.ccore.spoof;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Flagship device identities reported INSIDE the virtual container. The host
 * system and the real device are never modified; only the virtual app's view
 * of android.os.SystemProperties / android.os.Build is rewritten, so games
 * that gate features (e.g. high-FPS tiers) on a device whitelist see a
 * supported flagship.
 *
 * Never spoof ABI or SDK-level properties: identity only.
 */
public final class DeviceProfiles {

    /** Property key -> spoofed value for the default flagship profile. */
    public static final Map<String, String> PROPS;

    static {
        Map<String, String> m = new HashMap<>();
        // Samsung Galaxy S24 Ultra (SM-S928B), Snapdragon 8 Gen 3 (kalama).
        m.put("ro.product.brand", "samsung");
        m.put("ro.product.manufacturer", "samsung");
        m.put("ro.product.model", "SM-S928B");
        m.put("ro.product.device", "e3q");
        m.put("ro.product.name", "e3qxeea");
        m.put("ro.product.marketname", "Galaxy S24 Ultra");
        m.put("ro.product.board", "kalama");
        m.put("ro.board.platform", "kalama");
        m.put("ro.hardware", "qcom");
        m.put("ro.hardware.chipname", "sm8650");
        m.put("ro.build.fingerprint", "samsung/e3qxeea/e3q:14/UP1A.231005.007/S928BXXS2AXE5:user/release-keys");
        m.put("ro.build.display.id", "UP1A.231005.007.S928BXXS2AXE5");
        m.put("ro.build.id", "UP1A.231005.007");
        m.put("ro.build.version.incremental", "S928BXXS2AXE5");
        m.put("ro.build.product", "e3q");
        m.put("ro.bootloader", "S928BXXS2AXE5");
        PROPS = Collections.unmodifiableMap(m);
    }

    private DeviceProfiles() {
    }

    /** Spoofed value for a ro.* key, or null when the real value passes through. */
    public static String get(String key) {
        return key == null ? null : PROPS.get(key);
    }
}
