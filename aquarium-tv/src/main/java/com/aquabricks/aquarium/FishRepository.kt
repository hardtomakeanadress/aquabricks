package com.aquabricks.aquarium

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.random.Random

class TankFullException : Exception("The tank is full (64 fish).")

/**
 * Small, dependency-free persistence layer: one JSON index plus one PNG per fish.
 * All reads/writes are synchronized; images are kept on the HTTP server thread and
 * handed to the renderer as Bitmaps.
 *
 * The primary constructor takes a directory so the persistence logic can be unit
 * tested on the JVM without an Android device.
 */
class FishRepository(rootDir: File) {

    private val dir: File = rootDir.apply { mkdirs() }
    private val indexFile = File(dir, "fish.json")
    private val lock = Any()
    // An activity can be torn down while an old server worker is still finishing
    // a write. Both activities construct their own repository and lock, so a
    // second, directory-wide lock serialises mutations across repository instances.
    private val mutationLock = directoryLock(dir)
    private val entries = ArrayList<FishMeta>()
    private val rng = Random(System.currentTimeMillis())

    constructor(context: Context) : this(File(context.filesDir, "fish"))

    init {
        loadIndex()
    }

    fun entries(): List<FishMeta> = synchronized(lock) { entries.toList() }

    fun count(): Int = synchronized(lock) { entries.size }

    fun contains(id: String): Boolean = synchronized(lock) { entries.any { it.id == id } }

    fun fileFor(meta: FishMeta): File = File(dir, "${meta.id}.png")

    fun add(name: String, creator: String, facing: String, png: ByteArray, width: Int, height: Int): FishMeta {
        synchronized(mutationLock) {
            synchronized(lock) {
                if (!reloadIndexLocked()) {
                    throw java.io.IOException("Could not read the tank index.")
                }
                if (entries.size >= MAX_FISH) throw TankFullException()
                val safeName = clean(name).ifBlank { "Fishy" }
                val safeCreator = clean(creator)
                val id = "f" + System.currentTimeMillis().toString(36) +
                    rng.nextInt(0x10000).toString(16).padStart(4, '0')
                val target = File(dir, "$id.png")
                val tmp = File(dir, "$id.tmp")
                tmp.writeBytes(png)
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    throw java.io.IOException("Could not save the fish image.")
                }
                val meta = FishMeta(
                    id = id,
                    name = safeName,
                    creator = safeCreator,
                    facing = if (facing.equals("left", true)) "left" else "right",
                    addedAt = System.currentTimeMillis(),
                    width = width,
                    height = height
                )
                entries.add(meta)
                if (!saveIndexLocked()) {
                    entries.remove(meta)
                    target.delete()
                    throw java.io.IOException("Could not save the tank index.")
                }
                return meta
            }
        }
    }

    fun delete(id: String): Boolean {
        synchronized(mutationLock) {
            synchronized(lock) {
                if (!reloadIndexLocked()) return false
                val meta = entries.firstOrNull { it.id == id } ?: return false
                entries.remove(meta)
                if (!saveIndexLocked()) {
                    // Keep memory and disk in agreement if the index could not be
                    // committed; otherwise a restart would silently resurrect it.
                    entries.add(meta)
                    return false
                }
                // The index no longer references this PNG. A failed unlink leaves an
                // orphan file, but cannot bring the fish back after a restart.
                File(dir, "${meta.id}.png").delete()
                return true
            }
        }
    }

    private fun loadIndex() {
        synchronized(mutationLock) {
            synchronized(lock) {
                if (!reloadIndexLocked()) {
                    // A corrupt index should not stop the tank from starting.
                    entries.clear()
                    indexFile.delete()
                }
            }
        }
    }

    /**
     * Re-reads the index from disk while the directory mutation lock is held. A
     * repository instance left over from a destroyed activity must never write a
     * stale in-memory snapshot over the new activity's index.
     */
    private fun reloadIndexLocked(): Boolean {
        if (!indexFile.exists()) {
            entries.clear()
            return true
        }
        if (!indexFile.isFile) return false
        return try {
            val array = JSONArray(indexFile.readText())
            val loaded = ArrayList<FishMeta>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                val meta = FishMeta(
                    id = id,
                    name = o.optString("name", "Fishy"),
                    creator = o.optString("creator", ""),
                    facing = o.optString("facing", "right"),
                    addedAt = o.optLong("addedAt", 0L),
                    width = o.optInt("width", 0),
                    height = o.optInt("height", 0)
                )
                if (File(dir, "$id.png").exists()) loaded.add(meta)
            }
            entries.clear()
            entries.addAll(loaded)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun saveIndexLocked(): Boolean {
        val tmp = File(dir, "fish.json.tmp")
        return try {
            val array = JSONArray()
            for (m in entries) {
                array.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("name", m.name)
                        .put("creator", m.creator)
                        .put("facing", m.facing)
                        .put("addedAt", m.addedAt)
                        .put("width", m.width)
                        .put("height", m.height)
                )
            }
            tmp.writeText(array.toString())
            if (tmp.renameTo(indexFile)) {
                true
            } else {
                // Some filesystems cannot rename over an existing file. Move the
                // completed temp file instead of truncating the live index; a
                // partial direct write would defeat the add/delete rollback.
                Files.move(
                    tmp.toPath(),
                    indexFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                true
            }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    private fun clean(value: String): String {
        return value
            .replace(Regex("[\\u0000-\\u001f]"), "")
            .trim()
            .take(24)
    }

    companion object {
        const val MAX_FISH = 64
        private val directoryLocks = HashMap<String, Any>()

        private fun directoryLock(dir: File): Any = synchronized(directoryLocks) {
            val key = try {
                dir.canonicalPath
            } catch (_: Exception) {
                dir.absolutePath
            }
            directoryLocks.getOrPut(key) { Any() }
        }
    }
}
