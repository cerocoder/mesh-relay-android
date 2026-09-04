package com.cerocoder.meshrelay.ui.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.NodeId
import com.cerocoder.meshrelay.stats.model.CandidateVerdict
import com.cerocoder.meshrelay.stats.model.RelayCandidate
import com.cerocoder.meshrelay.ui.common.NodeIdText
import com.cerocoder.meshrelay.ui.common.StatsFormat
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme
import com.cerocoder.meshrelay.ui.theme.VerdictConsistent
import com.cerocoder.meshrelay.ui.theme.VerdictInconsistent
import com.cerocoder.meshrelay.ui.theme.VerdictUncertain
import java.util.Locale

private val CandidateDotSize = 10.dp
private val CandidateRowSpacing = 4.dp

/**
 * The dropdown that lets the owner pick which of a relay byte's candidates to
 * compare against the signal it is actually delivering - spec
 * `2026-09-04-relay-candidate-comparison-design.md` section 7.
 *
 * A file of its own rather than a third responsibility folded into
 * [SignalGraphScreen]: that screen already carries Freeze, Auto scale and the
 * chart itself, each with load-bearing comments of its own, and a dropdown
 * this dense does not need to share a file with them to be reviewed sanely.
 *
 * Renders nothing at all when [candidates] is empty - the one shape this
 * function has for both cases the spec asks for: the subject is a Neighbour
 * (which has no relay byte and so is never given any candidates at all), or
 * the subject is a Relay whose byte nobody currently answers to. Neither is
 * "an empty dropdown with only None in it".
 *
 * [selected] and [onSelect] are owned by the caller, on the same
 * `rememberSaveable` terms Freeze already is in [SignalGraphScreen] - a
 * rotation must not silently drop the reader back to comparing nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CandidateSelector(
    candidates: List<RelayCandidate>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (candidates.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val locale = displayLocale()
    val selectedCandidate = candidates.firstOrNull { it.nodeNum == selected }
    val fieldText = selectedCandidate?.let { candidateDisplayName(it) }
        ?: stringResource(R.string.graph_candidate_none)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            // PrimaryNotEditable: this field only ever shows a selection made
            // through the menu below, never free text.
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                .fillMaxWidth(),
            readOnly = true,
            value = fieldText,
            onValueChange = {},
            label = { Text(stringResource(R.string.graph_candidate_label)) },
            leadingIcon = if (selectedCandidate != null) {
                { VerdictDot(color = verdictColor(selectedCandidate.verdict)) }
            } else {
                null
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            maxLines = 1,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.graph_candidate_none)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            candidates.forEach { candidate ->
                DropdownMenuItem(
                    leadingIcon = { VerdictDot(color = verdictColor(candidate.verdict)) },
                    text = { CandidateMenuItemText(candidate = candidate, locale = locale) },
                    onClick = {
                        onSelect(candidate.nodeNum)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** The short name if there is one, the node id otherwise - the same fallback
 *  [NeighbourCard][com.cerocoder.meshrelay.ui.neighbours.NeighbourCard] uses
 *  for an unidentified node. */
private fun candidateDisplayName(candidate: RelayCandidate): String =
    candidate.shortName.ifEmpty { NodeId.format(candidate.nodeNum) }

/**
 * The colour the coloured dot names in spec section 7. [CandidateVerdict.UNKNOWN]
 * gets a neutral theme colour rather than a fourth verdict constant in
 * `ui/theme/Color.kt` - it is not a judgement about the signal at all, only an
 * absence of one to judge, and colouring it green/amber/red the way the other
 * three verdicts are would claim a reading this candidate never gave.
 */
@Composable
private fun verdictColor(verdict: CandidateVerdict): Color = when (verdict) {
    CandidateVerdict.CONSISTENT -> VerdictConsistent
    CandidateVerdict.UNCERTAIN -> VerdictUncertain
    CandidateVerdict.INCONSISTENT -> VerdictInconsistent
    CandidateVerdict.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The coloured dot itself - a filled circle, nothing more. */
@Composable
private fun VerdictDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(CandidateDotSize).background(color = color, shape = CircleShape))
}

/**
 * One candidate's row content: identity, then its signal comparison, then -
 * only for a candidate that cannot forward - a line naming the role that
 * disqualifies it.
 */
@Composable
private fun CandidateMenuItemText(candidate: RelayCandidate, locale: Locale) {
    Column(verticalArrangement = Arrangement.spacedBy(CandidateRowSpacing)) {
        Row(horizontalArrangement = Arrangement.spacedBy(CandidateRowSpacing)) {
            NodeIdText(nodeNum = candidate.nodeNum)
            if (candidate.shortName.isNotEmpty()) {
                Text(
                    text = candidate.shortName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Deliberately no `maxLines`/`overflow`, unlike the shortName Text
        // above: this is the corroboration line spec section 7 exists to
        // show for a candidate never heard directly - a silent ROUTER, the
        // spec's own "likeliest relay of all" case. `ExposedDropdownMenu`
        // cannot exceed its anchor's width, so a one-line cap ellipsised the
        // DB SNR and hop count away entirely in Spanish; wrapping to as many
        // lines as the content needs is what keeps them on screen.
        Text(
            text = candidateStatsLine(candidate, locale),
            style = MaterialTheme.typography.labelSmall,
        )
        if (candidate.cannotForward) {
            Text(
                text = stringResource(R.string.graph_candidate_cannot_forward, candidate.role.orEmpty()),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * The line under a candidate's name: its own average direct RSSI, sample
 * count and gap from the relay's average - or, when [RelayCandidate.directRssiAvg]
 * is null (never heard directly this session, spec section 7's other case),
 * that fact plus whatever session-independent corroboration the node database
 * still has (`dbSnr`, `hopsAway`), each shown only when present.
 *
 * A candidate that *was* heard directly but whose gap is null regardless (the
 * relay itself has no average yet, so every candidate is
 * [CandidateVerdict.UNKNOWN]) still gets its own average and sample count
 * here - only the gap segment is dropped, since there is nothing dishonest
 * about "we heard this node, we just cannot compare it to the relay yet".
 */
@Composable
private fun candidateStatsLine(candidate: RelayCandidate, locale: Locale): String {
    val avg = candidate.directRssiAvg
    if (avg == null) {
        // graph_candidate_db_snr/graph_candidate_hops carry their own label
        // and separator, on the same terms format_snr_db and graph_time do -
        // composing "label" + ": " + value in Kotlin was a literal, visible
        // punctuation mark this project's strings all live in a resource,
        // and hopsAway went through plain string interpolation rather than
        // StatsFormat, which is un-localised for a locale that ever spells
        // digits differently. stringResource's own vararg formatting already
        // threads the device locale through a %1$d placeholder, so no new
        // StatsFormat helper is needed for the count itself.
        val snrPart = candidate.dbSnr?.let {
            stringResource(
                R.string.graph_candidate_db_snr,
                stringResource(R.string.format_snr_db, StatsFormat.nodeDatabaseSnr(it, locale)),
            )
        }
        val hopsPart = candidate.hopsAway?.let { stringResource(R.string.graph_candidate_hops, it) }
        return listOfNotNull(stringResource(R.string.graph_candidate_not_heard), snrPart, hopsPart)
            .joinToString(STATS_LINE_SEPARATOR)
    }

    val avgPart = stringResource(R.string.format_rssi_dbm, StatsFormat.candidateRssiAvg(avg, locale))
    val samplesPart = pluralStringResource(
        R.plurals.graph_candidate_samples,
        candidate.directPacketCount,
        candidate.directPacketCount,
    )
    val gapPart = candidate.gapDb?.let {
        stringResource(R.string.graph_candidate_gap, StatsFormat.candidateGapDb(it, locale))
    }
    return listOfNotNull(avgPart, samplesPart, gapPart).joinToString(STATS_LINE_SEPARATOR)
}

/**
 * Structural glue between this line's segments, not translatable prose - the
 * same treatment [StatsFormat]'s own `TRIPLE_SEPARATOR` and `candidateIndex`
 * get (see either's KDoc): pure punctuation carries no word order for a
 * translator to get right, so a resource for it would gain both locale files
 * an entry neither translation would ever change.
 */
private const val STATS_LINE_SEPARATOR = " · "

/** The configured display locale. A copy of the same private helper
 *  [SignalGraphScreen] and others already carry - see that file's own copy
 *  for why one shared function is not worth it here either. */
@Composable
private fun displayLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty()) Locale.getDefault() else locales.get(0)
}

// ----------------------------------------------------------------------------
// Previews.
// ----------------------------------------------------------------------------

private val previewCandidates = listOf(
    RelayCandidate(
        nodeNum = 0x1a2b3c01,
        shortName = "TOL1",
        role = "ROUTER",
        directRssiAvg = -69f,
        directPacketCount = 42,
        gapDb = 2f,
        verdict = CandidateVerdict.CONSISTENT,
        dbSnr = null,
        hopsAway = null,
        cannotForward = false,
    ),
    RelayCandidate(
        nodeNum = 0x1a2b3c02,
        shortName = "CRE2",
        role = "CLIENT",
        directRssiAvg = -61f,
        directPacketCount = 5,
        gapDb = 10f,
        verdict = CandidateVerdict.UNCERTAIN,
        dbSnr = null,
        hopsAway = null,
        cannotForward = false,
    ),
    RelayCandidate(
        nodeNum = 0x1a2b3c03,
        shortName = "MUT3",
        role = "CLIENT_MUTE",
        directRssiAvg = -50f,
        directPacketCount = 3,
        gapDb = 33f,
        verdict = CandidateVerdict.INCONSISTENT,
        dbSnr = null,
        hopsAway = null,
        cannotForward = true,
    ),
    RelayCandidate(
        nodeNum = 0x1a2b3c04,
        shortName = "",
        role = "ROUTER",
        directRssiAvg = null,
        directPacketCount = 0,
        gapDb = null,
        verdict = CandidateVerdict.UNKNOWN,
        dbSnr = -8.5f,
        hopsAway = 3,
        cannotForward = false,
    ),
)

@Preview(showBackground = true, name = "None selected")
@Composable
private fun CandidateSelectorNonePreview() {
    MeshRelayTheme {
        CandidateSelector(candidates = previewCandidates, selected = null, onSelect = {})
    }
}

@Preview(showBackground = true, name = "Candidate selected")
@Composable
private fun CandidateSelectorSelectedPreview() {
    MeshRelayTheme {
        CandidateSelector(candidates = previewCandidates, selected = 0x1a2b3c01, onSelect = {})
    }
}

@Preview(showBackground = true, name = "No candidates")
@Composable
private fun CandidateSelectorEmptyPreview() {
    // Renders nothing at all - the empty space itself is what this preview checks.
    MeshRelayTheme {
        CandidateSelector(candidates = emptyList(), selected = null, onSelect = {})
    }
}

@Preview(showBackground = true, name = "Dark theme", uiMode = 0x20)
@Composable
private fun CandidateSelectorDarkPreview() {
    MeshRelayTheme(darkTheme = true) {
        CandidateSelector(candidates = previewCandidates, selected = 0x1a2b3c03, onSelect = {})
    }
}
