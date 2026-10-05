package no.heimflyt.launcher.theme.store

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Every file operation of the theme store (THEME_ARCHITECTURE.md §8, R1). Tests substitute a fault-injecting implementation
 * that fails or "kills" the job after any write, sync or rename.
 */
interface ThemeFiles {
    /** Writes, flushes and `FileDescriptor.sync()`s a new file. */
    fun write(file: File, bytes: ByteArray)
    /** Atomic rename(2); replaces a file target, requires an absent directory target. */
    fun rename(from: File, to: File)
    /** fsync of a directory, so renames and new entries in it are durable. */
    fun syncDir(dir: File)
    fun mkdir(dir: File)
    fun deleteTree(dir: File) { dir.deleteRecursively() }
    fun read(file: File, max: Int): ByteArray {
        val len = file.length(); if (len > max) throw IOException("${file.name} over $max bytes")
        return file.readBytes().also { if (it.size > max) throw IOException("${file.name} over $max bytes") }
    }
}

/** The Android implementation: directory sync through `android.system.Os` (libcore refuses to open directories as streams). */
object AndroidThemeFiles : ThemeFiles {
    override fun write(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.flush(); it.fd.sync() }
    }
    override fun rename(from: File, to: File) = try { Os.rename(from.path, to.path) } catch (e: Exception) { throw IOException("rename ${from.name}", e) }
    override fun syncDir(dir: File) {
        try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        } catch (e: Exception) { throw IOException("sync ${dir.name}", e) }
    }
    override fun mkdir(dir: File) { if (!dir.isDirectory && !dir.mkdir()) throw IOException("mkdir ${dir.name}") }
}
