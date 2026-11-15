/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.power;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Objects;

import exe.bbllw8.anemo.R;
import exe.bbllw8.anemo.config.ConfigurationActivity;
import exe.bbllw8.anemo.documents.provider.AnemoDocumentProvider;
import exe.bbllw8.anemo.documents.settings.StorageSettings;
import exe.bbllw8.anemo.documents.vfs.IoStats;
import exe.bbllw8.anemo.shell.LauncherActivity;

public final class KeepAliveService extends Service {
    private static final String TAG = "KeepAliveService";
    private static final String CHANNEL_ID = "anemo_keep_alive";
    private static final int NOTIFICATION_ID = 0x10;
    private static final String METER_THREAD_NAME = "anemo-io-meter";

    private static final String ACTION_NUKE = "exe.bbllw8.anemo.action.NUKE";
    private static final String ACTION_NUKE_EXIT = "exe.bbllw8.anemo.action.NUKE_EXIT";

    private StorageSettings storageSettings;
    private PendingIntent contentPi;
    private PendingIntent nukePi;
    private PendingIntent nukeExitPi;
    private PendingIntent configurePi;

    private HandlerThread meterThread;
    private Handler meterHandler;

    private volatile boolean lastMeterEnabled;
    private volatile long lastReadBytes;
    private volatile long lastWriteBytes;
    private volatile long lastUpdateMillis;
    private volatile long currentReadRate;
    private volatile long currentWriteRate;
    private volatile String lastContentText;

    private final Runnable meterUpdate = () -> {
        updateMeter();
        scheduleMeterUpdates();
    };

    private final Runnable settingsListener = this::onSettingsChanged;
    private final Runnable applySettingsChange = this::applySettingsChange;

    public static void start(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        final Intent intent = new Intent(context, KeepAliveService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start keep-alive service", e);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, KeepAliveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        storageSettings = StorageSettings.getInstance(this);
        lastMeterEnabled = storageSettings.isIoMeterEnabled();
        storageSettings.addListener(settingsListener);
        createNotificationChannel();
        buildPendingIntents();

        meterThread = new HandlerThread(METER_THREAD_NAME);
        meterThread.start();
        meterHandler = new Handler(meterThread.getLooper());
        resetMeterBaseline();
    }

    @Override
    public void onDestroy() {
        if (storageSettings != null) {
            storageSettings.removeListener(settingsListener);
        }
        if (meterHandler != null) {
            meterHandler.removeCallbacksAndMessages(null);
        }
        if (meterThread != null) {
            meterThread.quitSafely();
            meterThread = null;
            meterHandler = null;
        }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        resetMeterBaseline();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                // API 33 and below: no foreground service type is required
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to enter foreground, stopping service", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        lastContentText = buildContentText();

        final String action = intent != null ? intent.getAction() : null;
        if (ACTION_NUKE_EXIT.equals(action)) {
            AnemoDocumentProvider.nuke(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_NUKE.equals(action)) {
            AnemoDocumentProvider.nuke(this);
        }
        scheduleMeterUpdates();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            final NotificationManager nm = getSystemService(NotificationManager.class);
            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.keep_alive_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.keep_alive_channel_description));
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
    }

    private void buildPendingIntents() {
        contentPi = PendingIntent.getActivity(this, 0,
                new Intent(this, LauncherActivity.class), PendingIntent.FLAG_IMMUTABLE);
        nukePi = PendingIntent.getService(this, 1,
                new Intent(this, KeepAliveService.class).setAction(ACTION_NUKE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nukeExitPi = PendingIntent.getService(this, 2,
                new Intent(this, KeepAliveService.class).setAction(ACTION_NUKE_EXIT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        configurePi = PendingIntent.getActivity(this, 3,
                new Intent(this, ConfigurationActivity.class), PendingIntent.FLAG_IMMUTABLE);
    }

    private void onSettingsChanged() {
        if (meterHandler == null) {
            return;
        }
        meterHandler.removeCallbacks(applySettingsChange);
        meterHandler.post(applySettingsChange);
    }

    private void applySettingsChange() {
        if (storageSettings == null) {
            return;
        }
        final boolean enabled = storageSettings.isIoMeterEnabled();
        if (enabled != lastMeterEnabled) {
            lastMeterEnabled = enabled;
            resetMeterBaseline();
            lastContentText = null;
            pushNotificationIfChanged();
        }
        scheduleMeterUpdates();
    }

    private void scheduleMeterUpdates() {
        if (meterHandler == null) {
            return;
        }
        meterHandler.removeCallbacks(meterUpdate);
        if (storageSettings == null || !storageSettings.isIoMeterEnabled()) {
            return;
        }
        final long intervalMs = Math.max(1, storageSettings.getIoMeterIntervalSeconds()) * 1000L;
        meterHandler.postDelayed(meterUpdate, intervalMs);
    }

    private void updateMeter() {
        final long now = System.currentTimeMillis();
        final long elapsed = Math.max(1L, now - lastUpdateMillis);
        final long readTotal = IoStats.getReadBytes();
        final long writeTotal = IoStats.getWriteBytes();
        currentReadRate = Math.max(0L, (readTotal - lastReadBytes) * 1000L / elapsed);
        currentWriteRate = Math.max(0L, (writeTotal - lastWriteBytes) * 1000L / elapsed);
        lastReadBytes = readTotal;
        lastWriteBytes = writeTotal;
        lastUpdateMillis = now;
        pushNotificationIfChanged();
    }

    private void pushNotificationIfChanged() {
        final String text = buildContentText();
        if (Objects.equals(text, lastContentText)) {
            return;
        }
        lastContentText = text;
        notifyNotification(text);
    }

    private void notifyNotification(String contentText) {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(contentText));
        }
    }

    private void resetMeterBaseline() {
        lastReadBytes = IoStats.getReadBytes();
        lastWriteBytes = IoStats.getWriteBytes();
        lastUpdateMillis = System.currentTimeMillis();
        currentReadRate = 0L;
        currentWriteRate = 0L;
    }

    private Notification buildNotification() {
        return buildNotification(buildContentText());
    }

    private Notification buildNotification(String contentText) {
        final Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle(getString(R.string.keep_alive_notification_title))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentPi)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_delete),
                        getString(R.string.keep_alive_action_nuke),
                        nukePi).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this,
                                android.R.drawable.ic_menu_close_clear_cancel),
                        getString(R.string.keep_alive_action_nuke_exit),
                        nukeExitPi).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_manage),
                        getString(R.string.keep_alive_action_configure),
                        configurePi).build());
        if (contentText != null) {
            builder.setContentText(contentText);
        }
        return builder.setOnlyAlertOnce(true)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private String buildContentText() {
        if (storageSettings != null && storageSettings.isIoMeterEnabled()) {
            return getString(R.string.keep_alive_io_meter,
                    formatRate(currentReadRate), formatRate(currentWriteRate));
        }
        return null;
    }

    private static String formatRate(long bytesPerSecond) {
        if (bytesPerSecond < 1024L) {
            return bytesPerSecond + " B/s";
        }
        double value = bytesPerSecond;
        final String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        int unit = -1;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return formatOneDecimal(value) + " " + units[unit] + "/s";
    }

    private static String formatOneDecimal(double value) {
        final long tenths = Math.round(value * 10.0);
        final long whole = tenths / 10L;
        final int frac = (int) (tenths % 10L);
        return whole + "." + frac;
    }
}

