/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.documents.vfs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class Vfs {
    public static final String ROOT_ID = "root";

    private final Map<String, VfsNode> nodes = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong();
    private final AtomicLong totalBytes = new AtomicLong(0L);
    private volatile long maxBytes = -1L;

    public Vfs() {
        final VfsNode root = new VfsNode(ROOT_ID, null, "anemo", true);
        nodes.put(root.id, root);
    }

    public long getTotalBytes() {
        return totalBytes.get();
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long bytes) {
        this.maxBytes = bytes;
    }

    public boolean tryReserve(long delta) {
        if (delta <= 0) {
            return true;
        }
        while (true) {
            final long current = totalBytes.get();
            final long next = current + delta;
            final long limit = maxBytes;
            if (limit >= 0 && next > limit) {
                return false;
            }
            if (totalBytes.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    public void release(long delta) {
        if (delta > 0) {
            totalBytes.addAndGet(-delta);
        }
    }

    public VfsNode getRoot() {
        return nodes.get(ROOT_ID);
    }

    public VfsNode get(String id) {
        return id == null ? null : nodes.get(id);
    }

    public synchronized VfsNode createFile(String parentId, String name, byte[] initialData) {
        return createNode(parentId, name, false, initialData);
    }

    public synchronized VfsNode createDirectory(String parentId, String name) {
        return createNode(parentId, name, true, null);
    }

    public synchronized VfsNode createFileUnique(String parentId, String baseName,
                                                 byte[] initialData) {
        final VfsNode parent = nodes.get(parentId);
        if (parent == null || !parent.isDirectory) {
            return null;
        }
        final String name = uniqueName(parent, baseName);
        return name == null ? null : createNode(parentId, name, false, initialData);
    }

    public synchronized VfsNode createDirectoryUnique(String parentId, String baseName) {
        final VfsNode parent = nodes.get(parentId);
        if (parent == null || !parent.isDirectory) {
            return null;
        }
        final String name = uniqueName(parent, baseName);
        return name == null ? null : createNode(parentId, name, true, null);
    }

    private VfsNode createNode(String parentId, String name, boolean isDirectory, byte[] data) {
        final VfsNode parent = nodes.get(parentId);
        if (parent == null || !parent.isDirectory) {
            return null;
        }
        if (parent.childIds.containsKey(name)) {
            return null;
        }
        final long initialSize = isDirectory ? 0L : (data == null ? 0L : data.length);
        if (initialSize > 0 && !tryReserve(initialSize)) {
            return null;
        }
        final VfsNode node = new VfsNode("doc-" + idCounter.incrementAndGet(), parentId, name,
                isDirectory);
        if (!isDirectory) {
            final long handle = NativeMemory.nativeAlloc();
            if (handle == 0L) {
                if (initialSize > 0) {
                    release(initialSize);
                }
                return null;
            }
            node.nativeHandle = handle;
            if (data != null && data.length > 0) {
                final int written = NativeMemory.nativeWrite(handle, 0, data, 0, data.length);
                if (written < 0) {
                    NativeMemory.nativeFree(handle);
                    node.nativeHandle = 0L;
                    if (initialSize > 0) {
                        release(initialSize);
                    }
                    return null;
                }
                node.size = data.length;
            }
        }
        nodes.put(node.id, node);
        parent.childIds.put(name, node.id);
        parent.lastModified = System.currentTimeMillis();
        return node;
    }

    public synchronized boolean delete(String id) {
        if (ROOT_ID.equals(id)) {
            return false;
        }
        final VfsNode node = nodes.get(id);
        if (node == null) {
            return false;
        }
        final VfsNode parent = nodes.get(node.parentId);
        if (parent != null) {
            parent.childIds.remove(node.name);
            parent.lastModified = System.currentTimeMillis();
        }
        if (node.isDirectory) {
            deleteChildren(node);
        } else {
            if (node.nativeHandle != 0L) {
                NativeMemory.nativeFree(node.nativeHandle);
                node.nativeHandle = 0L;
            }
            release(node.size);
        }
        nodes.remove(id);
        return true;
    }

    private void deleteChildren(VfsNode node) {
        for (final String childId : new ArrayList<>(node.childIds.values())) {
            final VfsNode child = nodes.get(childId);
            if (child != null) {
                if (child.isDirectory) {
                    deleteChildren(child);
                } else {
                    if (child.nativeHandle != 0L) {
                        NativeMemory.nativeFree(child.nativeHandle);
                        child.nativeHandle = 0L;
                    }
                    release(child.size);
                }
                nodes.remove(childId);
            }
        }
    }

    public synchronized boolean rename(String id, String newName) {
        if (ROOT_ID.equals(id)) {
            return false;
        }
        final VfsNode node = nodes.get(id);
        if (node == null) {
            return false;
        }
        if (node.name.equals(newName)) {
            return true;
        }
        final VfsNode parent = nodes.get(node.parentId);
        if (parent == null || parent.childIds.containsKey(newName)) {
            return false;
        }
        parent.childIds.remove(node.name);
        parent.childIds.put(newName, id);
        node.name = newName;
        node.lastModified = System.currentTimeMillis();
        return true;
    }

    public synchronized boolean move(String id, String newParentId) {
        if (ROOT_ID.equals(id) || id.equals(newParentId)) {
            return false;
        }
        final VfsNode node = nodes.get(id);
        final VfsNode newParent = nodes.get(newParentId);
        if (node == null || newParent == null || !newParent.isDirectory) {
            return false;
        }
        if (node.parentId.equals(newParentId)) {
            return true;
        }
        if (node.isDirectory && isAncestor(newParent, node)) {
            return false;
        }
        if (newParent.childIds.containsKey(node.name)) {
            return false;
        }
        final VfsNode oldParent = nodes.get(node.parentId);
        if (oldParent != null) {
            oldParent.childIds.remove(node.name);
            oldParent.lastModified = System.currentTimeMillis();
        }
        newParent.childIds.put(node.name, id);
        newParent.lastModified = System.currentTimeMillis();
        node.parentId = newParentId;
        return true;
    }

    public synchronized VfsNode copy(String id, String newParentId) {
        final VfsNode source = nodes.get(id);
        final VfsNode newParent = nodes.get(newParentId);
        if (source == null || newParent == null || !newParent.isDirectory) {
            return null;
        }
        final String targetName = uniqueName(newParent, source.name);
        if (targetName == null) {
            return null;
        }
        return copyRecursive(source, newParentId, targetName);
    }

    private VfsNode copyRecursive(VfsNode source, String parentId, String name) {
        if (!source.isDirectory) {
            final long size = source.size;
            if (size > 0 && !tryReserve(size)) {
                return null;
            }
            final VfsNode copy = createNode(parentId, name, false, null);
            if (copy == null) {
                if (size > 0) {
                    release(size);
                }
                return null;
            }
            if (size > 0
                    && !NativeMemory.nativeCopy(source.nativeHandle, copy.nativeHandle)) {
                release(size);
                delete(copy.id);
                return null;
            }
            copy.size = size;
            return copy;
        }
        final VfsNode copy = createNode(parentId, name, true, null);
        if (copy == null) {
            return null;
        }
        for (final String childId : source.childIds.values()) {
            final VfsNode child = nodes.get(childId);
            if (child != null) {
                copyRecursive(child, copy.id, child.name);
            }
        }
        return copy;
    }

    public List<VfsNode> listChildren(VfsNode parent) {
        if (parent == null || !parent.isDirectory) {
            return Collections.emptyList();
        }
        synchronized (this) {
            final List<VfsNode> children = new ArrayList<>(parent.childIds.size());
            for (final String childId : parent.childIds.values()) {
                final VfsNode child = nodes.get(childId);
                if (child != null) {
                    children.add(child);
                }
            }
            return children;
        }
    }

    public String uniqueName(VfsNode parent, String name) {
        if (!parent.childIds.containsKey(name)) {
            return name;
        }
        final int dot = name.lastIndexOf('.');
        final String base;
        final String ext;
        if (dot >= 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        } else {
            base = name;
            ext = "";
        }
        for (int n = 1; n <= 32; n++) {
            final String candidate = base + " (" + n + ")" + ext;
            if (!parent.childIds.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public boolean isAncestor(VfsNode candidate, VfsNode ancestor) {
        VfsNode current = candidate;
        while (current != null) {
            if (current.id.equals(ancestor.id)) {
                return true;
            }
            current = current.parentId == null ? null : nodes.get(current.parentId);
        }
        return false;
    }
}
