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
import com.cerocoder.meshrelay.settings.MapProvider
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
private val ScrollbarWidth = 12.dp
private val ScrollbarCorner = 6.dp
private val GlobeSize = 40.dp
private val ScreenPadding = 16.dp
private val CrosshairLabelPadding = 8.dp
private val LabelSpacing = 12.dp
private val SwitchLabelSpacing = 8.dp

/**
 * The side length of one measurement's mark, in physical pixels - decision 45,
 * at the owner's instruction after reading the chart on hardware: *"the point is
 * not a square. The point MUST be a square with 2x2 pix. Don't antialias them."*
 * Ruling 46 raised it from 2x2 to 4x4, at the owner's later instruction after
 * seeing the 2x2 mark on the phone: *"they are too small, lets use 4 as
 * multiplicator."*
 *
 * **Pixels, not `dp`, and deliberately so.** Canvas units in [SignalChart] are
 * already physical pixels, so converting a `dp` value through [LocalDensity]
 * would size the mark differently on every phone this app runs on - exactly the
 * opposite of what a fixed-pixel-count instruction means. This constant is
 * density-independent on purpose: the mark is the same 4x4 physical pixels on a
 * 450 dpi phone and a 160 dpi one alike. What *does* vary with the screen and
 * with how much is on it is the spacing between marks - `pxPerSample`, fitted
 * per [MIN_PX_PER_SAMPLE] - never the mark itself.
 */
private const val POINT_SIZE_PX = 4f

/**
 * The shortest a measurement's row may be - the floor under the fitted scale, not
 * the scale itself. [ChartGeometry.fitPxPerSample] turns it into the working
 * value; this file computes that in exactly one place and passes it everywhere.
 *
 * **The scale used to be this constant, and that is what F-7 was.** At `1f`, one
 * physical pixel per measurement on a 450 dpi device, 69 measurements filled 87
 * of the plot's 1100 pixels - eight per cent - and the trace read as broken
 * rather than sparse. Ruling 41 doubled it to `2f`, which halved the
 * measurements needed to fill the plot without closing the issue: 550 of them is
 * still an hour and a half on a relay heard every ten seconds. Ruling 44 stopped
 * fixing the scale at all. The chart now fits the retained series to the plot and
 * falls back to this floor once fitting would crush the points together.
 *
 * **`2f` and not less**, because a young, still-fitting chart would otherwise be
 * crushed to a staircase - and it is also the changeover point, the series
 * length past which the chart scrolls again, which on the owner's 1100 px plot
 * is 550 measurements.
 *
 * **It no longer keeps consecutive dots apart.** At the 4x4 px [POINT_SIZE_PX]
 * square (ruling 46), a dot's half-extent is 2 px - exactly this floor, with no
 * room left over. Rows at the floor touch rather than sit apart, and once
 * fitting gives way to the floor, past the changeover, they overlap by up to 2
 * px: a long session's trace reads as a continuous band rather than as discrete
 * dots. That is an accepted consequence of the owner's chosen mark size, not a
 * defect - ruling 46 records it. It is not "fixed" here; the fix would be a
 * smaller mark or a larger floor, and a larger floor trades against how much
 * history fits on screen before the chart starts scrolling.
 *
 * A `Float` because requirement 13 says the coefficient may be fractional, and
 * because the fitted value above it almost always is.
 */
private const val MIN_PX_PER_SAMPLE = 2f

/**
 * The least travel that counts as scrollable, in pixels, for the purpose of
 * showing a scrollbar at all.
 *
 * One whole pixel, because a fraction of a pixel is not a distance anything can
 * be scrolled to. It exists because a fitted scale (ruling 44) makes the chart's
 * content height the round trip `size * (viewportPx / size)`, which in IEEE-754
 * misses `viewportPx` by about a ten-thousandth of a pixel in one direction or
 * the other, differently for each series length. [ChartScrollbar]'s KDoc has the
 * rest.
 */
private const val MIN_SCROLLABLE_PX = 1f

/**
 * Everything this screen draws at one instant, as one value.
 *
 * It exists so Freeze can capture the whole drawing atomically. The bars and the
 * plot are one picture - with Auto scale on they share a range derived from these
 * statistics - so capturing the series without them would freeze the trace while
 * still letting it slide sideways under a widening scale. Holding the four
 * together makes "what is frozen" one thing rather than four that a later edit
 * could let drift apart.
 */
private data class GraphFrame(
    val series: SignalSeries,
    val rssiStats: SignalStats,
    val snrStats: SignalStats,
    val lastPacketAtMillis: Long,
)

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
    mapProvider: MapProvider,
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

    val live = GraphFrame(
        series = series ?: SignalSeries.EMPTY,
        rssiStats = rssiStats,
        snrStats = snrStats,
        lastPacketAtMillis = lastPacketAtMillis,
    )

    // Freeze holds the drawing, not the collection: the engine keeps folding
    // packets throughout, and the graph is redrawn complete the moment it is
    // switched off.
    //
    // **The whole drawing is captured, not just the plot.** The bars and the
    // trace are one picture, and with Auto scale on they share one range derived
    // from these very statistics - so freezing the series alone would leave a
    // frozen trace that still slides sideways: one stronger packet widens the
    // session `maxVal`, `scaleRange` widens with it, and every point moves while
    // the bars redraw underneath. `lastPacketAtMillis` comes with them so the
    // bars' flash marker does not fire for packets this screen is not showing.
    // The consequence is intended: **while Freeze is on the bars stop updating
    // too.** That is requirement 4, not an oversight to be repaired later.
    //
    // Captured by `remember(freeze)` rather than by an effect, and that matters:
    // an effect runs *after* the composition that turned freeze on, leaving one
    // frame in which freeze is on and nothing is held. That frame would render
    // the empty state and, worse, would look to the re-anchoring effect below
    // like a reset - dropping the reader back to the top of the chart on every
    // press of the switch. `remember(freeze)` re-evaluates during the very
    // composition the switch flips in, so no such frame exists.
    //
    // A rotation loses the captured frame (a `SignalSeries` is not `Saveable`)
    // and re-captures the live one while leaving the switch on. That is the
    // honest behaviour: the switch's meaning is "stop moving", and it keeps
    // meaning that from the rotation onwards.
    //
    // That re-capture has one narrow consequence, and a second path reaches it:
    // if what gets captured is a *fully* empty frame - no statistics and no
    // measurements - the switches below go disabled while Freeze is still on, and
    // the reader cannot release it without leaving the screen. It happens on a
    // rotation taken after a reset, and equally if a reset lands in the very
    // composition that flips Freeze on, capturing the already-emptied frame. One
    // cause, not two: the frame is captured once and enablement is read from it,
    // so a frame with nothing in it disables the switch that would replace it.
    // Both are pre-existing and both got rarer when the predicate below stopped
    // being `shown.size > 0`; neither is worth holding state across a
    // configuration change to close.
    val frame = remember(freeze) { if (freeze) live else null } ?: live
    val shown = frame.series

    // Is there anything at all for the two switches to act on? Not "is there a
    // trace": Freeze holds the *drawing*, and a live bar readout is a drawing, so
    // it has work to do the moment either metric has a figure; Auto scale moves
    // the bars' borders as well as the plot's (requirement 5), which is meaningful
    // whenever the bars have data. Only the state where neither metric has a
    // figure and no measurement is retained - spec section 8.8's empty state,
    // whose branch below reads the same two `hasData` flags - leaves the pair with
    // nothing to do.
    //
    // Read from `frame`, never from `live`. The frame is what is on screen, and
    // tying enablement to it means the switch that turned Freeze on cannot be
    // disabled by what arrives afterwards: a reset under a frozen chart empties
    // the live values, and against those the reader would be locked out of the
    // very switch that would release the chart.
    val hasSomethingToAct = frame.rssiStats.hasData || frame.snrStats.hasData || shown.size > 0

    // How tall one measurement's row is - fitted to the plot while the series is
    // short enough to fit, floored at MIN_PX_PER_SAMPLE after that (ruling 44,
    // closing F-7). This is the only place it is computed; every consumer below
    // takes this value, so no two of them can disagree about the scale.
    //
    // **Derived from `shown`, never from `live`, and that is load-bearing.**
    // Ruling 36 says Freeze holds the whole drawing, and a scale is part of a
    // drawing: fitting to the live series would leave a frozen chart silently
    // rescaling - every point creeping upwards - as packets kept arriving behind
    // it. Reading the frozen frame's size is what makes Freeze mean "stop moving"
    // on the vertical axis as well as the horizontal one.
    //
    // While the chart is fitting, this changes slightly with every measurement,
    // so the plot compresses gently as it fills. Row 0 is the newest measurement
    // at y=0, so the top edge stays put and the compression happens below it.
    // That is inherent to fitting, not a defect to damp out.
    val pxPerSample = ChartGeometry.fitPxPerSample(shown.size, viewportPx, MIN_PX_PER_SAMPLE)

    // Re-anchor as measurements arrive, so the row under the reader's eye does not
    // move. A decrease means the statistics were reset under this chart and the
    // session it was showing no longer exists, so the view goes back to the top.
    LaunchedEffect(shown) {
        val delta = shown.totalAppended - lastTotal
        scrollPx = when {
            delta < 0L -> 0f
            delta > 0L -> ChartGeometry.anchorAfterAppend(scrollPx, delta, pxPerSample)
            else -> scrollPx
        }
        lastTotal = shown.totalAppended
    }

    // One clamped value, used by every consumer, so no caller can forget to clamp.
    // Read for *rendering* only - the two scroll callbacks below re-read the state
    // itself, for the reason each of them records.
    val effectiveScroll = ChartGeometry.clampScroll(scrollPx, shown.size, viewportPx, pxPerSample)

    val rssiRange =
        ChartGeometry.scaleRange(frame.rssiStats, autoScale, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX)
    val snrRange =
        ChartGeometry.scaleRange(frame.snrStats, autoScale, SignalScales.SNR_MIN, SignalScales.SNR_MAX)

    val labelRows = ChartGeometry.labelRows(effectiveScroll, viewportPx, shown.size, pxPerSample)
    val locale = displayLocale()

    // A wheel notch or a hardware scroll only: a touch drag on the plot belongs
    // to the crosshair, which consumes it before this ever sees it.
    //
    // `scrollPx` is read here from the state, not from the `effectiveScroll`
    // computed above: two deltas can be dispatched between recompositions, and a
    // composition-captured starting point would make the second overwrite the
    // first instead of continuing from it - the plot would track the wheel at a
    // fraction of its speed and report the wrong consumed amount.
    val wheelScroll = rememberScrollableState { delta ->
        val before = ChartGeometry.clampScroll(scrollPx, shown.size, viewportPx, pxPerSample)
        val after = ChartGeometry.clampScroll(before - delta, shown.size, viewportPx, pxPerSample)
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
            // under the app bar. Disabled only in the fully empty state - no
            // statistics and no measurements - where there is nothing to freeze
            // and nothing to scale.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenPadding),
                horizontalAlignment = Alignment.End,
            ) {
                LabelledSwitch(
                    label = stringResource(R.string.graph_freeze),
                    checked = freeze,
                    enabled = hasSomethingToAct,
                    onCheckedChange = { freeze = it },
                )
                LabelledSwitch(
                    label = stringResource(R.string.graph_auto_scale),
                    checked = autoScale,
                    enabled = hasSomethingToAct,
                    onCheckedChange = { autoScale = it },
                )
            }

            if (!frame.snrStats.hasData && !frame.rssiStats.hasData) {
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
                snr = frame.snrStats,
                rssi = frame.rssiStats,
                gaugeMode = gaugeMode,
                lastPacketAtMillis = frame.lastPacketAtMillis,
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
                        pxPerSample = pxPerSample,
                        rssiRange = rssiRange,
                        snrRange = snrRange,
                        rssiColor = RssiTrack,
                        snrColor = SnrTrack,
                        pointSizePx = POINT_SIZE_PX,
                        onViewportHeight = { viewportPx = it },
                        onCrosshairAt = { crosshairY = it },
                        onCrosshairCleared = { crosshairY = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                    crosshairY?.let { touchY ->
                        Crosshair(
                            series = shown,
                            touchY = touchY,
                            scrollPx = effectiveScroll,
                            pxPerSample = pxPerSample,
                            viewportPx = viewportPx,
                            locale = locale,
                            mapProvider = mapProvider,
                        )
                    }
                }
                ChartScrollbar(
                    scrollPx = effectiveScroll,
                    contentPx = ChartGeometry.contentHeightPx(shown.size, pxPerSample),
                    viewportPx = viewportPx,
                    onScrollBy = { delta ->
                        // Read from the state, not from the composition-captured
                        // `effectiveScroll`: a drag dispatches several deltas
                        // between recompositions, and each must continue from
                        // where the last one left the chart rather than from a
                        // starting point frozen at the last frame - otherwise the
                        // plot tracks the finger at a fraction of its speed.
                        // Clamped on the way in as well as out, so a drag past
                        // the end of the track cannot bank an offset the chart
                        // has to unwind before it moves again.
                        val from = ChartGeometry.clampScroll(scrollPx, shown.size, viewportPx, pxPerSample)
                        scrollPx = from + delta
                    },
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
 * same measurement even when the touch landed past the last one. Since ruling 44
 * the [pxPerSample] handed in is a fitted, arbitrary `Float` rather than a power
 * of two, so the round trip between the two is what `ChartGeometry`'s
 * `ROW_EPSILON` exists for - see its KDoc before touching either.
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
    pxPerSample: Float,
    viewportPx: Float,
    locale: Locale,
    mapProvider: MapProvider,
) {
    val row = ChartGeometry.rowAtClamped(touchY, scrollPx, pxPerSample, series.size)
    val index = ChartGeometry.indexOfRow(row, series.size)
    val y = ChartGeometry.yOf(row, scrollPx, pxPerSample)
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
        // All three ellipsise rather than clip. The block is a timestamp and two
        // values inside a plot already narrowed by padding and the scrollbar, and
        // the default `TextOverflow.Clip` would cut a digit in half; a reader
        // cannot tell a clipped number from a real one, but they can tell an
        // ellipsis. `TimeRow` below makes the same choice for the same reason,
        // and Spanish is the longer of this app's two languages.
        Text(
            text = StatsFormat.graphTimestamp(series.atMillis(index), locale),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.onSizeChanged { timestampHeightPx = it.height },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LabelSpacing)) {
            Text(
                text = stringResource(R.string.format_rssi_dbm, StatsFormat.sampleRssi(series.rssi(index), locale)),
                color = RssiTrack,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.format_snr_db, StatsFormat.sampleSnr(series.snr(index), locale)),
                color = SnrTrack,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    var globeHeightPx by remember { mutableIntStateOf(0) }
    IconButton(
        onClick = {
            // MapLinks.forProvider takes degrees and formats them under
            // Locale.ROOT; StampedPosition multiplies its scaled integers in
            // Double. Neither side ever touches a display formatter, whose
            // Spanish decimal comma would break the query string. Which service
            // it opens is AppSettings.mapProvider (decision 43); this globe is
            // one tap target with no room to offer both, unlike PositionLine's
            // three separate buttons.
            position?.let {
                uriHandler.openUri(MapLinks.forProvider(mapProvider, it.latitude, it.longitude))
            }
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
 * cannot scroll is an invitation to a gesture that does nothing. Since ruling 44
 * that is the normal state of a young chart rather than a rare one, because a
 * fitted scale puts the whole retained series on screen.
 *
 * **The threshold is one whole pixel of travel, not a bare `>`.** A fitted scale
 * makes `contentPx` the product `size * (viewportPx / size)`, and in IEEE-754
 * that round trip lands a ten-thousandth of a pixel either side of `viewportPx`,
 * more or less at random with each new measurement. Against a bare comparison the
 * scrollbar would appear and vanish as the series grew, over a difference no
 * finger could scroll and no eye could see. Below one pixel there is nothing to
 * scroll to; at the real changeover the travel is a whole row - two pixels at the
 * floor - so nothing that can genuinely be scrolled is hidden by this.
 */
@Composable
private fun ChartScrollbar(
    scrollPx: Float,
    contentPx: Float,
    viewportPx: Float,
    onScrollBy: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (contentPx - viewportPx < MIN_SCROLLABLE_PX) return

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
            mapProvider = MapProvider.GOOGLE,
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
            mapProvider = MapProvider.GOOGLE,
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
            mapProvider = MapProvider.GOOGLE,
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
            mapProvider = MapProvider.GOOGLE,
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
            mapProvider = MapProvider.GOOGLE,
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
            mapProvider = MapProvider.GOOGLE,
            lastPacketAtMillis = SampleData.graphLastPacketAtMillis,
            onBack = {},
        )
    }
}
