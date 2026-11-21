package com.eng.henrysvocab

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal const val BackupPasswordLimit = 256

internal class BackupFormatException : IOException()
internal class BackupAuthenticationException : IOException()
internal class BackupRollbackException : IOException()

private const val MAX_MODEL_BYTES = 64 * 1024
private const val MODEL_IDS_FIELD = "modelIds"
// Read-only compatibility with backups made while auto-fill was available.
private const val LEGACY_MEANING_LIMIT_FIELD = "maxGeneratedMeanings"
private const val LEGACY_GENERATE_EXAMPLES_FIELD = "generateExamples"
private const val MAX_VOCAB_BYTES = 12 * 1024 * 1024
private const val MAX_PNG_BYTES = 18 * 1024 * 1024
private const val MAX_WALLPAPER_EDGE = 2048
private const val PAYLOAD_HEADER_BYTES = 3 * Int.SIZE_BYTES

/** Manual SAF backups. The archive has no dependency on the Android Keystore. */
internal class AppBackup(
    context: Context,
    private val store: VocabStore,
    private val wallpaper: WallpaperStore,
    private val models: ModelSettings,
) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    suspend fun exportTo(document: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        requireDocument(document)
        val payload = synchronized(storageLock) { snapshot() }
        try {
            ensureActive()
            // Finish encryption before opening the provider for writing. Only ciphertext leaves memory.
            BackupCipher.encrypt(payload, password) { header, ciphertext ->
                ensureActive()
                val output = resolver.openOutputStream(document, "wt") ?: throw IOException()
                output.use {
                    it.write(header)
                    it.write(ciphertext)
                }
            }
        } finally {
            payload.fill(0)
        }
    }

    suspend fun restoreFrom(document: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        requireDocument(document)
        val stream = resolver.openInputStream(document) ?: throw IOException()
        val plaintext = stream.buffered().use { BackupCipher.decrypt(it, password) }
        val restored = try {
            parseSnapshot(plaintext)
        } finally {
            plaintext.fill(0)
        }
        try {
            synchronized(storageLock) {
                ensureActive()
                applySnapshot(restored)
            }
        } finally {
            restored.wallpaper?.recycle()
        }
    }

    private fun requireDocument(document: Uri) {
        if (document.scheme != ContentResolver.SCHEME_CONTENT ||
            document.authority.isNullOrBlank() || !DocumentsContract.isDocumentUri(app, document)
        ) throw BackupFormatException()
    }

    private fun snapshot(): ByteArray {
        val config = models.load()
        validateModel(config)
        val modelIds = ModelCatalog.load(app) ?: setOf(ModelConfig().model)
        checkFormat(modelIds.size <= ModelCatalog.MAX_IDS && modelIds.all(ModelCatalog::isValidId))
        val vocabulary = store.exportBackup().toString().toByteArray(Charsets.UTF_8)
        checkFormat(vocabulary.size in 1..MAX_VOCAB_BYTES)
        val png = wallpaper.load()?.let { bitmap ->
            try {
                checkWallpaperDimensions(bitmap.width, bitmap.height)
                val output = BoundedPngOutput()
                checkFormat(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.bytes()
            } finally {
                bitmap.recycle()
            }
        }
        val model = JSONObject().put("apiKey", config.apiKey).put("model", config.model)
            .put("baseUrl", config.baseUrl).put("path", config.path).put("name", config.name)
            .put(MODEL_IDS_FIELD, JSONArray(modelIds.sorted()))
            .toString().toByteArray(Charsets.UTF_8)
        return try {
            checkFormat(model.size in 1..MAX_MODEL_BYTES)
            val pngSize = png?.size ?: 0
            ByteBuffer.allocate(PAYLOAD_HEADER_BYTES + model.size + vocabulary.size + pngSize)
                .putInt(model.size).putInt(vocabulary.size).putInt(pngSize)
                .put(model).put(vocabulary).apply { png?.let { put(it) } }.array()
        } finally {
            model.fill(0)
        }
    }

    private fun parseSnapshot(bytes: ByteArray): Snapshot {
        try {
            checkFormat(bytes.size >= PAYLOAD_HEADER_BYTES)
            val input = ByteBuffer.wrap(bytes)
            val modelSize = input.int
            val vocabSize = input.int
            val pngSize = input.int
            checkFormat(
                modelSize in 1..MAX_MODEL_BYTES && vocabSize in 1..MAX_VOCAB_BYTES &&
                    pngSize in 0..MAX_PNG_BYTES && input.remaining() == modelSize + vocabSize + pngSize,
            )
            val model = readObject(bytes, PAYLOAD_HEADER_BYTES, modelSize)
            val hasCatalog = model.has(MODEL_IDS_FIELD)
            // Drop only the retired settings; keep the strict shape check for all other fields.
            model.remove(LEGACY_MEANING_LIMIT_FIELD)
            model.remove(LEGACY_GENERATE_EXAMPLES_FIELD)
            checkFormat(model.length() == 5 + (if (hasCatalog) 1 else 0))
            val config = ModelConfig(
                apiKey = model["apiKey"] as String,
                model = model["model"] as String,
                baseUrl = model["baseUrl"] as String,
                path = model["path"] as String,
                name = model["name"] as String,
            )
            validateModel(config)
            val modelIds = if (hasCatalog) readModelIds(model.getJSONArray(MODEL_IDS_FIELD)) else null
            val vocabulary = readObject(bytes, PAYLOAD_HEADER_BYTES + modelSize, vocabSize)
            val pngOffset = PAYLOAD_HEADER_BYTES + modelSize + vocabSize
            val bitmap = if (pngSize == 0) null else decodeWallpaper(bytes, pngOffset, pngSize)
            return Snapshot(vocabulary, config, modelIds, bitmap)
        } catch (_: Exception) {
            throw BackupFormatException()
        }
    }

    private fun applySnapshot(next: Snapshot) {
        // Capture rollback state only after authentication, model/catalog validation and PNG decode.
        val previousModel = models.load()
        validateModel(previousModel)
        // Catalog-less archives leave this preference untouched. Retain null for an unset key.
        val previousModelIds = if (next.modelIds != null) ModelCatalog.load(app) else null
        val previousWallpaper = wallpaper.load()
        try {
            var modelAttempted = false
            var catalogAttempted = false
            var wallpaperAttempted = false
            try {
                // Save can throw after an atomic rename (e.g. on directory fsync), so flag attempts
                // before invoking writes. Restore SQLite last; its API transactionally rejects bad data.
                if (previousModel != next.model) {
                    modelAttempted = true
                    models.save(next.model)
                }
                if (next.modelIds != null && next.modelIds != previousModelIds) {
                    // SharedPreferences may update memory even when commit reports failure.
                    catalogAttempted = true
                    ModelCatalog.save(app, next.modelIds)
                }
                if (previousWallpaper != null || next.wallpaper != null) {
                    wallpaperAttempted = true
                    setWallpaper(next.wallpaper)
                }
                store.restoreBackup(next.vocabulary)
            } catch (failure: Exception) {
                // Attempt each rollback independently. No raw exception is shown.
                val wallpaperRecovered = !wallpaperAttempted || rollback { setWallpaper(previousWallpaper) }
                val catalogRecovered = !catalogAttempted || rollback { ModelCatalog.save(app, previousModelIds) }
                val modelRecovered = !modelAttempted || rollback { models.save(previousModel) }
                if (!wallpaperRecovered || !catalogRecovered || !modelRecovered) throw BackupRollbackException()
                throw failure
            }
        } finally {
            previousWallpaper?.recycle()
        }
    }

    private fun setWallpaper(bitmap: Bitmap?) {
        if (bitmap == null) wallpaper.reset() else wallpaper.save(bitmap)
    }

    private fun rollback(work: () -> Unit): Boolean = try {
        work()
        true
    } catch (_: Exception) {
        false
    }

    private class Snapshot(
        val vocabulary: JSONObject,
        val model: ModelConfig,
        val modelIds: Set<String>?,
        val wallpaper: Bitmap?,
    )

    private companion object {
        // Serializes manual operations only, not independent store callers. Cross-store recovery is
        // best-effort on I/O errors, not a transaction that can survive process death mid-commit.
        val storageLock = Any()
    }
}

private fun validateModel(config: ModelConfig) {
    // Validate with the same rules as ModelSettings.save, without opening a connection or normalizing
    // the original fields. The archive/provider URI is never used for model requests.
    checkFormat(config.model.length <= 1024 && config.model.trim().length in 1..256 &&
        config.apiKey.length <= 8192 && config.apiKey.trim().all { it in '!'..'~' } &&
        config.name.length <= 1024 && config.baseUrl.length <= 4096 && config.path.length <= 4096 &&
        modelEndpoint(config) != null)
}

private fun readModelIds(array: JSONArray): Set<String> {
    checkFormat(array.length() <= ModelCatalog.MAX_IDS)
    val ids = linkedSetOf<String>()
    for (index in 0 until array.length()) {
        val id = array[index] as? String ?: throw BackupFormatException()
        checkFormat(ModelCatalog.isValidId(id) && ids.add(id))
    }
    return ids
}

private fun readObject(bytes: ByteArray, offset: Int, size: Int): JSONObject {
    val input = bytes.inputStream(offset, size)
    return JsonReader(InputStreamReader(input, Charsets.UTF_8.newDecoder())).use { reader ->
        val obj = reader.readBackupValue(depth = 0) as? JSONObject ?: throw BackupFormatException()
        checkFormat(reader.peek() == JsonToken.END_DOCUMENT)
        obj
    }
}

/** Only bounded-depth JSON of the shape emitted by our stores. Numeric fields are exact integers. */
private fun JsonReader.readBackupValue(depth: Int): Any {
    checkFormat(depth <= 16)
    return when (peek()) {
        JsonToken.BEGIN_OBJECT -> JSONObject().also { obj ->
            beginObject()
            while (hasNext()) {
                val key = nextName()
                checkFormat(!obj.has(key))
                obj.put(key, readBackupValue(depth + 1))
            }
            endObject()
        }
        JsonToken.BEGIN_ARRAY -> JSONArray().also { array ->
            beginArray()
            while (hasNext()) array.put(readBackupValue(depth + 1))
            endArray()
        }
        JsonToken.STRING -> nextString()
        JsonToken.NUMBER -> nextString().toLongOrNull() ?: throw BackupFormatException()
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.NULL -> { nextNull(); JSONObject.NULL }
        else -> throw BackupFormatException()
    }
}

private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

private fun decodeWallpaper(bytes: ByteArray, offset: Int, size: Int): Bitmap {
    checkFormat(size > pngSignature.size && pngSignature.indices.all { bytes[offset + it] == pngSignature[it] })
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, offset, size, bounds)
    checkFormat(bounds.outMimeType == "image/png")
    checkWallpaperDimensions(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    return BitmapFactory.decodeByteArray(bytes, offset, size, options) ?: throw BackupFormatException()
}

private fun checkWallpaperDimensions(width: Int, height: Int) {
    checkFormat(width in 1..MAX_WALLPAPER_EDGE && height in 1..MAX_WALLPAPER_EDGE)
}

private class BoundedPngOutput : OutputStream() {
    private val output = ByteArrayOutputStream()

    override fun write(value: Int) {
        checkFormat(output.size() < MAX_PNG_BYTES)
        output.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        checkFormat(length <= MAX_PNG_BYTES - output.size())
        output.write(bytes, offset, length)
    }

    fun bytes(): ByteArray = output.toByteArray().also { checkFormat(it.isNotEmpty()) }
}

private fun checkFormat(valid: Boolean) {
    if (!valid) throw BackupFormatException()
}

/** JVM-testable envelope. V1 uses big endian: magic | version | ciphertext length | salt | nonce
 * | ciphertext (including tag). The entire fixed-size header is GCM associated data. KDF work
 * is fixed by version, never chosen by the input file. Payload: 3 lengths, then model JSON
 * (with optional modelIds), vocabulary JSON and optional PNG. Do not parse any payload bytes
 * until doFinal succeeds.
 */
internal object BackupCipher {
    private val magic = byteArrayOf(0x48, 0x56, 0x42, 0x4b) // HVBK
    private val random = SecureRandom()
    private const val VERSION = 1
    private const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val HEADER_BYTES = 4 + 1 + Int.SIZE_BYTES + SALT_BYTES + NONCE_BYTES
    private const val MIN_PAYLOAD_BYTES = PAYLOAD_HEADER_BYTES + 2
    private const val MAX_PAYLOAD_BYTES = PAYLOAD_HEADER_BYTES + MAX_MODEL_BYTES + MAX_VOCAB_BYTES + MAX_PNG_BYTES

    fun encrypt(plaintext: ByteArray, password: CharArray, write: (ByteArray, ByteArray) -> Unit) {
        checkFormat(plaintext.size in MIN_PAYLOAD_BYTES..MAX_PAYLOAD_BYTES)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(magic).put(VERSION.toByte())
            .putInt(plaintext.size + TAG_BYTES).put(salt).put(nonce).array()
        val ciphertext = cipher(Cipher.ENCRYPT_MODE, password, salt, nonce, header).doFinal(plaintext)
        write(header, ciphertext)
    }

    fun decrypt(stream: InputStream, password: CharArray): ByteArray {
        val input = DataInputStream(stream)
        val header = ByteArray(HEADER_BYTES)
        val ciphertext: ByteArray
        val salt = ByteArray(SALT_BYTES)
        val nonce = ByteArray(NONCE_BYTES)
        try {
            input.readFully(header)
            checkFormat(magic.indices.all { header[it] == magic[it] } && header[magic.size] == VERSION.toByte())
            val fields = ByteBuffer.wrap(header).apply { position(magic.size + 1) }
            val length = fields.int
            checkFormat(length in (MIN_PAYLOAD_BYTES + TAG_BYTES)..(MAX_PAYLOAD_BYTES + TAG_BYTES))
            fields.get(salt).get(nonce)
            ciphertext = ByteArray(length)
            input.readFully(ciphertext)
            checkFormat(input.read() == -1)
        } catch (_: EOFException) {
            throw BackupFormatException()
        }
        return try {
            cipher(Cipher.DECRYPT_MODE, password, salt, nonce, header).doFinal(ciphertext)
        } catch (_: BadPaddingException) {
            // AEADBadTagException subclasses BadPaddingException; some providers report only the latter.
            throw BackupAuthenticationException()
        }
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray, header: ByteArray): Cipher {
        require(password.size in 1..BackupPasswordLimit && password.any { !it.isWhitespace() })
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
                updateAAD(header)
            }
        } finally {
            key.fill(0)
        }
    }
}
