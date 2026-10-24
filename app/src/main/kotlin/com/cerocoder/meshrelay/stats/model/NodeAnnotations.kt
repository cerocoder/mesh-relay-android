package com.cerocoder.meshrelay.stats.model

/**
 * Where the information held for a node came from: the local radio's own database,
 * or a NODEINFO packet heard over the air. The names are the file's spelling.
 */
enum class LearntSource(val fileName: String) {
    NODE("node"),
    AIR("air"),
    ;

    companion object {
        fun fromFileName(name: String): LearntSource? = entries.firstOrNull { it.fileName == name }
    }
}

/**
 * What the owner knows about a node that neither the radio nor the air can say.
 * Saved to and loaded from the node file; never part of the statistics.
 */
data class NodeAnnotations(
    val learntSource: LearntSource,
    /** True once this node was the one candidate left for a heard relay byte. */
    val knownAsRelay: Boolean = false,
    val notes: String = "",
    /**
     * Raw JSON text of each element. The element format belongs to a later feature, so
     * it is carried through a save and a load untouched rather than modelled here.
     */
    val watchPoints: List<String> = emptyList(),
    /** Average direct RSSI/SNR remembered from an earlier session; a fallback only. */
    val avgRssi: Float? = null,
    val avgSnr: Float? = null,
)
