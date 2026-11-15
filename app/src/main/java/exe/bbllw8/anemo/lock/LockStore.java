/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.lock;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class LockStore implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "LockStore";

    private static final String LOCK_PREFERENCES = "lock_store";
    private static final String KEY_LOCK = "is_locked";
    private static final String KEY_PASSWORD = "password_hash";
    private static final String KEY_BIOMETRIC_UNLOCK = "biometric_unlock";
    private static final boolean DEFAULT_LOCK_VALUE = false;

    private static final String HASH_ALGORITHM = "SHA-256";

    private final SharedPreferences preferences;
    private final List<Consumer<Boolean>> listeners = new ArrayList<>();
    private final BiometricManager biometricManager;

    private static volatile LockStore instance;

    public static LockStore getInstance(Context context) {
        if (instance == null) {
            synchronized (LockStore.class) {
                if (instance == null) {
                    instance = new LockStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private LockStore(Context context) {
        preferences = context.getSharedPreferences(LOCK_PREFERENCES, Context.MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(this);
        biometricManager = Build.VERSION.SDK_INT >= 29
                ? context.getSystemService(BiometricManager.class)
                : null;
    }

    @Override
    public void onSharedPreferenceChanged(@NonNull SharedPreferences sharedPreferences,
                                          @Nullable String key) {
        if (KEY_LOCK.equals(key)) {
            onLockChanged();
        }
    }

    public synchronized boolean isLocked() {
        return preferences.getBoolean(KEY_LOCK, DEFAULT_LOCK_VALUE);
    }

    public synchronized void lock() {
        preferences.edit().putBoolean(KEY_LOCK, true).apply();
    }

    public synchronized void unlock() {
        preferences.edit().putBoolean(KEY_LOCK, false).apply();
    }

    public synchronized boolean setPassword(String password) {
        return hashString(password).map(hashedPwd -> {
            preferences.edit().putString(KEY_PASSWORD, hashedPwd).apply();
            return hashedPwd;
        }).isPresent();
    }

    public synchronized boolean passwordMatch(String password) {
        return hashString(password)
                .map(hashedPwd -> hashedPwd.equals(preferences.getString(KEY_PASSWORD, null)))
                .orElse(false);
    }

    public synchronized boolean hasPassword() {
        return preferences.getString(KEY_PASSWORD, null) != null;
    }

    public synchronized void removePassword() {
        preferences.edit().remove(KEY_PASSWORD).apply();
    }

    public boolean canAuthenticateBiometric() {
        return Build.VERSION.SDK_INT >= 29 && biometricManager != null
                && biometricManager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public synchronized boolean isBiometricUnlockEnabled() {
        return canAuthenticateBiometric() && preferences.getBoolean(KEY_BIOMETRIC_UNLOCK, false);
    }

    public synchronized void setBiometricUnlockEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_BIOMETRIC_UNLOCK, enabled).apply();
    }

    public void addListener(Consumer<Boolean> listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<Boolean> listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void onLockChanged() {
        final boolean newValue = preferences.getBoolean(KEY_LOCK, DEFAULT_LOCK_VALUE);
        listeners.forEach(listener -> listener.accept(newValue));
    }

    private Optional<String> hashString(String string) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            digest.update(string.getBytes());
            return Optional.of(new String(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Couldn't get hash", e);
            return Optional.empty();
        }
    }
}

