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
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import exe.bbllw8.anemo.R;
import exe.bbllw8.anemo.documents.provider.AnemoDocumentProvider;
import exe.bbllw8.anemo.shell.LauncherActivity;

public final class KeepAliveService extends Service {
    private static final String TAG = "KeepAliveService";
    private static final String CHANNEL_ID = "anemo_keep_alive";
    private static final int NOTIFICATION_ID = 0x10;

    private static final String ACTION_NUKE = "exe.bbllw8.anemo.action.NUKE";
    private static final String ACTION_NUKE_EXIT = "exe.bbllw8.anemo.action.NUKE_EXIT";

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
    public int onStartCommand(Intent intent, int flags, int startId) {
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
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            final NotificationManager nm = getSystemService(NotificationManager.class);
            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.keep_alive_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.keep_alive_channel_description));
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        final PendingIntent contentPi = PendingIntent.getActivity(this, 0,
                new Intent(this, LauncherActivity.class), PendingIntent.FLAG_IMMUTABLE);
        final PendingIntent nukePi = PendingIntent.getService(this, 1,
                new Intent(this, KeepAliveService.class).setAction(ACTION_NUKE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final PendingIntent nukeExitPi = PendingIntent.getService(this, 2,
                new Intent(this, KeepAliveService.class).setAction(ACTION_NUKE_EXIT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        final Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setContentTitle(getString(R.string.keep_alive_notification_title))
                .setContentText(getString(R.string.keep_alive_notification_text))
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
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }
}
