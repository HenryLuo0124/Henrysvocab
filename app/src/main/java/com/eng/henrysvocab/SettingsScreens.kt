package com.eng.henrysvocab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.io.IOException
import java.io.InputStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LEARNED_WORDS_FILENAME = "learned_words.txt"

private val SettingsMuted = Color(0xFFDCE5F5)
private val SettingsGlass = Color(0xA6243349)
private val SettingsShape = RoundedCornerShape(24.dp)
private val SettingsPalette = darkColorScheme(
    primary = Color(0xFFC2D8FF),
    onPrimary = Color(0xFF0B203D),
    surface = Color(0xFF111D32),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF26344A),
    onSurfaceVariant = SettingsMuted,
    error = Color(0xFFFFB4AB),
)

@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    onBooks: () -> Unit,
    onWallpaper: () -> Unit,
    onModel: () -> Unit,
    onTranslation: () -> Unit,
    onBackup: () -> Unit,
) {
    SettingsLayout("设置", onBack) {
        SettingsNote("管理词书、自定义背景、配置模型服务、选择外部翻译应用及管理备份。")
        GlassButton("词书管理", onBooks, Modifier.fillMaxWidth())
        GlassButton("背景壁纸", onWallpaper, Modifier.fillMaxWidth())
        GlassButton("模型配置", onModel, Modifier.fillMaxWidth())
        GlassButton("外部翻译", onTranslation, Modifier.fillMaxWidth())
        GlassButton("备份与恢复", onBackup, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun ExternalTranslationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { ExternalTranslationPreferences(context) }
    val targets = remember { listOf(ExternalTranslator.BAIDU, ExternalTranslator.YOUDAO) }
    var selected by remember(preferences) { mutableStateOf(preferences.selected) }
    var installed by remember(preferences) {
        mutableStateOf(targets.associateWith { preferences.isInstalled(it) })
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        installed = targets.associateWith { preferences.isInstalled(it) }
    }

    SettingsLayout("外部翻译", onBack) {
        SettingsPanel {
            SettingsNote("选择要使用的外部翻译应用。")
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (target in targets) {
                    SettingsChoice(
                        label = target.label,
                        detail = if (installed.getValue(target)) "已安装"
                        else "未安装。使用此选项时，只会将待翻译文本复制到剪贴板。",
                        selected = selected == target,
                        enabled = true,
                        onSelect = {
                            preferences.selected = target
                            selected = target
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun BooksScreen(
    books: List<Book>,
    selectedId: String?,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    store: VocabStore,
) {
    val context = LocalContext.current.applicationContext
    val action = rememberSettingsOperation()
    val changed by rememberUpdatedState(onChanged)
    var editing by remember { mutableStateOf<Book?>(null) }
    var pendingDeletion by remember { mutableStateOf<Book?>(null) }
    val learnedPath = remember(context) { AppDataDirectory(context).file(LEARNED_WORDS_FILENAME).absolutePath }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) action.run(
            progress = "正在导出已学词表…",
            failureMessage = "无法导出已学词表，请选择其他位置重试。目标位置可能留有不完整的文件。",
            work = { withContext(Dispatchers.IO) { exportLearnedWords(context, store, uri) } },
        ) { action.status = it }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            action.run(
                progress = "正在导入词书…",
                failureMessage = "无法导入词书，请检查文件后重试。",
                errorDetail = { failure ->
                    when (failure) {
                        is IllegalArgumentException, is IOException -> failure.message?.takeIf { it.startsWith("Line ") }
                        else -> null
                    }
                },
                work = { withContext(Dispatchers.IO) { importVocabulary(context, store, uri) } },
            ) { (_, name, words) ->
                action.status = "已导入《$name》（${words.size} 个单词）。"
                changed()
            }
        }
    }

    SettingsLayout("词书管理", onBack, action.busy) {
        SettingsPanel {
            Text("已学词表", style = MaterialTheme.typography.titleMedium)
            SettingsNote("应用私有文件的绝对路径：")
            SelectionContainer {
                Text(learnedPath, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("已学词表路径", learnedPath))
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, "已复制路径。", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !action.busy,
            ) { Text("复制路径") }
            SettingsNote("Android 文件管理器无法访问此应用私有文件。要在文件管理器中查看，可导出最新词表，在系统保存界面选择「下载（Downloads）」或其他位置。")
            SettingsNote("导出为 UTF-8 文本，每行一个已学词条，不含释义或 API 密钥。")
            GlassButton("导出已学词表", {
                action.openPicker("无法打开系统保存界面。") { exportPicker.launch(LEARNED_WORDS_FILENAME) }
            }, Modifier.fillMaxWidth(), enabled = !action.busy)
        }
        SettingsMessages(action)
        SettingsPanel {
            Text("导入词书", style = MaterialTheme.typography.titleMedium)
            SettingsNote("选择 UTF-8 文本文件，每行一个词条，格式：term/(meaning1/meaning2)/example")
            GlassButton("选择文本文件", {
                action.openPicker("无法打开文件选择器。") { picker.launch(arrayOf("text/plain")) }
            }, Modifier.fillMaxWidth(), enabled = !action.busy)
        }
        Text("我的词书", style = MaterialTheme.typography.titleMedium)
        if (books.isEmpty()) {
            SettingsNote("还没有词书。导入一个文本文件即可开始学习。")
        } else {
            SettingsNote("模型查重默认关闭，需按词书开启。开启后，查重会通过 HTTPS 将每批最多 $LEARNING_BATCH_SIZE 个候选词条和已学词条历史发送给你在模型配置中指定的服务商。")
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (book in books) key(book.id) {
                val active = book.id == selectedId
                SettingsPanel {
                    SettingsChoice(book.name, "${book.words.size} 个单词" + if (active) " · 当前词书" else "",
                        active, !action.busy, onSelect = {
                            if (!active) action.run("正在切换词书…", "无法切换词书，请重试。", work = {
                                withContext(Dispatchers.IO) { store.selectedId = book.id }
                            }) { changed() }
                        }, onEdit = { editing = book }, editLabel = "重命名")
                    BookDedupToggle(book.id, store, action, onChanged = changed)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { pendingDeletion = book },
                            modifier = Modifier.heightIn(min = 48.dp),
                            enabled = !action.busy,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("删除词书", Modifier.clearAndSetSemantics {
                                contentDescription = "删除词书：${book.name}"
                            })
                        }
                    }
                }
            }
        }
    }

    editing?.let { book ->
        SettingsNameDialog("重命名词书", "词书名称", book.name, 120, { editing = null }) { name ->
            editing = null
            if (name != book.name) action.run("正在重命名…", "无法重命名词书，请重试。", work = {
                withContext(Dispatchers.IO) { store.rename(book.id, name) }
            }) { changed() }
        }
    }

    pendingDeletion?.let { book ->
        val cancel = { pendingDeletion = null }
        SettingsColors {
            AlertDialog(
                onDismissRequest = cancel,
                title = { Text("删除词书？") },
                text = {
                    Text("将删除《${book.name}》在应用中的副本，并清空该词书当前的待学习队列。原始文件不受影响，已学词条和复习记录将保留。此操作无法撤销。")
                },
                confirmButton = {
                    TextButton(
                        enabled = !action.busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            pendingDeletion = null
                            action.run("正在删除词书…", "无法删除词书，请重试。", work = {
                                withContext(Dispatchers.IO) {
                                    check(store.deleteBook(book.id))
                                }
                            }) {
                                action.status = "已删除《${book.name}》。"
                                changed()
                            }
                        },
                    ) { Text("确认删除") }
                },
                dismissButton = { TextButton(onClick = cancel) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun BookDedupToggle(
    bookId: String,
    store: VocabStore,
    action: SettingsOperation,
    onChanged: () -> Unit,
) {
    var checked by remember(store, bookId) { mutableStateOf<Boolean?>(null) }
    var loadFailed by remember(store, bookId) { mutableStateOf(false) }
    var loadAttempt by remember(store, bookId) { mutableIntStateOf(0) }
    LaunchedEffect(store, bookId, loadAttempt) {
        try {
            checked = withContext(Dispatchers.IO) { store.dedupEnabled(bookId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    val current = checked
    if (current == null) {
        SettingsNote(if (loadFailed) "无法读取模型查重设置。" else "正在读取模型查重设置…", error = loadFailed)
        if (loadFailed) GlassButton("重试", {
            loadFailed = false
            loadAttempt++
        }, enabled = !action.busy)
    } else {
        val enabled = !action.busy
        Row(
            modifier = Modifier.fillMaxWidth().clip(SettingsShape).heightIn(min = 48.dp)
                .toggleable(current, enabled = enabled, role = Role.Switch, onValueChange = { next ->
                    action.run("正在更新模型查重设置…", "无法更新模型查重设置，请重试。", work = {
                        withContext(Dispatchers.IO) { store.setDedupEnabled(bookId, next) }
                    }) {
                        checked = next
                        action.status = if (next) "已开启模型查重。" else "已关闭模型查重。"
                        onChanged()
                    }
                })
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("模型查重")
            Switch(checked = current, onCheckedChange = null, enabled = enabled)
        }
    }
}

private fun exportLearnedWords(context: Context, store: VocabStore, uri: Uri): String {
    val terms = store.learnedTerms()
    val resolver = context.contentResolver
    val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open export document")
    output.bufferedWriter(Charsets.UTF_8).use { writer ->
        for (term in terms) {
            writer.write(term.replace('\r', ' ').replace('\n', ' '))
            writer.write("\n")
        }
    }

    // Metadata is optional; not all providers reveal the document's name or parent folder.
    val displayName = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex < 0 || !cursor.moveToFirst()) return@use null
            cursor.getString(nameIndex)?.takeIf(String::isNotBlank)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    val inDownloads = uri.authority == "com.android.externalstorage.documents" &&
        DocumentsContract.isDocumentUri(context, uri) &&
        DocumentsContract.getDocumentId(uri).startsWith("primary:${Environment.DIRECTORY_DOWNLOADS}/")
    val destination = if (inDownloads) "「下载（Downloads）」" else "你选择的位置"
    val document = displayName?.let { "《$it》" }.orEmpty()
    return "已导出$document（${terms.size} 个词条），已保存到$destination。"
}

private fun importVocabulary(context: Context, store: VocabStore, uri: Uri): Book {
    val resolver = context.contentResolver
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    val name = displayName.orEmpty().substringBeforeLast('.').trim().take(120).ifBlank { "导入的词书" }
    val decoder = Charsets.UTF_8.newDecoder()
    val words = resolver.openInputStream(uri)?.let { InputStreamReader(it, decoder).use(::parseWords) }
        ?: throw IOException("Line 1: Could not open vocabulary file")
    require(words.isNotEmpty()) { "Line 1: The vocabulary file contains no entries" }
    return store.add(name, words)
}

@Composable
internal fun WallpaperScreen(
    current: Bitmap?,
    aspectRatio: Float,
    store: WallpaperStore,
    onChanged: (Bitmap?) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val action = rememberSettingsOperation()
    val changed by rememberUpdatedState(onChanged)
    var editing by remember { mutableStateOf<Bitmap?>(null) }
    val validRatio = aspectRatio.isFinite() && aspectRatio > 0f
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) action.run("正在读取照片…", "无法读取这张照片，请选择另一张。", work = {
            loadPhoto(context, uri)
        }) { editing = it }
    }
    val back = {
        if (editing != null) {
            editing = null
            action.clear()
        } else {
            onBack()
        }
    }
    BackHandler(enabled = editing != null && !action.busy, onBack = back)

    SettingsLayout("背景壁纸", back, action.busy) {
        SettingsMessages(action)
        val photo = editing
        if (photo != null && validRatio) {
            SettingsPanel {
                Text("裁剪照片", style = MaterialTheme.typography.titleMedium)
                CropWallpaper(photo, aspectRatio, onSave = { crop ->
                    action.run("正在保存壁纸…", "无法保存壁纸，你可以重试。", work = {
                        withContext(Dispatchers.IO) { store.save(crop) }
                    }) {
                        editing = null
                        action.status = "已更新壁纸。"
                        changed(crop)
                    }
                }) { if (!action.busy) back() }
            }
        } else {
            SettingsPanel {
                Text(if (current == null) "默认壁纸" else "自定义壁纸", style = MaterialTheme.typography.titleMedium)
                WallpaperSettingsPreview(current, if (validRatio) aspectRatio else 0.6f)
                SettingsNote("选择一张照片并裁剪到屏幕比例。保存的壁纸仅存储在此设备上。")
                if (!validRatio) SettingsNote("当前无法获取屏幕比例，暂不能裁剪照片。", error = true)
                GlassButton("选择照片", {
                    action.openPicker("无法打开照片选择器。") {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }, Modifier.fillMaxWidth(), enabled = validRatio && !action.busy)
                if (current != null) GlassButton("恢复默认壁纸", {
                    action.run("正在恢复默认壁纸…", "无法恢复默认壁纸，请重试。", work = {
                        withContext(Dispatchers.IO) { store.reset() }
                    }) {
                        action.status = "已恢复默认壁纸。"
                        changed(null)
                    }
                }, Modifier.fillMaxWidth(), enabled = !action.busy)
            }
        }
    }
}

@Composable
private fun WallpaperSettingsPreview(bitmap: Bitmap?, aspectRatio: Float) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth, 300.dp, 240.dp * aspectRatio).coerceAtLeast(1.dp)
        val preview = Modifier.size(width, (width / aspectRatio).coerceIn(1.dp, 240.dp)).clip(SettingsShape)
        if (bitmap == null) {
            Image(painterResource(defaultWallpaperResId), "默认壁纸预览", preview, contentScale = ContentScale.Crop)
        } else {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(image, "自定义壁纸预览", preview, contentScale = ContentScale.Crop)
        }
    }
}

internal object ModelCatalog {
    const val MAX_ID_LENGTH = 128
    const val MAX_IDS = 32
    private const val PREFS = "review_model_catalog"
    private const val KEY = "model_ids"

    fun isValidId(id: String): Boolean = id.length in 1..MAX_ID_LENGTH && id.all { it in '!'..'~' }

    fun load(context: Context): Set<String>? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(KEY, null)?.toSet()

    fun save(context: Context, ids: Set<String>?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (ids == null) edit.remove(KEY) else edit.putStringSet(KEY, ids)
        check(edit.commit()) { "Could not save model catalog" }
    }
}

@Composable
internal fun ModelScreen(
    config: ModelConfig,
    settings: ModelSettings,
    onChanged: (ModelConfig) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val defaults = remember { ModelConfig() }
    val action = rememberSettingsOperation()
    val changed by rememberUpdatedState(onChanged)
    val initialModel = config.model.trim().takeIf(String::isNotEmpty) ?: defaults.model
    var active by remember(initialModel) { mutableStateOf(initialModel) }
    var providerName by remember(config.name) { mutableStateOf(config.name) }
    var baseUrl by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var path by remember(config.path) { mutableStateOf(config.path) }
    // Do not place the key or its visibility in the saved-instance-state Bundle.
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var showKey by remember(config.apiKey) { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<List<String>?>(null) }
    var catalogError by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<ModelIdEditor?>(null) }
    LaunchedEffect(context, loadAttempt) {
        try {
            catalog = withContext(Dispatchers.IO) {
                ModelCatalog.load(context)?.sorted() ?: listOf(defaults.model)
            }
            catalogError = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            catalogError = true
        }
    }
    val modelIds = catalog?.let { (it + active).distinct().sorted() }
    val validKey = apiKey.trim().all { it in '!'..'~' }
    var providerModels by remember(config.name, config.baseUrl, config.path, config.apiKey) {
        mutableStateOf<ProviderModelsState?>(null)
    }
    var fetchedSelection by remember(config) { mutableStateOf<String?>(null) }
    val selectedModel = fetchedSelection ?: active
    val providerEdited = {
        // Invalidate immediately, including before recomposition cancels an in-flight lookup.
        providerModels = null
        fetchedSelection = null
        action.clear()
    }
    val request = providerModels as? ProviderModelsState.Loading
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        val result = try {
            val ids = fetchProviderModels(request.draft).filter(ModelCatalog::isValidId).distinct().sorted()
            ProviderModelsState.Ready(ids)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never show exception messages, URLs, keys, or server error bodies.
            ProviderModelsState.Failed
        }
        if (providerModels === request) providerModels = result
    }

    SettingsLayout("模型配置", onBack, action.busy) {
        SettingsPanel {
            Text("OpenAI 兼容接口（HTTPS）", style = MaterialTheme.typography.titleMedium)
            SettingsNote("请填写可信服务商提供的 API 基础网址和聊天补全路径。")
            SettingsField(providerName, { if (providerName != it) { providerName = it; providerEdited() } },
                "服务商名称", enabled = !action.busy)
            SettingsField(baseUrl, { if (baseUrl != it) { baseUrl = it; providerEdited() } },
                "API 基础网址（HTTPS）", enabled = !action.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            SettingsField(path, { if (path != it) { path = it; providerEdited() } },
                "API 路径", enabled = !action.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            SettingsField(apiKey, { if (apiKey != it) { apiKey = it; providerEdited() } },
                "API 密钥", enabled = !action.busy,
                isError = !validKey,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
                })
            SettingsNote(if (validKey) "保存时，留空将清除已存储的密钥。" else "密钥不能包含空格或不可见字符。",
                error = !validKey)
        }
        SettingsPanel {
            Text("复习模型", style = MaterialTheme.typography.titleMedium)
            SettingsNote("可使用上方填写的服务商设置获取模型，也可手动添加模型标识。点击「保存配置」后，服务商设置和所选模型一同生效。")
            GlassButton("获取模型列表", {
                if (providerModels is ProviderModelsState.Loading || action.busy) return@GlassButton
                action.clear()
                val draft = ModelConfig(
                    apiKey = apiKey.trim(), model = fetchedSelection ?: active,
                    baseUrl = baseUrl.trim(), path = path.trim(), name = providerName.trim(),
                )
                providerModels = if (modelEndpoint(draft) == null) {
                    ProviderModelsState.InvalidEndpoint
                } else {
                    ProviderModelsState.Loading(draft)
                }
            }, Modifier.fillMaxWidth(), enabled = validKey && !action.busy && request == null)
            fetchedSelection?.let { SettingsNote("所选模型（未保存）：$it") }
            when (val result = providerModels) {
                null -> Unit
                ProviderModelsState.InvalidEndpoint -> ProviderModelMessage("请检查 HTTPS API 基础网址和 API 路径。", error = true)
                is ProviderModelsState.Loading -> ProviderModelMessage("正在获取模型列表…")
                ProviderModelsState.Failed -> ProviderModelMessage(
                    "无法获取模型列表。请检查网络、API 地址和密钥，或手动添加模型。", error = true,
                )
                is ProviderModelsState.Ready -> key(result) {
                    ProviderModelResults(result.ids, selectedModel, enabled = !action.busy) { id ->
                        if (providerModels === result && !action.busy) {
                            fetchedSelection = id
                            action.clear()
                        }
                    }
                }
            }
            Text("本地模型", style = MaterialTheme.typography.titleSmall)
            when {
                catalogError -> {
                    SettingsNote("无法读取本地模型列表。", error = true)
                    GlassButton("重试", { catalogError = false; loadAttempt++ }, Modifier.fillMaxWidth())
                }
                modelIds == null -> SettingsNote("正在读取本地模型列表…")
                else -> {
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (id in modelIds) key(id) {
                            SettingsChoice(id, if (id == selectedModel) "当前选择" else null,
                                id == selectedModel, !action.busy,
                                onSelect = { active = id; fetchedSelection = null; action.clear() },
                                onEdit = { editor = ModelIdEditor(id) })
                        }
                    }
                    GlassButton("添加模型", { editor = ModelIdEditor(null) }, Modifier.fillMaxWidth(),
                        enabled = !action.busy && modelIds.size < ModelCatalog.MAX_IDS)
                    if (modelIds.size >= ModelCatalog.MAX_IDS) SettingsNote("已达到模型列表上限。")
                }
            }
        }
        SettingsPanel {
            Text("模型请求会发送哪些数据？", style = MaterialTheme.typography.titleMedium)
            SettingsNote("判定复习答案时，应用会通过 HTTPS 向配置的服务商发送当前单词、释义、例句、你输入的答案、模型标识和判定指令。对已开启模型查重的词书，查重时会发送每批最多 $LEARNING_BATCH_SIZE 个候选词条和已学词条历史。API 密钥随请求用于身份验证。")
            SettingsNote("每次都是新的无状态请求，不会延续之前的对话。这不代表服务商不会记录或保留数据；请查看其隐私和数据保留政策。")
        }
        SettingsMessages(action)
        GlassButton("保存配置", {
            val savedIds = catalog ?: return@GlassButton
            val model = fetchedSelection ?: active
            // The active model is stored in the config even if it isn't eligible for the catalog.
            val ids = if (savedIds.size < ModelCatalog.MAX_IDS && ModelCatalog.isValidId(model)) {
                (savedIds + model).toSet()
            } else {
                savedIds.toSet()
            }
            val next = config.copy(
                apiKey = apiKey.trim(), model = model, baseUrl = baseUrl.trim(), path = path.trim(),
                name = providerName.trim(),
            )
            showKey = false
            action.run(
                progress = "正在保存配置…",
                failureMessage = "未能保存全部模型配置，请重试。",
                errorDetail = { failure ->
                    if (failure is IllegalArgumentException) "请检查服务商名称、HTTPS 基础网址、API 路径和模型标识。" else null
                },
                work = {
                    withContext(Dispatchers.IO) {
                        // Validate and save the config before updating the separate ID catalog.
                        settings.save(next)
                        ModelCatalog.save(context, ids)
                    }
                },
            ) {
                catalog = ids.sorted()
                active = next.model
                fetchedSelection = null
                action.status = "已保存模型配置。"
                changed(next)
            }
        }, Modifier.fillMaxWidth(), enabled = modelIds != null && validKey && !action.busy)
    }

    editor?.let { edit ->
        SettingsNameDialog(if (edit.original == null) "添加模型" else "编辑模型", "模型标识",
            edit.original.orEmpty(), ModelCatalog.MAX_ID_LENGTH, onDismiss = { editor = null }, validate = { id ->
                when {
                    !ModelCatalog.isValidId(id) -> "模型标识不能包含空格或不可见字符。"
                    id != edit.original && modelIds?.contains(id) == true -> "此模型已在列表中。"
                    else -> null
                }
            }) { id ->
            val retained = catalog.orEmpty().filterNot { it == edit.original }
            // A restored active model can sit outside a full catalog; editing it need not add a row.
            if (retained.size < ModelCatalog.MAX_IDS) catalog = retained + id
            if (active == edit.original) active = id
            if (fetchedSelection != null && fetchedSelection == edit.original && fetchedSelection != id) {
                active = id
                fetchedSelection = null
            }
            action.clear()
            editor = null
        }
    }
}

private data class ModelIdEditor(val original: String?)

private sealed interface ProviderModelsState {
    class Loading(val draft: ModelConfig) : ProviderModelsState
    class Ready(val ids: List<String>) : ProviderModelsState
    object InvalidEndpoint : ProviderModelsState
    object Failed : ProviderModelsState
}

private const val PROVIDER_MODELS_PAGE_SIZE = 8

@Composable
private fun ProviderModelResults(ids: List<String>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    if (ids.isEmpty()) {
        ProviderModelMessage("服务商未返回可用的模型，可手动添加模型。")
        return
    }
    var query by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    val matches = remember(ids, query) {
        val filter = query.trim()
        if (filter.isEmpty()) ids else ids.filter { it.contains(filter, ignoreCase = true) }
    }
    SettingsField(query, { query = it; page = 0 }, "搜索服务商模型", enabled = enabled)
    if (matches.isEmpty()) {
        ProviderModelMessage("没有匹配的模型。")
        return
    }
    val start = page * PROVIDER_MODELS_PAGE_SIZE
    val end = minOf(start + PROVIDER_MODELS_PAGE_SIZE, matches.size)
    val lastPage = (matches.size - 1) / PROVIDER_MODELS_PAGE_SIZE
    ProviderModelMessage("显示第 ${start + 1}–$end 项，共 ${matches.size} 项")
    if (lastPage > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = enabled && page > 0) {
                Text("上一页")
            }
            TextButton(onClick = { page = (page + 1).coerceAtMost(lastPage) }, enabled = enabled && page < lastPage) {
                Text("下一页")
            }
        }
    }
    // The settings screen already scrolls vertically; compose only the current page inline.
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (index in start until end) {
            val id = matches[index]
            key(id) {
                SettingsChoice(id, null, id == selected, enabled, onSelect = { onSelect(id) })
            }
        }
    }
}

@Composable
private fun ProviderModelMessage(text: String, error: Boolean = false) {
    SettingsNote(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, error = error)
}

@Composable
private fun SettingsLayout(title: String, onBack: () -> Unit, busy: Boolean = false, content: @Composable () -> Unit) {
    SettingsColors {
        BackHandler(enabled = busy) { /* Keep the screen alive until its write completes. */ }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenHeader(title, onBack = { if (!busy) onBack() })
            content()
        }
    }
}

@Composable
private fun SettingsColors(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SettingsPalette) {
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}

@Composable
private fun SettingsPanel(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = SettingsShape, color = SettingsGlass, contentColor = Color.White) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun SettingsChoice(
    label: String,
    detail: String?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)? = null,
    editLabel: String = "编辑",
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.weight(1f).clip(SettingsShape).heightIn(min = 48.dp)
                .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected, onClick = null, enabled = enabled)
            Column {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                detail?.let { SettingsNote(it) }
            }
        }
        if (onEdit != null) GlassButton(editLabel, onEdit, enabled = enabled)
    }
}

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), shape = CircleShape, singleLine = true,
        enabled = enabled, isError = isError, keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation, trailingIcon = trailingIcon,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White.copy(alpha = 0.08f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
            unfocusedBorderColor = SettingsMuted.copy(alpha = 0.48f),
        ),
    )
}

@Composable
private fun SettingsNameDialog(
    title: String,
    label: String,
    initial: String,
    maxLength: Int,
    onDismiss: () -> Unit,
    validate: (String) -> String? = { null },
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    val name = value.trim()
    val error = when {
        name.length > maxLength -> "不能超过 $maxLength 个字符。"
        name.isNotEmpty() -> validate(name)
        else -> null
    }
    SettingsColors {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsField(value, { value = it }, label, isError = error != null)
                    error?.let { SettingsNote(it, error = true) }
                }
            },
            confirmButton = { GlassButton("确认", { onConfirm(name) }, enabled = name.isNotEmpty() && error == null) },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingsNote(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(text, modifier = modifier, color = if (error) MaterialTheme.colorScheme.error else SettingsMuted,
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SettingsMessages(action: SettingsOperation) {
    val message = action.error ?: action.status ?: return
    SettingsNote(message, error = action.error != null,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun rememberSettingsOperation(): SettingsOperation {
    val scope = rememberCoroutineScope()
    return remember(scope) { SettingsOperation(scope) }
}

private class SettingsOperation(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<String?>(null)

    fun clear() {
        error = null
        status = null
    }

    fun openPicker(failureMessage: String, launch: () -> Unit) {
        if (busy) return
        clear()
        try {
            launch()
        } catch (_: RuntimeException) {
            error = failureMessage
        }
    }

    fun <T> run(
        progress: String,
        failureMessage: String,
        errorDetail: (Exception) -> String? = { null },
        work: suspend () -> T,
        onSuccess: (T) -> Unit,
    ) {
        if (busy) return
        busy = true
        error = null
        status = progress
        scope.launch {
            val result = try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = errorDetail(failure)?.let { "$failureMessage\n$it" } ?: failureMessage
                return@launch
            } finally {
                busy = false
                status = null
            }
            onSuccess(result)
        }
    }
}
