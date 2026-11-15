# AGENTS.md

Anemo is an Android app that provides private local storage through the DocumentsProvider API.

## Project layout

app/src/main/java/exe/bbllw8/anemo/ contains the application code.

documents/vfs/ holds the in-memory virtual filesystem. All file contents live in native process memory, managed by the JNI bridge in app/src/main/cpp, and are never written to disk.

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

File contents live entirely in memory. Use StorageManager.openProxyFileDescriptor plus the in-memory VFS. The VFS stores file bytes in native (malloc'd) memory through the JNI bridge in app/src/main/cpp; do not add memfd, tmpfs, cacheDir, or any disk fallback.

The application uses android:largeHeap="true". The storage quota defaults, at StorageSettings initialization, to half of the system's available memory as reported by ActivityManager.MemoryInfo.availMem, falling back to Runtime.getRuntime().maxMemory() when that value is unavailable. Because file contents are held in native memory and not on the Java heap, a single file may exceed the heap limit; do not attempt to fake this with disk. The quota settings UI must reject a numeric quota above 95 percent of currently available system memory; the Unlimited option is exempt so a user who chooses it accepts the risk of the process being killed under memory pressure.

The DocumentsProvider must not be ejectable or unmountable. Do not set Root.FLAG_SUPPORTS_EJECT and do not implement ejectRoot.

The DocumentsProvider must not be exposed through a Quick Settings tile or an auto-lock job service.

First-run battery optimization and notification permission prompts are shown from UnlockActivity, which LauncherActivity routes through when needed. ConfigurationActivity also prompts for the notification permission when it is opened and the permission has never been requested; it does not prompt for battery optimization because that setting is exposed in the same screen. After the permission is granted, and on every subsequent launch when the permission is already present, ConfigurationActivity calls KeepAliveService.start so the persistent notification is displayed again if the service had been stopped. If the system prompt cannot be shown, the user can reach the same settings from the system application settings screen.

The keep-alive foreground service must declare both dataSync and specialUse foregroundServiceType in the manifest. The type passed to startForeground is selected by SDK version: specialUse on API 34+, dataSync on API 29 to 33, no type before that. startForeground failures must be logged and must stop the service; do not swallow them silently.

The keep-alive service is only started when POST_NOTIFICATIONS is granted on API 33+. This check is enforced inside KeepAliveService.start(Context), which is a no-op when the permission is missing; callers do not need to check the permission themselves and should call start unconditionally. Without a visible notification the service would be killed or would display a misleading system notification.

The keep-alive notification exposes two actions: Nuke and Nuke & Exit. Both clear every file from the VFS and refresh the DocumentsUI roots; Nuke & Exit additionally stops the service. The actions must be standard Notification.Action entries so they are revealed only when the notification is expanded; do not replace them with direct content intents, custom RemoteViews, or notification styles that pin actions on the collapsed view. Action icons must be monochrome framework drawables (android.R.drawable); do not introduce new icon resources for them.

The Configuration screen shows a Storage usage entry whose value is the used bytes in human-readable form. Tapping it opens a dialog reporting the file count, the directory count, and the used bytes as raw bytes followed by the human-readable form. The dialog exposes exactly two actions: a Close button that dismisses it, and a Nuke button that performs the same operation as the notification Nuke action. The nuke operation is implemented once as AnemoDocumentProvider.nuke(Context) and reused by both the notification and the Configuration dialog; do not duplicate the deletion or notifyChange logic.

The notification small icon must be a monochrome drawable. Do not use the adaptive launcher icon.

Thumbnail support is opt-in and defaults to off. When enabled, thumbnail data is emitted only for files whose extension appears in the configured comma-separated allowlist (an empty allowlist means any extension that MimeTypeMap maps to image/*) and whose size does not exceed the configured maximum (32 MiB by default). Thumbnail data is the file bytes as-is; do not decode, rescale, or transcode images.

Prefer framework APIs over third-party libraries. Do not introduce new dependencies.

Do not modify existing translation strings. Add new strings under new names.

## Code conventions

Java 21. Four-space indentation. 100-column lines.

Package names under exe.bbllw8.anemo.

English identifiers, comments, and resource strings.

In-memory VFS mutations must be synchronized on the Vfs instance.

Proxy callback reads, writes, and truncates must be synchronized on the target node.

Deletion of a file node must acquire the node monitor before freeing the native buffer, so an in-flight callback cannot use a freed handle. The same applies to deleteChildren. Lock ordering is always Vfs monitor first, then node monitor; never the other way.

Native buffers are owned by VfsNode instances; every nativeAlloc must be paired with a nativeFree on delete or shutdown.

