package com.focus.launcher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.R
import com.focus.launcher.ui.theme.LocalFocusColors
import com.focus.launcher.ui.theme.LocalFocusFont
import com.focus.launcher.ui.theme.LocalTextScale

/** The one text primitive. Picks up the theme's colour, typeface and text-size setting. */
@Composable
fun T(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    color: Color = Color.Unspecified,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    decoration: TextDecoration? = null,
    family: FontFamily? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = focusTextStyle(size, color, weight, align, letterSpacing, lineHeight, decoration, family),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun focusTextStyle(
    size: TextUnit = 16.sp,
    color: Color = Color.Unspecified,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Unspecified,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    decoration: TextDecoration? = null,
    family: FontFamily? = null,
): TextStyle {
    val scale = LocalTextScale.current
    return TextStyle(
        color = if (color == Color.Unspecified) LocalFocusColors.current.fg else color,
        fontSize = (size.value * scale).sp,
        fontWeight = weight,
        fontFamily = family ?: LocalFocusFont.current,
        textAlign = align,
        letterSpacing = letterSpacing,
        lineHeight = if (lineHeight == TextUnit.Unspecified) lineHeight else (lineHeight.value * scale).sp,
        textDecoration = decoration,
    )
}

/** Small spaced capitals used to title a section. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = LocalFocusColors.current.dim) {
    T(text.uppercase(), modifier, size = 11.sp, color = color, weight = FontWeight.Medium, letterSpacing = 1.8.sp, maxLines = 1)
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = LocalFocusColors.current.line) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))

private val GREYSCALE = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Drains the colour out of whatever it is applied to. Text is already black or white; this is for
 * the colour emoji that other apps put into calendar titles and app names.
 */
fun Modifier.monochrome(): Modifier = graphicsLayer { colorFilter = GREYSCALE }

/** True when [text] contains emoji or other symbols that are drawn in colour. */
fun hasColourGlyphs(text: String): Boolean = text.any { it.isSurrogate() || it.code in 0x2600..0x27BF || it.code == 0xFE0F }

/** Click + optional long-press, with the theme's fade feedback. */
fun Modifier.press(onLongClick: (() -> Unit)? = null, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    if (onLongClick == null) clickable(enabled = enabled, onClick = onClick)
    else combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)

/** A drawn, monochrome on/off switch. */
@Composable
fun FocusSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = LocalFocusColors.current
    val position by animateFloatAsState(if (checked) 1f else 0f, label = "switch")
    Canvas(modifier.size(width = 36.dp, height = 20.dp)) {
        val r = size.height / 2
        val stroke = 1.5.dp.toPx()
        if (checked) {
            drawRoundRect(c.fg, cornerRadius = CornerRadius(r, r))
        } else {
            drawRoundRect(
                c.faint,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(r, r),
                style = Stroke(stroke),
            )
        }
        val knob = r - 4.dp.toPx()
        val x = r + (size.width - 2 * r) * position
        drawCircle(if (checked) c.bg else c.dim, radius = knob, center = Offset(x, r))
    }
}

/** Filled (primary) or outlined button. Black on white or white on black, nothing else. */
@Composable
fun FocusButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = LocalFocusColors.current
    val content = when {
        !enabled -> c.faint
        primary -> c.bg
        else -> c.fg
    }
    Box(
        modifier
            .then(if (primary && enabled) Modifier.background(c.fg) else Modifier.border(1.dp, if (enabled) c.dim else c.line))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text, size = 16.sp, color = content, weight = FontWeight.Medium, maxLines = 1)
    }
}

/** A settings-style row: title (+ subtitle) on the left, a value or control on the right. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalFocusColors.current
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            T(title, size = 17.sp, color = if (enabled) c.fg else c.faint)
            if (subtitle != null) {
                VSpace(3.dp)
                T(subtitle, size = 13.sp, color = if (enabled) c.dim else c.faint, lineHeight = 18.sp)
            }
        }
        if (value != null) T(value, size = 15.sp, color = if (enabled) c.dim else c.faint, maxLines = 1)
        trailing?.invoke()
    }
}

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        enabled = enabled,
        onClick = { onChange(!checked) },
        trailing = { FocusSwitch(checked && enabled) },
    )
}

/**
 * The one pictogram in the launcher: a briefcase outline that marks a work-profile app, as the
 * phone's own launcher does. Drawn here in the text colour, so it stays monochrome and needs no asset.
 */
@Composable
fun WorkBadge(modifier: Modifier = Modifier, side: Dp = 14.dp, color: Color = LocalFocusColors.current.dim) {
    val description = stringResource(R.string.common_work_profile_badge)
    Canvas(modifier.size(side).semantics { contentDescription = description }) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.09f)
        val corner = CornerRadius(w * 0.12f)
        drawRoundRect(color, Offset(w * 0.06f, h * 0.30f), Size(w * 0.88f, h * 0.58f), corner, stroke) // case
        drawRoundRect(color, Offset(w * 0.34f, h * 0.12f), Size(w * 0.32f, h * 0.18f), corner, stroke) // handle
        drawLine(color, Offset(w * 0.06f, h * 0.56f), Offset(w * 0.94f, h * 0.56f), strokeWidth = stroke.width)
    }
}

/** A text tab: inverted when selected. Review (Today / Week) and the drawer (Personal / Work). */
@Composable
fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalFocusColors.current
    Box(
        Modifier
            .then(if (selected) Modifier.background(c.fg) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        T(text, size = 15.sp, color = if (selected) c.bg else c.dim, weight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
    }
}
