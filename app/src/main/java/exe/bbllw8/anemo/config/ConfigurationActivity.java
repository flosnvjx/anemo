/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.config;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.function.Consumer;

import exe.bbllw8.anemo.R;
import exe.bbllw8.anemo.config.password.ChangePasswordDialog;
import exe.bbllw8.anemo.config.password.SetPasswordDialog;
import exe.bbllw8.anemo.documents.provider.AnemoDocumentProvider;
import exe.bbllw8.anemo.documents.settings.StorageSettings;
import exe.bbllw8.anemo.documents.vfs.Vfs;
import exe.bbllw8.anemo.lock.LockStore;
import exe.bbllw8.anemo.lock.UnlockActivity;
import exe.bbllw8.anemo.power.BatteryOptimizationHelper;
import exe.bbllw8.anemo.power.KeepAliveService;
import exe.bbllw8.anemo.shell.AnemoShell;

public final class ConfigurationActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQ = 42;

    private ViewGroup rootView;
    private TextView passwordSetView;
    private TextView changeLockView;
    private TextView batteryOptimizationView;
    private TextView storageQuotaView;
    private TextView storageUsageView;
    private TextView thumbnailExtensionsView;
    private TextView thumbnailMaxSizeView;
    private Switch thumbnailsSwitch;
    private Switch biometricSwitch;

    private LockStore lockStore;
    private StorageSettings storageSettings;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        lockStore = LockStore.getInstance(getApplicationContext());
        lockStore.addListener(onLockChanged);
        storageSettings = StorageSettings.getInstance(getApplicationContext());
        storageSettings.addListener(onSettingsChanged);

        setContentView(R.layout.configuration);

        rootView = findViewById(R.id.root_view);
        enableEdgeToEdge();

        passwordSetView = findViewById(R.id.configuration_password_set);

        final Switch shortcutSwitch = findViewById(R.id.configuration_show_shortcut);
        shortcutSwitch.setChecked(AnemoShell.isEnabled(getApplication()));
        shortcutSwitch.setOnCheckedChangeListener(
                (v, isChecked) -> AnemoShell.setEnabled(getApplication(), isChecked));

        changeLockView = findViewById(R.id.configuration_lock);
        changeLockView.setText(lockStore.isLocked()
                ? R.string.configuration_storage_unlock
                : R.string.configuration_storage_lock);
        changeLockView.setOnClickListener($ -> {
            if (lockStore.isLocked()) {
                startActivity(new Intent(this, UnlockActivity.class));
            } else {
                lockStore.lock();
            }
        });

        batteryOptimizationView = findViewById(R.id.configuration_battery_optimization);
        batteryOptimizationView.setOnClickListener(
                $ -> BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this));

        thumbnailsSwitch = findViewById(R.id.configuration_thumbnails);
        thumbnailsSwitch.setChecked(storageSettings.areThumbnailsEnabled());
        thumbnailsSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            storageSettings.setThumbnailsEnabled(isChecked);
            updateThumbnailSubOptions();
        });

        thumbnailExtensionsView = findViewById(R.id.configuration_thumbnail_extensions);
        thumbnailExtensionsView.setOnClickListener($ -> showThumbnailExtensionsDialog());

        thumbnailMaxSizeView = findViewById(R.id.configuration_thumbnail_max_size);
        thumbnailMaxSizeView.setOnClickListener($ -> showThumbnailMaxSizeDialog());

        storageQuotaView = findViewById(R.id.configuration_storage_quota);
        storageQuotaView.setOnClickListener($ -> showQuotaDialog());

        storageUsageView = findViewById(R.id.configuration_storage_usage);
        storageUsageView.setOnClickListener($ -> showStorageUsageDialog());

        biometricSwitch = findViewById(R.id.configuration_biometric_unlock);
        biometricSwitch.setVisibility(lockStore.canAuthenticateBiometric()
                ? View.VISIBLE
                : View.GONE);
        biometricSwitch.setChecked(lockStore.isBiometricUnlockEnabled());
        biometricSwitch.setOnCheckedChangeListener(
                (v, isChecked) -> lockStore.setBiometricUnlockEnabled(isChecked));

        setupPasswordViews();
        updateBatteryOptimizationView();
        updateQuotaView();
        updateThumbnailSubOptions();
        updateStorageUsageView();

        maybeRequestNotificationPermission();
        KeepAliveService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBatteryOptimizationView();
        updateQuotaView();
        updateStorageUsageView();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        lockStore.removeListener(onLockChanged);
        storageSettings.removeListener(onSettingsChanged);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQ) {
            storageSettings.markNotificationPromptShown();
            KeepAliveService.start(this);
        }
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (storageSettings.wasNotificationPromptShown()) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQ);
    }

    private void setupPasswordViews() {
        if (lockStore.hasPassword()) {
            passwordSetView.setText(R.string.configuration_password_change);
            passwordSetView.setOnClickListener(
                    $ -> new ChangePasswordDialog(this, lockStore, this::setupPasswordViews)
                            .show());
        } else {
            passwordSetView.setText(R.string.configuration_password_set);
            passwordSetView.setOnClickListener(
                    $ -> new SetPasswordDialog(this, lockStore, this::setupPasswordViews).show());
        }
        final boolean enableViews = !lockStore.isLocked();
        passwordSetView.setEnabled(enableViews);
        biometricSwitch.setEnabled(enableViews);
    }

    private void updateBatteryOptimizationView() {
        final boolean exempt = BatteryOptimizationHelper
                .isIgnoringBatteryOptimizations(getApplicationContext());
        batteryOptimizationView.setText(exempt
                ? R.string.configuration_battery_optimization_done
                : R.string.configuration_battery_optimization);
    }

    private void updateQuotaView() {
        final long quota = storageSettings.getQuotaBytes();
        final String value;
        if (quota == StorageSettings.UNLIMITED) {
            value = getString(R.string.configuration_storage_quota_unlimited);
        } else {
            value = String.format(Locale.getDefault(), "%d %s",
                    Math.max(1L, quota / (1024L * 1024L)),
                    getString(R.string.configuration_storage_quota_unit));
        }
        storageQuotaView.setText(getString(R.string.configuration_storage_quota_value, value));
    }

    private void updateStorageUsageView() {
        final Vfs vfs = AnemoDocumentProvider.getVfsInstance();
        final long used = vfs == null ? 0L : vfs.getTotalBytes();
        storageUsageView.setText(getString(R.string.configuration_storage_usage_value,
                formatBytes(used)));
    }

    private void updateThumbnailSubOptions() {
        final int visibility = thumbnailsSwitch.isChecked() ? View.VISIBLE : View.GONE;
        thumbnailExtensionsView.setVisibility(visibility);
        thumbnailMaxSizeView.setVisibility(visibility);
        updateThumbnailExtensionsView();
        updateThumbnailMaxSizeView();
    }

    private void updateThumbnailExtensionsView() {
        final String extensions = storageSettings.getThumbnailExtensions();
        final String value = extensions.isEmpty()
                ? getString(R.string.configuration_thumbnail_extensions_all_images)
                : extensions;
        thumbnailExtensionsView.setText(
                getString(R.string.configuration_thumbnail_extensions_value, value));
    }

    private void updateThumbnailMaxSizeView() {
        final long bytes = storageSettings.getThumbnailMaxSizeBytes();
        final String value = String.format(Locale.getDefault(), "%d %s",
                Math.max(1L, bytes / (1024L * 1024L)),
                getString(R.string.configuration_storage_quota_unit));
        thumbnailMaxSizeView.setText(
                getString(R.string.configuration_thumbnail_max_size_value, value));
    }

    private void showStorageUsageDialog() {
        final Vfs vfs = AnemoDocumentProvider.getVfsInstance();
        final long[] counts;
        final long used;
        if (vfs == null) {
            counts = new long[]{0L, 0L};
            used = 0L;
        } else {
            counts = vfs.getTreeCounts();
            used = vfs.getTotalBytes();
        }
        final String message = getString(R.string.configuration_storage_usage_details,
                counts[0], counts[1], used, formatBytes(used));
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.configuration_storage_usage)
                .setMessage(message)
                .setPositiveButton(R.string.keep_alive_action_nuke, (d, which) -> {
                    AnemoDocumentProvider.nuke(this);
                    updateStorageUsageView();
                })
                .setNegativeButton(R.string.configuration_storage_usage_close, null)
                .show();
    }

    private void showQuotaDialog() {
        final String[] options = {
                getString(R.string.configuration_storage_quota_unlimited),
                getString(R.string.configuration_storage_quota_set_size),
                getString(R.string.configuration_storage_quota_reset_default_memory),
        };
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.configuration_storage_quota)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        storageSettings.setQuotaUnlimited();
                    } else if (which == 1) {
                        showQuotaSizeDialog();
                    } else {
                        storageSettings.resetQuotaToDefault();
                    }
                })
                .show();
    }

    private void showQuotaSizeDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        final long current = storageSettings.getQuotaBytes();
        if (current > 0) {
            input.setText(String.valueOf(Math.max(1L, current / (1024L * 1024L))));
        }
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.configuration_storage_quota_set_size)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    final long mib;
                    try {
                        mib = Long.parseLong(input.getText().toString().trim());
                    } catch (NumberFormatException ignored) {
                        return;
                    }
                    if (mib <= 0) {
                        return;
                    }
                    final long requestedBytes;
                    try {
                        requestedBytes = Math.multiplyExact(
                                Math.multiplyExact(mib, 1024L), 1024L);
                    } catch (ArithmeticException overflow) {
                        showQuotaTooLarge();
                        return;
                    }
                    if (exceedsAvailableMemory(requestedBytes)) {
                        showQuotaTooLarge();
                        return;
                    }
                    storageSettings.setQuotaMiB(mib);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean exceedsAvailableMemory(long requestedBytes) {
        final ActivityManager am = getSystemService(ActivityManager.class);
        if (am == null) {
            return false;
        }
        final ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem > 0 && requestedBytes > mi.availMem * 95L / 100L;
    }

    private void showQuotaTooLarge() {
        Toast.makeText(this, R.string.configuration_storage_quota_too_large,
                Toast.LENGTH_LONG).show();
    }

    private void showThumbnailExtensionsDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        final String current = storageSettings.getThumbnailExtensions();
        if (!current.isEmpty()) {
            input.setText(current);
        }
        input.setHint(R.string.configuration_thumbnail_extensions_hint);
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.configuration_thumbnail_extensions)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    storageSettings.setThumbnailExtensions(
                            input.getText().toString().trim());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.configuration_thumbnail_extensions_reset,
                        (d, which) -> storageSettings.setThumbnailExtensions(""))
                .show();
    }

    private void showThumbnailMaxSizeDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        final long current = storageSettings.getThumbnailMaxSizeBytes();
        input.setText(String.valueOf(Math.max(1L, current / (1024L * 1024L))));
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.configuration_thumbnail_max_size)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    try {
                        final long mib = Long.parseLong(input.getText().toString().trim());
                        if (mib > 0) {
                            storageSettings.setThumbnailMaxSizeMiB(mib);
                        }
                    } catch (NumberFormatException ignored) {
                        // keep current value
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        final String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        int unit = -1;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit]);
    }

    private final Consumer<Boolean> onLockChanged = isLocked -> {
        passwordSetView.setEnabled(!isLocked);
        biometricSwitch.setEnabled(!isLocked);
        changeLockView.setText(isLocked
                ? R.string.configuration_storage_unlock
                : R.string.configuration_storage_lock);
    };

    private final Runnable onSettingsChanged = () -> {
        if (thumbnailsSwitch.isChecked() != storageSettings.areThumbnailsEnabled()) {
            thumbnailsSwitch.setChecked(storageSettings.areThumbnailsEnabled());
        }
        updateQuotaView();
        updateThumbnailSubOptions();
    };

    private void enableEdgeToEdge() {
        if (Build.VERSION.SDK_INT < 35) {
            return;
        }

        rootView.setOnApplyWindowInsetsListener((v, windowInsets) -> {
            final Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
            final ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams)
                    v.getLayoutParams();
            mlp.topMargin = insets.top;
            mlp.rightMargin = insets.right;
            mlp.bottomMargin = insets.bottom;
            mlp.leftMargin = insets.left;
            v.setLayoutParams(mlp);
            return WindowInsets.CONSUMED;
        });
    }
}
