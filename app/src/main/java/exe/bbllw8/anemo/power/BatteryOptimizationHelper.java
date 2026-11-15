/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.power;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

public final class BatteryOptimizationHelper {
    private BatteryOptimizationHelper() {
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        final PowerManager pm = context.getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static Intent buildRequestIntent(Context context) {
        return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + context.getPackageName()));
    }

    public static void requestIgnoreBatteryOptimizations(Activity activity) {
        try {
            activity.startActivity(buildRequestIntent(activity));
            return;
        } catch (Exception ignored) {
            // fall through
        }
        try {
            activity.startActivity(
                    new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        } catch (Exception ignored) {
            // fall through
        }
        activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName())));
    }
}

