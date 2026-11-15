/*
 * Copyright (c) 2025 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.power;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Helper for OEM-specific background restrictions. Currently recognizes Xiaomi MIUI and
 * HyperOS builds. Used only to reveal advisory rows in Configuration; no runtime behaviour
 * depends on the result.
 */
public final class OemCompat {
    private static final String MIUI_SECURITY_CENTER = "com.miui.securitycenter";
    private static final String MIUI_AUTOSTART_ACTIVITY =
            "com.miui.permcenter.autostart.AutoStartManagementActivity";

    private OemCompat() {
    }

    public static boolean isMiuiLike() {
        final String manufacturer = Build.MANUFACTURER;
        if (manufacturer != null
                && manufacturer.toLowerCase(Locale.ROOT).contains("xiaomi")) {
            return true;
        }
        return !getSystemProperty("ro.miui.ui.version.name", "").isEmpty()
                || !getSystemProperty("ro.mi.os.version.name", "").isEmpty();
    }

    public static void openAutostartSettings(Activity activity) {
        try {
            activity.startActivity(new Intent().setComponent(
                    new ComponentName(MIUI_SECURITY_CENTER, MIUI_AUTOSTART_ACTIVITY)));
            return;
        } catch (Exception ignored) {
            // fall through
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception ignored) {
            // advisory only; nothing else to do
        }
    }

    private static String getSystemProperty(String key, String def) {
        try {
            final Class<?> cls = Class.forName("android.os.SystemProperties");
            final Method get = cls.getMethod("get", String.class, String.class);
            final Object value = get.invoke(null, key, def);
            return value == null ? def : value.toString();
        } catch (Exception e) {
            return def;
        }
    }
}

