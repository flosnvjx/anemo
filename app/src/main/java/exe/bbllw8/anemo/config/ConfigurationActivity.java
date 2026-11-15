/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.config;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.function.Consumer;

import exe.bbllw8.anemo.R;
import exe.bbllw8.anemo.config.password.ChangePasswordDialog;
import exe.bbllw8.anemo.config.password.SetPasswordDialog;
import exe.bbllw8.anemo.documents.settings.StorageSettings;
import exe.bbllw8.anemo.lock.LockStore;
import exe.bbllw8.anemo.lock.UnlockActivity;
import exe.bbllw8.anemo.power.BatteryOptimizationHelper;
import exe.bbllw8.anemo.shell.AnemoShell;

public final class ConfigurationActivity extends Activity {

    private ViewGroup rootView;
    private TextView passwordSetView;
    private TextView changeLockView;
    private TextView batteryOptimizationView;
    private TextView storageQuotaView;
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
        thumbnailsSwitch.setOnCheckedChangeListener(
                (v, isChecked) -> storageSettings.setThumbnailsEnabled(isChecked));

        storageQuotaView = findViewById(R.id.configuration_storage_quota);
        storageQuotaView.setOnClickListener($ -> showQuotaDialog());

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBatteryOptimizationView();
        updateQuotaView();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        lockStore.removeListener(onLockChanged);
        storageSettings.removeListener(onSettingsChanged);
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

    private void showQuotaDialog() {
        final String[] options = {
                getString(R.string.configuration_storage_quota_unlimited),
                getString(R.string.configuration_storage_quota_set_size),
                getString(R.string.configuration_storage_quota_reset_default),
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
                    try {
                        final long mib = Long.parseLong(input.getText().toString().trim());
                        if (mib > 0) {
                            storageSettings.setQuotaMiB(mib);
                        }
                    } catch (NumberFormatException ignored) {
                        // keep current value
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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

