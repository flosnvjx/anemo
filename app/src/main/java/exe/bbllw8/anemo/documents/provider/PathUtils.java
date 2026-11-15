/*
 * Copyright (c) 2024 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package exe.bbllw8.anemo.documents.provider;

import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import java.util.function.Predicate;

public final class PathUtils {
    private static final String MIME_TYPE_DEFAULT = "application/octet-stream";

    private PathUtils() {
    }

    public static String buildValidFileName(String name) {
        if (TextUtils.isEmpty(name) || ".".equals(name)) {
            return "(invalid)";
        }
        final StringBuilder res = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            res.append(isValidFatFilenameChar(c) ? c : '_');
        }
        return res.toString();
    }

    public static String buildUniqueName(String name, Predicate<String> exists) {
        if (!exists.test(name)) {
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
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static String getDocumentType(String name, boolean isDirectory) {
        if (isDirectory) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        }
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String ext = name.substring(lastDot + 1).toLowerCase();
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) {
                return mime;
            }
        }
        return MIME_TYPE_DEFAULT;
    }

    public static boolean mimeTypeMatches(String mimeType, String filter) {
        if (mimeType == null) {
            return false;
        }
        final String[] mimeTypeParts = mimeType.split("/");
        final String[] filterParts = filter.split("/");
        if (filterParts.length != 2) {
            throw new IllegalArgumentException(
                    "Ill-formatted MIME type filter. Must be type/subtype");
        }
        if (filterParts[0].isEmpty() || filterParts[1].isEmpty()) {
            throw new IllegalArgumentException(
                    "Ill-formatted MIME type filter. Type or subtype empty");
        }
        if (mimeTypeParts.length != 2) {
            return false;
        }
        if (!"*".equals(filterParts[0]) && !filterParts[0].equals(mimeTypeParts[0])) {
            return false;
        }
        return "*".equals(filterParts[1]) || filterParts[1].equals(mimeTypeParts[1]);
    }

    private static boolean isValidFatFilenameChar(int c) {
        if (0x00 <= c && c <= 0x1f) {
            return false;
        }
        return switch (c) {
            case '"',
                 '*',
                 '/',
                 ':',
                 '<',
                 '>',
                 '?',
                 '\\',
                 '|',
                 0x7F -> false;
            default -> true;
        };
    }
}

