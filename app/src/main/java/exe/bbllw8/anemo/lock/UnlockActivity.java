/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.lock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.concurrent.Executor;

import exe.bbllw8.anemo.R;
import exe.bbllw8.anemo.config.ConfigurationActivity;
import exe.bbllw8.anemo.config.password.TextListener;
import exe.bbllw8.anemo.power.BatteryOptimizationHelper;
import exe.bbllw8.anemo.power.KeepAliveService;
import exe.bbllw8.anemo.shell.LauncherActivity;

public final class UnlockActivity extends Activity {
    public static final String OPEN_AFTER_UNLOCK = "open_after_unlock";
    private static final int MIN_PASSWORD_LENGTH = 4;
    private static final int NOTIFICATION_PERMISSION_REQ = 42;

    private LockStore lockStore;
    private Runnable onUnlocked;
    private boolean promptedBatteryOptimization;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        lockStore = LockStore.getInstance(this);
        onUnlocked = getOnUnlocked(getIntent());
        maybePromptForBatteryOptimization();
        if (lockStore.hasPassword()) {
            if (lockStore.isBiometricUnlockEnabled()) {
                unlockViaBiometricAuthentication();
            } else {
                setupUI();
            }
        } else {
            doUnlock();
        }
    }

    private void setupUI() {
        setContentView(R.layout.password_input);
        setFinishOnTouchOutside(true);

        final EditText passwordField = findViewById(R.id.passwordFieldView);
        final ImageView configBtn = findViewById(R.id.configurationButton);
        final Button unlockBtn = findViewById(R.id.unlockButton);
        final Button cancelBtn = findViewById(R.id.cancelButton);

        passwordField.addTextChangedListener((TextListener) text -> unlockBtn
                .setEnabled(passwordField.getText().length() >= MIN_PASSWORD_LENGTH));

        configBtn.setOnClickListener(v -> {
            startActivity(new Intent(this, ConfigurationActivity.class));
            setResult(Activity.RESULT_CANCELED);
            finish();
        });

        unlockBtn.setEnabled(false);
        unlockBtn.setOnClickListener(v -> {
            final String value = passwordField.getText().toString();
            if (lockStore.passwordMatch(value)) {
                doUnlock();
            } else {
                passwordField.setError(getString(R.string.password_error_wrong));
            }
        });

        cancelBtn.setOnClickListener(v -> {
            setResult(Activity.RESULT_CANCELED);
            finish();
        });
    }

    private void doUnlock() {
        lockStore.unlock();
        maybeRequestNotificationPermission();
        onUnlocked.run();
    }

    private void maybePromptForBatteryOptimization() {
        if (promptedBatteryOptimization) {
            return;
        }
        promptedBatteryOptimization = true;
        if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            return;
        }
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(R.string.battery_optimization_title)
                .setMessage(R.string.battery_optimization_message)
                .setPositiveButton(R.string.battery_optimization_enable,
                        (d, which) -> BatteryOptimizationHelper
                                .requestIgnoreBatteryOptimizations(this))
                .setNegativeButton(R.string.battery_optimization_later, null)
                .show();
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            KeepAliveService.start(this);
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            KeepAliveService.start(this);
        } else {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQ);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == NOTIFICATION_PERMISSION_REQ && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            KeepAliveService.start(this);
        }
    }

    @RequiresApi(29)
    private void unlockViaBiometricAuthentication() {
        final Executor executor = getMainExecutor();

        final BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.tile_unlock))
                .setDescription(getString(R.string.password_input_biometric_message))
                .setNegativeButton(getString(R.string.password_input_biometric_fallback), executor,
                        (dialog, which) -> setupUI())
                .build();

        if (Build.VERSION.SDK_INT > 33) {
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    promptBiometricAuthentication(prompt, executor), 50);
        } else {
            promptBiometricAuthentication(prompt, executor);
        }
    }

    @RequiresApi(29)
    public void promptBiometricAuthentication(BiometricPrompt prompt, Executor executor) {
        final CancellationSignal cancellationSignal = new CancellationSignal();
        cancellationSignal.setOnCancelListener(this::finish);
        prompt.authenticate(cancellationSignal, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        doUnlock();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        setResult(Activity.RESULT_CANCELED);
                        finish();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        setResult(Activity.RESULT_CANCELED);
                        finish();
                    }
                });
    }

    private Runnable getOnUnlocked(Intent intent) {
        if (intent.getBooleanExtra(OPEN_AFTER_UNLOCK, false)) {
            return () -> {
                startActivity(new Intent(this, LauncherActivity.class));
                setResult(Activity.RESULT_OK);
                finish();
            };
        } else {
            return () -> {
                setResult(Activity.RESULT_OK);
                finish();
            };
        }
    }
}

