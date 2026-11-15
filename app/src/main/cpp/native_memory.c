/*
 * Copyright (c) 2025 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
#include <jni.h>
#include <errno.h>
#include <malloc.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* Practical cap so an absurd offset maps to EFBIG instead of overcommit-then-OOM-kill. */
#define MAX_FILE_SIZE ((uint64_t)1 << 40)  /* 1 TiB */

typedef struct {
    uint8_t *ptr;
    size_t size;
    size_t cap;
} NativeBuffer;

static NativeBuffer *buf_from_handle(jlong handle) {
    return (NativeBuffer *)(uintptr_t)handle;
}

static void grow_buffer(NativeBuffer *buf, size_t minCap) {
    size_t newCap = minCap;
    if (buf->cap > 0 && newCap < buf->cap * 2) {
        newCap = buf->cap * 2;
    }
    if (newCap < 4096) {
        newCap = 4096;
    }
    if (newCap < minCap) {
        newCap = minCap;
    }
    uint8_t *newPtr = (uint8_t *)realloc(buf->ptr, newCap);
    if (newPtr == NULL) {
        return;
    }
    buf->ptr = newPtr;
    size_t usable = malloc_usable_size(newPtr);
    buf->cap = usable > newCap ? usable : newCap;
}

JNIEXPORT jlong JNICALL
Java_exe_bbllw8_anemo_documents_vfs_NativeMemory_nativeAlloc(
        JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    NativeBuffer *buf = (NativeBuffer *)calloc(1, sizeof(NativeBuffer));
    if (buf == NULL) {
        return 0;
    }
    return (jlong)(uintptr_t)buf;
}

JNIEXPORT void JNICALL
Java_exe_bbllw8_anemo_documents_vfs_NativeMemory_nativeFree(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    NativeBuffer *buf = buf_from_handle(handle);
    if (buf == NULL) {
        return;
    }
    free(buf->ptr);
    free(buf);
}

JNIEXPORT jlong JNICALL
Java_exe_bbllw8_anemo_documents_vfs_NativeMemory_nativeSize(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    NativeBuffer *buf = buf_from_handle(handle);
    return buf == NULL ? 0 : (jlong)buf->size;
}

JNIEXPORT jint JNICALL
Java_exe_bbllw8_anemo_documents_vfs_NativeMemory_nativeRead(
        JNIEnv *env, jclass clazz, jlong handle, jlong offset,
        jbyteArray dst, jint dstOff, jint len) {
    (void)clazz;
    NativeBuffer *buf = buf_from_handle(handle);
    if (buf == NULL) {
        return -EINVAL;
    }
    if (offset < 0 || len < 0 || dstOff < 0) {
        return -EINVAL;
    }
    size_t off = (size_t)offset;
    if (off >= buf->size) {
        return 0;
    }
    size_t avail = buf->size - off;
    size_t toRead = (size_t)len < avail ? (size_t)len : avail;
    if (toRead == 0) {
        return 0;
    }
    (*env)->SetByteArrayRegion(env, dst, dstOff, (jsize)toRead,
            (const jbyte *)(buf->ptr + off));
    return (jint)toRead;
}

JNIEXPORT jint JNICALL
Java_exe_bbllw8_anemo_documents_vfs_NativeMemory_nativeWrite(
        JNIEnv *env, jclass clazz, jlong handle, jlong offset,
        jbyteArray src, jint srcOff, jint len) {
    (void)clazz;
    NativeBuffer *buf = buf_from_handle(handle);
    if (buf == NULL) {
        return -EINVAL;
    }
    if (offset < 0 || len < 0 || srcOff < 0) {
        return -EINVAL;
    }
    size_t off = (size_t)offset;
    size_t end = off + (size_t)len;
    if (end < off) {
        return -EFBIG;
    }
    if ((uint64_t)end > MAX_FILE_SIZE) {
        return -EFBIG;
    }
    if (end > buf->cap) {
        grow_buffer(buf, end);
        if (end > buf->cap) {
            return -ENOSPC;
        }
    }
    if (off > buf->size) {
        memset(buf->ptr + buf->size, 0, off - buf->size);
    }
    if (len > 0) {
        (*env)->GetByteArrayRegion(env, src, srcOff, len, (jbyte *)(buf->ptr + off));
    }
    if (end > buf->size) {
        buf->size = end;
    }
    return (jint)len;
}

JNIEXPORT jboolean JNICALL
Java_exe_bbllw8_anemo_documents_vfs_NativeMemory_nativeTruncate(
        JNIEnv *env, jclass clazz, jlong handle, jlong newSize) {
    (void)env;
    (void)clazz;
    NativeBuffer *buf = buf_from_handle(handle);
    if (buf == NULL || newSize < 0) {
        return JNI_FALSE;
    }
    size_t ns = (size_t)newSize;
    if ((uint64_t)ns > MAX_FILE_SIZE) {
        return JNI_FALSE;
    }
    if (ns > buf->cap) {
        grow_buffer(buf, ns);
        if (ns > buf->cap) {
            return JNI_FALSE;
        }
    }
    if (ns > buf->size) {
        memset(buf->ptr + buf->size, 0, ns - buf->size);
    }
    buf->size = ns;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_exe_bbllw8_anemo_documents_vfs_NativeMemory_nativeCopy(
        JNIEnv *env, jclass clazz, jlong srcHandle, jlong dstHandle) {
    (void)env;
    (void)clazz;
    if (srcHandle == dstHandle) {
        return JNI_TRUE;
    }
    NativeBuffer *src = buf_from_handle(srcHandle);
    NativeBuffer *dst = buf_from_handle(dstHandle);
    if (src == NULL || dst == NULL) {
        return JNI_FALSE;
    }
    size_t srcSize = src->size;
    if (srcSize > dst->cap) {
        grow_buffer(dst, srcSize);
        if (srcSize > dst->cap) {
            return JNI_FALSE;
        }
    }
    if (srcSize > 0) {
        memcpy(dst->ptr, src->ptr, srcSize);
    }
    dst->size = srcSize;
    return JNI_TRUE;
}
