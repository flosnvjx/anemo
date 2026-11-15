/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.documents.provider;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import exe.bbllw8.anemo.documents.vfs.Vfs;
import exe.bbllw8.anemo.documents.vfs.VfsNode;

public abstract class VfsDocumentProvider extends DocumentsProvider {
    private static final String TAG = "VfsDocumentProvider";
    private static final int MAX_SEARCH_RESULTS = 23;
    private static final String[] DEFAULT_PROJECTION = {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
    };

    private Handler handler;

    @Override
    public boolean onCreate() {
        handler = new Handler(Looper.getMainLooper());
        return true;
    }

    protected final Handler getHandler() {
        return handler;
    }

    protected final StorageManager getStorageManager() {
        return Objects.requireNonNull(getContext()).getSystemService(StorageManager.class);
    }

    protected abstract Vfs getVfs();

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        final VfsNode node = getVfs().get(documentId);
        if (node == null) {
            throw new FileNotFoundException("No document " + documentId);
        }
        final MatrixCursor result = new MatrixCursor(resolveProjection(projection));
        includeNode(result, node);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
                                      String sortOrder) throws FileNotFoundException {
        final Vfs vfs = getVfs();
        final VfsNode parent = vfs.get(parentDocumentId);
        if (parent == null || !parent.isDirectory) {
            throw new FileNotFoundException("Not a directory: " + parentDocumentId);
        }
        final MatrixCursor result = new MatrixCursor(resolveProjection(projection));
        for (final VfsNode child : vfs.listChildren(parent)) {
            includeNode(result, child);
        }
        return result;
    }

    @Override
    public Cursor querySearchDocuments(@NonNull String rootId, String[] projection,
                                       @NonNull Bundle queryArgs) throws FileNotFoundException {
        final Vfs vfs = getVfs();
        final VfsNode root = vfs.get(rootId);
        if (root == null) {
            throw new FileNotFoundException("No root " + rootId);
        }
        final MatrixCursor result = new MatrixCursor(resolveProjection(projection));
        final AtomicInteger remaining = new AtomicInteger(MAX_SEARCH_RESULTS);
        walk(root, result, queryArgs, remaining);
        return result;
    }

    private void walk(VfsNode node, MatrixCursor result, Bundle queryArgs, AtomicInteger remaining) {
        if (remaining.get() <= 0) {
            return;
        }
        if (matches(node, queryArgs)) {
            includeNode(result, node);
            remaining.decrementAndGet();
        }
        if (node.isDirectory) {
            for (final VfsNode child : getVfs().listChildren(node)) {
                walk(child, result, queryArgs, remaining);
            }
        }
    }

    private boolean matches(VfsNode node, Bundle queryArgs) {
        if (queryArgs == null) {
            return true;
        }
        final String displayName = queryArgs.getString(
                DocumentsContract.QUERY_ARG_DISPLAY_NAME, "");
        if (!displayName.isEmpty()) {
            final String needle = displayName.toLowerCase(Locale.ROOT);
            if (!node.name.toLowerCase(Locale.ROOT).contains(needle)) {
                return false;
            }
        }
        final long sizeOver = queryArgs.getLong(DocumentsContract.QUERY_ARG_FILE_SIZE_OVER, -1);
        if (sizeOver != -1 && (node.isDirectory || node.data.length < sizeOver)) {
            return false;
        }
        final long modifiedAfter = queryArgs.getLong(
                DocumentsContract.QUERY_ARG_LAST_MODIFIED_AFTER, -1);
        if (modifiedAfter != -1 && node.lastModified < modifiedAfter) {
            return false;
        }
        final String[] mimeTypes = queryArgs.getStringArray(DocumentsContract.QUERY_ARG_MIME_TYPES);
        if (mimeTypes != null && mimeTypes.length > 0) {
            final String nodeType = PathUtils.getDocumentType(node.name, node.isDirectory);
            for (final String filter : mimeTypes) {
                if (PathUtils.mimeTypeMatches(nodeType, filter)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) {
        final Vfs vfs = getVfs();
        final VfsNode parent = vfs.get(parentDocumentId);
        if (parent == null || !parent.isDirectory) {
            throw new IllegalStateException("Invalid parent " + parentDocumentId);
        }
        final String safeName = PathUtils.buildValidFileName(displayName);
        final String uniqueName = vfs.uniqueName(parent, safeName);
        if (uniqueName == null) {
            throw new IllegalStateException("Could not allocate a unique name");
        }
        final VfsNode created = Document.MIME_TYPE_DIR.equals(mimeType)
                ? vfs.createDirectory(parentDocumentId, uniqueName)
                : vfs.createFile(parentDocumentId, uniqueName, new byte[0]);
        if (created == null) {
            throw new IllegalStateException("Could not create document");
        }
        return created.id;
    }

    @Override
    public void deleteDocument(String documentId) {
        if (!getVfs().delete(documentId)) {
            Log.w(TAG, "Failed to delete " + documentId);
        }
    }

    @Override
    public void removeDocument(String documentId, String parentDocumentId) {
        deleteDocument(documentId);
    }

    @Override
    public String renameDocument(String documentId, String displayName) {
        final String safeName = PathUtils.buildValidFileName(displayName);
        if (!getVfs().rename(documentId, safeName)) {
            throw new IllegalStateException("Could not rename " + documentId);
        }
        return documentId;
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId,
                               String targetParentDocumentId) {
        if (!getVfs().move(sourceDocumentId, targetParentDocumentId)) {
            throw new IllegalStateException("Could not move " + sourceDocumentId);
        }
        return sourceDocumentId;
    }

    @Override
    public String copyDocument(String sourceDocumentId, String targetParentDocumentId) {
        final VfsNode copy = getVfs().copy(sourceDocumentId, targetParentDocumentId);
        if (copy == null) {
            throw new IllegalStateException("Could not copy " + sourceDocumentId);
        }
        return copy.id;
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        final VfsNode node = getVfs().get(documentId);
        if (node == null) {
            throw new FileNotFoundException("No document " + documentId);
        }
        return PathUtils.getDocumentType(node.name, node.isDirectory);
    }

    @Override
    public DocumentsContract.Path findDocumentPath(String parentDocumentId,
                                                   String childDocumentId) {
        final List<String> segments = new ArrayList<>();
        VfsNode current = getVfs().get(childDocumentId);
        while (current != null && !current.id.equals(parentDocumentId)) {
            segments.add(current.id);
            current = current.parentId == null ? null : getVfs().get(current.parentId);
        }
        Collections.reverse(segments);
        return new DocumentsContract.Path(parentDocumentId, segments);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        final VfsNode parent = getVfs().get(parentDocumentId);
        final VfsNode child = getVfs().get(documentId);
        return parent != null && child != null && getVfs().isAncestor(child, parent);
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode,
                                             CancellationSignal signal) throws FileNotFoundException {
        final VfsNode node = getVfs().get(documentId);
        if (node == null || node.isDirectory) {
            throw new FileNotFoundException("No file " + documentId);
        }
        final int pfdMode = ParcelFileDescriptor.parseMode(mode);
        if ((pfdMode & ParcelFileDescriptor.MODE_TRUNCATE) != 0) {
            synchronized (node) {
                node.data = new byte[0];
                node.lastModified = System.currentTimeMillis();
            }
        }
        final ProxyFileDescriptorCallback callback = new InMemoryFileCallback(node);
        try {
            return getStorageManager().openProxyFileDescriptor(pfdMode, callback, getHandler());
        } catch (IOException e) {
            throw new FileNotFoundException("Could not open " + documentId + ": " + e);
        }
    }

    @Override
    public android.content.res.AssetFileDescriptor openDocumentThumbnail(
            String documentId, Point sizeHint, CancellationSignal signal)
            throws FileNotFoundException {
        final VfsNode node = getVfs().get(documentId);
        if (node == null || node.isDirectory) {
            throw new FileNotFoundException("No file " + documentId);
        }
        final ParcelFileDescriptor pfd = openDocument(documentId,
                ParcelFileDescriptor.MODE_READ_ONLY == 0 ? "r" : "r", signal);
        return new android.content.res.AssetFileDescriptor(
                pfd, 0, android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    @Nullable
    public Bundle getDocumentMetadata(@NonNull String documentId) {
        final VfsNode node = getVfs().get(documentId);
        if (node == null) {
            return null;
        }
        final Bundle bundle = new Bundle();
        if (node.isDirectory) {
            final long[] totals = new long[2];
            collectMetadata(node, totals);
            bundle.putLong(DocumentsContract.METADATA_TREE_SIZE, totals[0]);
            bundle.putLong(DocumentsContract.METADATA_TREE_COUNT, totals[1]);
        } else {
            bundle.putLong(DocumentsContract.METADATA_TREE_SIZE, node.data.length);
            bundle.putLong(DocumentsContract.METADATA_TREE_COUNT, 1);
        }
        return bundle;
    }

    private void collectMetadata(VfsNode node, long[] totals) {
        for (final VfsNode child : getVfs().listChildren(node)) {
            if (child.isDirectory) {
                collectMetadata(child, totals);
            } else {
                totals[0] += child.data.length;
                totals[1] += 1;
            }
        }
    }

    protected final MatrixCursor.RowBuilder includeNode(MatrixCursor result, VfsNode node) {
        final String[] columns = result.getColumnNames();
        final MatrixCursor.RowBuilder row = result.newRow();
        final String mimeType = PathUtils.getDocumentType(node.name, node.isDirectory);

        row.add(Document.COLUMN_DOCUMENT_ID, node.id);
        row.add(Document.COLUMN_MIME_TYPE, mimeType);

        if (indexOf(columns, Document.COLUMN_FLAGS) != -1) {
            int flags = 0;
            if (node.isDirectory) {
                flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
            } else {
                flags |= Document.FLAG_SUPPORTS_WRITE;
                if (mimeType.startsWith("image/")) {
                    flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
                }
            }
            if (!Vfs.ROOT_ID.equals(node.id)) {
                flags |= Document.FLAG_SUPPORTS_DELETE;
                flags |= Document.FLAG_SUPPORTS_RENAME;
                flags |= Document.FLAG_SUPPORTS_MOVE;
                if (node.isDirectory) {
                    flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
                }
            }
            row.add(Document.COLUMN_FLAGS, flags);
        }

        if (indexOf(columns, Document.COLUMN_DISPLAY_NAME) != -1) {
            row.add(Document.COLUMN_DISPLAY_NAME, node.name);
        }

        if (indexOf(columns, Document.COLUMN_LAST_MODIFIED) != -1) {
            row.add(Document.COLUMN_LAST_MODIFIED, node.lastModified);
        }

        if (indexOf(columns, Document.COLUMN_SIZE) != -1) {
            row.add(Document.COLUMN_SIZE, node.isDirectory ? 0 : node.data.length);
        }

        return row;
    }

    private static String[] resolveProjection(String[] projection) {
        return projection == null ? DEFAULT_PROJECTION : projection;
    }

    private static int indexOf(String[] array, String target) {
        for (int i = 0; i < array.length; i++) {
            if (Objects.equals(array[i], target)) {
                return i;
            }
        }
        return -1;
    }
}

