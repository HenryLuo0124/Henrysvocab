package com.eng.henrysvocab

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VocabStoreDeletionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun deletingSelectedBookDropsOnlyItsPendingLearningAndPreservesStudyHistory() = withStore { store, document, _ ->
        val first = store.add("Zulu", words("shared", "hidden-first", "still-to-learn"))
        val deleted = store.add("Middle", words("shared", "deleted-only", "hidden-deleted", "unfinished"))
        val last = store.add("Alpha", words("retained-only"))
        assertEquals(listOf(first, deleted, last), store.books())
        store.selectedId = deleted.id
        store.setDedupEnabled(first.id, enabled = true)
        assertTrue(store.applyDedup(checkNotNull(store.pendingDedup(first.id)), setOf(1)))
        store.setDedupEnabled(deleted.id, enabled = true)
        assertTrue(store.applyDedup(checkNotNull(store.pendingDedup(deleted.id)), setOf(2)))
        assertTrue(store.completeLearning(first.id, 0, 100))
        assertTrue(store.completeLearning(deleted.id, 0, 100))
        assertTrue(store.completeLearning(deleted.id, 1, 105))
        assertEquals(listOf(3), store.pendingLearning(deleted.id))
        assertEquals(listOf(0), store.fillLearning(last.id))
        assertTrue(store.completeLearning(last.id, 0, 90))
        val reviews = store.dueReviews(1_000, 10)
        val detached = ReviewCard(deleted.id, 1, 0, 106)
        assertTrue(detached in reviews)
        val before = store.exportBackup()
        assertEquals("deleted-only\nretained-only\nshared\n", document.readText())

        assertTrue(store.deleteBook(deleted.id))

        assertEquals(first.id, store.selectedId)
        assertEquals(listOf(first, last), store.books())
        assertEquals(reviews, store.dueReviews(1_000, 10))
        assertEquals(deleted.words[1], store.reviewWord(detached))
        assertTrue(store.pendingLearning(deleted.id).isEmpty())
        assertTrue(store.fillLearning(deleted.id).isEmpty())
        assertFalse(store.completeLearning(deleted.id, 3, 106))
        assertFalse(store.discardLearning(deleted.id, 3))
        assertTrue(store.dedupEnabled(first.id))
        assertEquals(2, store.position(first.id))
        assertEquals(listOf(2), store.pendingLearning(first.id))
        assertEquals(listOf("deleted-only", "retained-only", "shared"), store.learnedTerms())
        assertEquals("deleted-only\nretained-only\nshared\n", document.readText())

        val after = store.exportBackup()
        for ((table, idColumn) in listOf("books" to "id", "words" to "book_id", "pending_learning" to "book_id")) {
            val expected = rows(before, table).asSequence()
                .filterNot { it.getString(idColumn) == deleted.id }
                .map { it.toString() }
                .toList()
            assertEquals(expected, rows(after, table).map { it.toString() })
        }
        // Includes an unrelated day, a shared day, and a day that only the deleted book contributed to.
        assertEquals(before.getJSONArray("daily_counts").toString(), after.getJSONArray("daily_counts").toString())
        assertEquals(before.getJSONArray("reviews").toString(), after.getJSONArray("reviews").toString())
        assertEquals(before.getJSONArray("learned_history").toString(), after.getJSONArray("learned_history").toString())
    }

    @Test
    fun deletingUnselectedBookKeepsSelectionAndRejectsOutstandingDedupBatch() = withStore { store, _, _ ->
        val deleted = store.add("First", words("one"))
        val firstRemaining = store.add("Second", words("two"))
        val selected = store.add("Third", words("three"))
        store.selectedId = selected.id
        store.setDedupEnabled(deleted.id, enabled = true)
        val staleBatch = checkNotNull(store.pendingDedup(deleted.id))

        assertTrue(store.deleteBook(deleted.id))

        assertEquals(selected.id, store.selectedId)
        assertEquals(listOf(firstRemaining, selected), store.books())
        assertNull(store.pendingDedup(deleted.id))
        assertFalse(store.applyDedup(staleBatch, setOf(0)))
    }

    @Test
    fun deletingOnlySelectedBookClearsSelectionButDetachedReviewCanStillBeGradedAndDiscarded() = withStore { store, document, _ ->
        val only = store.add("Only", words("sole-term"))
        store.selectedId = only.id
        assertEquals(listOf(0), store.fillLearning(only.id))
        assertTrue(store.completeLearning(only.id, 0, 100))
        val card = store.dueReviews(101, 1).single()

        assertTrue(store.deleteBook(only.id))

        assertNull(store.selectedId)
        assertTrue(store.books().isEmpty())
        assertEquals(listOf(card), store.dueReviews(101, 10))
        assertEquals(only.words.single(), store.reviewWord(card))
        assertTrue(store.recordReview(card, 101, correct = true))
        assertFalse(store.discardReview(card))
        val next = ReviewCard(only.id, 0, 1, 103)
        assertEquals(listOf(next), store.dueReviews(103, 10))
        assertTrue(store.discardReview(next))
        assertFalse(store.discardReview(next))
        assertFalse(store.recordReview(next, 103, correct = true))
        assertEquals(0, store.dueCount(1_000))
        assertEquals(only.words.single(), store.reviewWord(next))
        assertEquals(listOf("sole-term"), store.learnedTerms())
        assertEquals("sole-term\n", document.readText())
        assertEquals(1, store.todayCount(100))
        assertEquals(0, store.todayCount(101))
        assertFalse(store.deleteBook(only.id))
    }

    @Test
    fun deletingMissingBookLeavesBackupAndDocumentUnchangedEvenWithStaleSelection() = withStore { store, document, _ ->
        val book = store.add("Keep", words("keep-term"))
        assertEquals(listOf(0), store.fillLearning(book.id))
        assertTrue(store.completeLearning(book.id, 0, 100))
        val missing = "missing"
        store.selectedId = missing // Backups can carry a selection that no longer matches a book.
        val before = store.exportBackup().toString()
        val learned = document.readText()

        assertFalse(store.deleteBook(missing))

        assertEquals(before, store.exportBackup().toString())
        assertEquals(learned, document.readText())
    }

    @Test
    fun selectionFailureRollsBackBookWordsAndPendingLearning() = withStore { store, document, db ->
        val deleted = store.add("Selected", words("keep-on-error", "still-pending"))
        store.add("Fallback", words("fallback"))
        store.selectedId = deleted.id
        assertEquals(listOf(0, 1), store.fillLearning(deleted.id))
        assertTrue(store.completeLearning(deleted.id, 0, 100))
        val before = store.exportBackup().toString()
        db.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_selection BEFORE INSERT ON selection
            BEGIN SELECT RAISE(ABORT, 'selection update failed'); END
            """.trimIndent(),
        )

        try {
            store.deleteBook(deleted.id)
            fail("Expected replacement selection to fail")
        } catch (_: SQLiteException) {
            assertEquals(before, store.exportBackup().toString())
            assertEquals(listOf(1), store.pendingLearning(deleted.id))
            assertEquals("keep-on-error\n", document.readText())
        }
    }

    private fun words(vararg terms: String) = terms.map { Word(it, listOf("meaning"), "example") }

    private fun rows(snapshot: JSONObject, table: String): List<JSONObject> =
        snapshot.getJSONArray(table).let { rows -> List(rows.length()) { rows.getJSONObject(it) } }

    private fun withStore(test: (VocabStore, File, VocabDatabase) -> Unit) {
        val name = "vocab-delete-${UUID.randomUUID()}.db"
        val appData = AppDataDirectory(context)
        val document = appData.file("$name-learned.txt")
        try {
            VocabDatabase(context, name).use { db -> test(VocabStore(db), document, db) }
        } finally {
            context.deleteDatabase(name)
            for (suffix in listOf("learned", "pending", "reviews")) {
                appData.delete(appData.file("$name-$suffix.txt"))
            }
        }
    }
}
