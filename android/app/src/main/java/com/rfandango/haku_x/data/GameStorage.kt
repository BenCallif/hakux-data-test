package com.rfandango.haku_x.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.AtomicFile
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

/** One root for the emulated disk, states (including blobs), and shader caches. */
object GameStorage {
  fun defaultRoot(context: Context) = File(context.filesDir, "native")

  // AtomicFile is reread across the library and :native processes; SharedPreferences caches are not.
  private fun config(context: Context) = AtomicFile(File(context.filesDir, "game-storage-path"))

  fun root(context: Context): File {
    val c = config(context)
    if (!c.baseFile.exists() && !File(c.baseFile.path + ".bak").exists()) return defaultRoot(context)
    val path = c.openRead().bufferedReader().use { it.readText() }
    check(path.startsWith('/')) { "Invalid game storage configuration." }
    return File(path)
  }

  fun hdd(context: Context) = File(root(context), "hdd")
  fun states(context: Context) = File(root(context), "states")

  /** Prevent relocation while the core or disk management screen uses the data. */
  fun acquire(context: Context): Closeable {
    val file = RandomAccessFile(File(context.filesDir, "game-storage.lock"), "rw")
    try {
      val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
        ?: throw IOException("Game storage is in use. Close the game or hard disk screen and try again.")
      return Closeable { try { lock.release() } finally { file.close() } }
    } catch (e: Exception) {
      file.close()
      throw e
    }
  }

  @Synchronized
  fun <T> withAccess(context: Context, work: () -> T): T = acquire(context).use { checkReady(context); work() }

  /** Verify actual access: never fall back to a new empty disk if a card or permission disappears. */
  fun checkWritable(dir: File) {
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Storage is unavailable: ${dir.path}")
    val probe = File.createTempFile(".hakux-write-", ".tmp", dir)
    try { probe.outputStream().use { it.write(1); it.fd.sync() } } finally { probe.delete() }
  }

  fun checkReady(context: Context, dir: File = root(context)) {
    if (dir != defaultRoot(context) && !dir.isDirectory) throw IOException("The selected data folder is unavailable: ${dir.path}")
    checkWritable(dir)
  }

  fun relocate(context: Context, destination: File): Boolean = withAccess(context) {
    val src = root(context)
    val dst = destination.canonicalFile
    // io.c's HDD root buffer is 256 bytes; leave room for /hdd and native subpaths.
    require(dst.path.toByteArray(Charsets.UTF_8).size <= 200) { "Choose a shorter folder path (at most 200 bytes)." }
    checkReady(context, src)
    StorageMigration.move(src, dst) {
      val c = config(context)
      val output = c.startWrite()
      try {
        output.write(dst.path.toByteArray(Charsets.UTF_8))
        output.fd.sync()
        c.finishWrite(output)
      } catch (e: Exception) {
        c.failWrite(output)
        throw e
      }
      // AtomicFile may log a failed sync/rename without throwing. Confirm the
      // published value before allowing removal of the source data.
      val saved = c.openRead().use { it.readBytes() }
      if (!saved.contentEquals(dst.path.toByteArray(Charsets.UTF_8))) throw IOException("Could not save the new storage location. Both data copies were retained.")
    }
  }

  /** App-specific device/SD storage does not require broad file access. */
  fun appFolders(context: Context): List<File> = context.getExternalFilesDirs(null).filterNotNull().map { File(it, "native") }

  /** Native POSIX I/O needs a real local folder, not a cloud/document-provider URI. */
  fun pickedFolder(context: Context, uri: Uri): File {
    require(uri.authority == "com.android.externalstorage.documents") { "Choose a folder on device storage or an SD card." }
    val id = DocumentsContract.getTreeDocumentId(uri)
    val volume = id.substringBefore(':')
    val path = id.substringAfter(':', "")
    require(path.split('/').none { it == ".." || it == "." }) { "Invalid folder path." }
    val base = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory() else {
      context.getExternalFilesDirs(null).filterNotNull().map { File(it.path.substringBefore("/Android/data/")) }
        .firstOrNull { it.name.equals(volume, true) } ?: throw IOException("The selected storage volume is unavailable.")
    }
    val selected = File(base, path).canonicalFile
    require(selected.toPath().startsWith(base.canonicalFile.toPath())) { "Invalid folder path." }
    checkWritable(selected)
    // A dedicated child prevents treating the user's other files as emulator data.
    return File(selected, "hakuX")
  }
}
