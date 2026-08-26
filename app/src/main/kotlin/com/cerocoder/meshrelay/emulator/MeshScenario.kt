package com.cerocoder.meshrelay.emulator

import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo

/**
 * Description of an emulated mesh: data without behaviour.
 *
 * Frame order mirrors real firmware: MyNodeInfo comes first, because only after
 * it is known is the local node's own number known.
 */
class MeshScenario private constructor(
    val id: String,
    val displayName: String,
    /** The scenario's nodes - for display and for assertions. */
    val nodes: List<NodeInfo>,
    private val configStage: List<FromRadio>,
    private val nodeStage: List<FromRadio>,
    private val traffic: List<FromRadio>,
    /** How often the transport should emit one traffic frame while the demo runs. */
    val trafficIntervalMillis: Long,
) {

    /** Stage 1 reply frames for want_config_id, terminated by its completion marker. */
    fun configStageFrames(nonce: Int): List<FromRadio> = configStage + FromRadio(config_complete_id = nonce)

    /** Stage 2 reply frames for want_config_id, terminated by its completion marker. */
    fun nodeStageFrames(nonce: Int): List<FromRadio> = nodeStage + FromRadio(config_complete_id = nonce)

    /**
     * Mesh traffic that keeps arriving once the handshake is done.
     *
     * A finite sequence covering one cycle - repeating it for as long as the demo
     * runs is the transport's job, not this function's, so callers that only want
     * to inspect the data (tests included) get a sequence that actually ends.
     */
    fun trafficFrames(): Sequence<FromRadio> = traffic.asSequence()

    companion object {

        /**
         * A scenario built from structured data.
         *
         * Frame order mirrors real firmware: MyNodeInfo comes first, because only
         * after it is known is the local node's own number known.
         */
        fun of(
            id: String,
            displayName: String,
            myInfo: MyNodeInfo,
            metadata: DeviceMetadata,
            config: List<Config>,
            moduleConfig: List<ModuleConfig>,
            channels: List<Channel>,
            nodes: List<NodeInfo>,
            traffic: List<FromRadio> = emptyList(),
            trafficIntervalMillis: Long = 1_000L,
        ): MeshScenario = MeshScenario(
            id = id,
            displayName = displayName,
            nodes = nodes,
            configStage = buildList {
                add(FromRadio(my_info = myInfo))
                add(FromRadio(metadata = metadata))
                config.forEach { add(FromRadio(config = it)) }
                moduleConfig.forEach { add(FromRadio(moduleConfig = it)) }
                channels.forEach { add(FromRadio(channel = it)) }
            },
            nodeStage = nodes.map { FromRadio(node_info = it) },
            traffic = traffic,
            trafficIntervalMillis = trafficIntervalMillis,
        )

        /**
         * A scenario built from ready-made frames - for example, a capture of
         * traffic from a real node.
         *
         * The closing `config_complete_id` frames are added automatically, so a
         * capture must exclude them, or the app would receive the acknowledgement
         * twice.
         */
        fun fromFrames(
            id: String,
            displayName: String,
            configStage: List<FromRadio>,
            nodeStage: List<FromRadio>,
            traffic: List<FromRadio> = emptyList(),
            trafficIntervalMillis: Long = 1_000L,
        ): MeshScenario = MeshScenario(
            id = id,
            displayName = displayName,
            nodes = nodeStage.mapNotNull { it.node_info },
            configStage = configStage,
            nodeStage = nodeStage,
            traffic = traffic,
            trafficIntervalMillis = trafficIntervalMillis,
        )
    }
}
