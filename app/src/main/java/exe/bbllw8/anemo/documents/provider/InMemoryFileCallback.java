/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.documents.provider;

import android.os.ProxyFileDescriptorCallback;

import java.util.Arrays;

import exe.bbllw8.anemo.documents.vfs.VfsNode;

public final class InMemoryFileCallback extends ProxyFileDescriptorCallback {
    private final VfsNode node;

    public InMemoryFileCallback(VfsNode node) {
        this.node = node;
    }

    @Override
    public long onGetSize() {
        synchronized (node) {
            return node.data.length;
        }
    }

    @Override
    public int onRead(long offset, int size, byte[] data) {
        synchronized (node) {
            final int len = node.data.length;
            if (offset >= len) {
                return 0;
            }
            final int toRead = (int) Math.min(size, len - offset);
            System.arraycopy(node.data, (int) offset, data, 0, toRead);
            return toRead;
        }
    }

    @Override
    public int onWrite(long offset, int size, byte[] data) {
        synchronized (node) {
            final int end = (int) offset + size;
            if (end > node.data.length) {
                node.data = Arrays.copyOf(node.data, end);
            }
            System.arraycopy(data, 0, node.data, (int) offset, size);
            node.lastModified = System.currentTimeMillis();
            return size;
        }
    }

    @Override
    public void onFsync() {
        // no-op: data lives in memory
    }

    @Override
    public void onRelease() {
        // no-op: VfsNode is owned by the in-memory Vfs
    }
}

