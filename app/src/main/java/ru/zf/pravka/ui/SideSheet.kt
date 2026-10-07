package ru.zf.pravka.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Боковая панель (баг №1, 07.10.2026). Владелец про ☰ в Делах: «вылезает
// слишком высоко… перекрывается статусной строкой… я бы сделал не на весь
// экран… может быть, чтобы сбоку оно вылезало… я его крутил, выбирал и
// дальше оно выезжало обратно». Панель выезжает слева, висит с отступами от
// всех краёв (строка состояния и системная панель — сверху и снизу ещё и
// их высота), уезжает обратно по выбору, тапу мимо, «назад» и взмаху влево.
// Своё окно (`Dialog`) — чтобы быть над строкой «сказать» и шапкой вкладки,
// не трогая их раскладку; затемнение своё, плавное, системное выключено.

/**
 * Панель слева. [visible] — показана ли: выключили — панель уезжает и
 * только потом окно закрывается (выбор пункта не обрывает движение).
 * [onDismiss] — просьба закрыть (тап мимо, «назад», взмах влево, крестик).
 */
@Composable
fun SideSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    if (!state.currentState && !state.targetState && state.isIdle) return
    val mode = LocalMode.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Системное затемнение — рывком и на весь экран; своё — плавное.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0f) }
        val scrim by animateFloatAsState(if (state.targetState) 0.55f else 0f, tween(220), label = "scrim")
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF080706).copy(alpha = scrim))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            val width = (maxWidth * 0.86f).coerceAtMost(360.dp)
            val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { width.toPx() }
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            AnimatedVisibility(
                visibleState = state,
                enter = slideInHorizontally(tween(240)) { -it } + fadeIn(tween(160)),
                exit = slideOutHorizontally(tween(220)) { -it } + fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                val shape = RoundedCornerShape(24.dp)
                Column(
                    Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                        .width(width)
                        .fillMaxHeight()
                        .offset { IntOffset(drag.value.roundToInt(), 0) }
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtMost(0f)) } },
                            onDragStopped = { v ->
                                if (drag.value < -widthPx * 0.3f || v < -1200f) onDismiss()
                                drag.animateTo(0f)
                            },
                        )
                        .softShadow(shape)
                        .clip(shape)
                        .background(mode.ink.copy(alpha = 0.97f).compositeOver(Ink.Bg))
                        // Тап по самой панели — не «мимо».
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                ) {
                    Box(Modifier.padding(top = 10.dp)) {
                        SheetHeader(title, onClose = onDismiss, icon = icon, subtitle = subtitle)
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        content = content,
                    )
                }
            }
        }
    }
}
