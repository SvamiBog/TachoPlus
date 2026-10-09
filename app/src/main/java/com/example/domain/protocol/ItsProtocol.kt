package com.example.domain.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * ITS interface of the smart tachograph, Regulation (EU) 2016/799 Annex IC Appendix 13.
 *
 * Messages run over Bluetooth SPP and look like
 * `TGT SRC LEN | SID TRTP CC CM DATA… | CS`: the vehicle unit (VU) has the ID 0xEE, the phone starts as 0xA0 and
 * gets its own ID from the VU, LEN counts the DATA bytes, CC/CM number the parts of a split message (0xFF when
 * unused) and CS is the byte sum modulo 256.
 */
object Its {
    const val VU_ID = 0xEE
    const val DEFAULT_UNIT_ID = 0xA0
    const val UNUSED = 0xFF

    const val SID_REQUEST_PIN = 0x01
    const val SID_SEND_ITS_ID = 0x02
    const val SID_SEND_PIN = 0x03
    const val SID_PAIRING_RESULT = 0x04
    const val SID_SEND_PUC = 0x05
    const val SID_BAN_LIFTING_RESULT = 0x06
    const val SID_REQUEST_REJECTED = 0x07
    const val SID_REQUEST_DATA = 0x08
    const val SID_REQUEST_ACCEPTED = 0x09
    const val SID_DATA_UNAVAILABLE = 0x0A
    const val SID_NEGATIVE_ANSWER = 0x0B

    const val CODE_INCORRECT_LENGTH = 0x13
    const val CODE_PERSONAL_NOT_SHARED = 0x11

    enum class DataType(val trtp: Int, val titleRu: String, val personal: Boolean) {
        STANDARD_TACH(0x01, "данные тахографа", false),
        PERSONAL_TACH(0x02, "данные водителей", true),
        GNSS(0x03, "GNSS", true),
        STANDARD_EVENT(0x04, "события", false),
        PERSONAL_EVENT(0x05, "события водителя", true),
        STANDARD_FAULT(0x06, "неисправности", false),
        MANUFACTURER(0x07, "данные производителя", true);

        companion object {
            fun of(trtp: Int) = entries.firstOrNull { it.trtp == trtp }
        }
    }

    fun sidName(sid: Int): String = when (sid) {
        SID_REQUEST_PIN -> "RequestPIN"
        SID_SEND_ITS_ID -> "SendITSID"
        SID_SEND_PIN -> "SendPIN"
        SID_PAIRING_RESULT -> "PairingResult"
        SID_SEND_PUC -> "SendPUC"
        SID_BAN_LIFTING_RESULT -> "BanLiftingResult"
        SID_REQUEST_REJECTED -> "RequestRejected"
        SID_REQUEST_DATA -> "RequestData"
        SID_REQUEST_ACCEPTED -> "RequestAccepted"
        SID_DATA_UNAVAILABLE -> "DataUnavailable"
        SID_NEGATIVE_ANSWER -> "NegativeAnswer"
        else -> "SID %02X".format(sid)
    }

    fun negativeReason(code: Int): String = when (code) {
        0x10 -> "общий отказ"
        0x11 -> "сервис не поддерживается"
        0x12 -> "тип данных не поддерживается"
        0x13 -> "неверная длина сообщения"
        0x22 -> "неверная последовательность запросов"
        0x31, 0x33 -> "параметр запроса вне диапазона"
        0x78 -> "ответ готовится, повторите позже"
        0xFB -> "ID не совпадает с устройством"
        0xFC -> "ID не найден"
        else -> "код %02X".format(code)
    }

    fun checksum(bytes: ByteArray, from: Int, toExclusive: Int): Int {
        var sum = 0
        for (i in from until toExclusive) sum += bytes[i].toInt() and 0xFF
        return sum and 0xFF
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }
}

class ItsFrame(
    val target: Int,
    val source: Int,
    val sid: Int,
    val trtp: Int,
    val cc: Int,
    val cm: Int,
    val data: ByteArray
) {
    val isPart: Boolean get() = cc != Its.UNUSED && cm != Its.UNUSED

    /**
     * Serialises the frame. [withCounters] = false drops CC/CM (some implementations follow Table 3, which omits them);
     * [lenOverride] replaces the LEN byte for the RequestData length ambiguity in the regulation.
     */
    fun encode(withCounters: Boolean = true, lenOverride: Int? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(target)
        out.write(source)
        out.write(lenOverride ?: data.size)
        out.write(sid)
        out.write(trtp)
        if (withCounters) {
            out.write(cc)
            out.write(cm)
        }
        out.write(data)
        val bytes = out.toByteArray()
        return bytes + Its.checksum(bytes, 0, bytes.size).toByte()
    }

    override fun toString(): String =
        "${Its.sidName(sid)} %02X→%02X TRTP %02X".format(source, target, trtp) +
            (if (isPart) " часть $cc/$cm" else "") +
            (if (data.isNotEmpty()) " [${data.size} Б]" else "")
}

/**
 * Splits the byte stream from the VU into frames. The regulation is not consistent about whether CC/CM are present
 * and what LEN counts, so every plausible layout is tried and the checksum decides.
 */
class ItsFrameParser {
    private var buffer = ByteArray(0)
    private val parts = HashMap<Pair<Int, Int>, ByteArrayOutputStream>()

    /** Layout of the last frame that passed the checksum: true when CC/CM were present. */
    var lastHadCounters: Boolean? = null
        private set

    fun feed(chunk: ByteArray): List<ItsFrame> {
        buffer += chunk
        val frames = ArrayList<ItsFrame>()
        var start = 0
        while (true) {
            // A frame from the VU has SRC = 0xEE in its second byte.
            var i = start
            while (i + 1 < buffer.size && (buffer[i + 1].toInt() and 0xFF) != Its.VU_ID) i++
            if (i + 3 > buffer.size) {
                start = i
                break
            }
            val len = buffer[i + 2].toInt() and 0xFF
            var parsed: Pair<ItsFrame, Int>? = null
            var needMore = false
            for (layout in Layout.entries) {
                val total = layout.total(len)
                if (total < 4) continue
                if (i + total > buffer.size) {
                    needMore = true
                    continue
                }
                if (Its.checksum(buffer, i, i + total - 1) == (buffer[i + total - 1].toInt() and 0xFF)) {
                    parsed = layout.build(buffer, i, len) to total
                    lastHadCounters = layout == Layout.WITH_COUNTERS
                    break
                }
            }
            if (parsed != null) {
                start = i + parsed.second
                assemble(parsed.first)?.let(frames::add)
                continue
            }
            if (needMore && buffer.size - i < MAX_FRAME) {
                start = i
                break
            }
            start = i + 1 // not a frame start: resynchronise
        }
        buffer = buffer.copyOfRange(start.coerceAtMost(buffer.size), buffer.size)
        return frames
    }

    private fun assemble(frame: ItsFrame): ItsFrame? {
        if (!frame.isPart) return frame
        val key = frame.sid to frame.trtp
        val acc = if (frame.cc <= 1) ByteArrayOutputStream().also { parts[key] = it } else parts[key] ?: return null
        acc.write(frame.data)
        if (frame.cc < frame.cm) return null
        parts.remove(key)
        return ItsFrame(frame.target, frame.source, frame.sid, frame.trtp, Its.UNUSED, Its.UNUSED, acc.toByteArray())
    }

    private enum class Layout {
        /** Table 2: SID TRTP CC CM DATA[LEN]. */
        WITH_COUNTERS {
            override fun total(len: Int) = len + 8
            override fun build(b: ByteArray, i: Int, len: Int) =
                ItsFrame(u(b, i), u(b, i + 1), u(b, i + 3), u(b, i + 4), u(b, i + 5), u(b, i + 6), b.copyOfRange(i + 7, i + 7 + len))
        },

        /** Table 3: SID TRTP DATA[LEN]. */
        WITHOUT_COUNTERS {
            override fun total(len: Int) = len + 6
            override fun build(b: ByteArray, i: Int, len: Int) =
                ItsFrame(u(b, i), u(b, i + 1), u(b, i + 3), u(b, i + 4), Its.UNUSED, Its.UNUSED, b.copyOfRange(i + 5, i + 5 + len))
        },

        /** LEN counting everything after the header. */
        LEN_COUNTS_ALL {
            override fun total(len: Int) = len + 4
            override fun build(b: ByteArray, i: Int, len: Int): ItsFrame {
                val content = b.copyOfRange(i + 3, i + 3 + len)
                return ItsFrame(
                    u(b, i), u(b, i + 1),
                    content.getOrNull(0)?.toInt()?.and(0xFF) ?: Its.UNUSED,
                    content.getOrNull(1)?.toInt()?.and(0xFF) ?: Its.UNUSED,
                    Its.UNUSED, Its.UNUSED,
                    if (content.size > 2) content.copyOfRange(2, content.size) else ByteArray(0)
                )
            }
        };

        abstract fun total(len: Int): Int
        abstract fun build(b: ByteArray, i: Int, len: Int): ItsFrame

        companion object {
            fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
        }
    }

    private companion object {
        const val MAX_FRAME = 3 + 4 + 255 + 1
    }
}

/**
 * Best-effort reading of ITS data. The regulation gives the content as ASN.1 (fields from ISO 16844-7) but not the
 * encoding rules, so only values that can be recognised unambiguously are taken; everything is logged in hex.
 */
object ItsDataDecoder {
    private val vinRegex = Regex("[A-HJ-NPR-Z0-9]{17}")
    private val cardRegex = Regex("[A-Z0-9]{14,16}")

    fun decode(type: Its.DataType, data: ByteArray): VehicleUpdate? {
        val text = String(CharArray(data.size) { i ->
            val c = data[i].toInt() and 0xFF
            if (c in 0x20..0x7E) c.toChar() else '\u0000'
        })
        val runs = text.split('\u0000').map { it.trim() }.filter { it.length >= 4 }
        return when (type) {
            Its.DataType.STANDARD_TACH -> runs.firstNotNullOfOrNull { vinRegex.find(it)?.value }?.let { VehicleUpdate(vin = it) }
            Its.DataType.PERSONAL_TACH -> {
                val cards = runs.flatMap { run -> cardRegex.findAll(run).map { it.value }.toList() }
                    .filter { card -> card.any(Char::isDigit) && card.any(Char::isLetter) }
                if (cards.isEmpty()) null else VehicleUpdate(driver1Id = cards.getOrNull(0), driver2Id = cards.getOrNull(1))
            }
            else -> null
        }
    }
}

/**
 * Talks to the VU over its ITS interface: obtains an ID, answers the PIN request with the PIN the driver reads on
 * the tachograph, then polls tachograph and driver data. Runs until the link fails.
 */
class ItsSession(
    private val transport: ByteTransport,
    private val listener: TachoSession.Listener,
    private val userCommands: ReceiveChannel<String>,
    private val pins: ReceiveChannel<String>
) {
    private val parser = ItsFrameParser()
    private var unitId = Its.DEFAULT_UNIT_ID
    private var withCounters = true
    private var requestVariant = 0
    private var pinFailures = 0
    private var personalBlockedUntil = 0L

    suspend fun run() = coroutineScope {
        val frames = Channel<ItsFrame>(Channel.UNLIMITED)
        val reader = launch {
            try {
                transport.incoming.collect { chunk ->
                    listener.onBytes(chunk.size)
                    listener.onLog("RX ${Its.hex(chunk)}")
                    parser.feed(chunk).forEach { frames.trySend(it) }
                }
                frames.close(EOFException("Соединение закрыто тахографом"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                frames.close(e)
            }
        }
        try {
            listener.onModeResolved(ProtocolMode.ITS_TACHOGRAPH, "ITS-интерфейс (Прил. 13)")
            poll(frames)
        } finally {
            reader.cancel()
        }
    }

    private suspend fun poll(frames: Channel<ItsFrame>) {
        var lastStandard = 0L
        var silentRequests = 0
        while (true) {
            runUserCommands()
            val now = System.currentTimeMillis()
            val type = when {
                now - lastStandard >= STANDARD_INTERVAL_MS -> Its.DataType.STANDARD_TACH
                now < personalBlockedUntil -> null
                else -> Its.DataType.PERSONAL_TACH
            }
            if (type == null) {
                delay(1_000)
                continue
            }
            when (exchange(frames, type)) {
                Outcome.DATA -> {
                    silentRequests = 0
                    if (type == Its.DataType.STANDARD_TACH) lastStandard = System.currentTimeMillis()
                }
                Outcome.UNAVAILABLE -> {
                    silentRequests = 0
                    if (type == Its.DataType.STANDARD_TACH) lastStandard = System.currentTimeMillis()
                }
                Outcome.RETRY_NOW -> continue
                Outcome.NO_REPLY -> {
                    silentRequests++
                    if (silentRequests % 2 == 0) nextRequestVariant("тахограф не отвечает")
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private enum class Outcome { DATA, UNAVAILABLE, RETRY_NOW, NO_REPLY }

    private suspend fun exchange(frames: Channel<ItsFrame>, type: Its.DataType): Outcome {
        sendRequest(type)
        val deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return Outcome.NO_REPLY
            val frame = withTimeoutOrNull(remaining) { frames.receive() } ?: return Outcome.NO_REPLY
            listener.onLog("[ITS] ← $frame")
            listener.onMessageDecoded()
            parser.lastHadCounters?.let { withCounters = it }
            when (frame.sid) {
                Its.SID_SEND_ITS_ID -> {
                    frame.data.firstOrNull()?.let {
                        unitId = it.toInt() and 0xFF
                        listener.onLog("[ITS] Тахограф выдал ID %02X".format(unitId))
                    }
                }
                Its.SID_REQUEST_PIN -> {
                    handlePin(frames)
                    return Outcome.RETRY_NOW
                }
                Its.SID_REQUEST_REJECTED -> {
                    listener.onNotice("Тахограф временно заблокировал телефон после неверных PIN. Повтор через 30 с.")
                    delay(BAN_RETRY_MS)
                    return Outcome.RETRY_NOW
                }
                Its.SID_REQUEST_ACCEPTED -> {
                    val dataType = Its.DataType.of(frame.trtp) ?: type
                    listener.onLog("[ITS] ${dataType.titleRu}: ${frame.data.size} Б")
                    frame.data.toList().chunked(32).forEach { listener.onLog("[ITS]   ${Its.hex(it.toByteArray())}") }
                    ItsDataDecoder.decode(dataType, frame.data)?.let(listener::onUpdate)
                    return Outcome.DATA
                }
                Its.SID_DATA_UNAVAILABLE -> {
                    val code = frame.data.lastOrNull()?.toInt()?.and(0xFF) ?: 0
                    if (code == Its.CODE_PERSONAL_NOT_SHARED) {
                        personalBlockedUntil = System.currentTimeMillis() + PERSONAL_RETRY_MS
                        listener.onNotice("Водитель не разрешил передачу персональных данных. Согласие ITS задаётся в меню тахографа.")
                    } else {
                        listener.onLog("[ITS] Данные «${type.titleRu}» недоступны (код %02X)".format(code))
                    }
                    return Outcome.UNAVAILABLE
                }
                Its.SID_NEGATIVE_ANSWER -> {
                    val code = frame.data.lastOrNull()?.toInt()?.and(0xFF) ?: 0
                    listener.onLog("[ITS] Отказ на ${Its.sidName(frame.trtp)}: ${Its.negativeReason(code)}")
                    if (code == Its.CODE_INCORRECT_LENGTH) nextRequestVariant("неверная длина")
                    if (code == 0x78) delay(1_000)
                    return Outcome.RETRY_NOW
                }
                else -> listener.onLog("[ITS] Неожиданное сообщение: $frame")
            }
        }
    }

    private suspend fun handlePin(frames: Channel<ItsFrame>) {
        while (true) {
            listener.onPinRequired(pinFailures)
            val pin = pins.receive().filter(Char::isDigit)
            val digits = ByteArray(pin.length) { (pin[it] - '0').toByte() }
            send(ItsFrame(Its.VU_ID, unitId, Its.SID_SEND_PIN, Its.UNUSED, Its.UNUSED, Its.UNUSED, digits))
            val result = withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                var answer: ItsFrame
                do {
                    answer = frames.receive()
                    listener.onLog("[ITS] ← $answer")
                    if (answer.sid == Its.SID_SEND_ITS_ID) answer.data.firstOrNull()?.let { unitId = it.toInt() and 0xFF }
                } while (answer.sid != Its.SID_PAIRING_RESULT && answer.sid != Its.SID_NEGATIVE_ANSWER && answer.sid != Its.SID_REQUEST_REJECTED)
                answer
            }
            when {
                result == null -> listener.onNotice("Тахограф не ответил на PIN")
                result.sid == Its.SID_PAIRING_RESULT && result.data.any { it.toInt() != 0 } -> {
                    pinFailures = 0
                    listener.onNotice("PIN принят, телефон добавлен в список разрешённых устройств тахографа")
                    return
                }
                result.sid == Its.SID_PAIRING_RESULT -> {
                    pinFailures++
                    listener.onNotice("Неверный PIN (попыток подряд: $pinFailures). После 3 неверных тахограф блокирует телефон.")
                }
                result.sid == Its.SID_NEGATIVE_ANSWER -> {
                    val code = result.data.lastOrNull()?.toInt()?.and(0xFF) ?: 0
                    listener.onLog("[ITS] PIN не принят: ${Its.negativeReason(code)}")
                    if (code == Its.CODE_INCORRECT_LENGTH) withCounters = !withCounters
                }
                else -> {
                    listener.onNotice("Тахограф временно заблокировал телефон. Подождите и введите PIN снова.")
                    return
                }
            }
        }
    }

    private suspend fun sendRequest(type: Its.DataType) {
        // The regulation contradicts itself on the RequestData length; the variant is switched on "incorrect length".
        val frame = when (requestVariant % 3) {
            0 -> ItsFrame(Its.VU_ID, unitId, Its.SID_REQUEST_DATA, type.trtp, Its.UNUSED, Its.UNUSED, ByteArray(0))
            1 -> ItsFrame(Its.VU_ID, unitId, Its.SID_REQUEST_DATA, type.trtp, Its.UNUSED, Its.UNUSED, byteArrayOf(type.trtp.toByte()))
            else -> null
        }
        if (frame != null) {
            send(frame)
        } else {
            // Table 3 literally: LEN = 01, no counters, no data.
            send(ItsFrame(Its.VU_ID, unitId, Its.SID_REQUEST_DATA, type.trtp, Its.UNUSED, Its.UNUSED, ByteArray(0)), lenOverride = 1, counters = false)
        }
    }

    private fun nextRequestVariant(reason: String) {
        requestVariant++
        listener.onLog("[ITS] Формат запроса №${requestVariant % 3 + 1} ($reason)")
    }

    private suspend fun send(frame: ItsFrame, lenOverride: Int? = null, counters: Boolean = withCounters) {
        val bytes = frame.encode(withCounters = counters, lenOverride = lenOverride)
        listener.onLog("[ITS] → $frame")
        listener.onLog("TX ${Its.hex(bytes)}")
        transport.write(bytes)
    }

    /** Terminal commands: "REQ n" asks for data type n (1–7); hex bytes are sent as they are. */
    private suspend fun runUserCommands() {
        while (true) {
            val cmd = userCommands.tryReceive().getOrNull()?.trim()?.uppercase() ?: return
            val req = Regex("""REQ\s*([1-7])""").matchEntire(cmd)
            if (req != null) {
                Its.DataType.of(req.groupValues[1].toInt())?.let { sendRequest(it) }
                continue
            }
            val hex = cmd.replace(" ", "")
            if (hex.length % 2 == 0 && hex.isNotEmpty() && hex.all { it.isDigit() || it in 'A'..'F' }) {
                val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                listener.onLog("TX ${Its.hex(bytes)}")
                transport.write(bytes)
            } else {
                listener.onLog("[ITS] Команды: REQ 1…7 или байты в hex")
            }
        }
    }

    private companion object {
        const val REPLY_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 3_000L
        const val STANDARD_INTERVAL_MS = 60_000L
        const val PERSONAL_RETRY_MS = 60_000L
        const val BAN_RETRY_MS = 30_000L
    }
}
