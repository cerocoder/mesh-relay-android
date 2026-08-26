package com.cerocoder.meshrelay.emulator

import com.cerocoder.meshrelay.stats.Geo
import okio.ByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User
import kotlin.math.roundToInt

/** Ready-made datasets for demo devices. */
object Scenarios {

    const val HANDSHAKE_ONLY_ID = "handshake"
    const val FIVE_NODES_ID = "5nodes"
    const val LARGE_MESH_ID = "200nodes"
    const val EMPTY_MESH_ID = "empty"
    const val ZONA_CENTRO_ID = "zona-centro"

    private const val LOCAL_NODE_NUM = 0x11223344

    val all: List<MeshScenario> by lazy {
        listOf(
            // First, so anything that just grabs Scenarios.all.first() - the demo
            // launcher included - lands on the one scenario with something to show.
            zonaCentro(),
            scenario(FIVE_NODES_ID, "Demo: 5 nodes", nodeCount = 5),
            scenario(LARGE_MESH_ID, "Demo: 200 nodes", nodeCount = 200),
            scenario(EMPTY_MESH_ID, "Demo: empty mesh", nodeCount = 0),
            scenario(HANDSHAKE_ONLY_ID, "Demo: handshake only", nodeCount = 0, channelCount = 1),
        )
    }

    fun byId(id: String): MeshScenario? = all.firstOrNull { it.id == id }

    private fun scenario(
        id: String,
        displayName: String,
        nodeCount: Int,
        channelCount: Int = 3,
    ): MeshScenario = MeshScenario.of(
        id = id,
        displayName = displayName,
        myInfo = MyNodeInfo(
            my_node_num = LOCAL_NODE_NUM,
            reboot_count = 1,
            min_app_version = 30200,
            nodedb_count = nodeCount,
        ),
        metadata = DeviceMetadata(
            firmware_version = "2.7.26.demo",
            hasBluetooth = true,
            hasWifi = false,
        ),
        // A subset of config sections: enough for the diagnostic screen to show
        // that config frames are parsed.
        config = listOf(
            Config(device = Config.DeviceConfig()),
            Config(position = Config.PositionConfig()),
            Config(lora = Config.LoRaConfig()),
            Config(bluetooth = Config.BluetoothConfig()),
        ),
        // Two module-config sections: without them the moduleConfig branch in of()
        // would be dead code, and the frame order in this segment untestable.
        moduleConfig = listOf(
            ModuleConfig(telemetry = ModuleConfig.TelemetryConfig()),
            ModuleConfig(mqtt = ModuleConfig.MQTTConfig()),
        ),
        channels = List(channelCount) { index ->
            Channel(
                index = index,
                role = if (index == 0) Channel.Role.PRIMARY else Channel.Role.SECONDARY,
                settings = ChannelSettings(name = if (index == 0) "LongFast" else "Channel $index"),
            )
        },
        nodes = List(nodeCount) { index -> node(index) },
    )

    private fun node(index: Int): NodeInfo {
        val num = LOCAL_NODE_NUM + index + 1
        return NodeInfo(
            num = num,
            user = User(
                id = "!%08x".format(num),
                long_name = "Demo node ${index + 1}",
                short_name = "N%02d".format(index + 1),
            ),
            snr = 5.0f - index % 10,
            last_heard = 1_780_000_000 + index,
            channel = 0,
            hops_away = index % 3,
        )
    }

    // ---------------------------------------------------------------------
    // zona-centro: modelled on the Madrid-Toledo mesh this tool was written
    // for (see the project's root CLAUDE.md). The other scenarios above stop
    // after the handshake; this app has nothing to show until traffic
    // arrives, so this one keeps producing packets - see MeshScenario's
    // trafficFrames().
    //
    // Deliberately not tidy: it carries an ambiguous relay byte, a node whose
    // low byte substitutes to 0xff, encrypted packets with no decoded
    // payload, and a falling uptime counter. Nine screens are built against
    // this data by people who cannot see each other's work; a scenario with
    // only the easy cases would let every one of them look right while
    // mishandling exactly the packets that occur on a real mesh.
    // ---------------------------------------------------------------------

    private const val ZONA_CENTRO_LOCAL_NODE_NUM = 0xA1FFEE01.toInt()

    // Node numbers below are hand-picked, not sequential, so two properties
    // can be engineered on purpose:
    //  - NUM_GETAFE_ROUTER and NUM_TOLEDO_ESTACION share the low byte 0x2a -
    //    the relay list must show these as an ambiguous match, not a name.
    //  - NUM_TOLEDO_CASCO_ANTIGUO ends in 0x00, so Geo.lastByteOfNodeNum
    //    substitutes 0xff for it, exactly as real firmware does for itself.
    private const val NUM_MADRID_CENTRO = 0xA1000101.toInt()
    private const val NUM_GETAFE_ROUTER = 0xA1000A2A.toInt()
    private const val NUM_GETAFE_VECINO = 0xA1000C33.toInt()
    private const val NUM_PINTO = 0xA100119C.toInt()
    private const val NUM_VALDEMORO = 0xA1001377.toInt()
    private const val NUM_ILLESCAS_ROUTER = 0xA1001547.toInt()
    private const val NUM_YUNCOS = 0xA1001664.toInt()
    private const val NUM_OLIAS_DEL_REY = 0xA1001858.toInt()
    private const val NUM_TOLEDO_ESTACION = 0xA1001A2A.toInt()
    private const val NUM_TOLEDO_ALCAZAR = 0xA1001C5D.toInt()
    private const val NUM_TOLEDO_CASCO_ANTIGUO = 0xA1001E00.toInt()
    private const val NUM_ILLESCAS_VECINO = 0xA1002099.toInt()

    // The four relay bytes exercised by zonaCentroTraffic(), one per profile
    // called out in the task brief.
    private const val RELAY_STEADY = 0x2A // Getafe Router: strong and steady.
    private const val RELAY_INTERMITTENT = 0x47 // Illescas Router: long silent gaps.
    private const val RELAY_WEAK = 0x9C // Pinto: weak and variable.
    private const val RELAY_LATE = 0xFF // Toledo Casco Antiguo: shows up only later.

    private const val BASE_LAST_HEARD = 1_800_000_000
    private const val TOTAL_TRAFFIC_PACKETS = 132

    // Out of the 11 cyclic occurrences slot 11 gets (132 packets / 12 slots),
    // the late relay stays silent until this one - just past 60% through the
    // cycle - so a screen built against the first few dozen packets never
    // sees it and would otherwise ship never having rendered a new relay
    // appearing mid-session.
    private const val LATE_RELAY_START_OCCURRENCE = 6

    // Slot 8 (the intermittent relay) only speaks as itself in these
    // occurrences; everywhere else it falls back to the steady relay. Two
    // short bursts (0-2 and 7-8) separated by four silent occurrences - 48
    // packets, more than a third of the whole cycle - is the long gap the
    // brief asks for.
    private val INTERMITTENT_BURST_OCCURRENCES = setOf(0, 1, 2, 7, 8)

    private const val BROADCAST_NUM = 0xFFFFFFFF.toInt()

    private data class ZcNode(
        val num: Int,
        val longName: String,
        val shortName: String,
        val role: Config.DeviceConfig.Role,
        val hwModel: HardwareModel,
        val lat: Double,
        val lon: Double,
        val snr: Float,
        val hopsAway: Int,
    )

    // Twelve nodes along the real Madrid-Toledo corridor (see the project's
    // CLAUDE.md for the coordinates and the community's channel/preset
    // history this scenario nods to). Two routers, one CLIENT_MUTE, the rest
    // CLIENT, exactly as the brief specifies.
    private val ZONA_CENTRO_DEFS: List<ZcNode> = listOf(
        ZcNode(
            NUM_MADRID_CENTRO, "Madrid Centro", "MAD1", Config.DeviceConfig.Role.CLIENT,
            HardwareModel.T_ECHO, 40.4168, -3.7038, 9.25f, 0,
        ),
        ZcNode(
            NUM_GETAFE_ROUTER, "Getafe Router", "GETR", Config.DeviceConfig.Role.ROUTER,
            HardwareModel.HELTEC_V3, 40.3083, -3.7325, 8.5f, 1,
        ),
        ZcNode(
            NUM_GETAFE_VECINO, "Getafe Vecino", "GET2", Config.DeviceConfig.Role.CLIENT_MUTE,
            HardwareModel.RAK4631, 40.3050, -3.7400, 10.0f, 0,
        ),
        ZcNode(
            NUM_PINTO, "Pinto Nodo", "PINT", Config.DeviceConfig.Role.CLIENT,
            HardwareModel.TBEAM, 40.2400, -3.6900, -6.0f, 2,
        ),
        ZcNode(
            NUM_VALDEMORO, "Valdemoro Nodo", "VALD", Config.DeviceConfig.Role.CLIENT,
            HardwareModel.HELTEC_V3, 40.1900, -3.6750, 2.0f, 2,
        ),
        ZcNode(
            NUM_ILLESCAS_ROUTER, "Illescas Router", "ILLR", Config.DeviceConfig.Role.ROUTER,
            HardwareModel.STATION_G2, 40.1225, -3.8480, 1.5f, 2,
        ),
        ZcNode(
            NUM_YUNCOS, "Yuncos Nodo", "YUNC", Config.DeviceConfig.Role.CLIENT,
            HardwareModel.T_ECHO, 40.0650, -3.8600, 0.5f, 2,
        ),
        ZcNode(
            NUM_OLIAS_DEL_REY, "Olias del Rey", "OLIA", Config.DeviceConfig.Role.CLIENT,
            HardwareModel.RAK4631, 39.8975, -3.9805, 7.0f, 1,
        ),
        ZcNode(
            NUM_TOLEDO_ESTACION, "Toledo Estacion", "TOL1", Config.DeviceConfig.Role.CLIENT,
            HardwareModel.HELTEC_MESH_NODE_T114, 39.8628, -4.0273, 6.5f, 1,
        ),
        ZcNode(
            NUM_TOLEDO_ALCAZAR, "Toledo Alcazar", "TOL2", Config.DeviceConfig.Role.CLIENT,
            HardwareModel.TBEAM, 39.8580, -4.0230, -8.0f, 3,
        ),
        ZcNode(
            NUM_TOLEDO_CASCO_ANTIGUO, "Toledo Casco Antiguo", "TOL3", Config.DeviceConfig.Role.CLIENT,
            HardwareModel.HELTEC_V3, 39.8560, -4.0290, -1.0f, 2,
        ),
        ZcNode(
            NUM_ILLESCAS_VECINO, "Illescas Vecino", "ILL2", Config.DeviceConfig.Role.CLIENT,
            HardwareModel.T_ECHO, 40.1200, -3.8450, 8.0f, 1,
        ),
    )

    private fun zonaCentro(): MeshScenario = MeshScenario.of(
        id = ZONA_CENTRO_ID,
        displayName = "Demo: Zona Centro (Madrid-Toledo)",
        myInfo = MyNodeInfo(
            my_node_num = ZONA_CENTRO_LOCAL_NODE_NUM,
            reboot_count = 1,
            min_app_version = 30200,
            nodedb_count = ZONA_CENTRO_DEFS.size,
        ),
        metadata = DeviceMetadata(
            firmware_version = "2.7.15.demo",
            hasBluetooth = true,
            hasWifi = false,
        ),
        config = listOf(
            Config(device = Config.DeviceConfig()),
            Config(position = Config.PositionConfig()),
            Config(lora = Config.LoRaConfig()),
            Config(bluetooth = Config.BluetoothConfig()),
        ),
        moduleConfig = listOf(
            ModuleConfig(telemetry = ModuleConfig.TelemetryConfig()),
            ModuleConfig(mqtt = ModuleConfig.MQTTConfig()),
        ),
        // The community's own channel line-up (see CLAUDE.md): SFNarrow became
        // the standing Zona Centro preset on 2026-07-04, plus the provincial
        // and cross-border channels every node in the region also carries.
        channels = listOf(
            Channel(index = 0, role = Channel.Role.PRIMARY, settings = ChannelSettings(name = "SFNarrow")),
            Channel(index = 1, role = Channel.Role.SECONDARY, settings = ChannelSettings(name = "Madrid")),
            Channel(index = 2, role = Channel.Role.SECONDARY, settings = ChannelSettings(name = "Iberia")),
        ),
        nodes = zonaCentroNodes(),
        traffic = zonaCentroTraffic(),
        trafficIntervalMillis = 700L,
    )

    private fun zonaCentroNodes(): List<NodeInfo> = ZONA_CENTRO_DEFS.mapIndexed { index, def ->
        NodeInfo(
            num = def.num,
            user = User(
                id = "!%08x".format(def.num),
                long_name = def.longName,
                short_name = def.shortName,
                hw_model = def.hwModel,
                role = def.role,
            ),
            position = Position(
                latitude_i = (def.lat * 1e7).roundToInt(),
                longitude_i = (def.lon * 1e7).roundToInt(),
                precision_bits = 32,
            ),
            snr = def.snr,
            last_heard = BASE_LAST_HEARD + index * 4,
            channel = 0,
            hops_away = def.hopsAway,
        )
    }

    private val ZONA_CENTRO_BY_NUM: Map<Int, ZcNode> by lazy { ZONA_CENTRO_DEFS.associateBy { it.num } }

    private fun textData(message: String): Data = Data(
        portnum = PortNum.TEXT_MESSAGE_APP,
        payload = ByteString.of(*message.toByteArray(Charsets.UTF_8)),
    )

    /** Encodes a real Position for [fromNode]'s home coordinates - a fixed relay reporting where it already was. */
    private fun positionData(fromNode: Int, precisionBits: Int = 32): Data {
        val def = ZONA_CENTRO_BY_NUM.getValue(fromNode)
        val position = Position(
            latitude_i = (def.lat * 1e7).roundToInt(),
            longitude_i = (def.lon * 1e7).roundToInt(),
            precision_bits = precisionBits,
        )
        return Data(portnum = PortNum.POSITION_APP, payload = ByteString.of(*position.encode()))
    }

    private fun telemetryData(uptimeSeconds: Int, batteryLevel: Int, voltage: Float): Data {
        val telemetry = Telemetry(
            device_metrics = DeviceMetrics(
                battery_level = batteryLevel,
                voltage = voltage,
                channel_utilization = 12.5f,
                air_util_tx = 3.2f,
                uptime_seconds = uptimeSeconds,
            ),
        )
        return Data(portnum = PortNum.TELEMETRY_APP, payload = ByteString.of(*telemetry.encode()))
    }

    private fun nodeInfoData(fromNode: Int): Data {
        val def = ZONA_CENTRO_BY_NUM.getValue(fromNode)
        val user = User(
            id = "!%08x".format(fromNode),
            long_name = def.longName,
            short_name = def.shortName,
            hw_model = def.hwModel,
            role = def.role,
        )
        return Data(portnum = PortNum.NODEINFO_APP, payload = ByteString.of(*user.encode()))
    }

    /**
     * Opaque bytes standing in for real ciphertext. The app cannot see more than
     * this either - it does not hold the channel key - so there is no reason for
     * the bytes to look like anything, only to exist.
     */
    private fun encryptedBytes(seed: Int): ByteString {
        val bytes = ByteArray(16) { i -> ((seed * 31 + i * 17) and 0xFF).toByte() }
        return ByteString.of(*bytes)
    }

    /**
     * A packet this node's radio heard directly, never forwarded.
     *
     * [hasOwnRelayByte] picks between the two shapes real firmware produces for
     * a direct reception: no relay byte at all, or the sender's own low byte
     * with zero hops made (Geo.lastByteOfNodeNum handles the 0-becomes-0xff
     * substitution the same way a genuine relay byte would need it).
     */
    private fun directPacket(
        from: Int,
        hasOwnRelayByte: Boolean,
        snr: Float,
        rssi: Int,
        decoded: Data?,
        encrypted: ByteString? = null,
    ): MeshPacket = MeshPacket(
        from = from,
        to = BROADCAST_NUM,
        relay_node = if (hasOwnRelayByte) Geo.lastByteOfNodeNum(from) else 0,
        hop_start = 3,
        hop_limit = 3,
        rx_snr = snr,
        rx_rssi = rssi,
        decoded = decoded,
        encrypted = encrypted,
    )

    /** A packet forwarded by [relayByte], having made [hopsMade] hops out of a hop_start of 3. */
    private fun relayedPacket(
        from: Int,
        relayByte: Int,
        hopsMade: Int,
        snr: Float,
        rssi: Int,
        decoded: Data?,
        encrypted: ByteString? = null,
    ): MeshPacket = MeshPacket(
        from = from,
        to = BROADCAST_NUM,
        relay_node = relayByte,
        hop_start = 3,
        hop_limit = (3 - hopsMade).coerceAtLeast(0),
        rx_snr = snr,
        rx_rssi = rssi,
        decoded = decoded,
        encrypted = encrypted,
    )

    /**
     * One cycle of Zona Centro traffic: at least 120 packets (the brief's floor;
     * this produces [TOTAL_TRAFFIC_PACKETS]), repeating a fixed 12-slot pattern
     * so every relay profile, portnum and edge case keeps recurring rather than
     * appearing once and never again. FakeRadioTransport is what loops this
     * list for as long as the demo runs.
     */
    private fun zonaCentroTraffic(): List<FromRadio> {
        val steadyPositionSenders = listOf(NUM_VALDEMORO, NUM_TOLEDO_ESTACION, NUM_OLIAS_DEL_REY)
        val weakTextSenders = listOf(NUM_YUNCOS, NUM_TOLEDO_ALCAZAR, NUM_VALDEMORO)
        val weakPositionSenders = listOf(NUM_OLIAS_DEL_REY, NUM_TOLEDO_ALCAZAR)
        val intermittentSenders = listOf(NUM_TOLEDO_ESTACION, NUM_TOLEDO_ALCAZAR, NUM_OLIAS_DEL_REY)
        val steadyTextSenders = listOf(NUM_OLIAS_DEL_REY, NUM_VALDEMORO, NUM_TOLEDO_ESTACION)
        val encryptedSenders = listOf(NUM_TOLEDO_ALCAZAR, NUM_VALDEMORO, NUM_YUNCOS)
        // Toledo Casco Antiguo forwards its Toledo-area neighbours once it comes
        // into range - not its own traffic. A node relaying only itself two hops
        // after originating a packet is not a pattern real firmware produces.
        val lateRelayOrigins = listOf(NUM_TOLEDO_ALCAZAR, NUM_TOLEDO_ESTACION)
        val nodeInfoRotation = listOf(
            NUM_MADRID_CENTRO, NUM_GETAFE_VECINO, NUM_PINTO, NUM_VALDEMORO, NUM_YUNCOS,
            NUM_OLIAS_DEL_REY, NUM_TOLEDO_ESTACION, NUM_TOLEDO_ALCAZAR, NUM_TOLEDO_CASCO_ANTIGUO,
            NUM_ILLESCAS_VECINO, NUM_GETAFE_ROUTER,
        )
        val directNodeInfoSenders = setOf(NUM_MADRID_CENTRO, NUM_GETAFE_VECINO, NUM_ILLESCAS_VECINO)

        // Yuncos Nodo's own uptime counter: climbs for a while, then falls back
        // near zero. A monotonic firmware counter only falls for one reason - it
        // rebooted and started again from zero - which is exactly what restart
        // detection exists to notice.
        val yuncosUptime = listOf(600, 1500, 2700, 4100, 5600, 7200, 8100, 900, 2200, 3900, 5400)
        val yuncosBattery = listOf(95, 88, 81, 74, 67, 60, 53, 100, 96, 90, 84)

        // A wide, noisy spread on purpose: this is the "weak and variable" relay
        // profile, and the swing between a fair reading and a very poor one is
        // the point, not an accident.
        val weakSnr = listOf(-18.0f, -4.0f, 3.0f, -14.0f, 0.5f, -9.0f, -16.0f)

        val textMessages = listOf(
            "QRV en 869.618, SFNarrow estable por aqui.",
            "Buenas tardes desde el corredor Madrid-Toledo.",
            "Paquete de prueba, ignorar.",
            "Cambio de bateria hecho, seguimos escuchando.",
            "Enlace con Illescas algo debil hoy.",
            "Repetidor funcionando con normalidad.",
        )

        val packets = mutableListOf<MeshPacket>()
        for (i in 0 until TOTAL_TRAFFIC_PACKETS) {
            val occurrence = i / 12
            val packet: MeshPacket = when (i % 12) {
                // Directly heard: Madrid Centro, right next to the receiver.
                0 -> directPacket(
                    from = NUM_MADRID_CENTRO,
                    hasOwnRelayByte = occurrence % 2 == 1,
                    snr = 9.0f + (occurrence % 3) * 0.3f,
                    rssi = -58 - (occurrence % 5),
                    decoded = textData(textMessages[occurrence % textMessages.size]),
                )
                // RELAY_STEADY: strong and steady, on almost every lap.
                1 -> {
                    val from = steadyPositionSenders[occurrence % steadyPositionSenders.size]
                    relayedPacket(
                        from = from, relayByte = RELAY_STEADY, hopsMade = 1,
                        snr = 6.5f + (occurrence % 4) * 0.6f, rssi = -75 + (occurrence % 6),
                        decoded = positionData(from),
                    )
                }
                // RELAY_WEAK: weak and variable - a noisy SNR table, not a formula.
                2 -> {
                    val from = weakTextSenders[occurrence % weakTextSenders.size]
                    relayedPacket(
                        from = from, relayByte = RELAY_WEAK,
                        hopsMade = if (occurrence % 2 == 0) 2 else 3,
                        snr = weakSnr[occurrence % weakSnr.size],
                        rssi = -95 - (occurrence % 5) * 5,
                        decoded = textData(textMessages[(occurrence + 2) % textMessages.size]),
                    )
                }
                // Every node except Illescas Router re-announces itself once per
                // 11 occurrences, direct for the nearby three, relayed otherwise.
                3 -> {
                    val from = nodeInfoRotation[occurrence % nodeInfoRotation.size]
                    if (from in directNodeInfoSenders) {
                        directPacket(
                            from = from, hasOwnRelayByte = occurrence % 2 == 1,
                            snr = 8.0f, rssi = -62, decoded = nodeInfoData(from),
                        )
                    } else {
                        val steady = occurrence % 2 == 0
                        relayedPacket(
                            from = from,
                            relayByte = if (steady) RELAY_STEADY else RELAY_WEAK,
                            hopsMade = if (steady) 1 else 2,
                            snr = if (steady) 7.5f else -4.0f,
                            rssi = if (steady) -70 else -102,
                            decoded = nodeInfoData(from),
                        )
                    }
                }
                // Yuncos Nodo's telemetry, carrying the restart signature above.
                4 -> relayedPacket(
                    from = NUM_YUNCOS, relayByte = RELAY_STEADY, hopsMade = 1,
                    snr = 7.0f, rssi = -72,
                    decoded = telemetryData(
                        uptimeSeconds = yuncosUptime[occurrence % yuncosUptime.size],
                        batteryLevel = yuncosBattery[occurrence % yuncosBattery.size],
                        voltage = 3.9f - (occurrence % 5) * 0.05f,
                    ),
                )
                // Encrypted: no decoded payload, header fields still legible - the
                // same as real Meshtastic traffic on a channel this phone can't read.
                5 -> {
                    val from = encryptedSenders[occurrence % encryptedSenders.size]
                    when (occurrence % 3) {
                        0 -> relayedPacket(
                            from = from, relayByte = RELAY_STEADY, hopsMade = 1,
                            snr = 7.2f, rssi = -69, decoded = null, encrypted = encryptedBytes(occurrence),
                        )
                        1 -> relayedPacket(
                            from = from, relayByte = RELAY_WEAK, hopsMade = 2,
                            snr = -9.0f, rssi = -108, decoded = null, encrypted = encryptedBytes(occurrence),
                        )
                        else -> directPacket(
                            from = from, hasOwnRelayByte = false,
                            snr = 8.5f, rssi = -60, decoded = null, encrypted = encryptedBytes(occurrence),
                        )
                    }
                }
                // Directly heard: Getafe Vecino, the CLIENT_MUTE node - mute nodes
                // never rebroadcast, so hearing them is always a direct reception.
                6 -> directPacket(
                    from = NUM_GETAFE_VECINO, hasOwnRelayByte = occurrence % 2 == 1,
                    snr = 10.5f + (occurrence % 3) * 0.4f, rssi = -55 - (occurrence % 4),
                    decoded = if (occurrence % 2 == 0) {
                        textData(textMessages[occurrence % textMessages.size])
                    } else {
                        positionData(NUM_GETAFE_VECINO)
                    },
                )
                // RELAY_WEAK again, on position traffic this time.
                7 -> {
                    val from = weakPositionSenders[occurrence % weakPositionSenders.size]
                    relayedPacket(
                        from = from, relayByte = RELAY_WEAK,
                        hopsMade = if (occurrence % 2 == 0) 2 else 3,
                        snr = weakSnr[(occurrence + 1) % weakSnr.size],
                        rssi = -98 - (occurrence % 4) * 4,
                        decoded = positionData(from),
                    )
                }
                // RELAY_INTERMITTENT only inside its two short bursts; RELAY_STEADY
                // fills in for the four occurrences of silence in between.
                8 -> {
                    val from = intermittentSenders[occurrence % intermittentSenders.size]
                    if (occurrence in INTERMITTENT_BURST_OCCURRENCES) {
                        relayedPacket(
                            from = from, relayByte = RELAY_INTERMITTENT,
                            hopsMade = if (occurrence % 2 == 0) 1 else 2,
                            snr = 1.5f + (occurrence % 3), rssi = -92 - (occurrence % 3) * 3,
                            decoded = textData(textMessages[(occurrence + 3) % textMessages.size]),
                        )
                    } else {
                        relayedPacket(
                            from = from, relayByte = RELAY_STEADY, hopsMade = 1,
                            snr = 7.8f, rssi = -68,
                            decoded = textData(textMessages[(occurrence + 3) % textMessages.size]),
                        )
                    }
                }
                // Directly heard: Illescas Vecino.
                9 -> directPacket(
                    from = NUM_ILLESCAS_VECINO, hasOwnRelayByte = occurrence % 2 == 0,
                    snr = 7.5f + (occurrence % 3) * 0.3f, rssi = -63 - (occurrence % 5),
                    decoded = textData(textMessages[(occurrence + 4) % textMessages.size]),
                )
                // RELAY_STEADY, on text traffic.
                10 -> {
                    val from = steadyTextSenders[occurrence % steadyTextSenders.size]
                    relayedPacket(
                        from = from, relayByte = RELAY_STEADY, hopsMade = 1,
                        snr = 7.0f + (occurrence % 4) * 0.4f, rssi = -70 + (occurrence % 4),
                        decoded = textData(textMessages[(occurrence + 5) % textMessages.size]),
                    )
                }
                // RELAY_LATE from Toledo Casco Antiguo once occurrence reaches the
                // threshold - before that, RELAY_WEAK fills the slot instead, so a
                // screen fed only the first portion of the cycle never sees byte
                // 0xff appear at all.
                else -> if (occurrence >= LATE_RELAY_START_OCCURRENCE) {
                    val from = lateRelayOrigins[occurrence % lateRelayOrigins.size]
                    relayedPacket(
                        from = from, relayByte = RELAY_LATE, hopsMade = 2,
                        snr = -2.0f + (occurrence % 3), rssi = -90 - (occurrence % 3) * 2,
                        decoded = positionData(from),
                    )
                } else {
                    val from = weakPositionSenders[occurrence % weakPositionSenders.size]
                    relayedPacket(
                        from = from, relayByte = RELAY_WEAK,
                        hopsMade = if (occurrence % 2 == 0) 2 else 3,
                        snr = weakSnr[occurrence % weakSnr.size],
                        rssi = -100 - (occurrence % 3) * 3,
                        decoded = positionData(from),
                    )
                }
            }
            packets += packet
        }
        return packets.map { FromRadio(packet = it) }
    }
}
