package eu.kanade.presentation.reader

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.updateLayoutParams
import eu.kanade.tachiyomi.ui.webview.DevToolsLiteSession
import tachiyomi.i18n.novel.TDMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

@Composable
fun DevToolsLiteWorkspace(target: WebView, readerPane: View, onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    var error by remember(target) { mutableStateOf<String?>(null) }
    val session = remember(target) { DevToolsLiteSession(target, { dismiss() }, { error = it }) }
    var overlay by remember(target) { mutableStateOf(false) }
    var toolsOnTop by remember(target) { mutableStateOf(false) }
    var fraction by remember(target) { mutableFloatStateOf(0.5f) }
    var height by remember { mutableIntStateOf(1) }
    val resizeLabel = stringResource(TDMR.strings.devtools_resize)

    Layout(
        modifier = Modifier.fillMaxSize().imePadding().onSizeChanged { height = it.height.coerceAtLeast(1) },
        content = {
            // This empty slot measures the real native Reader, including its Compose chrome.
            Box(
                Modifier.onGloballyPositioned { coordinates ->
                    val readerHeight = if (overlay) ViewGroup.LayoutParams.MATCH_PARENT else coordinates.size.height
                    val top = if (!overlay && toolsOnTop) height - readerHeight else 0
                    val params = readerPane.layoutParams as FrameLayout.LayoutParams
                    if (params.height != readerHeight || params.topMargin != top) {
                        readerPane.updateLayoutParams<FrameLayout.LayoutParams> {
                            this.height = readerHeight
                            topMargin = top
                        }
                    }
                },
            )
            Surface {
                Layout(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(
                        WindowInsets.systemBars.only(
                            WindowInsetsSides.Horizontal +
                                if (toolsOnTop) WindowInsetsSides.Top else WindowInsetsSides.Bottom,
                        ),
                    ),
                    content = {
                        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.weight(1f).height(28.dp).semantics {
                                    contentDescription = resizeLabel
                                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0.1f..0.9f)
                                    setProgress {
                                        fraction = it.coerceIn(0.1f, 0.9f)
                                        true
                                    }
                                }.draggable(
                                    rememberDraggableState { delta ->
                                        fraction = (fraction + (if (toolsOnTop) delta else -delta) / height)
                                            .coerceIn(0.1f, 0.9f)
                                    },
                                    Orientation.Vertical,
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.DragHandle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(onClick = { overlay = !overlay }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    if (overlay) Icons.Outlined.Splitscreen else Icons.Outlined.Layers,
                                    stringResource(
                                        if (overlay) TDMR.strings.devtools_split else TDMR.strings.devtools_overlay,
                                    ),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(onClick = { toolsOnTop = !toolsOnTop }, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    Icons.Outlined.SwapVert,
                                    stringResource(TDMR.strings.devtools_swap),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Column(Modifier.fillMaxSize()) {
                            error?.let { Text(it, Modifier.padding(8.dp)) }
                            AndroidView(factory = { session.frontend }, modifier = Modifier.weight(1f).fillMaxWidth())
                        }
                    },
                ) { children, constraints ->
                    val bar = children[0].measure(constraints.copy(minHeight = 0))
                    val frontend = children[1].measure(
                        Constraints.fixed(
                            constraints.maxWidth,
                            (
                                constraints.maxHeight -
                                    bar.height
                                ).coerceAtLeast(0),
                        ),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        bar.placeRelative(0, if (toolsOnTop) frontend.height else 0)
                        frontend.placeRelative(0, if (toolsOnTop) 0 else bar.height)
                    }
                }
            }
        },
    ) { children, constraints ->
        val toolsHeight = (constraints.maxHeight * fraction).roundToInt()
        val readerHeight = if (overlay) constraints.maxHeight else constraints.maxHeight - toolsHeight
        val reader = children[0].measure(Constraints.fixed(constraints.maxWidth, readerHeight))
        val tools = children[1].measure(Constraints.fixed(constraints.maxWidth, toolsHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            reader.placeRelative(0, if (!overlay && toolsOnTop) toolsHeight else 0)
            tools.placeRelative(0, if (toolsOnTop) 0 else constraints.maxHeight - toolsHeight)
        }
    }
    BackHandler(onBack = onDismiss)
    DisposableEffect(session) {
        session.start()
        onDispose {
            session.close()
            readerPane.updateLayoutParams<FrameLayout.LayoutParams> {
                this.height = ViewGroup.LayoutParams.MATCH_PARENT
                topMargin = 0
            }
        }
    }
}
