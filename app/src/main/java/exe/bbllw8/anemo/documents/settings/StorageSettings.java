/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.documents.settings;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class StorageSettings implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String PREFS = "storage_settings";
    private static final String KEY_THUMBNAILS = "thumbnails_enabled";
    private static final String KEY_THUMBNAIL_EXTENSIONS = "thumbnail_extensions";
    private static final String KEY_THUMBNAIL_MAX_SIZE = "thumbnail_max_size_bytes";
    private static final String KEY_QUOTA_BYTES = "quota_bytes";
    private static final String KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown";
    private static final String KEY_NOTIFICATION_PROMPT_SHOWN = "notification_prompt_shown";
    private static final String KEY_IO_METER_ENABLED = "io_meter_enabled";
    private static final String KEY_IO_METER_INTERVAL_SECONDS = "io_meter_interval_seconds";

    private static final long DEFAULT_THUMBNAIL_MAX_SIZE_BYTES = 32L * 1024L * 1024L;
    private static final int DEFAULT_IO_METER_INTERVAL_SECONDS = 1;

    public static final int MIN_IO_METER_INTERVAL_SECONDS = 1;
    public static final int MAX_IO_METER_INTERVAL_SECONDS = 60;

    public static final long UNLIMITED = -1L;

    private final Context appContext;
    private final SharedPreferences preferences;
    private final long defaultQuotaBytes;
    private final List<Runnable> listeners = new ArrayList<>();
    private volatile Set<String> thumbnailExtensionsCache;

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
        appContext = context;
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(this);
        defaultQuotaBytes = computeDefaultQuotaBytes(context);
        if (!preferences.contains(KEY_QUOTA_BYTES)) {
            preferences.edit()
                    .putLong(KEY_QUOTA_BYTES, defaultQuotaBytes)
                    .apply();
        }
    }

    @Override
    public void onSharedPreferenceChanged(@NonNull SharedPreferences sharedPreferences,
                                          @Nullable String key) {
        if (KEY_THUMBNAIL_EXTENSIONS.equals(key)) {
            thumbnailExtensionsCache = null;
        }
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
        return preferences.getBoolean(KEY_THUMBNAILS, false);
    }

    public void setThumbnailsEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_THUMBNAILS, enabled).apply();
    }

    public String getThumbnailExtensions() {
        return preferences.getString(KEY_THUMBNAIL_EXTENSIONS, "");
    }

    public void setThumbnailExtensions(String extensions) {
        preferences.edit()
                .putString(KEY_THUMBNAIL_EXTENSIONS, extensions == null ? "" : extensions)
                .apply();
    }

    public Set<String> getThumbnailExtensionSet() {
        Set<String> cached = thumbnailExtensionsCache;
        if (cached == null) {
            final String raw = getThumbnailExtensions();
            if (raw.isEmpty()) {
                cached = Collections.emptySet();
            } else {
                final Set<String> parsed = new HashSet<>();
                for (final String item : raw.split(",")) {
                    final String trimmed = item.trim();
                    if (!trimmed.isEmpty()) {
                        parsed.add(trimmed.toLowerCase(Locale.ROOT));
                    }
                }
                cached = parsed.isEmpty() ? Collections.emptySet() : parsed;
            }
            thumbnailExtensionsCache = cached;
        }
        return cached;
    }

    public long getThumbnailMaxSizeBytes() {
        return preferences.getLong(KEY_THUMBNAIL_MAX_SIZE, DEFAULT_THUMBNAIL_MAX_SIZE_BYTES);
    }

    public void setThumbnailMaxSizeMiB(long mib) {
        if (mib > 0) {
            preferences.edit()
                    .putLong(KEY_THUMBNAIL_MAX_SIZE, mib * 1024L * 1024L)
                    .apply();
        }
    }

    public boolean isIoMeterEnabled() {
        return preferences.getBoolean(KEY_IO_METER_ENABLED, false);
    }

    public void setIoMeterEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_IO_METER_ENABLED, enabled).apply();
    }

    public int getIoMeterIntervalSeconds() {
        final int seconds = preferences.getInt(KEY_IO_METER_INTERVAL_SECONDS,
                DEFAULT_IO_METER_INTERVAL_SECONDS);
        return Math.max(MIN_IO_METER_INTERVAL_SECONDS,
                Math.min(MAX_IO_METER_INTERVAL_SECONDS, seconds));
    }

    public void setIoMeterIntervalSeconds(int seconds) {
        if (seconds < MIN_IO_METER_INTERVAL_SECONDS
                || seconds > MAX_IO_METER_INTERVAL_SECONDS) {
            return;
        }
        preferences.edit().putInt(KEY_IO_METER_INTERVAL_SECONDS, seconds).apply();
    }

    public long getQuotaBytes() {
        return preferences.getLong(KEY_QUOTA_BYTES, defaultQuotaBytes);
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
        final long currentDefault = computeDefaultQuotaBytes(appContext);
        final long effective = Math.max(currentDefault, defaultQuotaBytes);
        preferences.edit().putLong(KEY_QUOTA_BYTES, effective).apply();
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

    private static long computeDefaultQuotaBytes(Context context) {
        final ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am != null) {
            final ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            final long half = mi.availMem / 2L;
            if (half > 0) {
                return half;
            }
        }
        final long max = Runtime.getRuntime().maxMemory();
        return max > 0 ? max : 1L;
    }
}

