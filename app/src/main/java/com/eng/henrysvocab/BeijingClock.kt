package com.eng.henrysvocab

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.URL
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/** Beijing calendar date from network time, independent of the device's editable clock and zone. */
internal class BeijingClock(context: Context) {
    private val appContext = context.applicationContext
    private val anchorFile = AtomicFile(appContext.noBackupFilesDir.resolve("beijing-clock-v1"))

    suspend fun today(): Long = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                anchor = readAnchor()
                loaded = true
            }
            val now = SystemClock.elapsedRealtime()
            anchor?.timeAt(now, REFRESH_AFTER_MS)?.let { return@withLock beijingDay(it) }

            if (now >= nextAttemptAt) {
                // Even cancellation of this caller shouldn't cause every subsequent tap to retry.
                nextAttemptAt = now + RETRY_AFTER_MS
                val fresh = fetchWithDeadline()
                if (fresh != null) {
                    anchor = fresh
                    saveAnchor(fresh)
                    nextAttemptAt = 0
                    fresh.timeAt(SystemClock.elapsedRealtime(), OFFLINE_TTL_MS)
                        ?.let { return@withLock beijingDay(it) }
                } else {
                    nextAttemptAt = SystemClock.elapsedRealtime() + RETRY_AFTER_MS
                }
            }

            // Prefer a still-valid HTTPS anchor to Android's potentially unauthenticated network time.
            val millis = anchor?.timeAt(SystemClock.elapsedRealtime(), OFFLINE_TTL_MS)
                ?: androidNetworkTime()
                ?: throw IOException("无法获取可信的北京时间，请连接互联网后重试。")
            beijingDay(millis)
        }
    }

    private fun bootCount(): Int? = try {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
            .takeIf { it >= 0 }
    } catch (_: SecurityException) {
        null
    }

    private fun readAnchor(): Anchor? {
        val boot = bootCount() ?: return null
        return try {
            DataInputStream(anchorFile.openRead()).use { input ->
                if (input.readInt() != boot) return null
                val saved = Anchor(input.readLong(), input.readLong())
                saved.takeIf { it.timeAt(SystemClock.elapsedRealtime(), OFFLINE_TTL_MS) != null }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun saveAnchor(value: Anchor) {
        val boot = bootCount() ?: return
        try {
            val stream = anchorFile.startWrite()
            var written = false
            try {
                DataOutputStream(stream).apply {
                    writeInt(boot)
                    writeLong(value.epochMillis)
                    writeLong(value.elapsedMillis)
                    flush()
                }
                written = true
            } finally {
                if (written) anchorFile.finishWrite(stream) else anchorFile.failWrite(stream)
            }
        } catch (_: IOException) {
            // Persistence is optional; the in-memory anchor is still usable.
        } catch (_: SecurityException) {
            // Storage unavailable: keep the in-memory anchor only.
        }
    }

    private data class Anchor(val epochMillis: Long, val elapsedMillis: Long) {
        fun timeAt(now: Long, maxAge: Long): Long? {
            if (elapsedMillis !in 0..now || epochMillis !in MIN_EPOCH_MS until MAX_EPOCH_MS) return null
            val age = now - elapsedMillis
            return if (age < maxAge) epochMillis + age else null
        }
    }

    private companion object {
        val beijingZone: ZoneId = ZoneId.of("Asia/Shanghai")
        val httpDate: DateTimeFormatter = DateTimeFormatter
            .ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
            .withResolverStyle(ResolverStyle.STRICT)
        val endpoints = listOf("https://www.baidu.com/", "https://www.bilibili.com/")
        val cacheStatusHeaders = listOf("Cache-Status", "X-Cache", "X-Cache-Webcdn", "CF-Cache-Status")
        val cachedStatus = Regex("(?:^|[^a-z])(?:hit|stale|revalidated|updating)(?:$|[^a-z])", RegexOption.IGNORE_CASE)

        const val REFRESH_AFTER_MS = 6 * 60 * 60 * 1_000L
        const val OFFLINE_TTL_MS = 7 * 24 * 60 * 60 * 1_000L
        const val RETRY_AFTER_MS = 60_000L
        const val FETCH_DEADLINE_MS = 4_000L
        const val MAX_RESPONSE_MS = 2_000L
        const val CONNECT_TIMEOUT_MS = 850
        const val READ_TIMEOUT_MS = 900
        const val MIN_EPOCH_MS = 1_577_836_800_000L // 2020-01-01 UTC
        const val MAX_EPOCH_MS = 4_102_444_800_000L // 2100-01-01 UTC (exclusive)

        val mutex = Mutex()
        var loaded = false
        var anchor: Anchor? = null
        var nextAttemptAt = 0L

        // Socket timeouts don't bound DNS or a slow stream of headers. Give the caller a separate
        // deadline and allow at most one blocking worker, with no queue if it hasn't unwound yet.
        val ioWorker = ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "BeijingClock-HTTPS").apply { isDaemon = true } },
        )

        fun beijingDay(millis: Long): Long =
            Instant.ofEpochMilli(millis).atZone(beijingZone).toLocalDate().toEpochDay()

        fun androidNetworkTime(): Long? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
            // The public API exposes no sample age; let Android judge availability and don't
            // re-anchor or persist this value as if it were a fresh HTTPS sample.
            return try {
                SystemClock.currentNetworkTimeClock().millis()
                    .takeIf { it in MIN_EPOCH_MS until MAX_EPOCH_MS }
            } catch (_: DateTimeException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

        suspend fun fetchWithDeadline(): Anchor? = withTimeoutOrNull(FETCH_DEADLINE_MS.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                val job = try {
                    ioWorker.submit {
                        var result: Anchor? = null
                        for (endpoint in endpoints) {
                            if (!continuation.isActive) break
                            result = readDate(endpoint)
                            if (result != null) break
                        }
                        continuation.resume(result)
                    }
                } catch (_: RejectedExecutionException) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation { job.cancel(true) }
            }
        }

        fun readDate(endpoint: String): Anchor? {
            return try {
                val connection = URL(endpoint).openConnection() as HttpsURLConnection
                try {
                    connection.requestMethod = "HEAD"
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
                    connection.setRequestProperty("Pragma", "no-cache")
                    // Keep the platform's normal HTTPS certificate and hostname verification.
                    val started = SystemClock.elapsedRealtime()
                    val status = connection.responseCode
                    val received = SystemClock.elapsedRealtime()
                    if (status !in 200..299 || received - started > MAX_RESPONSE_MS) return null

                    val headers = connection.headerFields
                    fun values(name: String): List<String> = headers.entries
                        .filter { it.key.equals(name, ignoreCase = true) }.flatMap { it.value }

                    if (values("Age").isNotEmpty()) return null
                    if (cacheStatusHeaders.any { name -> values(name).any(cachedStatus::containsMatchIn) }) {
                        return null
                    }
                    val date = values("Date").singleOrNull()?.trim() ?: return null
                    val millis = LocalDateTime.parse(date, httpDate).toInstant(ZoneOffset.UTC).toEpochMilli()
                    Anchor(millis, received).takeIf { it.timeAt(received, OFFLINE_TTL_MS) != null }
                } finally {
                    connection.disconnect()
                }
            } catch (_: IOException) {
                null
            } catch (_: DateTimeException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
    }
}
