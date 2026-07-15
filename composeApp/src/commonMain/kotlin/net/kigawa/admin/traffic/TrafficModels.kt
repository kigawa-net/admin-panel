package net.kigawa.admin.traffic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrafficPoint(
    @SerialName("timestampSeconds") val timestampSeconds: Long,
    @SerialName("rxBitsPerSecond") val rxBitsPerSecond: Double,
    @SerialName("txBitsPerSecond") val txBitsPerSecond: Double
)

@Serializable
data class TrafficResponse(
    @SerialName("rangeMinutes") val rangeMinutes: Int,
    @SerialName("series") val series: List<TrafficPoint>
)

fun formatBitsPerSecond(value: Double): String {
    val units = listOf("bps", "Kbps", "Mbps", "Gbps")
    var scaled = value
    var unitIndex = 0
    while (scaled >= 1000.0 && unitIndex < units.lastIndex) {
        scaled /= 1000.0
        unitIndex++
    }
    val formatted = if (scaled < 10) {
        (kotlin.math.round(scaled * 10) / 10.0).toString()
    } else {
        kotlin.math.round(scaled).toInt().toString()
    }
    return "$formatted ${units[unitIndex]}"
}
