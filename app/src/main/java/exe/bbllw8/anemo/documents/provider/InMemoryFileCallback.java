/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.documents.provider;

import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;
import android.system.OsConstants;

import exe.bbllw8.anemo.documents.vfs.NativeMemory;
import exe.bbllw8.anemo.documents.vfs.Vfs;
import exe.bbllw8.anemo.documents.vfs.VfsNode;

public final class InMemoryFileCallback extends ProxyFileDescriptorCallback {
    private final Vfs vfs;
    private final VfsNode node;

    public InMemoryFileCallback(Vfs vfs, VfsNode node) {
        this.vfs = vfs;
        this.node = node;
    }

    @Override
    public long onGetSize() {
        synchronized (node) {
            return node.size;
        }
    }

    @Override
    public int onRead(long offset, int size, byte[] data) {
        synchronized (node) {
            final int read = NativeMemory.nativeRead(node.nativeHandle, offset, data, 0, size);
            return read < 0 ? 0 : read;
        }
    }

    @Override
    public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
        synchronized (node) {
            final long newEnd = offset + size;
            final long grow = newEnd > node.size ? newEnd - node.size : 0L;
            if (grow > 0 && !vfs.tryReserve(grow)) {
                throw new ErrnoException("onWrite", OsConstants.ENOSPC);
            }
            final int written = NativeMemory.nativeWrite(node.nativeHandle, offset, data, 0, size);
            if (written < 0) {
                if (grow > 0) {
                    vfs.release(grow);
                }
                throw new ErrnoException("onWrite", mapErrno(-written));
            }
            if (newEnd > node.size) {
                node.size = newEnd;
            }
            node.lastModified = System.currentTimeMillis();
            return written;
        }
    }

    private static int mapErrno(int errno) {
        if (errno == OsConstants.EINVAL) {
            return OsConstants.EINVAL;
        }
        if (errno == OsConstants.EFBIG) {
            return OsConstants.EFBIG;
        }
        return OsConstants.ENOSPC;
    }

    @Override
    public void onFsync() {
        // no-op: data lives in native memory
    }

    @Override
    public void onRelease() {
        // no-op: VfsNode owns the native buffer
    }
}
