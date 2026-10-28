package com.rfandango.haku_x.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Copies into staging on the destination volume before publishing a new storage root. */
object StorageMigration {
  /** The source is retained until activation succeeds. Returns whether cleanup completed. */
  fun move(source: File, destination: File, activate: () -> Unit): Boolean {
    val src = source.canonicalFile
    val dst = destination.canonicalFile
    if (src == dst) return true
    if (!src.isDirectory) throw IOException("The current data folder is unavailable.")
    require(!src.toPath().startsWith(dst.toPath()) && !dst.toPath().startsWith(src.toPath())) {
      "Choose a folder outside the current data folder."
    }
    if (dst.exists() && (!dst.isDirectory || dst.list()?.isEmpty() != true)) {
      throw IOException("The destination data folder must be empty. Existing files will not be overwritten.")
    }
    val parent = dst.parentFile ?: throw IOException("Invalid destination.")
    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create the destination folder.")
    val stage = File(parent, ".${dst.name}-moving-${UUID.randomUUID()}")
    if (!stage.mkdir()) throw IOException("Cannot write to the destination.")
    try {
      copy(src, stage)
      // Recheck after copying: another app may have written into the destination.
      if (dst.exists() && !dst.delete()) throw IOException("The destination is no longer empty.")
      if (!stage.renameTo(dst)) throw IOException("Cannot put the copied data in place.")
      activate()
    } finally {
      // If activation fails after publication, retain both copies: a settings
      // write can fail ambiguously after committing. Never delete a root that
      // the persisted configuration might already reference.
      stage.deleteRecursively()
    }
    return !src.exists() || runCatching { src.deleteRecursively() }.getOrDefault(false)
  }

  private fun copy(src: File, dst: File) {
    if (Files.isSymbolicLink(src.toPath())) throw IOException("Symbolic links are not supported: ${src.name}")
    if (src.isDirectory) {
      if (!dst.isDirectory && !dst.mkdir()) throw IOException("Cannot create ${dst.name}")
      for (f in src.listFiles() ?: throw IOException("Cannot read ${src.name}")) copy(f, File(dst, f.name))
    } else if (src.isFile) {
      val digest = MessageDigest.getInstance("SHA-256")
      src.inputStream().use { input ->
        dst.outputStream().use { output ->
          val buf = ByteArray(1 shl 16)
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            digest.update(buf, 0, n)
          }
          output.fd.sync()
        }
      }
      val check = MessageDigest.getInstance("SHA-256")
      dst.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          check.update(buf, 0, n)
        }
      }
      if (!digest.digest().contentEquals(check.digest())) throw IOException("Copy verification failed: ${src.name}")
    } else throw IOException("Cannot read ${src.name}")
    // FAT timestamps have lower precision; failure to preserve a timestamp is not data loss.
    dst.setLastModified(src.lastModified())
  }
}
