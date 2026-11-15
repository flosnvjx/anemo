/*
 * Copyright (c) 2025 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.documents.vfs;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide byte counters for the in-memory VFS. Only successful native reads and writes
 * are recorded; failed attempts (negative errno) do not contribute.
 */
public final class IoStats {
    private static final AtomicLong READ_BYTES = new AtomicLong(0L);
    private static final AtomicLong WRITE_BYTES = new AtomicLong(0L);

    private IoStats() {
    }

    public static void recordRead(long bytes) {
        if (bytes > 0) {
            READ_BYTES.addAndGet(bytes);
        }
    }

    public static void recordWrite(long bytes) {
        if (bytes > 0) {
            WRITE_BYTES.addAndGet(bytes);
        }
    }

    public static long getReadBytes() {
        return READ_BYTES.get();
    }

    public static long getWriteBytes() {
        return WRITE_BYTES.get();
    }
}

