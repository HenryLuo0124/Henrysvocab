package com.eng.henrysvocab

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.IOException
import java.io.Reader
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class Word(val term: String, val meanings: List<String>, val example: String)
/** The word list retains original indices, including hidden duplicates. */
internal data class Book(val id: String, val name: String, val words: List<Word>)
internal data class DedupBatch(val entries: List<Word>, val bookId: String, val start: Int)

internal const val LEARNING_BATCH_SIZE = 20
internal const val LEARNING_REFILL_AT = 5
private const val DATABASE_VERSION = 4
private const val REVIEW_IDENTITY = "book_id = ? AND word_index = ?"
private const val REVIEW_MATCH = "$REVIEW_IDENTITY AND step = ? AND due_day = ?"

private fun ReviewCard.identityArgs() = arrayOf(bookId, wordIndex.toString())
private fun ReviewCard.matchArgs() = arrayOf(bookId, wordIndex.toString(), step.toString(), dueDay.toString())

private const val MAX_IMPORT_CHARS = 4 * 1024 * 1024
private const val MAX_LINE_CHARS = 8_192
private const val MAX_WORDS = 20_000
private const val MAX_WORD_MEANINGS = 64


/** Parses a caller-owned reader. Blank lines are ignored, but still count in errors. */
internal fun parseWords(reader: Reader): List<Word> {
    val input = reader.buffered()
    val words = mutableListOf<Word>()
    val line = StringBuilder()
    var lineNumber = 1
    var charsRead = 0
    var afterCr = false

    fun acceptLine() {
        if (line.isNotBlank()) {
            if (words.size == MAX_WORDS) invalidLine(lineNumber, "Maximum of $MAX_WORDS words exceeded")
            words += parseWord(line.toString().trim(), lineNumber)
        }
        line.setLength(0)
        lineNumber++
    }

    // Bound memory even if an input contains a single very long line.
    while (true) {
        val next = try {
            input.read()
        } catch (failure: IOException) {
            throw IOException("Line $lineNumber: Could not read vocabulary", failure)
        }
        if (next == -1) {
            acceptLine()
            return words
        }
        if (++charsRead > MAX_IMPORT_CHARS) invalidLine(lineNumber, "Vocabulary file is too large")
        val ch = next.toChar()
        if (charsRead == 1 && ch == '\uFEFF') continue
        val skipLf = afterCr && ch == '\n'
        afterCr = ch == '\r'
        if (skipLf) continue
        if (ch == '\r' || ch == '\n') {
            acceptLine()
        } else {
            if (line.length == MAX_LINE_CHARS) invalidLine(lineNumber, "Line is too long")
            line.append(ch)
        }
    }
}

private fun parseWord(line: String, lineNumber: Int): Word {
    val firstSlash = line.indexOf('/')
    if (firstSlash < 0) invalidLine(lineNumber, "Expected term/(meaning1/meaning2)/example")
    val term = line.substring(0, firstSlash).trim()
    if (term.isEmpty()) invalidLine(lineNumber, "Missing term")

    val open = (firstSlash + 1 until line.length).firstOrNull { !line[it].isWhitespace() }
    if (open == null || line[open] != '(') invalidLine(lineNumber, "Expected '(' before meanings")
    var depth = 1
    var close = -1
    for (i in open + 1 until line.length) {
        when (line[i]) {
            '(' -> depth++
            ')' -> if (--depth == 0) {
                close = i
                break
            }
        }
    }
    if (close < 0) invalidLine(lineNumber, "Missing ')' after meanings")
    val suffix = line.substring(close + 1).trimStart()
    if (!suffix.startsWith('/')) invalidLine(lineNumber, "Expected '/' before example")
    val example = suffix.substring(1).trim()
    if (example.isEmpty()) invalidLine(lineNumber, "Missing example")
    val meanings = buildList {
        var start = open + 1
        var nested = 0
        for (i in start until close) {
            when (line[i]) {
                '(' -> nested++
                ')' -> nested--
                '/' -> if (nested == 0) {
                    add(line.substring(start, i).trim())
                    start = i + 1
                }
            }
        }
        add(line.substring(start, close).trim())
    }
    if (meanings.size > MAX_WORD_MEANINGS) invalidLine(lineNumber, "Maximum of $MAX_WORD_MEANINGS meanings exceeded")
    if (meanings.any { it.isEmpty() }) invalidLine(lineNumber, "Meanings must not be blank")
    return Word(term, meanings, example)
}

private fun invalidLine(number: Int, message: String): Nothing =
    throw IllegalArgumentException("Line $number: $message")

/** Synchronous app-private storage; call from a background thread when appropriate. */
internal class VocabStore(private val helper: VocabDatabase) {
    constructor(context: Context) : this(databaseHelper(context))

    fun books(): List<Book> {
        val books = mutableListOf<Book>()
        helper.readableDatabase.rawQuery(
            """
            SELECT b.id, b.name, w.term, w.meanings, w.example
            FROM books b LEFT JOIN words w ON w.book_id = b.id
            ORDER BY b.rowid, w.word_index
            """.trimIndent(),
            null,
        ).use { cursor ->
            var currentWords: MutableList<Word>? = null
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if (books.lastOrNull()?.id != id) {
                    val entries = mutableListOf<Word>()
                    books += Book(id, cursor.getString(1), entries)
                    currentWords = entries
                }
                if (!cursor.isNull(2)) {
                    checkNotNull(currentWords).add(cursor.wordAt(2))
                }
            }
        }
        return books
    }

    fun add(name: String, words: List<Word>): Book {
        val book = Book(UUID.randomUUID().toString(), name, words.map { it.copy(meanings = it.meanings.toList()) })
        helper.writableDatabase.inTransaction {
            execSQL(
                "INSERT INTO books (id, name, word_count) VALUES (?, ?, ?)",
                arrayOf<Any>(book.id, book.name, book.words.size),
            )
            compileStatement(
                "INSERT INTO words (book_id, word_index, term, meanings, example) VALUES (?, ?, ?, ?, ?)",
            ).use { insert ->
                insert.bindString(1, book.id)
                book.words.forEachIndexed { index, word ->
                    insert.bindLong(2, index.toLong())
                    insert.bindString(3, word.term)
                    insert.bindString(4, JSONArray(word.meanings).toString())
                    insert.bindString(5, word.example)
                    insert.executeInsert()
                }
            }
        }
        return book
    }

    fun rename(id: String, name: String) {
        helper.writableDatabase.execSQL("UPDATE books SET name = ? WHERE id = ?", arrayOf(name, id))
    }

    /** Deletes the book and its pending learning queue, but never its study history. */
    fun deleteBook(id: String): Boolean = studyChange {
        val args = arrayOf(id)
        val exists = rawQuery("SELECT 1 FROM books WHERE id = ?", args).use { it.moveToFirst() }
        if (!exists) return@studyChange false

        // Pending learning references the source words; history and review snapshots don't.
        delete("words", "book_id = ?", args)
        check(delete("books", "id = ?", args) == 1)
        if (delete("selection", "singleton = 1 AND book_id = ?", args) == 1) {
            execSQL(
                "INSERT INTO selection (singleton, book_id) SELECT 1, id FROM books ORDER BY rowid LIMIT 1",
            )
        }
        true
    }

    /** First remaining queue index, next source index if not queued, or word count when finished. */
    fun position(id: String): Int = helper.readableDatabase.rawQuery(
        """
        SELECT COALESCE((SELECT MIN(word_index) FROM pending_learning WHERE book_id = b.id), b.position)
        FROM books b WHERE b.id = ?
        """.trimIndent(), arrayOf(id),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun pendingLearning(id: String): List<Int> = helper.readableDatabase.readPendingLearning(id)

    fun pendingLearningEntries(id: String): List<LearningEntry> = helper.readableDatabase.readLearningEntries(id)

    /** Appends one original slice without running dedup if the queue is at or below the threshold. */
    fun fillLearning(id: String, prefetch: Boolean = false): List<Int> = synchronized(helper) {
        val db = helper.writableDatabase
        val (indices, changed) = db.inTransaction {
            val current = readPendingLearning(id)
            if (current.size > refillThreshold(prefetch)) return@inTransaction current to false
            val state = learningState(id) ?: return@inTransaction emptyList<Int>() to false
            // Legacy v3 could pre-check up to 50 words. Don't recheck the remainder, but still
            // queue at most one 20-source slice at a time, including previously hidden words.
            val limit = if (state.dedup) state.checkedUntil else state.size
            if (state.position >= limit) return@inTransaction current to false
            val end = state.position + minOf(LEARNING_BATCH_SIZE, limit - state.position)
            enqueueLearning(id, state.position, end) to true
        }
        if (changed) helper.rebuildStudyDocuments(db)
        indices
    }

    /** Compatibility entry point: a read for a new session now persists the batch first. */
    fun learningIndexes(id: String, from: Int, limit: Int): List<Int> =
        if (limit <= 0) emptyList() else fillLearning(id).asSequence().filter { it >= from }.take(limit).toList()

    /** Compatibility entry point for callers that didn't explicitly fill the queue. */
    fun markLearned(id: String, index: Int, day: Long) {
        studyChange {
            if (readPendingLearning(id).isEmpty()) {
                val state = learningState(id) ?: return@studyChange false
                val end = state.position + minOf(LEARNING_BATCH_SIZE, state.learningEnd - state.position)
                if (index !in state.position until end) return@studyChange false
                val available = rawQuery(
                    "SELECT 1 FROM words WHERE $REVIEW_IDENTITY AND hidden = 0 AND learned = 0",
                    arrayOf(id, index.toString()),
                ).use { it.moveToFirst() }
                if (!available) return@studyChange false
                enqueueLearning(id, state.position, end)
            }
            resolveLearning(id, index, day)
        }
    }

    fun completeLearning(id: String, index: Int, day: Long): Boolean = studyChange {
        resolveLearning(id, index, day)
    }

    fun discardLearning(id: String, index: Int): Boolean = studyChange {
        resolveLearning(id, index, null)
    }

    private fun SQLiteDatabase.resolveLearning(id: String, index: Int, day: Long?): Boolean {
        val args = arrayOf(id, index.toString())
        if (delete("pending_learning", REVIEW_IDENTITY, args) != 1) return false
        compileStatement(
            """
            INSERT INTO learned_history (book_id, word_index, term, meanings, example, learned_day)
            SELECT book_id, word_index, term, meanings, example, ? FROM words
            WHERE book_id = ? AND word_index = ? AND hidden = 0 AND learned = 0
            """.trimIndent(),
        ).use { insert ->
            if (day == null) insert.bindNull(1) else insert.bindLong(1, day)
            insert.bindString(2, id)
            insert.bindLong(3, index.toLong())
            check(insert.executeInsert() != -1L)
        }
        check(update("words", ContentValues().apply { put("learned", 1) }, "book_id = ? AND word_index = ?", args) == 1)
        if (day != null) {
            val schedule = ReviewSchedule.start(day)
            execSQL(
                "INSERT INTO reviews (book_id, word_index, step, anchor_day, due_day) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any>(id, index, schedule.step, schedule.anchorDay, schedule.dueDay),
            )
            execSQL(
                """
                INSERT OR REPLACE INTO daily_counts (day, learned)
                VALUES (?, COALESCE((SELECT learned FROM daily_counts WHERE day = ?), 0) + 1)
                """.trimIndent(),
                arrayOf(day, day),
            )
        }
        return true
    }

    fun dedupEnabled(id: String): Boolean = helper.readableDatabase.learningState(id)?.dedup == true

    /** Disabling dedup stops requiring checks, but does not undo earlier checks or unhide rows. */
    fun setDedupEnabled(id: String, enabled: Boolean) {
        helper.writableDatabase.execSQL(
            "UPDATE books SET dedup_enabled = ? WHERE id = ?", arrayOf<Any>(if (enabled) 1 else 0, id),
        )
    }

    /** Returns the next unchecked original slice only when the queue is at or below the threshold. */
    fun pendingDedup(id: String, prefetch: Boolean = false): DedupBatch? =
        helper.writableDatabase.inTransaction { pendingBatch(id, prefetch) }

    /** Duplicate offsets are relative to batch.start. Revalidate the whole slice before committing. */
    fun applyDedup(batch: DedupBatch, duplicateIndexes: Set<Int>, prefetch: Boolean = false): Boolean = studyChange {
        val pending = pendingBatch(batch.bookId, prefetch) ?: return@studyChange false
        if (pending != batch) return@studyChange false
        val duplicates = duplicateIndexes.toList()
        if (duplicates.any { it !in pending.entries.indices }) return@studyChange false

        val hidden = ContentValues().apply { put("hidden", 1) }
        for (relative in duplicates) {
            check(update(
                "words", hidden, "book_id = ? AND word_index = ? AND hidden = 0 AND learned = 0",
                arrayOf(pending.bookId, (pending.start + relative).toString()),
            ) == 1)
        }
        val end = pending.start + pending.entries.size
        execSQL("UPDATE books SET dedup_checked_until = ? WHERE id = ?", arrayOf<Any>(end, pending.bookId))
        enqueueLearning(pending.bookId, pending.start, end)
        true
    }

    fun learnedTerms(): List<String> = helper.readableDatabase.readLearnedTerms()

    /** Versioned database snapshot. All three plaintext study documents are derived. */
    fun exportBackup(): JSONObject = helper.writableDatabase.inTransaction { VocabBackup.export(this) }

    /** Full replacement; invalid data throws and leaves the previous database intact. */
    fun restoreBackup(data: JSONObject) {
        synchronized(helper) {
            val db = helper.writableDatabase
            db.inTransaction { VocabBackup.restore(this, data) }
            helper.rebuildStudyDocuments(db)
        }
    }

    fun todayCount(day: Long): Int = helper.readableDatabase.rawQuery(
        "SELECT learned FROM daily_counts WHERE day = ?", arrayOf(day.toString()),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Anchors pending v1 cards on the first trusted Beijing day. Safe to call repeatedly. */
    fun seedLegacyReview(day: Long) {
        val schedule = ReviewSchedule.start(day)
        studyChange {
            update("reviews", ContentValues().apply {
                put("anchor_day", schedule.anchorDay)
                put("due_day", schedule.dueDay)
            }, "due_day IS NULL", null) > 0
        }
    }

    fun dueReviews(day: Long, limit: Int): List<ReviewCard> {
        if (limit <= 0) return emptyList()
        return helper.readableDatabase.rawQuery(
            """
            SELECT book_id, word_index, step, due_day FROM reviews
            WHERE due_day <= ? ORDER BY due_day, book_id, word_index LIMIT ?
            """.trimIndent(),
            arrayOf(day.toString(), limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ReviewCard(cursor.getString(0), cursor.getInt(1), cursor.getInt(2), cursor.getLong(3)))
                }
            }
        }
    }

    fun dueCount(day: Long): Int = helper.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM reviews WHERE due_day <= ?", arrayOf(day.toString()),
    ).use {
        it.moveToFirst()
        it.getInt(0)
    }

    /** The original snapshot remains available after a grade, discard, graduation, or book deletion. */
    fun reviewWord(card: ReviewCard): Word? = helper.readableDatabase.rawQuery(
        "SELECT term, meanings, example FROM learned_history WHERE book_id = ? AND word_index = ?",
        arrayOf(card.bookId, card.wordIndex.toString()),
    ).use { if (it.moveToFirst()) it.wordAt(0) else null }

    /**
     * Before grading, only removes an exact schedule match. Pass [afterGrade] only after a
     * successful [recordReview] for this card, to remove its new schedule or accept graduation.
     * In both cases the original word stays in learned history.
     */
    fun discardReview(card: ReviewCard, afterGrade: Boolean = false): Boolean = studyChange {
        if (!afterGrade) return@studyChange delete("reviews", REVIEW_MATCH, card.matchArgs()) == 1
        val args = card.identityArgs()
        if (delete("reviews", REVIEW_IDENTITY, args) == 1) return@studyChange true
        rawQuery("SELECT 1 FROM learned_history WHERE $REVIEW_IDENTITY", args).use { it.moveToFirst() }
    }

    /** Only grades a due card that still matches the stored schedule snapshot. */
    fun recordReview(card: ReviewCard, day: Long, correct: Boolean): Boolean {
        if (card.dueDay > day) return false
        val args = card.matchArgs()
        return studyChange {
            val anchor = rawQuery("SELECT anchor_day FROM reviews WHERE $REVIEW_MATCH", args).use {
                if (it.moveToFirst()) it.getLong(0) else null
            } ?: return@studyChange false
            val next = ReviewSchedule(anchor, card.step, card.dueDay).grade(day, correct)
            if (next == null) {
                delete("reviews", REVIEW_MATCH, args) == 1
            } else {
                val values = ContentValues().apply {
                    put("anchor_day", next.anchorDay)
                    put("step", next.step)
                    put("due_day", next.dueDay)
                }
                update("reviews", values, REVIEW_MATCH, args) == 1
            }
        }
    }

    private fun studyChange(block: SQLiteDatabase.() -> Boolean): Boolean = synchronized(helper) {
        val db = helper.writableDatabase
        db.inTransaction(block).also { changed -> if (changed) helper.rebuildStudyDocuments(db) }
    }

    var selectedId: String?
        get() = helper.readableDatabase.rawQuery(
            "SELECT book_id FROM selection WHERE singleton = 1", null,
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        set(value) {
            val database = helper.writableDatabase
            if (value == null) {
                database.execSQL("DELETE FROM selection WHERE singleton = 1")
            } else {
                database.execSQL(
                    "INSERT OR REPLACE INTO selection (singleton, book_id) VALUES (1, ?)", arrayOf(value),
                )
            }
        }

    private companion object {
        private var helper: VocabDatabase? = null

        @Synchronized
        fun databaseHelper(context: Context): VocabDatabase = helper ?: VocabDatabase(context.applicationContext)
            .also { helper = it }
    }
}

internal class VocabDatabase(
    context: Context,
    name: String = "vocabulary.db",
    private val learnedFileName: String = if (name == "vocabulary.db") "learned_words.txt" else "$name-learned.txt",
    private val pendingFileName: String = if (name == "vocabulary.db") "pending_learning.txt" else "$name-pending.txt",
    private val reviewFileName: String = if (name == "vocabulary.db") "review_queue.txt" else "$name-reviews.txt",
) : SQLiteOpenHelper(context, name, null, DATABASE_VERSION) {
    private val appData = AppDataDirectory(context)

    override fun onOpen(db: SQLiteDatabase) {
        // SQLiteOpenHelper has committed its migration by now. Also repairs stale files on reopen.
        rebuildStudyDocuments(db)
    }

    fun rebuildStudyDocuments(db: SQLiteDatabase) {
        writeDocument(learnedFileName) { emit ->
            db.readLearnedTerms().forEach { emit(it.replace('\r', ' ').replace('\n', ' ')) }
        }
        writeDocument(pendingFileName) { emit ->
            db.rawQuery(
                """
                SELECT p.book_id, p.word_index, w.term, w.meanings, w.example
                FROM pending_learning p JOIN words w USING (book_id, word_index)
                ORDER BY p.book_id, p.word_index
                """.trimIndent(), null,
            ).use { rows -> while (rows.moveToNext()) emit(rows.studyWordJson().toString()) }
        }
        writeDocument(reviewFileName) { emit ->
            db.rawQuery(
                """
                SELECT r.book_id, r.word_index, h.term, h.meanings, h.example, r.step, r.anchor_day, r.due_day
                FROM reviews r JOIN learned_history h USING (book_id, word_index)
                ORDER BY r.due_day, r.book_id, r.word_index
                """.trimIndent(), null,
            ).use { rows ->
                while (rows.moveToNext()) {
                    emit(rows.studyWordJson().apply {
                        put("step", rows.getInt(5))
                        put("anchor_day", if (rows.isNull(6)) JSONObject.NULL else rows.getLong(6))
                        put("due_day", if (rows.isNull(7)) JSONObject.NULL else rows.getLong(7))
                    }.toString())
                }
            }
        }
    }

    private fun writeDocument(name: String, write: ((String) -> Unit) -> Unit) {
        try {
            appData.write(appData.file(name)) { stream ->
                val writer = stream.bufferedWriter(Charsets.UTF_8)
                write { line ->
                    writer.write(line)
                    writer.newLine()
                }
                // AppDataDirectory owns the stream and fsyncs it before closing.
                writer.flush()
            }
        } catch (failure: IOException) {
            // SQLite is authoritative. Don't fail a committed write or skip the other documents.
            Log.w("VocabDatabase", "Could not refresh a study document", failure)
        } catch (failure: SecurityException) {
            Log.w("VocabDatabase", "Could not access a study document", failure)
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE books (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                word_count INTEGER NOT NULL,
                position INTEGER NOT NULL DEFAULT 0,
                dedup_enabled INTEGER NOT NULL DEFAULT 0 CHECK (dedup_enabled IN (0, 1)),
                dedup_checked_until INTEGER NOT NULL DEFAULT 0 CHECK (dedup_checked_until BETWEEN 0 AND word_count)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE words (
                book_id TEXT NOT NULL REFERENCES books(id),
                word_index INTEGER NOT NULL,
                term TEXT NOT NULL,
                meanings TEXT NOT NULL,
                example TEXT NOT NULL,
                hidden INTEGER NOT NULL DEFAULT 0 CHECK (hidden IN (0, 1)),
                learned INTEGER NOT NULL DEFAULT 0 CHECK (learned IN (0, 1) AND hidden + learned <= 1),
                PRIMARY KEY (book_id, word_index)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE TABLE daily_counts (day INTEGER PRIMARY KEY, learned INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE selection (singleton INTEGER PRIMARY KEY CHECK (singleton = 1), book_id TEXT NOT NULL)")
        createStudyTables(db)
        createReviews(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..3 && newVersion == DATABASE_VERSION) {
            "No vocabulary database migration from $oldVersion to $newVersion"
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE books ADD COLUMN dedup_enabled INTEGER NOT NULL DEFAULT 0 CHECK (dedup_enabled IN (0, 1))")
            db.execSQL("ALTER TABLE books ADD COLUMN dedup_checked_until INTEGER NOT NULL DEFAULT 0 CHECK (dedup_checked_until BETWEEN 0 AND word_count)")
            db.execSQL("ALTER TABLE words ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0 CHECK (hidden IN (0, 1))")
            db.execSQL("ALTER TABLE words ADD COLUMN learned INTEGER NOT NULL DEFAULT 0 CHECK (learned IN (0, 1) AND hidden + learned <= 1)")
            // V1/V2 had only a high-water mark. V2 already removed graduated review rows.
            db.execSQL("UPDATE words SET learned = 1 WHERE word_index < (SELECT position FROM books WHERE id = words.book_id)")
        }
        createStudyTables(db)
        db.copyLegacyLearnedHistory()
        if (oldVersion == 1) {
            createReviews(db)
            // Anchor these legacy rows on the first trusted day, not at migration time.
            db.execSQL("INSERT INTO reviews (book_id, word_index) SELECT book_id, word_index FROM learned_history")
        } else {
            db.execSQL("ALTER TABLE reviews RENAME TO reviews_legacy")
            db.execSQL("DROP INDEX IF EXISTS reviews_due")
            createReviews(db)
            db.execSQL(
                """
                INSERT INTO reviews (book_id, word_index, step, anchor_day, due_day)
                SELECT book_id, word_index, step, anchor_day, due_day FROM reviews_legacy
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE reviews_legacy")
        }
    }

    private fun createStudyTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE learned_history (
                book_id TEXT NOT NULL,
                word_index INTEGER NOT NULL CHECK (word_index BETWEEN 0 AND ${Int.MAX_VALUE}),
                term TEXT NOT NULL,
                meanings TEXT NOT NULL,
                example TEXT NOT NULL,
                learned_day INTEGER,
                PRIMARY KEY (book_id, word_index)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE pending_learning (
                book_id TEXT NOT NULL,
                word_index INTEGER NOT NULL,
                PRIMARY KEY (book_id, word_index),
                FOREIGN KEY (book_id, word_index) REFERENCES words(book_id, word_index) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun createReviews(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE reviews (
                book_id TEXT NOT NULL,
                word_index INTEGER NOT NULL,
                step INTEGER NOT NULL DEFAULT 0 CHECK (step BETWEEN 0 AND ${ReviewSchedule.lastStep}),
                anchor_day INTEGER,
                due_day INTEGER,
                PRIMARY KEY (book_id, word_index),
                FOREIGN KEY (book_id, word_index) REFERENCES learned_history(book_id, word_index),
                CHECK ((anchor_day IS NULL) = (due_day IS NULL))
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX reviews_due ON reviews (due_day, book_id, word_index)")
    }
}

private data class LearningState(val position: Int, val size: Int, val dedup: Boolean, val checkedUntil: Int) {
    val learningEnd: Int get() = if (dedup) checkedUntil else size
}

private fun SQLiteDatabase.learningState(id: String): LearningState? = rawQuery(
    "SELECT position, word_count, dedup_enabled, dedup_checked_until FROM books WHERE id = ?", arrayOf(id),
).use { if (it.moveToFirst()) LearningState(it.getInt(0), it.getInt(1), it.getInt(2) == 1, it.getInt(3)) else null }

private fun refillThreshold(prefetch: Boolean) = if (prefetch) LEARNING_REFILL_AT else 0

private fun SQLiteDatabase.pendingBatch(id: String, prefetch: Boolean): DedupBatch? {
    val state = learningState(id) ?: return null
    val start = state.position
    if (!state.dedup || start < state.checkedUntil || start >= state.size ||
        readPendingLearning(id).size > refillThreshold(prefetch)
    ) return null
    val count = minOf(state.size - start, LEARNING_BATCH_SIZE)
    val entries = rawQuery(
        """
        SELECT term, meanings, example FROM words WHERE book_id = ? AND word_index >= ? AND word_index < ?
            AND hidden = 0 AND learned = 0 ORDER BY word_index
        """.trimIndent(),
        arrayOf(id, start.toString(), (start + count).toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.wordAt(0)) } }
    check(entries.size == count) { "Invalid original word block" }
    return DedupBatch(entries, id, start)
}

private fun Cursor.wordAt(column: Int): Word {
    val meanings = JSONArray(getString(column + 1))
    return Word(getString(column), List(meanings.length()) { meanings.getString(it) }, getString(column + 2))
}

private fun Cursor.studyWordJson(): JSONObject = JSONObject().apply {
    put("book_id", getString(0))
    put("word_index", getInt(1))
    put("term", getString(2))
    put("meanings", JSONArray(getString(3)))
    put("example", getString(4))
}

private fun SQLiteDatabase.readPendingLearning(id: String): List<Int> = rawQuery(
    "SELECT word_index FROM pending_learning WHERE book_id = ? ORDER BY word_index", arrayOf(id),
).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) } }

private fun SQLiteDatabase.readLearningEntries(id: String): List<LearningEntry> = rawQuery(
    """
    SELECT p.word_index, w.term, w.meanings, w.example
    FROM pending_learning p JOIN words w USING (book_id, word_index)
    WHERE p.book_id = ? AND w.hidden = 0 AND w.learned = 0 ORDER BY p.word_index
    """.trimIndent(), arrayOf(id),
).use { cursor -> buildList { while (cursor.moveToNext()) add(LearningEntry(cursor.getInt(0), cursor.wordAt(1))) } }

/** Advances by the original slice, not by the number of cards that survived dedup. */
private fun SQLiteDatabase.enqueueLearning(id: String, start: Int, end: Int): List<Int> {
    check(end - start in 1..LEARNING_BATCH_SIZE)
    execSQL(
        """
        INSERT INTO pending_learning (book_id, word_index)
        SELECT book_id, word_index FROM words
        WHERE book_id = ? AND word_index >= ? AND word_index < ? AND hidden = 0 AND learned = 0
        """.trimIndent(), arrayOf<Any>(id, start, end),
    )
    execSQL("UPDATE books SET position = ? WHERE id = ?", arrayOf<Any>(end, id))
    return readPendingLearning(id)
}

/** The old schema didn't record the original learning day per word. */
internal fun SQLiteDatabase.copyLegacyLearnedHistory() {
    execSQL(
        """
        INSERT INTO learned_history (book_id, word_index, term, meanings, example)
        SELECT book_id, word_index, term, meanings, example FROM words WHERE learned = 1
        """.trimIndent(),
    )
}

private fun SQLiteDatabase.readLearnedTerms(): List<String> = rawQuery(
    "SELECT DISTINCT term FROM learned_history ORDER BY term", null,
).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        val result = block()
        setTransactionSuccessful()
        return result
    } finally {
        endTransaction()
    }
}
