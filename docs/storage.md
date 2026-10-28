# Game storage

Open **Settings → Storage → Game data location** to choose one location for game
saves, game data, and savestates. The screen displays the actual paths in use.

The original/default root is Android's private app folder:

```
/data/user/0/com.rfandango.haku_x/files/native
```

For the debug APK, the package is `com.rfandango.haku_x.debug`. Android user and
work profiles can have a different user number. `/data/data/<package>` is an alias
on typical devices. These locations are confirmed from the code; no physical
device's files were inspected.

| Content | Path below the selected root |
| --- | --- |
| In-game saves (Xbox E:\UDATA / U:) | `hdd/Partition1/UDATA/<title ID>/…` |
| Game data, settings, DLC (E:\TDATA / T:) | `hdd/Partition1/TDATA/<title ID>/…` |
| Savestates | `states/<title ID.version>/slot1.hxs` through `slot10.hxs` |
| State thumbnails and cache blobs | The same game's `states/` folder |
| Xbox cache drives X:, Y:, Z: | `hdd/Partition3`, `Partition4`, `Partition5` |
| Console identity | `hdd/eeprom.bin` |
| Shader/pipeline caches | `hle/` |

Some game keys are hashed instead of an ID/version. The EEPROM moves with the
disk so signed saves keep the same console identity.

Available locations:

- **Private app storage**, the original default.
- **Device app storage**, typically `/storage/emulated/0/Android/data/<package>/files/native`.
- **SD card app storage**, when Android reports a mounted removable volume.
- **A custom device/SD folder**. Pick a parent directory; the app uses a dedicated
  `hakuX` child. The native filesystem needs **All files access** on Android 11+,
  or storage permission on Android 8–10. Cloud/document-provider folders are not
  supported. No broad permission is needed for the app-storage choices.

App-specific storage is deleted on uninstall. A custom shared folder is outside
that app-specific directory. App preferences, controller layouts, library
metadata, logs, imported console dumps and installed GPU drivers remain in their
existing app locations.

Changing the location copies the complete root into staging on the destination
volume, verifies every file with SHA-256, then publishes the new root and saves
its path atomically. Only after activation does it remove the source. Existing
destination data is never merged or overwritten. If copying fails, the original
remains active. If saving the new location fails after publication, both copies
are retained because a settings write can fail after committing. If cleanup
fails, the new root stays active and the
app reports that some old files remain. Keep the app open and storage connected
until the operation finishes. An interrupted move can leave an unused staging
or destination copy; the original is retained until the new path is saved.

The core and disk-management jobs share a file lock with migration. A running
game must close before relocation. If the selected card or its permission is
unavailable, the core reports the problem instead of choosing a different empty
disk. Backups, restores, save imports/exports, hard disk management and both
library/in-game savestate menus use the selected root. Savestate loading resolves
saved game-file handles under the current HDD root rather than the previous
absolute path; the savestate format is unchanged.

The development APK is debug-signed and installs separately from the official
release. To bring data across, use the original app's hard disk backup (include
savestates), then restore it in the debug app before relocating. It cannot read
another package's private files directly. Keep a backup before uninstalling an
existing app.

Validation for this change: the arm64 native core built successfully; all 15
host HLE tests and all 22 JVM tests passed (including eight storage migration
regressions). Android lint reported 58 errors on unchanged source lines,
primarily in the bundled SDL Bluetooth code; the findings were not suppressed.
The cloud environment has no Android device, so the permission/picker flow,
physical SD card behavior and loading a relocated savestate in a running game
still require device testing.
