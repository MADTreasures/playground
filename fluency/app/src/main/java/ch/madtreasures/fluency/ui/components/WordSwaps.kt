package ch.madtreasures.fluency.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ch.madtreasures.fluency.words.WordGroups
import ch.madtreasures.fluency.words.WordSwap
import kotlinx.coroutines.delay

/** a marker pen: the word that a touched button would replace, and the touched button (same in light and dark mode) */
private val Mark = Color(0xFFFFCA28)
private val OnMark = Color(0xFF241A00)

/**
 * The marks in a translation: [preview] while a replace button is touched (the word it would
 * replace), [flash] for a moment after a replacement (the new word).
 */
@Stable
class SwapHighlight {
    var preview by mutableStateOf<IntRange?>(null)
    var flash by mutableStateOf<IntRange?>(null)
    internal var flashOn by mutableStateOf(false)
}

@Composable
fun rememberSwapHighlight(): SwapHighlight {
    val h = remember { SwapHighlight() }
    LaunchedEffect(h.flash) {
        if (h.flash == null) return@LaunchedEffect
        h.flashOn = true
        delay(1_200)
        h.flashOn = false
        delay(500)
        h.flash = null
    }
    return h
}

/** [text] with the marks of [h]: the word to be replaced in colour, bold and underlined */
@Composable
fun highlighted(text: String, h: SwapHighlight): AnnotatedString {
    val flashAlpha by animateFloatAsState(if (h.flashOn) 1f else 0f, tween(if (h.flashOn) 150 else 450), label = "flash")
    val preview = h.preview?.takeIf { it.last < text.length }
    val flash = h.flash?.takeIf { it.last < text.length && flashAlpha > 0f }
    if (preview == null && flash == null) return AnnotatedString(text)
    val c = MaterialTheme.colorScheme
    return buildAnnotatedString {
        append(text)
        if (flash != null) addStyle(SpanStyle(background = c.primaryContainer.copy(alpha = flashAlpha)), flash.first, flash.last + 1)
        if (preview != null) {
            addStyle(
                SpanStyle(background = Mark, color = OnMark, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
                preview.first, preview.last + 1,
            )
        }
    }
}

/**
 * The replacements for a translation, one button each ("news → messages · Mitteilungen").
 * Touching a button marks the word in the translation ([highlight]), releasing it replaces the
 * word; sliding the finger off the button changes nothing.
 */
@Composable
fun SwapChips(swaps: List<WordSwap>, highlight: SwapHighlight, onReplace: (WordSwap) -> Unit, modifier: Modifier = Modifier) {
    if (swaps.isEmpty()) return
    // a word that is there more than once gets its number: "bank (2.) → bench"
    val numbers = swaps.map { it.range to it.found.lowercase() }.distinct()
        .groupBy({ it.second }, { it.first })
        .filterValues { it.size > 1 }
        .flatMap { (_, ranges) -> ranges.mapIndexed { i, r -> r to i + 1 } }
        .toMap()
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        swaps.forEach { s ->
            key(s.range, s.replacement) { SwapChip(s, numbers[s.range], highlight, onReplace) }
        }
    }
}

@Composable
private fun SwapChip(s: WordSwap, number: Int?, h: SwapHighlight, onReplace: (WordSwap) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val active = pressed || hovered
    LaunchedEffect(active) {
        if (active) h.preview = s.range else if (h.preview == s.range) h.preview = null
    }
    DisposableEffect(Unit) {
        onDispose { if (h.preview == s.range) h.preview = null }
    }
    val c = MaterialTheme.colorScheme
    AssistChip(
        onClick = {
            h.preview = null
            h.flash = WordGroups.replacedRange(s)
            onReplace(s)
        },
        label = {
            Text(
                buildAnnotatedString {
                    append(s.found)
                    if (number != null) append(" ($number.)")
                    append(" → ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.replacement) }
                    if (s.note.isNotEmpty()) withStyle(SpanStyle(color = if (active) OnMark else c.onSurfaceVariant)) { append(" · ${s.note}") }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        colors = if (active) {
            AssistChipDefaults.assistChipColors(containerColor = Mark, labelColor = OnMark, leadingIconContentColor = OnMark)
        } else {
            AssistChipDefaults.assistChipColors()
        },
        interactionSource = interaction,
        modifier = Modifier.semantics { contentDescription = "„${s.found}“ durch „${s.replacement}“ ersetzen" },
    )
}
