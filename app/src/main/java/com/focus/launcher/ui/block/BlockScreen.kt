package com.focus.launcher.ui.block

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.Graph
import com.focus.launcher.R
import com.focus.launcher.data.Settings
import com.focus.launcher.data.WeekSummary
import com.focus.launcher.ui.components.FocusButton
import com.focus.launcher.ui.components.Label
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.components.VSpace
import com.focus.launcher.ui.theme.LocalFocusColors
import com.focus.launcher.util.formatDuration
import com.focus.launcher.util.formatMinutes
import kotlinx.coroutines.delay
import java.time.LocalDate

data class BlockRequest(
    val packageName: String,
    val label: String,
    val usedMs: Long,
    val limitMinutes: Int,
    val midSession: Boolean,
    val appKey: String?,
    val preview: Boolean,
    /** Not the wall but the question asked before each visit once the limit was ignored for today. */
    val consent: Boolean = false,
)

/**
 * "Time's up." Closing the app is the big, obvious choice. Carrying on is possible, for a few
 * minutes or for the rest of the day, but only after a short pause, and every time it is counted
 * and shown back to the user here and in the weekly review.
 */
@Composable
fun BlockScreen(
    request: BlockRequest,
    settings: Settings,
    onClose: () -> Unit,
    onContinue: (minutes: Int) -> Unit,
    onBypass: () -> Unit,
    onConsent: () -> Unit = {},
    onRestoreLimit: () -> Unit = {},
) {
    val c = LocalFocusColors.current
    val res = LocalResources.current
    BackHandler(onBack = onClose)

    var wait by remember(request) { mutableIntStateOf(settings.frictionSeconds.coerceAtLeast(0)) }
    LaunchedEffect(request) {
        while (wait > 0) {
            delay(1_000)
            wait--
        }
    }
    val unlocked = wait <= 0

    val weekStats = remember(request) {
        val today = LocalDate.now()
        Graph.limits.stats(WeekSummary.weekStartOf(today, settings.weekStartsMonday), today)[request.packageName]
    }

    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing)) }

    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 28.dp.toPx()
            }
            .systemBarsPadding()
            .padding(horizontal = 30.dp),
    ) {
        Spacer(Modifier.weight(0.8f))

        Label(
            when {
                request.preview -> stringResource(R.string.block_label_preview)
                request.consent -> stringResource(R.string.block_label_over_limit)
                else -> stringResource(R.string.block_label_time_up)
            },
        )
        VSpace(14.dp)
        T(request.label, size = 40.sp, weight = FontWeight.Light, maxLines = 2, lineHeight = 46.sp)
        VSpace(18.dp)

        // Today's allowance, fully used.
        Box(Modifier.fillMaxWidth().height(3.dp).background(c.fg))
        VSpace(14.dp)
        T(
            stringResource(R.string.block_usage_today, formatDuration(res, request.usedMs), formatMinutes(res, request.limitMinutes)),
            size = 16.sp, color = c.dim,
        )
        val past = (weekStats?.continued ?: 0) + (weekStats?.bypassed ?: 0)
        if (past > 0) {
            VSpace(6.dp)
            T(
                pluralStringResource(R.plurals.block_past_limit_this_week, past, past),
                size = 14.sp, color = c.faint, lineHeight = 20.sp,
            )
        }

        if (request.consent) {
            VSpace(6.dp)
            T(stringResource(R.string.block_consent_note), size = 14.sp, color = c.faint, lineHeight = 20.sp)
        }

        Spacer(Modifier.weight(1f))

        if (request.consent) {
            // Saying no is the big button; going ahead takes a deliberate second look.
            FocusButton(stringResource(R.string.block_not_now), Modifier.fillMaxWidth(), primary = true, onClick = onClose)
            VSpace(12.dp)
            FocusButton(stringResource(R.string.block_open_anyway, request.label), Modifier.fillMaxWidth(), onClick = onConsent)
            T(
                stringResource(R.string.block_restore_limit),
                Modifier.fillMaxWidth().clickable(onClick = onRestoreLimit).padding(vertical = 16.dp),
                size = 15.sp, color = c.dim,
            )
            VSpace(14.dp)
            return@Column
        }

        FocusButton(stringResource(R.string.block_close_app, request.label), Modifier.fillMaxWidth(), primary = true, onClick = onClose)

        val canContinue = settings.allowContinue && settings.continueOptions.isNotEmpty()
        if (canContinue) {
            VSpace(26.dp)
            Label(if (unlocked) stringResource(R.string.block_or_continue_for) else stringResource(R.string.block_or_continue_in, wait), color = if (unlocked) c.dim else c.faint)
            VSpace(10.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (minutes in settings.continueOptions.sorted().take(4)) {
                    FocusButton(stringResource(R.string.block_continue_minutes, minutes), Modifier.weight(1f), enabled = unlocked) { onContinue(minutes) }
                }
            }
        }
        if (settings.allowBypass) {
            VSpace(if (canContinue) 8.dp else 22.dp)
            T(
                if (unlocked || canContinue) stringResource(R.string.block_ignore_today) else stringResource(R.string.block_ignore_today_countdown, wait),
                Modifier.fillMaxWidth().clickable(enabled = unlocked, onClick = onBypass).padding(vertical = 14.dp),
                size = 15.sp, color = if (unlocked) c.dim else c.faint,
            )
        }
        if (!canContinue && !settings.allowBypass) {
            VSpace(18.dp)
            T(stringResource(R.string.block_strict_mode), size = 14.sp, color = c.faint)
        }
        VSpace(20.dp)
    }
}
