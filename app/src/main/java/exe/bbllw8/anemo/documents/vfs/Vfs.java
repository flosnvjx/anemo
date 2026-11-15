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

    public Vfs() {
        final VfsNode root = new VfsNode(ROOT_ID, null, "anemo", true);
        nodes.put(root.id, root);
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

    private VfsNode createNode(String parentId, String name, boolean isDirectory, byte[] data) {
        final VfsNode parent = nodes.get(parentId);
        if (parent == null || !parent.isDirectory) {
            return null;
        }
        if (parent.childIds.containsKey(name)) {
            return null;
        }
        final VfsNode node = new VfsNode("doc-" + idCounter.incrementAndGet(), parentId, name,
                isDirectory);
        if (!isDirectory) {
            node.data = data == null ? new byte[0] : data;
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
        final VfsNode copy = createNode(parentId, name, source.isDirectory, source.data);
        if (copy != null && source.isDirectory) {
            for (final String childId : source.childIds.values()) {
                final VfsNode child = nodes.get(childId);
                if (child != null) {
                    copyRecursive(child, copy.id, child.name);
                }
            }
        }
        return copy;
    }

    public List<VfsNode> listChildren(VfsNode parent) {
        if (parent == null || !parent.isDirectory) {
            return Collections.emptyList();
        }
        final List<VfsNode> children = new ArrayList<>(parent.childIds.size());
        for (final String childId : parent.childIds.values()) {
            final VfsNode child = nodes.get(childId);
            if (child != null) {
                children.add(child);
            }
        }
        return children;
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

