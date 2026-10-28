package com.rfandango.haku_x.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class StorageMigrationTest {
  private fun withFolders(test: (File, File, File) -> Unit) {
    val temp = Files.createTempDirectory("storage-move").toFile()
    try { test(temp, File(temp, "private").apply { mkdir() }, File(temp, "card/hakuX")) }
    finally { temp.deleteRecursively() }
  }

  private fun seed(src: File): Map<String, ByteArray> {
    val files = listOf("hdd/Partition1/UDATA/4d530004/save/data.bin", "hdd/Partition1/TDATA/4d530004/content.bin",
      "hdd/eeprom.bin", "hdd/.overwrite/123.bin", "states/4D530004.1/slot1.hxs",
      "states/4D530004.1/slot1.jpg", "states/4D530004.1/blobs/abc", "hle/shader.cache")
    File(src, "hdd/Partition1/TDATA/empty").mkdirs()
    return files.associateWith { path ->
      ByteArray(131079) { (it % 251).toByte() }.also { data ->
        File(src, path).apply { parentFile!!.mkdirs(); writeBytes(data); setLastModified(1_700_000_000_000L) }
      }
    }
  }

  @Test fun movePreservesAllGameDataAndPublishesBeforeRemovingSource() = withFolders { _, src, dst ->
    val expected = seed(src)
    var activated = false
    assertTrue(StorageMigration.move(src, dst) {
      assertTrue(src.isDirectory)
      expected.forEach { (path, data) -> assertArrayEquals(data, File(dst, path).readBytes()) }
      activated = true
    })
    assertTrue(activated)
    assertFalse(src.exists())
    assertTrue(File(dst, "hdd/Partition1/TDATA/empty").isDirectory)
    assertEquals(1_700_000_000_000L, File(dst, "states/4D530004.1/slot1.hxs").lastModified())
  }

  @Test fun activationFailureLeavesOriginalUsable() = withFolders { _, src, dst ->
    val expected = seed(src)
    assertThrows(IOException::class.java) { StorageMigration.move(src, dst) { throw IOException("settings write failed") } }
    expected.forEach { (path, data) -> assertArrayEquals(data, File(src, path).readBytes()) }
    // A settings write can throw after actually committing: retain the
    // published copy as well so either configuration remains usable.
    expected.forEach { (path, data) -> assertArrayEquals(data, File(dst, path).readBytes()) }
    assertEquals(listOf("hakuX"), dst.parentFile!!.list()!!.toList())
  }

  @Test fun refusesToOverwriteAnExistingDestination() = withFolders { _, src, dst ->
    seed(src)
    dst.mkdirs()
    File(dst, "keep.txt").writeText("existing game data")
    assertThrows(IOException::class.java) { StorageMigration.move(src, dst) { fail("must not activate") } }
    assertEquals("existing game data", File(dst, "keep.txt").readText())
    assertTrue(src.exists())
  }

  @Test fun ambiguousSettingsFailureKeepsTheSelectedCopyUsable() = withFolders { _, src, dst ->
    seed(src)
    var selected = src
    assertThrows(IOException::class.java) {
      StorageMigration.move(src, dst) { selected = dst; throw IOException("failure after commit") }
    }
    assertTrue(File(selected, "states/4D530004.1/slot1.hxs").isFile)
    assertTrue(File(src, "states/4D530004.1/slot1.hxs").isFile)
  }

  @Test fun refusesOverlappingFolders() = withFolders { parent, src, _ ->
    assertThrows(IllegalArgumentException::class.java) { StorageMigration.move(src, File(src, "child")) {} }
    assertThrows(IllegalArgumentException::class.java) { StorageMigration.move(src, parent) {} }
    assertTrue(src.isDirectory)
  }

  @Test fun failedCopyDoesNotFollowLinksOrActivate() = withFolders { parent, src, dst ->
    val outside = File(parent, "outside").apply { writeText("untouched") }
    Files.createSymbolicLink(File(src, "link").toPath(), outside.toPath())
    assertThrows(IOException::class.java) { StorageMigration.move(src, dst) { fail("must not activate") } }
    assertEquals("untouched", outside.readText())
    assertTrue(src.exists())
    assertFalse(dst.exists())
  }

  @Test fun canMoveBackToDefaultAfterMovingOut() = withFolders { _, src, dst ->
    val expected = seed(src)
    StorageMigration.move(src, dst) {}
    StorageMigration.move(dst, src) {}
    expected.forEach { (path, data) -> assertArrayEquals(data, File(src, path).readBytes()) }
    assertFalse(dst.exists())
  }

  @Test fun missingSourceCannotActivateAnEmptyDisk() = withFolders { _, src, dst ->
    src.delete()
    assertThrows(IOException::class.java) { StorageMigration.move(src, dst) { fail("must not activate") } }
  }
}
