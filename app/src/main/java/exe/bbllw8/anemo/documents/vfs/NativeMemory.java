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

    public static native int nativeWrite(long handle, long offset, byte[] src, int srcOff,
            int len);

    public static native boolean nativeTruncate(long handle, long newSize);

    public static native boolean nativeCopy(long srcHandle, long dstHandle);
}
