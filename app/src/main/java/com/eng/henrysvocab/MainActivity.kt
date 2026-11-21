package com.eng.henrysvocab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.RoundedCorner
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import java.io.IOException
import java.io.Serializable
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Navy = Color(0xFF031827)
private val AppColors = darkColorScheme(
    background = Navy,
    surface = Color(0xFF18242B),
    primary = Color(0xFFD6E8C1),
    onPrimary = Color(0xFF223118),
)
private const val DailyLearningGoal = 10
private const val ReviewBatchSize = 50
private val CounterRefreshInterval = 60.seconds
private const val SyncingReviewStatus = "正在同步北京时间…"
private const val StaleReviewMessage = "复习卡片已失效或待复习列表已更新，请返回首页重新开始。"

// Store only the compare-and-set identity, never book contents or model settings.
private data class SavedReviewCard(
    val bookId: String,
    val wordIndex: Int,
    val step: Int,
    val dueDay: Long,
) : Serializable {
    constructor(card: ReviewCard) : this(card.bookId, card.wordIndex, card.step, card.dueDay)

    fun toCard() = ReviewCard(bookId, wordIndex, step, dueDay)
}

// Keep the verdict across recreation; the word remains available in learned history.
private data class SavedReviewReveal(val card: SavedReviewCard, val correct: Boolean) : Serializable

private data class LearningBatch(
    val entries: List<LearningEntry> = emptyList(),
    val status: String? = null,
    val canRetry: Boolean = true,
)

private data class DiscardConfirmation(val message: String, val confirm: () -> Unit)

class MainActivity : ComponentActivity() {
    private val translationShortcut by lazy { TranslationShortcut(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                VocabularyApp(lifecycle, translationShortcut::lookup)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        translationShortcut.clear()
    }

    override fun onDestroy() {
        translationShortcut.clear()
        super.onDestroy()
    }
}

@Composable
private fun VocabularyApp(lifecycle: Lifecycle, onLookupWord: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { VocabStore(context) }
    val clock = remember(context) { BeijingClock(context) }
    val wallpaperStore = remember(context) { WallpaperStore(context) }
    val modelSettings = remember(context) { ModelSettings(context) }
    val scope = rememberCoroutineScope()

    var stack by rememberSaveable { mutableStateOf<List<String>>(arrayListOf("home")) }
    var books by remember { mutableStateOf(emptyList<Book>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var today by remember { mutableIntStateOf(0) }
    var reviewStatus by remember { mutableStateOf(SyncingReviewStatus) }
    var config by remember { mutableStateOf(ModelConfig()) }
    var modelLoaded by remember { mutableStateOf(false) }
    var modelLoadComplete by remember { mutableStateOf(false) }
    var wallpaper by remember { mutableStateOf<Bitmap?>(null) }
    var defaultPhoto by remember { mutableStateOf<Bitmap?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var restoreRefresh by remember { mutableIntStateOf(0) }
    var counterRefresh by remember { mutableIntStateOf(0) }

    var studyBookId by rememberSaveable { mutableStateOf("") }
    var learningBatch by remember { mutableStateOf<LearningBatch?>(null) }
    var learningRefresh by remember { mutableIntStateOf(0) }
    var current by rememberSaveable { mutableIntStateOf(0) }
    var reviewQueue by rememberSaveable { mutableStateOf<List<SavedReviewCard>>(arrayListOf()) }
    var answer by rememberSaveable { mutableStateOf("") }
    var reveal by rememberSaveable { mutableStateOf<SavedReviewReveal?>(null) }
    var reviewStale by rememberSaveable { mutableStateOf(false) }
    // If recreated during a SQLite commit, reopen from the due queue instead of replaying it.
    var reviewCommitPending by rememberSaveable { mutableStateOf(false) }
    var actionJob by remember { mutableStateOf<Job?>(null) }
    var preparingLearning by remember { mutableStateOf(false) }
    var dedupFailure by rememberSaveable { mutableStateOf<String?>(null) }
    var lookupResult by remember { mutableStateOf<Pair<String, List<LookupMeaning>>?>(null) }
    var studyError by remember { mutableStateOf<String?>(null) }
    var discardConfirmation by remember { mutableStateOf<DiscardConfirmation?>(null) }
    var notice by remember { mutableStateOf<Pair<String, String?>?>(null) }

    fun open(route: String) {
        if (stack.last() != route) stack = ArrayList(stack + route)
    }
    fun cancelAction() {
        actionJob?.cancel()
        actionJob = null
    }
    fun clearStudySession() {
        studyBookId = ""
        learningRefresh++
        learningBatch = null
        preparingLearning = false
        dedupFailure = null
        lookupResult = null
        reviewQueue = arrayListOf()
        reviewStale = false
        reviewCommitPending = false
        current = 0
        answer = ""
        reveal = null
        studyError = null
        discardConfirmation = null
    }
    fun refreshBooks() {
        loaded = false
        refresh++
    }
    fun onBackupRestored() {
        cancelAction()
        clearStudySession()
        notice = null
        today = 0
        reviewStatus = SyncingReviewStatus
        books = emptyList()
        selected = null
        config = ModelConfig()
        modelLoaded = false
        modelLoadComplete = false
        wallpaper = null
        refreshBooks()
        restoreRefresh++
        counterRefresh++
    }
    fun back() {
        if (stack.size > 1) {
            cancelAction()
            if (stack.last() == "learn" || stack.last() == "review") {
                clearStudySession()
                counterRefresh++
            }
            stack = ArrayList(stack.dropLast(1))
        }
    }
    fun perform(onError: (String) -> Unit, work: suspend () -> Unit) {
        if (actionJob != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val running = coroutineContext[Job]
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                onError(if (e is IOException) e.message ?: "操作失败，请重试。" else "操作失败，请重试。")
            } finally {
                if (actionJob === running) {
                    actionJob = null
                }
            }
        }
        actionJob = job
        job.start()
    }

    LaunchedEffect(Unit) {
        defaultPhoto = withContext(Dispatchers.IO) {
            try {
                context.assets.open("forest.jpg").use { BitmapFactory.decodeStream(it) }
            } catch (_: IOException) {
                null
            }
        }
    }
    LaunchedEffect(wallpaperStore, restoreRefresh) {
        try {
            wallpaper = withContext(Dispatchers.IO) { wallpaperStore.load() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notice = "读取自定义壁纸失败，已使用默认背景。" to null
        }
    }
    LaunchedEffect(modelSettings, restoreRefresh) {
        try {
            config = withContext(Dispatchers.IO) { modelSettings.load() }
            modelLoaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notice = "读取模型配置失败，请在模型设置中检查并保存配置。" to "model"
        }
        modelLoadComplete = true
    }
    LaunchedEffect(refresh) {
        try {
            val snapshot = withContext(Dispatchers.IO) { store.books() to store.selectedId }
            books = snapshot.first
            selected = snapshot.second
            loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notice = "读取词书失败，请重试。" to null
        }
    }
    LaunchedEffect(lifecycle, counterRefresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            reviewStatus = SyncingReviewStatus
            while (true) {
                try {
                    val day = clock.today()
                    val (learned, due) = withContext(Dispatchers.IO) {
                        store.seedLegacyReview(day)
                        store.todayCount(day) to store.dueCount(day)
                    }
                    today = learned
                    reviewStatus = "待复习 $due 词（北京时间）"
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    today = 0
                    reviewStatus = "北京时间暂不可用，无法统计待复习词数。"
                } catch (_: Exception) {
                    today = 0
                    reviewStatus = "暂时无法读取复习进度。"
                }
                delay(CounterRefreshInterval)
            }
        }
    }

    val active = books.find { it.id == selected } ?: books.firstOrNull()
    fun beginLearn() {
        if (stack.last() != "home" || actionJob != null) return
        if (!loaded) {
            refresh++
            notice = "正在重新读取词书，请稍后重试。" to null
            return
        }
        val book = active
        if (book == null) {
            notice = "请先在词书管理中导入并选择一本词书。" to "books"
            return
        }
        clearStudySession()
        studyBookId = book.id
        open("learn")
    }

    val route = stack.last()
    val learningBook = books.find { it.id == studyBookId }
    fun searchVocabulary(term: String) {
        if (actionJob != null || preparingLearning) return
        studyError = null
        perform({ studyError = it }) {
            if (!modelLoaded) throw IOException("模型设置尚未就绪，请检查设置后重试。")
            lookupResult = term to lookupVocabulary(config, term)
        }
    }

    fun retryLearning() {
        dedupFailure = null
        preparingLearning = true
        learningRefresh++
    }

    if (route == "learn") {
        LaunchedEffect(studyBookId, learningBook, loaded, modelLoadComplete, learningRefresh) {
            if (!loaded || !modelLoadComplete) return@LaunchedEffect
            val book = learningBook
            if (book == null) {
                learningBatch = LearningBatch(status = "找不到当前词书，请返回首页重新选择。", canRetry = false)
                preparingLearning = false
                return@LaunchedEffect
            }
            val id = book.id
            val attempt = learningRefresh
            preparingLearning = true
            try {
                val indexes = withContext(Dispatchers.IO) {
                    val before = store.pendingLearning(id)
                    ensureActive()
                    before to store.fillLearning(id, prefetch = true)
                }
                if (indexes.first == indexes.second && indexes.second.size <= LEARNING_REFILL_AT && dedupFailure == null) {
                    // Check only the next unchecked slice, never entries already in the queue.
                    val batch = withContext(Dispatchers.IO) { store.pendingDedup(id, prefetch = true) }
                    if (batch != null) {
                        try {
                            val learned = withContext(Dispatchers.IO) { store.learnedTerms() }
                            val duplicates = if (learned.isEmpty()) emptySet() else {
                                if (!modelLoaded) throw IOException()
                                findDuplicates(config, batch.entries, learned)
                            }
                            withContext(Dispatchers.IO) {
                                ensureActive()
                                if (!store.applyDedup(batch, duplicates, prefetch = true)) throw IOException()
                            }
                        } catch (_: IOException) {
                            dedupFailure = "查重未完成，没有跳过任何词。请检查模型配置或网络后重试。"
                        }
                    }
                }
                val failure = dedupFailure
                learningBatch = withContext(Dispatchers.IO) {
                    val entries = store.pendingLearningEntries(id)
                    when {
                        entries.isNotEmpty() -> LearningBatch(entries, failure)
                        failure != null -> LearningBatch(status = failure)
                        store.position(id) >= book.words.size -> LearningBatch(status = "这本词书没有剩余新词。", canRetry = false)
                        else -> LearningBatch(status = "本批没有可学词条。点击继续检查下一批。")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                learningBatch = LearningBatch(learningBatch?.entries.orEmpty(), "读取学习列表失败，请重试。")
            } finally {
                if (studyBookId == id && learningRefresh == attempt) preparingLearning = false
            }
        }
    }

    fun beginReview() {
        if (stack.last() != "home") return
        perform({ if (stack.last() == "home") notice = it to null }) {
            val day = clock.today()
            val due = withContext(Dispatchers.IO) {
                store.seedLegacyReview(day)
                store.dueReviews(day, ReviewBatchSize)
            }
            if (stack.last() != "home") return@perform
            counterRefresh++
            if (due.isEmpty()) {
                notice = "暂无待复习单词（北京时间）。" to null
            } else {
                clearStudySession()
                reviewQueue = ArrayList(due.map { SavedReviewCard(it) })
                open("review")
            }
        }
    }

    val reviewCard = if (route == "review") reviewQueue.getOrNull(current) else null
    var reviewWord by remember(reviewCard) { mutableStateOf<Word?>(null) }
    var reviewReadError by remember(reviewCard) { mutableStateOf<String?>(null) }
    var reviewRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(reviewCard, reviewRetry) {
        val card = reviewCard ?: return@LaunchedEffect
        if (reviewStale || reviewCommitPending) return@LaunchedEffect
        reviewReadError = null
        try {
            reviewWord = withContext(Dispatchers.IO) { store.reviewWord(card.toCard()) }
            if (reviewWord == null) reviewStale = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reviewReadError = "读取复习词条失败，请重试。"
        }
    }

    fun advanceReview() {
        if (current == reviewQueue.lastIndex) {
            back()
            notice = "本轮复习完成！" to null
        } else {
            current++
            answer = ""
            reveal = null
        }
    }

    val corners = roundedCornerInsets()
    BoxWithConstraints(Modifier.fillMaxSize().background(Navy)) {
        val ratio = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 0.5f
        if (route != "books" && route != "model") {
            ForestBackground(wallpaper ?: defaultPhoto, frosted = route != "home")
        }
        Box(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeContent.union(corners)),
            contentAlignment = Alignment.TopCenter,
        ) {
            NavDisplay(
                backStack = stack,
                onBack = ::back,
                modifier = Modifier.widthIn(max = 620.dp).fillMaxSize(),
                entryProvider = { page ->
                    NavEntry(page) {
                        when (page) {
                            "home" -> HomeScreen(
                                today = today,
                                bookName = active?.name,
                                onLearn = ::beginLearn,
                                onReview = ::beginReview,
                                onSettings = {
                                    cancelAction()
                                    open("settings")
                                },
                                reviewStatus = reviewStatus,
                            )
                            "learn" -> {
                                val id = studyBookId
                                val batch = learningBatch
                                val entry = batch?.entries?.firstOrNull()
                                if (batch == null || entry == null) {
                                    SettingsPage {
                                        ScreenHeader("学习", ::back)
                                        Text(when {
                                            preparingLearning || batch == null -> "正在准备学习词条…"
                                            batch.status != null -> batch.status
                                            else -> "本批没有可学词条。"
                                        })
                                        if (!preparingLearning && batch?.canRetry == true) {
                                            TextButton(onClick = ::retryLearning) { Text("继续 / 重试") }
                                        }
                                    }
                                } else {
                                    val (index, word) = entry
                                    fun resolve(discard: Boolean) {
                                        if (stack.last() != "learn" || studyBookId != id || actionJob != null || preparingLearning ||
                                            learningBatch?.entries?.firstOrNull() != entry
                                        ) return
                                        studyError = null
                                        perform({ studyError = it }) {
                                            val day = if (discard) null else clock.today()
                                            val (changed, pending, learnedToday) = withContext(Dispatchers.IO) {
                                                ensureActive()
                                                val resolved = if (day == null) store.discardLearning(id, index)
                                                else store.completeLearning(id, index, day)
                                                Triple(resolved, store.pendingLearning(id), day?.let(store::todayCount))
                                            }
                                            if (learnedToday != null) today = learnedToday
                                            counterRefresh++
                                            val remaining = batch.entries.filter { it.index in pending }
                                            learningBatch = batch.copy(entries = remaining)
                                            // Trigger only after a user action, never from the refill result itself.
                                            if (!changed || pending.size <= LEARNING_REFILL_AT) {
                                                preparingLearning = true
                                                learningRefresh++
                                            }
                                        }
                                    }
                                    StudyScreen(
                                        word = word,
                                        title = "$today/$DailyLearningGoal",
                                        onBack = ::back,
                                        onLookupWord = onLookupWord,
                                        answer = null,
                                        onAnswerChange = {},
                                        feedback = null,
                                        busy = actionJob != null || preparingLearning,
                                        primaryLabel = "下一词",
                                        error = studyError,
                                        workspaceStatus = if (preparingLearning) "正在补充学习队列…" else batch.status,
                                        onWorkspaceRetry = if (!preparingLearning && batch.status != null) ::retryLearning else null,
                                        onModelLookup = { searchVocabulary(word.term) },
                                        onPrimary = { resolve(discard = false) },
                                        onDiscard = {
                                            discardConfirmation = DiscardConfirmation("丢弃此词？它将不再进入学习或复习。") {
                                                resolve(discard = true)
                                            }
                                        },
                                    )
                                }
                            }
                            "review" -> {
                                val card = reviewCard
                                val word = reviewWord
                                val busy = actionJob != null
                                val interrupted = reviewCommitPending && !busy
                                if (interrupted || reviewStale || card == null || word == null) {
                                    SettingsPage {
                                        ScreenHeader("复习", ::back)
                                        Text(when {
                                            interrupted -> "复习进度保存时中断，请返回首页重新读取待复习列表。"
                                            reviewStale || card == null -> StaleReviewMessage
                                            else -> reviewReadError ?: "正在读取复习词条…"
                                        })
                                        if (!interrupted && !reviewStale && card != null && reviewReadError != null) {
                                            TextButton(onClick = { reviewRetry++ }) { Text("重试") }
                                        }
                                    }
                                } else {
                                    val displayedReveal = reveal?.takeIf { it.card == card }
                                    val missingMeaning = word.reviewMeanings.isEmpty()
                                    val prompt = displayedReveal == null && !missingMeaning
                                    fun canAct() = stack.last() == "review" && reviewQueue.getOrNull(current) == card &&
                                        reveal == displayedReveal && !reviewStale && !reviewCommitPending && actionJob == null

                                    suspend fun commitReview(change: () -> Boolean): Boolean {
                                        reviewCommitPending = true
                                        try {
                                            return withContext(Dispatchers.IO) {
                                                ensureActive()
                                                change()
                                            }.also { reviewCommitPending = false }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            reviewCommitPending = false
                                            throw e
                                        }
                                    }

                                    StudyScreen(
                                        word = word,
                                        title = "${current + 1}/${reviewQueue.size}",
                                        onBack = ::back,
                                        onLookupWord = onLookupWord,
                                        answer = if (prompt) answer else null,
                                        onModelLookup = if (prompt) null else ({ searchVocabulary(word.term) }),
                                        onAnswerChange = {
                                            if (canAct()) {
                                                answer = it.take(1000)
                                                studyError = null
                                            }
                                        },
                                        feedback = when (displayedReveal?.correct) {
                                            true -> "回答正确"
                                            false -> "回答不够准确。请查看下方释义和例句；复习计划已重置，明天（北京时间）再复习。"
                                            null -> if (missingMeaning) "此词条没有可判定的释义。本次跳过，不更改复习进度。" else null
                                        },
                                        busy = busy,
                                        primaryLabel = when {
                                            prompt -> "确认"
                                            current == reviewQueue.lastIndex -> "完成"
                                            else -> "下一词"
                                        },
                                        error = studyError,
                                        onPrimary = primary@{
                                            if (!canAct()) return@primary
                                            studyError = null
                                            if (!prompt) {
                                                advanceReview()
                                                return@primary
                                            }
                                            perform({ studyError = it }) {
                                                if (!modelLoaded) {
                                                    throw IOException("模型设置尚未就绪，请检查设置后重试。")
                                                }
                                                val result = judge(config, word, answer.trim())
                                                val day = clock.today()
                                                if (commitReview { store.recordReview(card.toCard(), day, result) }) {
                                                    reveal = SavedReviewReveal(card, result)
                                                } else {
                                                    reviewStale = true
                                                }
                                                counterRefresh++
                                            }
                                        },
                                        onDiscard = {
                                            discardConfirmation = DiscardConfirmation("丢弃此复习词？它将不再安排复习。") {
                                                if (canAct()) {
                                                    studyError = null
                                                    perform({ studyError = it }) {
                                                        val discarded = commitReview {
                                                            store.discardReview(card.toCard(), afterGrade = displayedReveal != null)
                                                        }
                                                        counterRefresh++
                                                        if (discarded) advanceReview() else reviewStale = true
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                            "settings" -> SettingsPage {
                                SettingsScreen(
                                    onBack = ::back,
                                    onBooks = { open("books") },
                                    onWallpaper = { open("wallpaper") },
                                    onModel = { open("model") },
                                    onTranslation = { open("translation") },
                                    onBackup = { open("backup") },
                                )
                            }
                            "books" -> SettingsPage {
                                BooksScreen(books, active?.id, ::back, ::refreshBooks, store)
                            }
                            "wallpaper" -> SettingsPage {
                                WallpaperScreen(wallpaper, ratio, wallpaperStore, { wallpaper = it }, ::back)
                            }
                            "model" -> SettingsPage {
                                ModelScreen(config, modelSettings, { config = it; modelLoaded = true }, ::back)
                            }
                            "translation" -> SettingsPage {
                                ExternalTranslationScreen(::back)
                            }
                            "backup" -> SettingsPage {
                                BackupScreen(store, wallpaperStore, modelSettings, ::onBackupRestored, ::back)
                            }
                        }
                    }
                },
            )
        }
    }
    lookupResult?.let { (term, result) ->
        val dismiss = { lookupResult = null }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(term) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("模型生成的释义，非实时网页搜索，不会修改词书。")
                    result.forEach { Text("(${it.pos}) ${it.definition}") }
                }
            },
            confirmButton = { TextButton(onClick = dismiss) { Text("确定") } },
        )
    }
    discardConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { discardConfirmation = null },
            title = { Text("丢弃当前词条") },
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardConfirmation = null
                        pending.confirm()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("丢弃") }
            },
            dismissButton = { TextButton(onClick = { discardConfirmation = null }) { Text("取消") } },
        )
    }
    notice?.let { (message, destination) ->
        AlertDialog(
            onDismissRequest = { notice = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = {
                    notice = null
                    if (destination != null) {
                        if (stack.last() == "home") open("settings")
                        open(destination)
                    }
                }) { Text(if (destination == null) "确定" else "去设置") }
            },
            dismissButton = if (destination != null) {
                { TextButton(onClick = { notice = null }) { Text("取消") } }
            } else null,
        )
    }
}

@Composable
private fun SettingsPage(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) { content() }
}

@Composable
private fun ForestBackground(bitmap: Bitmap?, frosted: Boolean) {
    Box(Modifier.fillMaxSize().background(Color(0xFF142015))) {
        val imageModifier = Modifier.fillMaxSize().then(
            if (frosted) Modifier.blur(20.dp, BlurredEdgeTreatment.Rectangle) else Modifier,
        )
        if (bitmap == null) {
            Image(painterResource(R.drawable.forest), null, imageModifier, contentScale = ContentScale.Crop)
        } else {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(image, null, imageModifier, contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (frosted) 0.56f else 0.50f)))
    }
}

@Composable
private fun roundedCornerInsets(): WindowInsets {
    val view = LocalView.current
    var corners by remember(view) { mutableStateOf(WindowInsets(0, 0, 0, 0)) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (Build.VERSION.SDK_INT >= 31) {
                val insets = view.rootWindowInsets
                // A quarter circle recedes about 30% of its radius at 45 degrees.
                fun guard(position: Int): Int = ((insets?.getRoundedCorner(position)?.radius ?: 0) * 0.32f).toInt()
                val tl = guard(RoundedCorner.POSITION_TOP_LEFT)
                val tr = guard(RoundedCorner.POSITION_TOP_RIGHT)
                val bl = guard(RoundedCorner.POSITION_BOTTOM_LEFT)
                val br = guard(RoundedCorner.POSITION_BOTTOM_RIGHT)
                corners = WindowInsets(maxOf(tl, bl), maxOf(tl, tr), maxOf(tr, br), maxOf(bl, br))
            }
        }
        listener.onGlobalLayout()
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return corners
}
