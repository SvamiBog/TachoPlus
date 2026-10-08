package com.example.domain.protocol

import com.example.data.model.DriverActivity

class J1939Frame(
    val priority: Int,
    val pgn: Int,
    val sourceAddress: Int,
    val data: ByteArray
) {
    fun byte(index: Int): Int = if (index < data.size) data[index].toInt() and 0xFF else 0xFF

    fun word(index: Int): Int = byte(index) or (byte(index + 1) shl 8)

    fun dword(index: Int): Long =
        byte(index).toLong() or (byte(index + 1).toLong() shl 8) or
            (byte(index + 2).toLong() shl 16) or (byte(index + 3).toLong() shl 24)

    override fun toString(): String =
        "PGN %04X SA %02X [%s]".format(pgn, sourceAddress, data.joinToString(" ") { "%02X".format(it) })
}

/**
 * SAE J1939 / FMS-Standard messages that carry tachograph and vehicle data.
 * Byte numbering in comments follows J1939-71 (byte 1 = data[0]).
 */
object J1939 {
    /** Tachograph: driver working states, cards, time states, speed. */
    const val PGN_TCO1 = 0xFE6C
    /** Cruise control / vehicle speed: wheel-based speed. */
    const val PGN_CCVS = 0xFEF1
    /** Electronic engine controller 1: engine speed. */
    const val PGN_EEC1 = 0xF004
    /** High resolution vehicle distance. */
    const val PGN_VDHR = 0xFEC1
    /** Vehicle distance. */
    const val PGN_VD = 0xFEE0
    /** Driver's identification (driver card numbers). */
    const val PGN_DI = 0xFE6B
    /** Vehicle identification (VIN). */
    const val PGN_VI = 0xFEEC
    const val PGN_TP_CM = 0xEC00
    const val PGN_TP_DT = 0xEB00

    private const val MAX_VALID_WORD = 0xFAFF
    private const val MAX_VALID_DWORD = 0xFAFFFFFFL

    /** Builds a frame from a 29-bit CAN identifier. */
    fun frameFromId(id: Long, data: ByteArray): J1939Frame {
        val priority = ((id shr 26) and 0x7).toInt()
        val edp = ((id shr 25) and 0x1).toInt()
        val dp = ((id shr 24) and 0x1).toInt()
        val pf = ((id shr 16) and 0xFF).toInt()
        val ps = ((id shr 8) and 0xFF).toInt()
        val sa = (id and 0xFF).toInt()
        val pgn = (edp shl 17) or (dp shl 16) or (pf shl 8) or (if (pf >= 0xF0) ps else 0)
        return J1939Frame(priority, pgn, sa, data)
    }

    fun decode(frame: J1939Frame): VehicleUpdate? = when (frame.pgn) {
        PGN_TCO1 -> decodeTco1(frame)
        PGN_CCVS -> decodeCcvs(frame)
        PGN_EEC1 -> decodeEec1(frame)
        PGN_VDHR -> decodeVdhr(frame)
        PGN_VD -> decodeVd(frame)
        PGN_DI -> decodeDriverIdentification(frame)
        PGN_VI -> decodeVehicleIdentification(frame)
        else -> null
    }

    fun decodeTco1(frame: J1939Frame): VehicleUpdate? {
        if (frame.data.size < 8) return null
        val b1 = frame.byte(0)
        val b2 = frame.byte(1)
        val b3 = frame.byte(2)
        val speedRaw = frame.word(6)
        return VehicleUpdate(
            driver1Activity = workingState(b1 and 0x07),
            driver2Activity = workingState((b1 shr 3) and 0x07),
            vehicleMotion = twoBit((b1 shr 6) and 0x03),
            driver1TimeState = TimeRelatedState.fromRaw(b2 and 0x0F),
            driver1CardPresent = twoBit((b2 shr 4) and 0x03),
            overspeed = twoBit((b2 shr 6) and 0x03),
            driver2TimeState = TimeRelatedState.fromRaw(b3 and 0x0F),
            driver2CardPresent = twoBit((b3 shr 4) and 0x03),
            tachographSpeedKmh = if (speedRaw <= MAX_VALID_WORD) speedRaw / 256.0 else null
        )
    }

    fun decodeCcvs(frame: J1939Frame): VehicleUpdate? {
        if (frame.data.size < 3) return null
        val raw = frame.word(1)
        return if (raw <= MAX_VALID_WORD) VehicleUpdate(wheelSpeedKmh = raw / 256.0) else null
    }

    fun decodeEec1(frame: J1939Frame): VehicleUpdate? {
        if (frame.data.size < 5) return null
        val raw = frame.word(3)
        return if (raw <= MAX_VALID_WORD) VehicleUpdate(engineRpm = raw * 0.125) else null
    }

    fun decodeVdhr(frame: J1939Frame): VehicleUpdate? {
        if (frame.data.size < 4) return null
        val raw = frame.dword(0)
        return if (raw <= MAX_VALID_DWORD) VehicleUpdate(odometerKm = raw * 0.005) else null
    }

    fun decodeVd(frame: J1939Frame): VehicleUpdate? {
        if (frame.data.size < 8) return null
        val raw = frame.dword(4) // bytes 5–8: total vehicle distance, 0.125 km/bit
        return if (raw <= MAX_VALID_DWORD) VehicleUpdate(odometerKm = raw * 0.125) else null
    }

    /** DI: "driver 1 id*driver 2 id*" in ASCII. */
    fun decodeDriverIdentification(frame: J1939Frame): VehicleUpdate? {
        val fields = ascii(frame.data).split('*')
        val driver1 = fields.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() }
        val driver2 = fields.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        if (driver1 == null && driver2 == null) return null
        return VehicleUpdate(driver1Id = driver1, driver2Id = driver2)
    }

    /** VI: "VIN*" in ASCII. */
    fun decodeVehicleIdentification(frame: J1939Frame): VehicleUpdate? {
        val vin = ascii(frame.data).substringBefore('*').trim()
        return if (vin.length >= 11) VehicleUpdate(vin = vin) else null
    }

    private fun workingState(raw: Int): DriverActivity? = when (raw) {
        0 -> DriverActivity.REST
        1 -> DriverActivity.AVAILABLE
        2 -> DriverActivity.WORK
        3 -> DriverActivity.DRIVING
        else -> null // 6 = error, 7 = not available
    }

    private fun twoBit(raw: Int): Boolean? = when (raw) {
        0 -> false
        1 -> true
        else -> null
    }

    private fun ascii(bytes: ByteArray): String = buildString {
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) append(c.toChar())
        }
    }
}

/**
 * Reassembles J1939 broadcast multi-packet messages (TP.CM BAM + TP.DT), e.g. DI or VI with more than 8 bytes.
 */
class J1939TransportAssembler {
    private class Pending(val pgn: Int, val size: Int, val packets: Int) {
        val buffer = ByteArray(packets * 7)
        var received = 0
    }

    private val pending = HashMap<Int, Pending>()

    /** Returns a reassembled frame when [frame] completes a message, otherwise null. */
    fun accept(frame: J1939Frame): J1939Frame? {
        when (frame.pgn) {
            J1939.PGN_TP_CM -> {
                if (frame.byte(0) == 0x20 && frame.data.size >= 8) { // BAM
                    val size = frame.word(1)
                    val packets = frame.byte(3)
                    val pgn = frame.byte(5) or (frame.byte(6) shl 8) or (frame.byte(7) shl 16)
                    if (packets in 1..255 && size in 9..1785) {
                        pending[frame.sourceAddress] = Pending(pgn, size, packets)
                    }
                }
            }
            J1939.PGN_TP_DT -> {
                val message = pending[frame.sourceAddress] ?: return null
                val sequence = frame.byte(0)
                if (sequence !in 1..message.packets || frame.data.size < 2) return null
                val offset = (sequence - 1) * 7
                frame.data.copyInto(message.buffer, offset, 1, minOf(frame.data.size, 8))
                message.received++
                if (sequence == message.packets || message.received >= message.packets) {
                    pending.remove(frame.sourceAddress)
                    return J1939Frame(frame.priority, message.pgn, frame.sourceAddress, message.buffer.copyOf(message.size))
                }
            }
        }
        return null
    }
}
