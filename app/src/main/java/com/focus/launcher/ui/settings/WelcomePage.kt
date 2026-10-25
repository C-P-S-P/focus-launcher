package com.focus.launcher.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.data.Settings
import com.focus.launcher.ui.components.FocusButton
import com.focus.launcher.ui.components.T
import com.focus.launcher.ui.components.VSpace
import com.focus.launcher.ui.theme.LocalFocusColors

/**
 * The first thing a new user sees, once: the name, what it is for, and two ways on. One screen and
 * no more: a many-page tour was written first and thrown out as far too much to read. What Focus
 * can do is taught afterwards, on the home screen, one line at a time ([com.focus.launcher.data.Tip]).
 * It is shown on the first start only, never on the way to the home screen after that: a launcher
 * is opened dozens of times a day. The one choice asked here is the app a swipe right opens (the web
 * search); it can be changed later in Settings → Gestures. [onDone] is told whether to go on to Setup.
 */
@Composable
internal fun WelcomePage(settings: Settings, onDone: (toSetup: Boolean) -> Unit) {
    val c = LocalFocusColors.current
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp)) {
        VSpace(96.dp)
        T("Focus", size = 56.sp, weight = FontWeight.Light)
        VSpace(10.dp)
        T("Reclaim your time.\nSpend it touching some grass.", size = 20.sp, lineHeight = 28.sp)
        VSpace(22.dp)
        T("No icons. No colour. No feed.\nTips on the home screen show you around.", size = 15.sp, color = c.dim, lineHeight = 23.sp)
        VSpace(36.dp)
        T("Swipe right opens. Which app?", size = 15.sp, color = c.dim, lineHeight = 23.sp)
        SwipeRightRow(settings)
        VSpace(36.dp)
        FocusButton("Start", Modifier.fillMaxWidth(), primary = true) { onDone(false) }
        VSpace(12.dp)
        FocusButton("Set up Focus first", Modifier.fillMaxWidth()) { onDone(true) }
    }
}
