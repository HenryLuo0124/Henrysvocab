package com.eng.henrysvocab

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val ScreenInk = Color(0xFFFFFCF5)
private val SecondaryInk = Color(0xFFE5E9DE)
private val ErrorInk = Color(0xFFFFC4BD)
private val GlassRim = Color.White.copy(alpha = 0.36f)
private val GlassPanel = Color(0xFF343D36).copy(alpha = 0.70f)
private val PanelShape = RoundedCornerShape(24.dp)

@Composable
internal fun HomeScreen(
    today: Int,
    bookName: String?,
    onLearn: () -> Unit,
    onReview: () -> Unit,
    onSettings: () -> Unit,
    reviewStatus: String? = null,
) {
    val message = when {
        today < 5 -> "Keep going❤️"
        today < 10 -> "U r awesome👍"
        else -> "Have a rest?🥱"
    }

    StudyViewport { width, _ ->
        Spacer(Modifier.weight(4f))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                modifier = Modifier.semantics { heading() },
                color = ScreenInk,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            if (!bookName.isNullOrBlank()) {
                Text(
                    text = bookName,
                    color = SecondaryInk,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            if (!reviewStatus.isNullOrBlank()) {
                Text(
                    text = reviewStatus,
                    color = SecondaryInk,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.weight(3f))

        val sideBySideWidth = 258.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (width < sideBySideWidth) {
            Column(
                modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeAction("Learn", onLearn, Modifier.fillMaxWidth())
                HomeAction("Review", onReview, Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .widthIn(max = 420.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeAction("Learn", onLearn, Modifier.weight(1f))
                HomeAction("Review", onReview, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        IconButton(
            onClick = onSettings,
            modifier = Modifier.align(Alignment.CenterHorizontally).size(56.dp),
        ) {
            SettingsGear()
        }
    }
}

@Composable
internal fun StudyScreen(
    word: Word,
    title: String,
    onBack: () -> Unit,
    onLookupWord: (String) -> Unit,
    answer: String?,
    onAnswerChange: (String) -> Unit,
    feedback: String?,
    busy: Boolean,
    onPrimary: () -> Unit,
    primaryLabel: String,
    error: String?,
    onDiscard: (() -> Unit)? = null,
    onModelLookup: (() -> Unit)? = null,
    workspaceStatus: String? = null,
    onWorkspaceRetry: (() -> Unit)? = null,
) {
    // Each new word or reveal starts at the top, even after a long entry or answer.
    key(word, title, answer == null) {
        StudyViewport { _, height ->
            ScreenHeader(title = title, onBack = onBack) {
                onDiscard?.let { discard ->
                    IconButton(
                        onClick = discard,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "丢弃当前词条" },
                        enabled = !busy,
                    ) {
                        DiscardGlyph(enabled = !busy)
                    }
                }
            }
            Spacer(Modifier.height((height * 0.085f).coerceIn(16.dp, 56.dp)))
            StudyTerm(term = word.term, onLookupWord = onLookupWord)
            onModelLookup?.let { TextButton(onClick = it, enabled = !busy) { Text("搜索（模型查词）") } }
            Spacer(Modifier.height(16.dp))

            if (answer == null) {
                if (!feedback.isNullOrBlank()) {
                    Text(
                        text = feedback,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = SecondaryInk,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    word.meanings.forEach { meaning ->
                        Text(meaning, color = ScreenInk, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (word.example.isNotBlank()) {
                    Spacer(Modifier.height(20.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = PanelShape,
                        color = GlassPanel,
                        border = BorderStroke(1.dp, GlassRim),
                    ) {
                        Text(
                            text = word.example,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                            color = ScreenInk,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = answer,
                    onValueChange = onAnswerChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Meaning and part of speech") },
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    readOnly = busy,
                    isError = !error.isNullOrBlank(),
                    shape = PanelShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ScreenInk,
                        unfocusedTextColor = ScreenInk,
                        errorTextColor = ScreenInk,
                        focusedContainerColor = GlassPanel,
                        unfocusedContainerColor = GlassPanel,
                        errorContainerColor = GlassPanel,
                        cursorColor = ScreenInk,
                        errorCursorColor = ErrorInk,
                        focusedBorderColor = ScreenInk,
                        unfocusedBorderColor = GlassRim,
                        errorBorderColor = ErrorInk,
                        focusedLabelColor = ScreenInk,
                        unfocusedLabelColor = SecondaryInk,
                        errorLabelColor = ErrorInk,
                    ),
                )
            }

            workspaceStatus?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = SecondaryInk, style = MaterialTheme.typography.bodyMedium)
                onWorkspaceRetry?.let { retry -> TextButton(onClick = retry, enabled = !busy) { Text("重试补充队列") } }
            }

            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = ErrorInk,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.weight(1f))
            FrostedButton(
                label = primaryLabel,
                onClick = onPrimary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .widthIn(min = 144.dp).heightIn(min = 60.dp),
                enabled = !busy,
                busy = busy,
            )
        }
    }
}

// Only join runs of letters/numbers across internal apostrophes or hyphens, not enclosing quotes or dashes.
private val StudyWordPattern = Regex(
    """[\p{L}\p{N}][\p{L}\p{M}\p{N}]*(?:['’\u2010\u2011-][\p{L}\p{N}][\p{L}\p{M}\p{N}]*)*""",
)

internal fun studyWordMatches(term: String): Sequence<MatchResult> = StudyWordPattern.findAll(term)

@Composable
private fun StudyTerm(term: String, onLookupWord: (String) -> Unit) {
    val context = LocalContext.current
    val lookupWord = rememberUpdatedState(onLookupWord)
    val linkedTerm = remember(term, context) {
        val linkStyles = TextLinkStyles(
            style = SpanStyle(color = Color(0xFFC8F1D6), textDecoration = TextDecoration.Underline),
            focusedStyle = SpanStyle(background = Color(0xFF295042)),
            pressedStyle = SpanStyle(background = Color(0xFF295042)),
        )
        buildAnnotatedString {
            append(term)
            studyWordMatches(term).forEach { match ->
                val word = match.value
                addLink(
                    LinkAnnotation.Clickable(tag = word, styles = linkStyles) {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("单词", word))
                        lookupWord.value(word)
                    },
                    start = match.range.first,
                    end = match.range.last + 1,
                )
            }
        }
    }
    Text(
        text = linkedTerm,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.SemiBold,
        color = ScreenInk,
    )
    if (linkedTerm.getLinkAnnotations(0, linkedTerm.length).isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text("点按单词可复制查词", color = SecondaryInk, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun GlassButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FrostedButton(label, onClick, modifier, enabled)
}

@Composable
internal fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    action: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            BackChevron()
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f).semantics { heading() },
            color = ScreenInk,
            style = MaterialTheme.typography.titleMedium,
        )
        action()
    }
}

@Composable
private fun StudyViewport(content: @Composable ColumnScope.(width: Dp, height: Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 360.dp) 16.dp else 24.dp
        val contentWidth = (maxWidth - (horizontalPadding * 2)).coerceAtLeast(0.dp)
        // Capture the bounded viewport before scrolling; surplus space goes to weighted spacers.
        val viewportHeight = if (constraints.hasBoundedHeight) maxHeight else 0.dp
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier.fillMaxWidth()
                .then(if (constraints.hasBoundedHeight) Modifier.verticalScroll(scrollState) else Modifier)
                .heightIn(min = viewportHeight)
                .padding(horizontal = horizontalPadding, vertical = 12.dp),
        ) {
            content(contentWidth, viewportHeight)
        }
    }
}

@Composable
private fun HomeAction(label: String, onClick: () -> Unit, modifier: Modifier) {
    FrostedButton(
        label = label,
        onClick = onClick,
        modifier = modifier.heightIn(min = 68.dp),
        light = true,
    )
}

@Composable
private fun FrostedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    light: Boolean = false,
    busy: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp)
            .then(if (busy) Modifier.semantics { stateDescription = "Checking answer" } else Modifier),
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(1.dp, GlassRim),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (light) Color(0xFFDDE0D6).copy(alpha = 0.78f) else GlassPanel,
            contentColor = if (light) Color(0xFF141B16) else ScreenInk,
            disabledContainerColor = GlassPanel,
            disabledContentColor = SecondaryInk,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp).size(18.dp),
                color = SecondaryInk,
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = label,
            textAlign = TextAlign.Center,
            style = if (light) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
        )
    }
}

// Small vector glyphs keep the screens independent of the optional Material icons artifact.
@Composable
private fun BackChevron() {
    Canvas(Modifier.size(24.dp).semantics { contentDescription = "Back" }) {
        val leading = if (layoutDirection == LayoutDirection.Ltr) 0.34f else 0.66f
        val trailing = 1f - leading
        val chevron = Path().apply {
            moveTo(size.width * trailing, size.height * 0.23f)
            lineTo(size.width * leading, size.height * 0.50f)
            lineTo(size.width * trailing, size.height * 0.77f)
        }
        drawPath(
            chevron,
            color = ScreenInk,
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun DiscardGlyph(enabled: Boolean) {
    val red = Color(0xFFFF7775)
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val trash = Path().apply {
            moveTo(3 * unit, 6 * unit)
            lineTo(21 * unit, 6 * unit)
            moveTo(9 * unit, 6 * unit)
            lineTo(9 * unit, 3 * unit)
            lineTo(15 * unit, 3 * unit)
            lineTo(15 * unit, 6 * unit)
            moveTo(5 * unit, 6 * unit)
            lineTo(6.5f * unit, 21 * unit)
            lineTo(17.5f * unit, 21 * unit)
            lineTo(19 * unit, 6 * unit)
            moveTo(10 * unit, 10 * unit)
            lineTo(10 * unit, 17 * unit)
            moveTo(14 * unit, 10 * unit)
            lineTo(14 * unit, 17 * unit)
        }
        drawPath(
            trash,
            color = red.copy(alpha = if (enabled) 1f else 0.38f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun SettingsGear() {
    Canvas(Modifier.size(28.dp).semantics { contentDescription = "Settings" }) {
        val radius = size.minDimension / 2f
        val origin = center
        val gear = Path().apply {
            fillType = PathFillType.EvenOdd
            repeat(32) { vertex ->
                val angle = (vertex - 1) * PI / 16
                val depth = if (vertex % 4 in 1..2) 0.97f else 0.76f
                val point = origin + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * (radius * depth)
                if (vertex == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            close()
        }
        val hole = radius * 0.35f
        gear.addOval(androidx.compose.ui.geometry.Rect(origin, hole))
        drawPath(gear, ScreenInk)
    }
}

@Preview(name = "Home • compact", widthDp = 320, heightDp = 600)
@Composable
private fun CompactHomePreview() {
    HomePreview(today = 3)
}

@Preview(name = "Home • wide", widthDp = 840, heightDp = 560)
@Composable
private fun WideHomePreview() {
    HomePreview(today = 8)
}

@Composable
private fun HomePreview(today: Int) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.forest),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.50f)))
            Box(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                HomeScreen(
                    today = today,
                    bookName = "Everyday English",
                    onLearn = {},
                    onReview = {},
                    onSettings = {},
                    reviewStatus = "待复习 8 词（北京时间）",
                )
            }
        }
    }
}
