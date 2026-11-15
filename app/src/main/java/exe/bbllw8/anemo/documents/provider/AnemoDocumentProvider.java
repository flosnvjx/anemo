/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.documents.provider;

import android.app.AuthenticationRequiredException;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Root;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.FileNotFoundException;
import java.util.function.Consumer;

import exe.bbllw8.anemo.R;
import exe.bbllw8.anemo.documents.home.HomeEnvironment;
import exe.bbllw8.anemo.documents.settings.StorageSettings;
import exe.bbllw8.anemo.documents.vfs.Vfs;
import exe.bbllw8.anemo.lock.LockStore;
import exe.bbllw8.anemo.lock.UnlockActivity;

public final class AnemoDocumentProvider extends VfsDocumentProvider {
    private static final String TAG = "AnemoDocumentProvider";

    private static final String[] DEFAULT_ROOT_PROJECTION = {
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_CAPACITY_BYTES,
    };

    private static volatile Vfs sVfs;

    private final Vfs vfs = new Vfs();
    private LockStore lockStore;
    private StorageSettings storageSettings;

    private boolean showInfo = true;

    public static Vfs getVfsInstance() {
        return sVfs;
    }

    @Override
    public boolean onCreate() {
        if (!super.onCreate()) {
            return false;
        }
        final Context context = getContext();
        if (context == null) {
            Log.e(TAG, "Missing context");
            return false;
        }
        lockStore = LockStore.getInstance(context);
        lockStore.addListener(onLockChanged);
        storageSettings = StorageSettings.getInstance(context);
        storageSettings.addListener(onSettingsChanged);
        vfs.setMaxBytes(storageSettings.getQuotaBytes());
        sVfs = vfs;
        return true;
    }

    @Override
    public void shutdown() {
        lockStore.removeListener(onLockChanged);
        storageSettings.removeListener(onSettingsChanged);
        sVfs = null;
        super.shutdown();
    }

    @Override
    protected Vfs getVfs() {
        return vfs;
    }

    @Override
    protected StorageSettings getSettings() {
        return storageSettings;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        if (lockStore.isLocked()) {
            return new EmptyCursor();
        }

        final Context context = getContext();
        final String[] columns = projection == null ? DEFAULT_ROOT_PROJECTION : projection;
        final MatrixCursor result = new MatrixCursor(columns);
        final MatrixCursor.RowBuilder row = result.newRow();

        int flags = Root.FLAG_LOCAL_ONLY;
        flags |= Root.FLAG_SUPPORTS_CREATE;
        flags |= Root.FLAG_SUPPORTS_IS_CHILD;
        if (Build.VERSION.SDK_INT >= 29) {
            flags |= Root.FLAG_SUPPORTS_SEARCH;
        }

        row.add(Root.COLUMN_ROOT_ID, HomeEnvironment.ROOT_DOC_ID)
                .add(Root.COLUMN_DOCUMENT_ID, HomeEnvironment.ROOT_DOC_ID)
                .add(Root.COLUMN_FLAGS, flags)
                .add(Root.COLUMN_ICON, R.drawable.ic_storage);
        if (context != null) {
            row.add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
                    .add(Root.COLUMN_SUMMARY, context.getString(R.string.anemo_description));
        }

        final long quota = storageSettings.getQuotaBytes();
        if (quota >= 0) {
            final long used = vfs.getTotalBytes();
            final long available = Math.max(0L, quota - used);
            if (indexOf(columns, Root.COLUMN_AVAILABLE_BYTES) != -1) {
                row.add(Root.COLUMN_AVAILABLE_BYTES, available);
            }
            if (indexOf(columns, Root.COLUMN_CAPACITY_BYTES) != -1) {
                row.add(Root.COLUMN_CAPACITY_BYTES, quota);
            }
        }
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
                                      String sortOrder) throws FileNotFoundException {
        if (lockStore.isLocked()) {
            return new EmptyCursor();
        }
        final Cursor c = super.queryChildDocuments(parentDocumentId, projection, sortOrder);
        if (showInfo && HomeEnvironment.ROOT_DOC_ID.equals(parentDocumentId)) {
            showInfo = false;
            final Bundle extras = new Bundle();
            final Context context = getContext();
            if (context != null) {
                extras.putCharSequence(DocumentsContract.EXTRA_INFO,
                        context.getText(R.string.anemo_info));
            }
            c.setExtras(extras);
        }
        return c;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        return lockStore.isLocked()
                ? new EmptyCursor()
                : super.queryDocument(documentId, projection);
    }

    @Override
    public Cursor querySearchDocuments(@NonNull String rootId, String[] projection,
                                       @NonNull Bundle queryArgs) throws FileNotFoundException {
        final String properRootId = HomeEnvironment.ROOT_DOC_ID.equals(rootId)
                ? HomeEnvironment.ROOT_DOC_ID
                : rootId;
        return lockStore.isLocked()
                ? new EmptyCursor()
                : super.querySearchDocuments(properRootId, projection, queryArgs);
    }

    @Override
    public DocumentsContract.Path findDocumentPath(String parentDocumentId,
                                                   String childDocumentId) {
        if (lockStore.isLocked()) {
            return new DocumentsContract.Path(null, java.util.Collections.emptyList());
        }
        return super.findDocumentPath(parentDocumentId, childDocumentId);
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode,
                                             CancellationSignal signal)
            throws FileNotFoundException {
        assertUnlocked();
        return super.openDocument(documentId, mode, signal);
    }

    @Override
    public android.content.res.AssetFileDescriptor openDocumentThumbnail(
            String docId, android.graphics.Point sizeHint, CancellationSignal signal)
            throws FileNotFoundException {
        assertUnlocked();
        return super.openDocumentThumbnail(docId, sizeHint, signal);
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) {
        assertUnlocked();
        return super.createDocument(parentDocumentId, mimeType, displayName);
    }

    @Override
    public void deleteDocument(String documentId) {
        assertUnlocked();
        super.deleteDocument(documentId);
    }

    @Override
    public void removeDocument(String documentId, String parentDocumentId) {
        deleteDocument(documentId);
    }

    @Override
    public String copyDocument(String sourceDocumentId, String targetParentDocumentId) {
        assertUnlocked();
        return super.copyDocument(sourceDocumentId, targetParentDocumentId);
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId,
                               String targetParentDocumentId) {
        assertUnlocked();
        return super.moveDocument(sourceDocumentId, sourceParentDocumentId,
                targetParentDocumentId);
    }

    @Override
    public String renameDocument(String documentId, String displayName) {
        assertUnlocked();
        return super.renameDocument(documentId, displayName);
    }

    private void assertUnlocked() {
        if (lockStore.isLocked()) {
            final Context context = getContext();
            final Intent intent = new Intent(context, UnlockActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            throw new AuthenticationRequiredException(new Throwable("Locked storage"),
                    PendingIntent.getActivity(context, 0, intent,
                            PendingIntent.FLAG_IMMUTABLE));
        }
    }

    private final Consumer<Boolean> onLockChanged = isLocked -> notifyRootsChanged();

    private final Runnable onSettingsChanged = () -> {
        vfs.setMaxBytes(storageSettings.getQuotaBytes());
        notifyRootsChanged();
    };

    private void notifyRootsChanged() {
        final Context context = getContext();
        if (context != null) {
            context.getContentResolver().notifyChange(
                    DocumentsContract.buildRootsUri(HomeEnvironment.AUTHORITY), null);
        }
    }
}
