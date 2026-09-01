package com.cerocoder.meshrelay.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.stats.SignalScales
import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.SignalSeries
import com.cerocoder.meshrelay.stats.model.SignalStats
import com.cerocoder.meshrelay.ui.common.MapLinks
import com.cerocoder.meshrelay.ui.common.StatsFormat
import com.cerocoder.meshrelay.ui.detail.SignalBlock
import com.cerocoder.meshrelay.ui.preview.SampleData
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme
import com.cerocoder.meshrelay.ui.theme.RssiTrack
import com.cerocoder.meshrelay.ui.theme.SnrTrack
import java.util.Locale
import kotlin.math.roundToInt

private val CrosshairStroke = 1.dp
private val LineStroke = 1.5.dp
private val ScrollbarWidth = 12.dp
private val ScrollbarCorner = 6.dp
private val GlobeSize = 40.dp
private val ScreenPadding = 16.dp
private val CrosshairLabelPadding = 8.dp
private val LabelSpacing = 12.dp
private val SwitchLabelSpacing = 8.dp

/**
 * One measurement is one pixel row, and this is the "scale coefficient" spec
 * requirement 13 asks to exist without being exposed: [ChartGeometry] takes it
 * everywhere, this is its only caller, and a zoom control becomes a value to pass
 * rather than a restructuring. It is a `Float` because the requirement says the
 * coefficient may be fractional (0.1, say) so that scaling down is available too.
 */
private const val PX_PER_SAMPLE = 1f

/**
 * RSSI and SNR against time, for one relay or one neighbour.
 *
 * **The signature carries no `DetailSubject`, deliberately.** Spec requirement 2
 * says this is implemented once and shared by both subjects; a component that
 * cannot see which subject it is drawing cannot diverge per subject, so the rule
 * is enforced by the type signature rather than by discipline. Everything
 * subject-shaped - the title, the subtitle, the statistics - is resolved by
 * `MeshRelayNavHost` and arrives here already decided.
 *
 * [series] is null when nothing is being watched yet, and
 * [SignalSeries.EMPTY]-shaped when the subject has no measurements; both render
 * the empty state. A reset under an open chart arrives as the second of those,
 * which is how this screen survives one - the same way `DetailScreen` already
 * does.
 *
 * **The gesture split (plan decision 4).** A touch drag on the plot moves the
 * crosshair; the scrollbar down the right edge is what scrolls. Only one of the
 * two can win a touch gesture, and [SignalChart] claims it by consuming the drag.
 * A `Modifier.scrollable` stays on the plot all the same, so a mouse wheel or a
 * hardware scroll still moves the chart - it simply never sees a touch drag the
 * crosshair has already taken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalGraphScreen(
    title: String,
    subtitle: String,
    series: SignalSeries?,
    rssiStats: SignalStats,
    snrStats: SignalStats,
    gaugeMode: GaugeMode,
    lastPacketAtMillis: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Both survive a rotation. Freeze especially: a rotation must not silently
    // resume a chart the reader deliberately stopped.
    var freeze by rememberSaveable { mutableStateOf(false) }
    var autoScale by rememberSaveable { mutableStateOf(false) }

    var scrollPx by remember { mutableFloatStateOf(0f) }
    var viewportPx by remember { mutableFloatStateOf(0f) }
    var crosshairY by remember { mutableStateOf<Float?>(null) }
    var lastTotal by remember { mutableLongStateOf(0L) }

    // Freeze holds the drawing, not the collection: the engine keeps folding
    // packets throughout, and the graph is redrawn complete the moment it is
    // switched off.
    //
    // Captured by `remember(freeze)` rather than by an effect, and that matters:
    // an effect runs *after* the composition that turned freeze on, leaving one
    // frame in which freeze is on and nothing is held. That frame would render
    // the empty state and, worse, would look to the re-anchoring effect below
    // like a reset - dropping the reader back to the top of the chart on every
    // press of the switch. `remember(freeze)` re-evaluates during the very
    // composition the switch flips in, so no such frame exists.
    //
    // A rotation loses the captured snapshot (a `SignalSeries` is not
    // `Saveable`) and re-captures the live series while leaving the switch on.
    // That is the honest behaviour: the switch's meaning is "stop moving", and it
    // keeps meaning that from the rotation onwards.
    val frozen = remember(freeze) { if (freeze) series else null }
    val shown = (if (freeze) frozen else series) ?: SignalSeries.EMPTY

    // Re-anchor as measurements arrive, so the row under the reader's eye does not
    // move. A decrease means the statistics were reset under this chart and the
    // session it was showing no longer exists, so the view goes back to the top.
    LaunchedEffect(shown) {
        val delta = shown.totalAppended - lastTotal
        scrollPx = when {
            delta < 0L -> 0f
            delta > 0L -> ChartGeometry.anchorAfterAppend(scrollPx, delta, PX_PER_SAMPLE)
            else -> scrollPx
        }
        lastTotal = shown.totalAppended
    }

    // One clamped value, used by every consumer, so no caller can forget to clamp.
    val effectiveScroll = ChartGeometry.clampScroll(scrollPx, shown.size, viewportPx, PX_PER_SAMPLE)

    val rssiRange = ChartGeometry.scaleRange(rssiStats, autoScale, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX)
    val snrRange = ChartGeometry.scaleRange(snrStats, autoScale, SignalScales.SNR_MIN, SignalScales.SNR_MAX)

    val labelRows = ChartGeometry.labelRows(effectiveScroll, viewportPx, shown.size, PX_PER_SAMPLE)
    val locale = displayLocale()
    val strokeWidthPx = with(LocalDensity.current) { LineStroke.toPx() }

    // A wheel notch or a hardware scroll only: a touch drag on the plot belongs
    // to the crosshair, which consumes it before this ever sees it.
    val wheelScroll = rememberScrollableState { delta ->
        val before = effectiveScroll
        val after = ChartGeometry.clampScroll(before - delta, shown.size, viewportPx, PX_PER_SAMPLE)
        scrollPx = after
        // What was actually absorbed, so a wheel at either end of the series
        // hands the gesture back rather than swallowing it.
        before - after
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { GraphTitle(primary = title, secondary = subtitle) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            // Requirement 20: label left, switch right, stacked and right-aligned
            // under the app bar. Disabled with no measurements - there is nothing
            // to freeze and nothing to scale.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenPadding),
                horizontalAlignment = Alignment.End,
            ) {
                LabelledSwitch(
                    label = stringResource(R.string.graph_freeze),
                    checked = freeze,
                    enabled = shown.size > 0,
                    onCheckedChange = { freeze = it },
                )
                LabelledSwitch(
                    label = stringResource(R.string.graph_auto_scale),
                    checked = autoScale,
                    enabled = shown.size > 0,
                    onCheckedChange = { autoScale = it },
                )
            }

            if (!snrStats.hasData && !rssiStats.hasData) {
                // Spec section 8.8's empty state, exactly: the message once, the
                // two switches above it disabled. `SignalBlock` renders this same
                // string itself when neither metric has data, so drawing it here
                // *and* calling that would put it on screen twice.
                NoSignalData(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                return@Column
            }

            SignalBlock(
                snr = snrStats,
                rssi = rssiStats,
                gaugeMode = gaugeMode,
                lastPacketAtMillis = lastPacketAtMillis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenPadding),
                // Requirement 5: Auto scale moves the bars' borders with the
                // plot's, so the two cannot describe the same reading differently.
                snrScaleMin = snrRange.min,
                snrScaleMax = snrRange.max,
                rssiScaleMin = rssiRange.min,
                rssiScaleMax = rssiRange.max,
            )

            if (shown.size == 0) {
                // Statistics but no retained measurements - a subject whose series
                // has not arrived yet, or one cleared under an open chart. The bars
                // above still have something to say; the plot does not.
                NoSignalData(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                return@Column
            }

            TimeRow(
                atMillis = shown.atMillis(ChartGeometry.indexOfRow(labelRows.firstRow, shown.size)),
                locale = locale,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = ScreenPadding),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = ScrollbarWidth)
                        .scrollable(state = wheelScroll, orientation = Orientation.Vertical),
                ) {
                    SignalChart(
                        series = shown,
                        scrollPx = effectiveScroll,
                        pxPerSample = PX_PER_SAMPLE,
                        rssiRange = rssiRange,
                        snrRange = snrRange,
                        rssiColor = RssiTrack,
                        snrColor = SnrTrack,
                        strokeWidthPx = strokeWidthPx,
                        onViewportHeight = { viewportPx = it },
                        onCrosshairAt = { crosshairY = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    crosshairY?.let { touchY ->
                        Crosshair(
                            series = shown,
                            touchY = touchY,
                            scrollPx = effectiveScroll,
                            viewportPx = viewportPx,
                            locale = locale,
                        )
                    }
                }
                ChartScrollbar(
                    scrollPx = effectiveScroll,
                    contentPx = ChartGeometry.contentHeightPx(shown.size, PX_PER_SAMPLE),
                    viewportPx = viewportPx,
                    onScrollBy = { delta -> scrollPx = effectiveScroll + delta },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(ScrollbarWidth)
                        .fillMaxHeight(),
                )
            }

            TimeRow(
                atMillis = shown.atMillis(ChartGeometry.indexOfRow(labelRows.lastRow, shown.size)),
                locale = locale,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }
    }
}

/**
 * The horizontal rule, the timestamp above it, the two values below it and the
 * globe at its right.
 *
 * The globe is a real [IconButton] offset into the box, **not** a shape painted
 * into the canvas: a painted glyph has no touch target, no ripple and no
 * accessible name. It is enabled only when that measurement stored a position,
 * and its content description names the origin, so "where did this pin come
 * from" is answerable rather than a mystery.
 *
 * The rule is drawn at [ChartGeometry.yOf] of the *resolved* row rather than at
 * the raw touch height, so the line and the numbers beside it always describe the
 * same measurement even when the touch landed past the last one.
 *
 * Both overlays are measured rather than offset by a guessed height. A fixed
 * `dp` would drift the moment the reader turns their system font size up, and
 * this is the one place in the screen where two pieces of text have to meet a
 * line drawn at an exact pixel.
 */
@Composable
private fun BoxScope.Crosshair(
    series: SignalSeries,
    touchY: Float,
    scrollPx: Float,
    viewportPx: Float,
    locale: Locale,
) {
    val row = ChartGeometry.rowAtClamped(touchY, scrollPx, PX_PER_SAMPLE, series.size)
    val index = ChartGeometry.indexOfRow(row, series.size)
    val y = ChartGeometry.yOf(row, scrollPx, PX_PER_SAMPLE)
    val position = series.positionOf(index)
    val uriHandler = LocalUriHandler.current

    // onSurface rather than a new entry in Color.kt: it reads in both themes, and
    // the two metric colours are the ones that carry meaning here.
    val ruleColor = MaterialTheme.colorScheme.onSurface
    val ruleStrokePx = with(LocalDensity.current) { CrosshairStroke.toPx() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        drawLine(
            color = ruleColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = ruleStrokePx,
        )
    }

    // The timestamp above the rule and the two values below it, each value in its
    // metric's own colour - which is why this needs no legend. The block hangs by
    // the measured height of its first line, so the rule falls exactly between the
    // timestamp and the pair.
    var timestampHeightPx by remember { mutableIntStateOf(0) }
    var labelBlockHeightPx by remember { mutableIntStateOf(0) }
    Column(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = 0,
                    y = ChartGeometry.overlayTopPx(
                        anchorY = y,
                        aboveAnchorPx = timestampHeightPx.toFloat(),
                        overlayHeightPx = labelBlockHeightPx.toFloat(),
                        viewportPx = viewportPx,
                    ).roundToInt(),
                )
            }
            .onSizeChanged { labelBlockHeightPx = it.height }
            .padding(horizontal = CrosshairLabelPadding),
    ) {
        Text(
            text = StatsFormat.graphTimestamp(series.atMillis(index), locale),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.onSizeChanged { timestampHeightPx = it.height },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LabelSpacing)) {
            Text(
                text = stringResource(R.string.format_rssi_dbm, StatsFormat.sampleRssi(series.rssi(index), locale)),
                color = RssiTrack,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.format_snr_db, StatsFormat.sampleSnr(series.snr(index), locale)),
                color = SnrTrack,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }

    var globeHeightPx by remember { mutableIntStateOf(0) }
    IconButton(
        onClick = {
            // MapLinks.googleMaps takes degrees and formats them under
            // Locale.ROOT; StampedPosition multiplies its scaled integers in
            // Double. Neither side ever touches a display formatter, whose
            // Spanish decimal comma would break the query string.
            position?.let { uriHandler.openUri(MapLinks.googleMaps(it.latitude, it.longitude)) }
        },
        enabled = position != null,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset {
                IntOffset(
                    x = 0,
                    y = ChartGeometry.overlayTopPx(
                        anchorY = y,
                        // Centred on the rule, by the height it was actually laid
                        // out at - Material may enlarge a button to the minimum
                        // interactive size, and a half of the requested size would
                        // then be a half of the wrong number.
                        aboveAnchorPx = globeHeightPx / 2f,
                        overlayHeightPx = globeHeightPx.toFloat(),
                        viewportPx = viewportPx,
                    ).roundToInt(),
                )
            }
            .onSizeChanged { globeHeightPx = it.height }
            .size(GlobeSize),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_field_coordinates),
            contentDescription = stringResource(
                when (position?.origin) {
                    PositionOrigin.NODE -> R.string.graph_position_from_node
                    PositionOrigin.PHONE -> R.string.graph_position_from_phone
                    null -> R.string.graph_open_map
                },
            ),
        )
    }
}

/**
 * The chart's own scrollbar: a track, and a thumb as tall a share of it as the
 * viewport is of the whole series, sitting as far down it as the chart is
 * scrolled.
 *
 * **The whole bar is the drag target, not just the thumb.** At the 5000-sample
 * ceiling in a short plot the thumb can be only a few pixels tall, and a control
 * that small is not something a finger can catch. Dragging anywhere on the bar
 * scrolls, and the thumb is left as the indicator it is.
 *
 * All four numbers come from [ChartGeometry]; this multiplies two fractions by
 * the measured track height and does nothing else, exactly as [SignalChart]
 * multiplies its fractions by a measured width. The drag conversion is the exact
 * inverse of the thumb's position, so the thumb reaches the foot of its track at
 * the same moment the chart reaches its oldest measurement.
 *
 * Nothing is drawn at all when the whole series already fits: a scrollbar that
 * cannot scroll is an invitation to a gesture that does nothing.
 */
@Composable
private fun ChartScrollbar(
    scrollPx: Float,
    contentPx: Float,
    viewportPx: Float,
    onScrollBy: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (contentPx <= viewportPx) return

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cornerPx = with(LocalDensity.current) { ScrollbarCorner.toPx() }
    var trackPx by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .onSizeChanged { trackPx = it.height.toFloat() }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    onScrollBy(ChartGeometry.contentDeltaFor(delta, trackPx, contentPx))
                },
            ),
    ) {
        val corner = CornerRadius(cornerPx, cornerPx)
        drawRoundRect(color = trackColor, cornerRadius = corner)
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(0f, ChartGeometry.thumbTopFraction(scrollPx, viewportPx, contentPx) * size.height),
            size = Size(size.width, ChartGeometry.thumbHeightFraction(viewportPx, contentPx) * size.height),
            cornerRadius = corner,
        )
    }
}

/**
 * One switch and its label, the label on the left - requirement 20. The parent
 * stack is what right-aligns it.
 *
 * The label is weighted rather than left to its natural width so that a long
 * translation wraps instead of pushing the switch off the right edge: `Escala
 * automática` is seventeen characters against `Auto scale`'s ten, and this is the
 * first place in the screen a layout defect would show.
 */
@Composable
private fun LabelledSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SwitchLabelSpacing, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** One of the two `Time` fields, above and below the plot: the timestamp of the
 *  newest measurement on screen and of the oldest. */
@Composable
private fun TimeRow(atMillis: Long, locale: Locale, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.graph_time, StatsFormat.graphTimestamp(atMillis, locale)),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The same string [SignalBlock] shows when it has nothing, centred in whatever
 *  space is left. Never rendered in the same composition as that block's own
 *  copy of it. */
@Composable
private fun NoSignalData(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.detail_no_signal_data),
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.wrapContentSize(Alignment.Center),
    )
}

/** The app bar's title, the shape `DetailScreen`'s own `DetailTitle` uses - a
 *  fourth independent copy of a six-line private helper, as this codebase already
 *  copies `displayLocale` three times, rather than a shared component whose two
 *  callers would then have to agree on every future change to either app bar. */
@Composable
private fun GraphTitle(primary: String, secondary: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (secondary.isNotEmpty()) {
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The configured display locale. A fourth copy of the identical private helper
 *  [SignalBlock], `RelayCard` and `NeighbourCard` each already carry - a Spanish
 *  reader expects a decimal comma in the crosshair's SNR. */
@Composable
private fun displayLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty()) Locale.getDefault() else locales.get(0)
}

// ----------------------------------------------------------------------------
// Previews.
//
// `freeze` and `autoScale` are internal `rememberSaveable` state, so the two
// previews named for them cannot seed it from the outside - and widening this
// screen's signature with two parameters that exist only for previews would be a
// worse trade than the previews are worth. They render the switches in their
// default positions; what each is *for* is checking that its label and switch
// still lay out beside the plot, which is exactly the thing that does not depend
// on the state behind them.
// ----------------------------------------------------------------------------

@Preview(showBackground = true, name = "Populated")
@Composable
private fun SignalGraphPopulatedPreview() {
    MeshRelayTheme {
        SignalGraphScreen(
            title = stringResource(R.string.graph_title_relay, "0xcd"),
            subtitle = "PQPL1",
            series = SampleData.graphSeries,
            rssiStats = SampleData.graphRssiStats,
            snrStats = SampleData.graphSnrStats,
            gaugeMode = GaugeMode.COMPLEX,
            lastPacketAtMillis = SampleData.graphLastPacketAtMillis,
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "One measurement")
@Composable
private fun SignalGraphSingleMeasurementPreview() {
    // A point, not a line, and both Time fields showing the one timestamp -
    // spec section 8.8's thin state. Its sample carries no position, so the
    // crosshair's globe is the disabled case.
    MeshRelayTheme {
        SignalGraphScreen(
            title = stringResource(R.string.graph_title_neighbour, "!b1a2c3d4"),
            subtitle = "GTF",
            series = SampleData.graphSeriesSingle,
            rssiStats = SampleData.graphSingleRssiStats,
            snrStats = SampleData.graphSingleSnrStats,
            gaugeMode = GaugeMode.COMPLEX,
            lastPacketAtMillis = SampleData.graphSeriesSingle.atMillis(0),
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Empty")
@Composable
private fun SignalGraphEmptyPreview() {
    // Nothing watched yet: one centred message, the two switches above it
    // disabled, and no second copy of the message from SignalBlock.
    MeshRelayTheme {
        SignalGraphScreen(
            title = stringResource(R.string.graph_title_relay, "0x99"),
            subtitle = "",
            series = null,
            rssiStats = SignalStats.EMPTY,
            snrStats = SignalStats.EMPTY,
            gaugeMode = GaugeMode.COMPLEX,
            lastPacketAtMillis = 0L,
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Auto scale on")
@Composable
private fun SignalGraphAutoScalePreview() {
    // Renders with Auto scale off, for the reason the block comment above gives.
    MeshRelayTheme {
        SignalGraphScreen(
            title = stringResource(R.string.graph_title_relay, "0xcd"),
            subtitle = "PQPL1",
            series = SampleData.graphSeries,
            rssiStats = SampleData.graphRssiStats,
            snrStats = SampleData.graphSnrStats,
            gaugeMode = GaugeMode.SIMPLE,
            lastPacketAtMillis = SampleData.graphLastPacketAtMillis,
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Frozen")
@Composable
private fun SignalGraphFrozenPreview() {
    // Renders live, for the reason the block comment above gives. A preview has
    // no arriving packets to freeze in any case.
    MeshRelayTheme {
        SignalGraphScreen(
            title = stringResource(R.string.graph_title_neighbour, "!b1a2c3d4"),
            subtitle = "GTF",
            series = SampleData.graphSeries,
            rssiStats = SampleData.graphRssiStats,
            snrStats = SampleData.graphSnrStats,
            gaugeMode = GaugeMode.COMPLEX,
            lastPacketAtMillis = SampleData.graphLastPacketAtMillis,
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Dark theme", uiMode = 0x20)
@Composable
private fun SignalGraphDarkPreview() {
    MeshRelayTheme(darkTheme = true) {
        SignalGraphScreen(
            title = stringResource(R.string.graph_title_relay, "0xcd"),
            subtitle = "PQPL1",
            series = SampleData.graphSeries,
            rssiStats = SampleData.graphRssiStats,
            snrStats = SampleData.graphSnrStats,
            gaugeMode = GaugeMode.COMPLEX,
            lastPacketAtMillis = SampleData.graphLastPacketAtMillis,
            onBack = {},
        )
    }
}
