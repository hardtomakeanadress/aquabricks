package com.aquabricks.aquarium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FishRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fishSurviveARepositoryRestart() {
        val directory = temporaryFolder.newFolder("fish")
        val first = FishRepository(directory)
        val meta = first.add("Luna", "Mia", "left", byteArrayOf(1, 2, 3, 4), 120, 80)

        assertEquals(1, first.count())
        assertTrue(first.fileFor(meta).exists())
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), first.fileFor(meta).readBytes().toList())

        val second = FishRepository(directory)
        assertEquals(listOf(meta), second.entries())
        assertTrue(second.delete(meta.id))
        assertEquals(0, FishRepository(directory).count())
    }

    @Test
    fun separateRepositoriesCannotOverwriteEachOthersUpdates() {
        val directory = temporaryFolder.newFolder("overlap")
        val oldActivity = FishRepository(directory)
        val newActivity = FishRepository(directory)

        // The old instance loaded an empty snapshot before the new instance wrote.
        val first = oldActivity.add("Luna", "", "right", byteArrayOf(1), 2, 2)
        val second = newActivity.add("Nemo", "", "right", byteArrayOf(2), 2, 2)

        val reopened = FishRepository(directory)
        assertEquals(
            setOf(first.id, second.id),
            reopened.entries().map { it.id }.toSet()
        )
        assertTrue("the new instance must be able to delete the old instance's fish", newActivity.delete(first.id))
        assertEquals(listOf(second.id), FishRepository(directory).entries().map { it.id })
    }

    @Test
    fun namesAreSanitisedAndBlankNamesGetADefault() {
        val repository = FishRepository(temporaryFolder.newFolder("clean"))
        val longName = "N".repeat(40)
        val meta = repository.add("  $longName\u0001  ", "  Mia\u0002  ", "right", ByteArray(2), 1, 1)
        assertEquals(24, meta.name.length)
        assertEquals("Mia", meta.creator)

        val fallback = repository.add("   ", "", "right", ByteArray(2), 1, 1)
        assertEquals("Fishy", fallback.name)
        assertEquals("", fallback.creator)
    }

    @Test
    fun tankRejectsTheSixtyFifthFish() {
        val repository = FishRepository(temporaryFolder.newFolder("full"))
        repeat(FishRepository.MAX_FISH) { index ->
            repository.add("Fish $index", "", "right", ByteArray(2), 1, 1)
        }
        assertEquals(FishRepository.MAX_FISH, repository.count())
        assertThrows(TankFullException::class.java) {
            repository.add("One too many", "", "right", ByteArray(2), 1, 1)
        }
        assertEquals(FishRepository.MAX_FISH, repository.count())
    }

    @Test
    fun corruptIndexIsDiscardedInsteadOfCrashingTheTank() {
        val directory = temporaryFolder.newFolder("corrupt")
        File(directory, "fish.json").writeText("{this is not json")
        val repository = FishRepository(directory)
        assertEquals(0, repository.count())
        assertTrue(repository.add("Nemo", "", "right", ByteArray(2), 1, 1).id.isNotBlank())
    }

    @Test
    fun indexEntriesWithoutPngsAreIgnored() {
        val directory = temporaryFolder.newFolder("missing")
        File(directory, "fish.json").writeText(
            """[{"id":"gone","name":"Gone","creator":"","facing":"right","addedAt":1,"width":4,"height":4}]"""
        )
        val repository = FishRepository(directory)
        assertEquals(0, repository.count())
    }

    @Test
    fun deletingAnUnknownFishReturnsFalse() {
        val repository = FishRepository(temporaryFolder.newFolder("unknown"))
        assertFalse(repository.delete("does-not-exist"))
    }

    @Test
    fun failedIndexWriteRollsBackAnAdd() {
        val directory = temporaryFolder.newFolder("failed-add")
        val repository = FishRepository(directory)
        val index = File(directory, "fish.json")
        index.delete()
        index.mkdirs()
        try {
            assertThrows(java.io.IOException::class.java) {
                repository.add("Luna", "", "right", ByteArray(4), 2, 2)
            }
            assertEquals(0, repository.count())
            assertEquals(0, directory.listFiles { file -> file.name.endsWith(".png") }?.size ?: 0)
        } finally {
            index.deleteRecursively()
        }
    }

    @Test
    fun failedIndexWriteKeepsAFishOnDelete() {
        val directory = temporaryFolder.newFolder("failed-delete")
        val repository = FishRepository(directory)
        val meta = repository.add("Luna", "", "right", ByteArray(4), 2, 2)
        val index = File(directory, "fish.json")
        index.delete()
        index.mkdirs()
        try {
            assertFalse(repository.delete(meta.id))
            assertEquals(1, repository.count())
            assertTrue(repository.contains(meta.id))
        } finally {
            index.deleteRecursively()
        }
    }
}
