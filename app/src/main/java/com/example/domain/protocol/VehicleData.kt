package com.example.domain.protocol

import com.example.data.model.DriverActivity

/**
 * "Driver time related states" reported by the tachograph in TCO1 (SAE J1939-71, SPN 1612 / 1613).
 */
enum class TimeRelatedState(val raw: Int, val titleRu: String) {
    NORMAL(0, "Норма"),
    BEFORE_4H30(1, "15 мин до 4ч30"),
    REACHED_4H30(2, "4ч30 достигнуто"),
    BEFORE_9H(3, "15 мин до 9ч"),
    REACHED_9H(4, "9ч достигнуто"),
    BEFORE_16H(5, "15 мин до 16ч"),
    REACHED_16H(6, "16ч достигнуто");

    companion object {
        fun fromRaw(raw: Int): TimeRelatedState? = entries.firstOrNull { it.raw == raw }
    }
}

/** A partial update decoded from one message. Null fields were not part of the message. */
data class VehicleUpdate(
    val driver1Activity: DriverActivity? = null,
    val driver2Activity: DriverActivity? = null,
    val vehicleMotion: Boolean? = null,
    val driver1CardPresent: Boolean? = null,
    val driver2CardPresent: Boolean? = null,
    val overspeed: Boolean? = null,
    val driver1TimeState: TimeRelatedState? = null,
    val driver2TimeState: TimeRelatedState? = null,
    val tachographSpeedKmh: Double? = null,
    val wheelSpeedKmh: Double? = null,
    val engineRpm: Double? = null,
    val odometerKm: Double? = null,
    val driver1Id: String? = null,
    val driver2Id: String? = null,
    val driver1Name: String? = null,
    val vin: String? = null,
    val registration: String? = null
)

/** Everything currently known about the vehicle. Null means "not received". */
data class VehicleLiveState(
    val driver1Activity: DriverActivity? = null,
    val driver2Activity: DriverActivity? = null,
    val vehicleMotion: Boolean? = null,
    val driver1CardPresent: Boolean? = null,
    val driver2CardPresent: Boolean? = null,
    val overspeed: Boolean? = null,
    val driver1TimeState: TimeRelatedState? = null,
    val driver2TimeState: TimeRelatedState? = null,
    val tachographSpeedKmh: Double? = null,
    val wheelSpeedKmh: Double? = null,
    val engineRpm: Double? = null,
    val odometerKm: Double? = null,
    val driver1Id: String? = null,
    val driver2Id: String? = null,
    val driver1Name: String? = null,
    val vin: String? = null,
    val registration: String? = null,
    val lastUpdateMs: Long? = null
) {
    /** Tachograph speed is authoritative; wheel-based speed (CCVS / OBD) is the fallback. */
    val speedKmh: Double? get() = tachographSpeedKmh ?: wheelSpeedKmh

    fun merge(update: VehicleUpdate, nowMs: Long) = copy(
        driver1Activity = update.driver1Activity ?: driver1Activity,
        driver2Activity = update.driver2Activity ?: driver2Activity,
        vehicleMotion = update.vehicleMotion ?: vehicleMotion,
        driver1CardPresent = update.driver1CardPresent ?: driver1CardPresent,
        driver2CardPresent = update.driver2CardPresent ?: driver2CardPresent,
        overspeed = update.overspeed ?: overspeed,
        driver1TimeState = update.driver1TimeState ?: driver1TimeState,
        driver2TimeState = update.driver2TimeState ?: driver2TimeState,
        tachographSpeedKmh = update.tachographSpeedKmh ?: tachographSpeedKmh,
        wheelSpeedKmh = update.wheelSpeedKmh ?: wheelSpeedKmh,
        engineRpm = update.engineRpm ?: engineRpm,
        odometerKm = update.odometerKm ?: odometerKm,
        driver1Id = update.driver1Id ?: driver1Id,
        driver2Id = update.driver2Id ?: driver2Id,
        driver1Name = update.driver1Name ?: driver1Name,
        vin = update.vin ?: vin,
        registration = update.registration ?: registration,
        lastUpdateMs = nowMs
    )
}

enum class ProtocolMode(val title: String, val description: String) {
    AUTO(
        "Автоопределение",
        "ELM327/STN: сначала J1939 (FMS грузовика), затем OBD-II. Иначе — текстовый шлюз."
    ),
    ELM_J1939(
        "ELM327/STN · J1939 / FMS",
        "Грузовики: режим водителя и карты от тахографа (TCO1), скорость, обороты, пробег, ID карт (DI)."
    ),
    ELM_OBD2(
        "ELM327 · OBD-II",
        "Фургоны и легковые: только скорость, обороты и VIN. Режим водителя — по движению."
    ),
    TEXT_GATEWAY(
        "Текстовый шлюз",
        "Свой CAN-мост или эмулятор: строки KEY=VALUE или кадры в формате candump."
    )
}
