# Storage-location development APK

[Download hakuX-storage-debug.apk](hakuX-storage-debug.apk?raw=true)

Open **Settings → Storage → Game data location** to choose one folder for game
saves, game data, and savestates. See [storage details](../docs/storage.md) for
current default paths, supported folders, permissions, and migration behavior.

This is an arm64 Android debug build of this fork, package
`com.rfandango.haku_x.debug`, version 1.2.0 (versionCode 8). It installs separately
from the official release. Use the original app's hard disk backup, including
savestates, and restore it in this build to transfer existing private data.

SHA-256:

```text
310f2ab8952f2384a69a5e94c56a1464ad520ab6072002a882294c93892df3cf
```

The [checksum file](hakuX-storage-debug.apk.sha256) can also be checked with
`sha256sum -c hakuX-storage-debug.apk.sha256` from this directory.

Validation: the arm64 native core and debug APK built; all 15 host HLE tests and
22 JVM tests passed; APK integrity, bundled native libraries, and APK signature
were verified. Android lint reports 58 errors on unchanged source lines. Device
permission/picker behavior, physical SD cards, and loading relocated savestates
in a running game still require Android device testing.

Build prerequisites and native dependencies are documented in
[android/README.md](../android/README.md). This APK uses
[`nv2a_vsh_cpu` commit 89315e8eff62605d2a96fe14429ebeac1dacaa8c](https://github.com/abaire/nv2a_vsh_cpu/tree/89315e8eff62605d2a96fe14429ebeac1dacaa8c)
because the earlier documented `561fe80` revision was unavailable in the inspected
upstream histories. Other third-party sources use the documented revisions.
The native savestate format remains unchanged.
