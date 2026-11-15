/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.documents.vfs;

import java.util.LinkedHashMap;
import java.util.Map;

public final class VfsNode {
    public final String id;
    public final boolean isDirectory;
    public final Map<String, String> childIds;
    public String parentId;
    public String name;
    public byte[] data;
    public long lastModified;

    public VfsNode(String id, String parentId, String name, boolean isDirectory) {
        this.id = id;
        this.parentId = parentId;
        this.name = name;
        this.isDirectory = isDirectory;
        this.childIds = new LinkedHashMap<>();
        this.lastModified = System.currentTimeMillis();
        this.data = isDirectory ? null : new byte[0];
    }
}

