package com.eng.henrysvocab

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class VocabStoreMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun migrationPreservesV1DataAndAnchorsOnlyTheLegacyPrefixOnce() = withDatabaseName { name ->
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, word_count INTEGER NOT NULL, position INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE words (book_id TEXT NOT NULL REFERENCES books(id), word_index INTEGER NOT NULL, term TEXT NOT NULL, meanings TEXT NOT NULL, example TEXT NOT NULL, PRIMARY KEY (book_id, word_index))")
            db.execSQL("CREATE TABLE daily_counts (day INTEGER PRIMARY KEY, learned INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE selection (singleton INTEGER PRIMARY KEY CHECK (singleton = 1), book_id TEXT NOT NULL)")
            db.execSQL("INSERT INTO books VALUES ('old', 'Legacy', 2, 1)")
            db.execSQL("INSERT INTO words VALUES ('old', 0, 'learned', '[\"meaning\"]', 'example'), ('old', 1, 'new', '[\"translation\"]', 'another')")
            db.execSQL("INSERT INTO daily_counts VALUES (90, 3)")
            db.execSQL("INSERT INTO selection VALUES (1, 'old')")
            db.version = 1
        }
        testDatabase(name).use { db ->
            val store = VocabStore(db)
            assertEquals(
                listOf(Book("old", "Legacy", listOf(Word("learned", listOf("meaning"), "example"), Word("new", listOf("translation"), "another")))),
                store.books(),
            )
            assertEquals(4, db.readableDatabase.version)
            assertEquals(listOf("learned"), store.learnedTerms())
            assertEquals("learned\n", learnedFile(name).readText())
            assertEquals("old", store.selectedId)
            assertEquals(1, store.position("old"))
            assertEquals(3, store.todayCount(90))
            assertEquals(0, store.dueCount(1000))
            assertEquals(listOf(1), store.fillLearning("old"))
            assertTrue(store.completeLearning("old", 1, 99))
            store.seedLegacyReview(100)
            assertEquals(1, store.dueCount(100))
        }
        testDatabase(name).use { db ->
            val store = VocabStore(db)
            store.seedLegacyReview(110)
            val legacy = ReviewCard("old", 0, 0, 101)
            assertEquals(listOf(ReviewCard("old", 1, 0, 100), legacy), store.dueReviews(101, 10))
            assertEquals(1, store.todayCount(99))
            var card = legacy
            repeat(7) { step ->
                assertEquals(step, card.step)
                assertTrue(store.recordReview(card, card.dueDay, correct = true))
                assertFalse(store.recordReview(card, card.dueDay, correct = true))
                if (step < 6) card = store.dueReviews(1000, 10).single { (_, index) -> index == 0 }
            }
            store.seedLegacyReview(200)
            assertEquals(Word("learned", listOf("meaning"), "example"), store.reviewWord(legacy))
            assertFalse(store.completeLearning("old", 0, 200))
            assertEquals(listOf(ReviewCard("old", 1, 0, 100)), store.dueReviews(1000, 10))
            assertEquals(0, store.todayCount(200))
        }
    }

    @Test
    fun freshStoreOrdersDueCardsAcrossBooksAndRejectsStaleGrades() = withDatabaseName { name ->
        testDatabase(name).use { db ->
            val store = VocabStore(db)
            val word = Word("term", listOf("meaning"), "example")
            val first = store.add("First", listOf(word))
            val second = store.add("Second", listOf(word, word))
            assertEquals(listOf(0), store.fillLearning(first.id))
            assertEquals(listOf(0, 1), store.fillLearning(second.id))
            assertTrue(store.completeLearning(first.id, 0, 12))
            assertTrue(store.completeLearning(second.id, 1, 10))
            assertFalse(store.completeLearning(second.id, 1, 12))
            assertEquals(listOf(0), store.pendingLearning(second.id))
            store.seedLegacyReview(100)
            assertEquals(1, store.todayCount(10))
            assertEquals(1, store.todayCount(12))
            assertEquals(2, store.dueCount(13))
            val oldest = ReviewCard(second.id, 1, 0, 11)
            assertEquals(listOf(oldest), store.dueReviews(13, 1))
            assertEquals(emptyList<ReviewCard>(), store.dueReviews(13, 0))
            assertFalse(store.recordReview(oldest, 10, correct = false))
            assertTrue(store.recordReview(oldest, 13, correct = false))
            assertFalse(store.recordReview(oldest, 14, correct = true))
            assertEquals(1, store.dueCount(13))
            assertEquals(
                listOf(ReviewCard(first.id, 0, 0, 13), ReviewCard(second.id, 1, 0, 14)),
                store.dueReviews(14, 10),
            )
            try {
                db.writableDatabase.execSQL("INSERT INTO reviews (book_id, word_index) VALUES (?, 0)", arrayOf(second.id))
                fail("Expected a foreign-key violation for a queued but unlearned word")
            } catch (_: SQLiteConstraintException) {
                // An existing source word is not enough; reviews reference learned history.
            }
        }
    }

    @Test
    fun v2MigrationKeepsDueDatesStepsAndDailyCountsWithoutResurrectingGraduatedCards() = withDatabaseName { name ->
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, word_count INTEGER NOT NULL, position INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE words (book_id TEXT NOT NULL REFERENCES books(id), word_index INTEGER NOT NULL, term TEXT NOT NULL, meanings TEXT NOT NULL, example TEXT NOT NULL, PRIMARY KEY (book_id, word_index))")
            db.execSQL("CREATE TABLE daily_counts (day INTEGER PRIMARY KEY, learned INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE selection (singleton INTEGER PRIMARY KEY CHECK (singleton = 1), book_id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE reviews (book_id TEXT NOT NULL, word_index INTEGER NOT NULL, step INTEGER NOT NULL DEFAULT 0 CHECK (step BETWEEN 0 AND 6), anchor_day INTEGER, due_day INTEGER, PRIMARY KEY (book_id, word_index), FOREIGN KEY (book_id, word_index) REFERENCES words(book_id, word_index) ON DELETE CASCADE, CHECK ((anchor_day IS NULL) = (due_day IS NULL)))")
            db.execSQL("CREATE INDEX reviews_due ON reviews (due_day, book_id, word_index)")
            db.execSQL("INSERT INTO books VALUES ('old', 'Legacy', 4, 3)")
            db.execSQL("INSERT INTO words VALUES ('old', 0, 'graduated', '[]', ''), ('old', 1, 'active', '[]', ''), ('old', 2, 'pending', '[]', ''), ('old', 3, 'unused', '[]', '')")
            db.execSQL("INSERT INTO reviews VALUES ('old', 1, 2, 100, 104), ('old', 2, 0, NULL, NULL)")
            db.execSQL("INSERT INTO daily_counts VALUES (100, 2)")
            db.execSQL("INSERT INTO selection VALUES (1, 'old')")
            db.version = 2
        }
        testDatabase(name).use { db ->
            val store = VocabStore(db)
            assertEquals(4, db.readableDatabase.version)
            assertEquals(3, store.position("old"))
            assertEquals("old", store.selectedId)
            assertEquals(2, store.todayCount(100))
            assertEquals(listOf("active", "graduated", "pending"), store.learnedTerms())
            assertFalse(store.dedupEnabled("old"))
            val active = ReviewCard("old", 1, 2, 104)
            assertEquals(Word("active", emptyList(), ""), store.reviewWord(active))
            assertEquals(emptyList<ReviewCard>(), store.dueReviews(103, 10))
            assertEquals(listOf(active), store.dueReviews(104, 10))
            assertFalse(store.recordReview(active, 103, correct = true))
            store.seedLegacyReview(110)
            assertEquals(listOf(active), store.dueReviews(110, 10))
            assertTrue(store.recordReview(active, 110, correct = true))
            val seeded = ReviewCard("old", 2, 0, 111)
            val next = ReviewCard("old", 1, 3, 117)
            assertEquals(listOf(seeded), store.dueReviews(116, 10))
            assertEquals(listOf(seeded, next), store.dueReviews(117, 10))
            assertTrue(store.recordReview(next, 120, correct = true))
            assertEquals(listOf(seeded), store.dueReviews(134, 10))
            assertEquals(listOf(seeded, ReviewCard("old", 1, 4, 135)), store.dueReviews(135, 10))
        }
    }

    @Test
    fun v3MigrationKeepsSparseHistoryAndQueuesOnlyTheRemainingCheckedWordsInSlices() = withDatabaseName { name ->
        val words = List(53) { Word("term-$it", listOf("definition"), "example") }
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE books (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, word_count INTEGER NOT NULL, position INTEGER NOT NULL, dedup_enabled INTEGER NOT NULL, dedup_checked_until INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE words (book_id TEXT NOT NULL REFERENCES books(id), word_index INTEGER NOT NULL, term TEXT NOT NULL, meanings TEXT NOT NULL, example TEXT NOT NULL, hidden INTEGER NOT NULL, learned INTEGER NOT NULL, PRIMARY KEY (book_id, word_index))")
            db.execSQL("CREATE TABLE daily_counts (day INTEGER PRIMARY KEY, learned INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE selection (singleton INTEGER PRIMARY KEY, book_id TEXT NOT NULL)")
            db.execSQL("CREATE TABLE reviews (book_id TEXT NOT NULL, word_index INTEGER NOT NULL, step INTEGER NOT NULL, anchor_day INTEGER, due_day INTEGER, PRIMARY KEY (book_id, word_index), FOREIGN KEY (book_id, word_index) REFERENCES words(book_id, word_index) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX reviews_due ON reviews (due_day, book_id, word_index)")
            db.execSQL("INSERT INTO books VALUES ('old', 'Legacy dedup', 53, 4, 1, 50)")
            val hidden = setOf(1, 5, 28)
            val learned = setOf(2, 3)
            words.forEachIndexed { index, word ->
                db.execSQL(
                    "INSERT INTO words VALUES ('old', ?, ?, '[\"definition\"]', ?, ?, ?)",
                    arrayOf<Any>(index, word.term, word.example, if (index in hidden) 1 else 0, if (index in learned) 1 else 0),
                )
            }
            db.execSQL("INSERT INTO reviews VALUES ('old', 3, 2, 100, 104)")
            db.execSQL("INSERT INTO daily_counts VALUES (100, 2)")
            db.version = 3
        }
        testDatabase(name).use { db ->
            val store = VocabStore(db)
            assertEquals(4, db.readableDatabase.version)
            assertEquals(listOf("term-2", "term-3"), store.learnedTerms())
            assertEquals(2, store.todayCount(100))
            val active = ReviewCard("old", 3, 2, 104)
            assertEquals(listOf(active), store.dueReviews(104, 10))
            assertEquals(words[3], store.reviewWord(active))
            assertEquals(4, store.position("old"))
            assertTrue(store.pendingLearning("old").isEmpty())
            assertNull(store.pendingDedup("old"))
            assertEquals((4..23).toList() - 5, store.fillLearning("old"))
            assertTrue(store.completeLearning("old", 23, 105))
        }
        testDatabase(name).use { db ->
            val store = VocabStore(db)
            val remaining = (4..22).toList() - 5
            assertEquals(remaining, store.pendingLearning("old"))
            assertEquals(remaining, store.fillLearning("old"))
            for (index in remaining) assertTrue(store.discardLearning("old", index))
            val next = (24..43).toList() - 28
            assertEquals(next, store.fillLearning("old"))
            for (index in next) assertTrue(store.discardLearning("old", index))
            val tail = (44..49).toList()
            assertEquals(tail, store.fillLearning("old"))
            for (index in tail) assertTrue(store.discardLearning("old", index))
            assertTrue(store.fillLearning("old").isEmpty())
            assertEquals(DedupBatch(words.drop(50), "old", 50), store.pendingDedup("old"))
        }
    }

    @Test
    fun dedupQueuesOneOriginalSliceAtATimeAndWaitsForPendingLearning() = withDatabaseName { name ->
        val words = List(43) { Word("term-$it", listOf("definition"), "example") }
        val id = testDatabase(name).use { db ->
            val store = VocabStore(db)
            val id = store.add("Book", words).id
            assertFalse(store.dedupEnabled(id))
            store.setDedupEnabled(id, enabled = true)
            assertTrue(store.dedupEnabled(id))
            assertTrue(store.fillLearning(id).isEmpty())
            assertFalse(store.completeLearning(id, 2, 100))
            val first = checkNotNull(store.pendingDedup(id))
            assertEquals(DedupBatch(words.take(20), id, 0), first)
            assertFalse(store.applyDedup(first.copy(entries = first.entries.reversed()), emptySet()))
            assertFalse(store.applyDedup(first, setOf(0, 20)))
            assertTrue(store.applyDedup(first, first.entries.indices.toSet() - setOf(2, 11, 19)))
            assertFalse(store.applyDedup(first, emptySet()))
            assertEquals(listOf(2, 11, 19), store.pendingLearning(id))
            assertEquals(2, store.position(id))
            assertFalse(store.completeLearning(id, 0, 100)) // hidden
            assertFalse(store.discardLearning(id, 20)) // unchecked
            assertTrue(store.completeLearning(id, 19, 100)) // out of order
            assertEquals(2, store.position(id))
            assertNull(store.pendingDedup(id))
            id
        }
        testDatabase(name).use { db ->
            val store = VocabStore(db)
            assertEquals(words, store.books().single().words)
            assertEquals(listOf(2, 11), store.pendingLearning(id))
            assertEquals(listOf(2, 11), store.fillLearning(id))
            assertNull(store.pendingDedup(id))
            assertTrue(store.discardLearning(id, 2))
            assertFalse(store.completeLearning(id, 2, 100))
            assertFalse(store.discardLearning(id, 2))
            assertEquals(11, store.position(id))
            assertTrue(store.completeLearning(id, 11, 100))
            assertEquals(20, store.position(id))
            val middle = checkNotNull(store.pendingDedup(id))
            assertEquals(DedupBatch(words.subList(20, 40), id, 20), middle)
            assertTrue(store.applyDedup(middle, middle.entries.indices.toSet()))
            assertEquals(40, store.position(id))
            assertTrue(store.fillLearning(id).isEmpty())
            val last = checkNotNull(store.pendingDedup(id))
            assertEquals(DedupBatch(words.drop(40), id, 40), last)
            assertTrue(store.applyDedup(last, last.entries.indices.toSet()))
            assertEquals(43, store.position(id))
            assertNull(store.pendingDedup(id))
            assertTrue(store.pendingLearning(id).isEmpty())
            assertEquals(listOf("term-11", "term-19", "term-2"), store.learnedTerms())
            assertEquals(2, store.todayCount(100))
            assertEquals(listOf(11, 19), store.dueReviews(101, 99).map { it.wordIndex })
        }
    }

    @Test
    fun enablingDedupWaitsForExistingQueueAndEmptyResultChecksTheNextSlice() = withDatabaseName { name ->
        testDatabase(name).use { db ->
            val store = VocabStore(db)
            val words = List(45) { Word("term-$it", listOf("definition"), "example") }
            val id = store.add("Book", words).id
            assertEquals((0..19).toList(), store.fillLearning(id))
            store.setDedupEnabled(id, enabled = true)
            assertNull(store.pendingDedup(id))
            assertEquals((0..19).toList(), store.fillLearning(id))
            for (index in 0..19) assertTrue(store.discardLearning(id, index))
            val batch = checkNotNull(store.pendingDedup(id))
            assertEquals(DedupBatch(words.subList(20, 40), id, 20), batch)
            store.setDedupEnabled(id, enabled = false)
            assertFalse(store.applyDedup(batch, emptySet()))
            store.setDedupEnabled(id, enabled = true)
            assertTrue(store.applyDedup(batch, emptySet()))
            assertEquals(20, store.position(id))
            store.setDedupEnabled(id, enabled = false)
            assertEquals((20..39).toList(), store.fillLearning(id))
            store.setDedupEnabled(id, enabled = true)
            assertNull(store.pendingDedup(id))
            assertEquals((20..39).toList(), store.pendingLearning(id))
            assertEquals(0, store.todayCount(100))
            assertEquals(0, store.dueCount(1_000))
        }
    }

    @Test
    fun archiveRoundTripsEveryLayerAndInvalidRestoreRollsBack() = withDatabaseName { name ->
        testDatabase(name).use { db ->
            val store = VocabStore(db)
            val word = Word("shared", listOf("one", "two"), "example")
            val first = store.add("First", List(12) { if (it == 0) word else word.copy(term = "term-$it") })
            val second = store.add("Second", listOf(word, word.copy(term = "extra")))
            store.selectedId = first.id
            store.setDedupEnabled(first.id, enabled = true)
            assertTrue(store.applyDedup(checkNotNull(store.pendingDedup(first.id)), setOf(1, 2)))
            assertTrue(store.completeLearning(first.id, 0, 100))
            assertEquals(listOf(0, 1), store.fillLearning(second.id))
            assertTrue(store.completeLearning(second.id, 0, 100))
            assertTrue(store.completeLearning(second.id, 1, 105))
            val detached = ReviewCard(second.id, 1, 0, 106)
            var card = ReviewCard(second.id, 0, 0, 101)
            repeat(7) { step ->
                assertTrue(store.recordReview(card, card.dueDay, correct = true))
                if (step < 6) {
                    card = store.dueReviews(1000, 10).single { (bookId, index) ->
                        (bookId == second.id) && (index == 0)
                    }
                }
            }
            assertTrue(store.deleteBook(second.id))
            assertEquals(listOf("extra", "shared"), store.learnedTerms())
            assertEquals("extra\nshared\n", learnedFile(name).readText())
            val backup = store.exportBackup().toString()
            assertTrue(store.discardReview(detached))
            assertTrue(store.discardLearning(first.id, 3))
            val replacement = store.add("To replace", listOf(word))
            store.selectedId = replacement.id
            store.restoreBackup(JSONObject(backup))
            assertEquals(backup, store.exportBackup().toString())
            assertEquals(first.id, store.selectedId)
            assertEquals(listOf(first), store.books())
            assertEquals("extra\nshared\n", learnedFile(name).readText())
            assertEquals((3..11).toList(), store.pendingLearning(first.id))
            assertEquals(listOf(ReviewCard(first.id, 0, 0, 101), detached), store.dueReviews(1000, 10))
            assertEquals(second.words[1], store.reviewWord(detached))
            assertEquals(word, store.reviewWord(card)) // graduated before deletion and restore
            assertEquals(2, store.todayCount(100))

            val malformed = JSONObject(backup)
            malformed.getJSONArray("books").getJSONObject(0).put("position", 99)
            try {
                store.restoreBackup(malformed)
                fail("Expected an invalid position to abort the restore")
            } catch (_: IllegalArgumentException) {
                assertEquals(backup, store.exportBackup().toString())
                assertEquals("extra\nshared\n", learnedFile(name).readText())
            }
        }
    }

    private fun testDatabase(name: String) = VocabDatabase(context, name)

    private fun learnedFile(name: String) = AppDataDirectory(context).file("$name-learned.txt")

    private fun withDatabaseName(test: (String) -> Unit) {
        val name = "vocab-test-${UUID.randomUUID()}.db"
        try {
            test(name)
        } finally {
            context.deleteDatabase(name)
            val appData = AppDataDirectory(context)
            for (suffix in listOf("learned", "pending", "reviews")) {
                appData.delete(appData.file("$name-$suffix.txt"))
            }
        }
    }
}
