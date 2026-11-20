package com.eng.henrysvocab

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Logical archive versioning is independent of the SQLite schema version. */
internal object VocabBackup {
    private const val LEGACY_VERSION = 1L
    private const val VERSION = 2L

    private enum class Kind { TEXT, INT, NULLABLE_INT, MEANINGS }
    private data class Column(val name: String, val kind: Kind)
    private data class Table(val name: String, val order: String, val columns: List<Column>)

    private fun text(name: String) = Column(name, Kind.TEXT)
    private fun int(name: String) = Column(name, Kind.INT)
    private fun date(name: String) = Column(name, Kind.NULLABLE_INT)
    private val tables = listOf(
        Table("books", "rowid", listOf(text("id"), text("name"), int("word_count"), int("position"),
            int("dedup_enabled"), int("dedup_checked_until"))),
        Table("words", "book_id, word_index", listOf(text("book_id"), int("word_index"), text("term"),
            Column("meanings", Kind.MEANINGS), text("example"), int("hidden"), int("learned"))),
        Table("learned_history", "book_id, word_index", listOf(text("book_id"), int("word_index"), text("term"),
            Column("meanings", Kind.MEANINGS), text("example"), date("learned_day"))),
        Table("pending_learning", "book_id, word_index", listOf(text("book_id"), int("word_index"))),
        Table("reviews", "book_id, word_index", listOf(text("book_id"), int("word_index"), int("step"),
            date("anchor_day"), date("due_day"))),
        Table("daily_counts", "day", listOf(int("day"), int("learned"))),
        Table("selection", "singleton", listOf(int("singleton"), text("book_id"))),
    )

    fun export(db: SQLiteDatabase): JSONObject {
        check(db.inTransaction())
        return JSONObject().put("version", VERSION).apply {
            for (table in tables) {
                val rows = JSONArray()
                db.query(table.name, table.columns.map { it.name }.toTypedArray(), null, null, null, null, table.order)
                    .use { cursor ->
                        while (cursor.moveToNext()) {
                            val row = JSONObject()
                            table.columns.forEachIndexed { index, column -> row.put(column.name, cursor.value(index, column.kind)) }
                            rows.put(row)
                        }
                    }
                put(table.name, rows)
            }
        }
    }

    fun restore(db: SQLiteDatabase, data: JSONObject) {
        check(db.inTransaction())
        val version = data.get("version").integer()
        require(version == LEGACY_VERSION || version == VERSION) { "Unsupported vocabulary backup version" }
        val rowsByTable = tables.associateWith {
            if (version == LEGACY_VERSION && it.name in setOf("learned_history", "pending_learning")) null
            else data.getJSONArray(it.name)
        }
        for (table in tables.asReversed()) db.delete(table.name, null, null)
        for ((table, rows) in rowsByTable) {
            if (rows == null) {
                if (table.name == "learned_history") db.copyLegacyLearnedHistory()
                continue
            }
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val values = ContentValues(table.columns.size)
                for ((name, kind) in table.columns) {
                    val value = row.get(name)
                    when (kind) {
                        Kind.TEXT -> {
                            require(value is String) { "Expected text for ${table.name}.$name" }
                            values.put(name, value)
                        }
                        Kind.INT -> values.put(name, value.integer())
                        Kind.NULLABLE_INT -> if (value === JSONObject.NULL) values.putNull(name)
                            else values.put(name, value.integer())
                        Kind.MEANINGS -> {
                            require(value is JSONArray && (0 until value.length()).all { value.get(it) is String }) {
                                "Expected a list of meanings"
                            }
                            values.put(name, value.toString())
                        }
                    }
                }
                // Bind only known table/column names and explicit primitive values. PKs, foreign keys,
                // and column checks also reject corrupt rows; all changes roll back on failure.
                db.insertOrThrow(table.name, null, values)
            }
        }
        validate(db)
    }

    private fun Cursor.value(index: Int, kind: Kind): Any = when (kind) {
        Kind.TEXT -> getString(index)
        Kind.MEANINGS -> JSONArray(getString(index))
        Kind.INT -> getLong(index)
        Kind.NULLABLE_INT -> if (isNull(index)) JSONObject.NULL else getLong(index)
    }

    private fun Any.integer(): Long = when (this) {
        is Int -> toLong()
        is Long -> this
        else -> throw IllegalArgumentException("Expected an integer in vocabulary backup")
    }

    private fun validate(db: SQLiteDatabase) {
        db.requireNoRows(
            """
            SELECT 1 FROM books b WHERE b.word_count NOT BETWEEN 0 AND ${Int.MAX_VALUE}
                OR b.position NOT BETWEEN 0 AND b.word_count
                OR b.word_count != (SELECT COUNT(*) FROM words w WHERE w.book_id = b.id)
            """.trimIndent(), "Invalid book size or position",
        )
        db.requireNoRows(
            """
            SELECT 1 FROM words w JOIN books b ON b.id = w.book_id
            WHERE w.word_index NOT BETWEEN 0 AND b.word_count - 1
                OR (w.learned = 1 AND w.word_index >= b.position)
               OR (w.hidden = 1 AND w.word_index >= b.dedup_checked_until)
            """.trimIndent(), "Invalid word index or learning state",
        )
        db.requireNoRows(
            """
            SELECT 1 FROM pending_learning p JOIN words w USING (book_id, word_index)
            JOIN books b ON b.id = p.book_id
            WHERE w.learned != 0 OR w.hidden != 0
               OR p.word_index NOT BETWEEN b.position - $LEARNING_BATCH_SIZE AND b.position - 1
            """.trimIndent(), "Invalid pending learning batch",
        )
        db.requireNoRows(
            """
            SELECT 1 FROM words w LEFT JOIN learned_history h USING (book_id, word_index)
            WHERE w.learned != (h.book_id IS NOT NULL)
               OR w.term != h.term OR w.meanings != h.meanings OR w.example != h.example
            """.trimIndent(), "Invalid learned word snapshot",
        )
        db.requireNoRows(
            """
            SELECT 1 FROM learned_history h JOIN books b ON b.id = h.book_id
            LEFT JOIN words w ON w.book_id = h.book_id AND w.word_index = h.word_index
            WHERE w.book_id IS NULL
            """.trimIndent(), "Learned history does not match an existing source book",
        )
        db.requireNoRows(
            """
            SELECT 1 FROM reviews r
            WHERE r.due_day <= r.anchor_day OR (r.due_day IS NULL AND r.step != 0)
            """.trimIndent(), "Invalid review state",
        )
        db.requireNoRows(
            "SELECT 1 FROM daily_counts WHERE learned NOT BETWEEN 0 AND ${Int.MAX_VALUE}", "Invalid daily count",
        )
        // A selection is a preference, not a foreign key in the existing schema; an unmatched
        // selection is harmless (the app falls back to the first book) and is round-tripped as-is.
    }

    private fun SQLiteDatabase.requireNoRows(sql: String, message: String) {
        rawQuery("$sql LIMIT 1", null).use { require(!it.moveToFirst()) { message } }
    }
}
