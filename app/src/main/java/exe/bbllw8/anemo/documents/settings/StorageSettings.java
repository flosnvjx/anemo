/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.documents.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class StorageSettings implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String PREFS = "storage_settings";
    private static final String KEY_THUMBNAILS = "thumbnails_enabled";
    private static final String KEY_QUOTA_BYTES = "quota_bytes";
    private static final String KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown";
    private static final String KEY_NOTIFICATION_PROMPT_SHOWN = "notification_prompt_shown";

    public static final long UNLIMITED = -1L;

    private final SharedPreferences preferences;
    private final List<Runnable> listeners = new ArrayList<>();

    private static volatile StorageSettings instance;

    public static StorageSettings getInstance(Context context) {
        if (instance == null) {
            synchronized (StorageSettings.class) {
                if (instance == null) {
                    instance = new StorageSettings(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private StorageSettings(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(this);
        if (!preferences.contains(KEY_QUOTA_BYTES)) {
            preferences.edit()
                    .putLong(KEY_QUOTA_BYTES, defaultQuotaBytes())
                    .apply();
        }
    }

    @Override
    public void onSharedPreferenceChanged(@NonNull SharedPreferences sharedPreferences,
                                          @Nullable String key) {
        final Runnable[] snapshot;
        synchronized (listeners) {
            snapshot = listeners.toArray(new Runnable[0]);
        }
        for (final Runnable listener : snapshot) {
            listener.run();
        }
    }

    public void addListener(Runnable listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public boolean areThumbnailsEnabled() {
        return preferences.getBoolean(KEY_THUMBNAILS, true);
    }

    public void setThumbnailsEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_THUMBNAILS, enabled).apply();
    }

    public long getQuotaBytes() {
        return preferences.getLong(KEY_QUOTA_BYTES, defaultQuotaBytes());
    }

    public boolean isQuotaUnlimited() {
        return getQuotaBytes() == UNLIMITED;
    }

    public void setQuotaUnlimited() {
        preferences.edit().putLong(KEY_QUOTA_BYTES, UNLIMITED).apply();
    }

    public void setQuotaMiB(long mib) {
        if (mib <= 0) {
            return;
        }
        preferences.edit().putLong(KEY_QUOTA_BYTES, mib * 1024L * 1024L).apply();
    }

    public void resetQuotaToDefault() {
        preferences.edit().putLong(KEY_QUOTA_BYTES, defaultQuotaBytes()).apply();
    }

    public boolean wasBatteryPromptShown() {
        return preferences.getBoolean(KEY_BATTERY_PROMPT_SHOWN, false);
    }

    public void markBatteryPromptShown() {
        preferences.edit().putBoolean(KEY_BATTERY_PROMPT_SHOWN, true).apply();
    }

    public boolean wasNotificationPromptShown() {
        return preferences.getBoolean(KEY_NOTIFICATION_PROMPT_SHOWN, false);
    }

    public void markNotificationPromptShown() {
        preferences.edit().putBoolean(KEY_NOTIFICATION_PROMPT_SHOWN, true).apply();
    }

    private static long defaultQuotaBytes() {
        final long half = Runtime.getRuntime().maxMemory() / 2L;
        return half > 0 ? half : 1L;
    }
}

