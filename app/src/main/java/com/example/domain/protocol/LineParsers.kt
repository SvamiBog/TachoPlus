package com.example.domain.protocol

import com.example.data.model.DriverActivity

/** A CAN frame as printed by an ELM327/STN adapter or a candump-style gateway. */
class CanLine(val id: Long, val extended: Boolean, val data: ByteArray)

object ElmResponses {
    private val statusPrefixes = listOf(
        "OK", "?", "STOPPED", "NO DATA", "SEARCHING", "BUFFER FULL", "CAN ERROR", "BUS INIT", "BUS ERROR",
        "BUS BUSY", "UNABLE TO CONNECT", "DATA ERROR", "<DATA ERROR", "<RX ERROR", "RX ERROR", "FB ERROR",
        "LV RESET", "ACT ALERT", "ERROR", "ELM", "STN", "OBDLINK", "LP ALERT"
    )

    fun isStatusLine(line: String): Boolean {
        val upper = line.trim().uppercase()
        return upper.isEmpty() || statusPrefixes.any { upper.startsWith(it) }
    }

    private fun isHex(token: String) = token.isNotEmpty() && token.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }

    private fun hexBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || !isHex(hex)) return null
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * Parses one line of ELM327 monitor/response output with headers on.
     * Accepted forms (spaces optional):
     *  - 29-bit:            "18 FE 6C 00 C3 41 00 C0 FF FF 00 50"
     *  - 29-bit, ATJHF1:    "6 FE6C 00 C3 41 00 C0 FF FF 00 50" (priority, PGN, source address)
     *  - 11-bit:            "7E8 03 41 0D 52"
     */
    fun parseCanLine(rawLine: String, expectExtended: Boolean): CanLine? {
        val line = rawLine.trim()
        if (isStatusLine(line)) return null
        val tokens = line.split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty() || !tokens.all(::isHex)) return null

        if (tokens.size >= 3 && tokens[0].length == 1 && tokens[1].length in 4..5 && tokens[2].length == 2) {
            // J1939 header formatting: priority, PGN, source address
            val priority = tokens[0].toInt(16)
            val pgn = tokens[1].toInt(16)
            val sa = tokens[2].toInt(16)
            val pf = (pgn shr 8) and 0xFF
            val ps = if (pf >= 0xF0) pgn and 0xFF else 0
            val id = (priority.toLong() shl 26) or (((pgn shr 16) and 0x3).toLong() shl 24) or
                (pf.toLong() shl 16) or (ps.toLong() shl 8) or sa.toLong()
            val data = hexBytes(tokens.drop(3).joinToString("")) ?: return null
            return CanLine(id, true, data)
        }

        if (tokens.size > 1 && tokens[0].length == 3 && tokens.drop(1).all { it.length == 2 }) {
            val data = hexBytes(tokens.drop(1).joinToString("")) ?: return null
            return CanLine(tokens[0].toLong(16), false, data)
        }

        val joined = tokens.joinToString("")
        if (tokens.size > 1 && tokens.any { it.length != 2 }) return null
        return if (expectExtended) {
            if (joined.length < 8) return null
            val data = hexBytes(joined.substring(8)) ?: return null
            CanLine(joined.substring(0, 8).toLong(16), true, data)
        } else {
            if (joined.length < 3 || (joined.length - 3) % 2 != 0) return null
            val data = hexBytes(joined.substring(3)) ?: return null
            CanLine(joined.substring(0, 3).toLong(16), false, data)
        }
    }

    /** Returns the data bytes of a positive OBD-II response (headers off), e.g. "41 0D 52" → [41, 0D, 52]. */
    fun parseObdBytes(rawLine: String): ByteArray? {
        val line = rawLine.trim()
        if (isStatusLine(line)) return null
        // Multi-line responses are prefixed with "0:", "1:" … by the ELM327.
        val withoutIndex = line.substringAfter(':', line).trim()
        val hex = withoutIndex.replace(" ", "")
        return hexBytes(hex)
    }
}

object ObdDecoder {
    /** Decodes a mode 01 response for [pid] from the bytes of one line. */
    fun decodeMode01(bytes: ByteArray, pid: Int): VehicleUpdate? {
        val values = bytes.map { it.toInt() and 0xFF }
        val index = (0 until values.size - 1).firstOrNull { values[it] == 0x41 && values[it + 1] == pid } ?: return null
        val a = values.getOrNull(index + 2) ?: return null
        val b = values.getOrNull(index + 3)
        return when (pid) {
            0x0D -> VehicleUpdate(wheelSpeedKmh = a.toDouble())
            0x0C -> b?.let { VehicleUpdate(engineRpm = (a * 256 + it) / 4.0) }
            0xA6 -> {
                val second = b ?: return null
                val c = values.getOrNull(index + 4) ?: return null
                val d = values.getOrNull(index + 5) ?: return null
                val raw = (a.toLong() shl 24) or (second.toLong() shl 16) or (c.toLong() shl 8) or d.toLong()
                VehicleUpdate(odometerKm = raw / 10.0)
            }
            else -> null
        }
    }

    /** Extracts a VIN from the lines of a mode 09 PID 02 response. */
    fun decodeVin(lines: List<String>): String? {
        val bytes = lines.mapNotNull { ElmResponses.parseObdBytes(it) }.flatMap { it.toList() }
        val values = bytes.map { it.toInt() and 0xFF }
        val start = (0 until values.size - 1).firstOrNull { values[it] == 0x49 && values[it + 1] == 0x02 } ?: return null
        val text = values.drop(start + 2)
            .filter { it in 0x30..0x5A }
            .map { it.toChar() }
            .joinToString("")
        return text.takeLast(17).takeIf { it.length == 17 }
    }
}

/**
 * Parser for a simple line protocol, meant for custom CAN bridges and for testing with any Bluetooth terminal.
 *
 * Each line holds `KEY=VALUE` pairs separated by `;` or `,`, for example
 * `ACT=D;SPD=82;CARD1=DF00000012345601;ODO=284190.5`.
 * A line in candump format (`18FE6C00#C3410000FFFF0050` or `can0 18FE6C00 [8] C3 41 …`) is decoded as J1939.
 */
object TextGatewayParser {
    private val candumpCompact = Regex("""(?:^|\s)([0-9A-Fa-f]{3}|[0-9A-Fa-f]{8})#([0-9A-Fa-f]*)\s*$""")
    private val candumpSpaced = Regex("""^\S+\s+([0-9A-Fa-f]{3}|[0-9A-Fa-f]{8})\s+\[(\d)]\s+((?:[0-9A-Fa-f]{2}\s*)*)$""")

    fun parseCandump(line: String): CanLine? {
        val trimmed = line.trim()
        candumpCompact.find(trimmed)?.let { match ->
            val id = match.groupValues[1]
            val hex = match.groupValues[2]
            if (hex.length % 2 != 0) return null
            val data = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return CanLine(id.toLong(16), id.length == 8, data)
        }
        candumpSpaced.find(trimmed)?.let { match ->
            val id = match.groupValues[1]
            val bytes = match.groupValues[3].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val data = ByteArray(bytes.size) { bytes[it].toInt(16).toByte() }
            return CanLine(id.toLong(16), id.length == 8, data)
        }
        return null
    }

    fun parse(line: String): VehicleUpdate? {
        val pairs = line.split(';', ',')
            .mapNotNull { part ->
                val key = part.substringBefore('=', "").trim().uppercase()
                val value = part.substringAfter('=', "").trim()
                if (key.isEmpty() || !part.contains('=')) null else key to value
            }
        if (pairs.isEmpty()) return null

        var update = VehicleUpdate()
        var recognised = false
        for ((key, value) in pairs) {
            val next = when (key) {
                "ACT", "ACT1" -> activity(value)?.let { update.copy(driver1Activity = it) }
                "ACT2" -> activity(value)?.let { update.copy(driver2Activity = it) }
                "SPD", "SPEED" -> value.toDoubleOrNull()?.takeIf { it in 0.0..250.0 }?.let { update.copy(tachographSpeedKmh = it) }
                "RPM" -> value.toDoubleOrNull()?.takeIf { it in 0.0..10000.0 }?.let { update.copy(engineRpm = it) }
                "ODO" -> value.toDoubleOrNull()?.takeIf { it >= 0 }?.let { update.copy(odometerKm = it) }
                "MOTION" -> flag(value)?.let { update.copy(vehicleMotion = it) }
                "CARD1" -> card(value).let { (present, id) -> update.copy(driver1CardPresent = present, driver1Id = id) }
                "CARD2" -> card(value).let { (present, id) -> update.copy(driver2CardPresent = present, driver2Id = id) }
                "CARD1_IN" -> flag(value)?.let { update.copy(driver1CardPresent = it) }
                "CARD2_IN" -> flag(value)?.let { update.copy(driver2CardPresent = it) }
                "NAME1" -> value.takeIf { it.isNotBlank() }?.let { update.copy(driver1Name = it.take(48)) }
                "VIN" -> value.uppercase().takeIf { it.length == 17 && it.all(Char::isLetterOrDigit) }?.let { update.copy(vin = it) }
                "VRN", "PLATE" -> value.takeIf { it.isNotBlank() }?.let { update.copy(registration = it.take(20)) }
                else -> null
            }
            if (next != null) {
                update = next
                recognised = true
            }
        }
        return if (recognised) update else null
    }

    private fun activity(value: String): DriverActivity? = when (value.trim().uppercase()) {
        "D", "DRIVE", "DRIVING", "3" -> DriverActivity.DRIVING
        "W", "WORK", "2" -> DriverActivity.WORK
        "A", "AVAIL", "AVAILABLE", "1" -> DriverActivity.AVAILABLE
        "R", "REST", "BREAK", "0" -> DriverActivity.REST
        else -> null
    }

    private fun flag(value: String): Boolean? = when (value.trim().uppercase()) {
        "1", "Y", "YES", "TRUE", "ON" -> true
        "0", "N", "NO", "FALSE", "OFF" -> false
        else -> null
    }

    private fun card(value: String): Pair<Boolean, String?> {
        val id = value.trim()
        return if (id.isEmpty() || id == "0" || id == "-" || id.equals("NONE", ignoreCase = true)) false to null
        else true to id.take(24)
    }
}
