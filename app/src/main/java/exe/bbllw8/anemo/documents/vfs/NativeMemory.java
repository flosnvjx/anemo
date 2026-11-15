/*
 * Copyright (c) 2025 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.documents.vfs;

public final class NativeMemory {
    static {
        System.loadLibrary("anemo_native");
    }

    private NativeMemory() {
    }

    public static native long nativeAlloc();

    public static native void nativeFree(long handle);

    public static native long nativeSize(long handle);

    public static native int nativeRead(long handle, long offset, byte[] dst, int dstOff,
            int len);

    /**
     * Writes {@code len} bytes from {@code src[srcOff..]} to the native buffer at {@code offset}.
     *
     * @return the number of bytes written on success, or {@code -(errno)} on failure. The
     *     possible errno values are {@link android.system.OsConstants#EINVAL} for a bad argument,
     *     {@link android.system.OsConstants#EFBIG} for an overflowing or oversized end offset,
     *     and {@link android.system.OsConstants#ENOSPC} for allocation failure.
     */
    public static native int nativeWrite(long handle, long offset, byte[] src, int srcOff,
            int len);

    public static native boolean nativeTruncate(long handle, long newSize);

    public static native boolean nativeCopy(long srcHandle, long dstHandle);
}
