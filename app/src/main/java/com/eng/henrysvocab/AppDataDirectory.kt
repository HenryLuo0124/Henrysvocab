package com.eng.henrysvocab

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Persistent app-private storage excluded from Android backup and device transfer. */
internal class AppDataDirectory(context: Context) {
    val root: File = context.applicationContext.noBackupFilesDir
    private val directory = File(root, "henrysvocab")

    fun file(name: String): File = File(directory, name)

    // Callers serialize access to each file. Only the committed file is ever read.
    fun write(file: File, content: (FileOutputStream) -> Unit) {
        Files.createDirectories(directory.toPath())
        val pending = pendingFile(file)
        try {
            FileOutputStream(pending).use { stream ->
                content(stream)
                stream.fd.sync()
            }
            Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            sync()
        } finally {
            pending.delete()
        }
    }

    fun delete(file: File) {
        Files.deleteIfExists(pendingFile(file).toPath())
        Files.deleteIfExists(file.toPath())
        if (directory.isDirectory) syncDataDirectory(directory)
    }

    // Persist the new file's directory entry (and the directory itself) before cleaning up legacy data.
    fun sync() {
        syncDataDirectory(directory)
        syncDataDirectory(root)
    }

    private fun pendingFile(file: File): File = File(directory, "${file.name}.new")
}

internal fun syncDataDirectory(directory: File) {
    try {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    } catch (e: ErrnoException) {
        throw IOException("Could not sync app data directory", e)
    }
}
