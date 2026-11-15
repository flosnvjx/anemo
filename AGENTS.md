# AGENTS.md

Anemo is an Android app that provides private local storage through the DocumentsProvider API.

## Project layout

app/src/main/java/exe/bbllw8/anemo/ contains the application code.

documents/vfs/ holds the in-memory virtual filesystem. All file contents live in process memory and are never written to disk.

documents/provider/ holds the DocumentsProvider implementation and the StorageManager proxy callback.

documents/home/ holds shared document authority and root id constants.

documents/settings/ holds storage settings such as the quota and the thumbnail toggle.

lock/ holds password and biometric lock state.

config/ holds configuration UI and password dialogs.

power/ holds battery optimization and foreground service helpers.

shell/ holds the launcher entry point and shortcut management.

task/ holds the background task executor used by the import receiver.

resources live under app/src/main/res.

## Design constraints

File contents live entirely in memory. Use StorageManager.openProxyFileDescriptor plus the in-memory VFS. Do not add memfd, tmpfs, cacheDir, or any disk fallback.

The application uses android:largeHeap="true". The storage quota defaults to half of Runtime.getRuntime().maxMemory(). For files much larger than the heap, native memory (JNI plus direct ByteBuffer) would be required; do not attempt to fake this with disk.

The DocumentsProvider must not be ejectable or unmountable. Do not set Root.FLAG_SUPPORTS_EJECT and do not implement ejectRoot.

The DocumentsProvider must not be exposed through a Quick Settings tile or an auto-lock job service.

First-run battery optimization and notification permission prompts are shown from UnlockActivity, which LauncherActivity routes through when needed. If the system prompt cannot be shown, the user can reach the same settings from ConfigurationActivity.

Prefer framework APIs over third-party libraries. Do not introduce new dependencies.

Do not modify existing translation strings. Add new strings under new names.

## Code conventions

Java 21. Four-space indentation. 100-column lines.

Package names under exe.bbllw8.anemo.

English identifiers, comments, and resource strings.

In-memory VFS mutations must be synchronized on the Vfs instance.

Proxy callback reads, writes, and truncates must be synchronized on the target node.

