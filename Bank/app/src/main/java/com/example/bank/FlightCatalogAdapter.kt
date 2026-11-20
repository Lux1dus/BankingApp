package com.example.bank

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.bank.api.CatalogItemDto
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToLong

class FlightCatalogAdapter(
    private val onFlightSelected: (CatalogItemDto) -> Unit
) : RecyclerView.Adapter<FlightCatalogAdapter.FlightViewHolder>() {

    private val items = mutableListOf<CatalogItemDto>()
    private var defaultFromIata: String = "SGN"
    private var defaultToIata: String = "HAN"
    private var defaultDate: String = ""

    private val vndFormatter: DecimalFormat by lazy {
        val symbols = DecimalFormatSymbols(Locale.US).apply {
            groupingSeparator = '.'
        }
        DecimalFormat("#,###", symbols)
    }

    fun submitList(
        newItems: List<CatalogItemDto>,
        fromIata: String = defaultFromIata,
        toIata: String = defaultToIata,
        date: String = defaultDate
    ) {
        defaultFromIata = fromIata.ifBlank { "SGN" }
        defaultToIata = toIata.ifBlank { "HAN" }
        defaultDate = date
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FlightViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_flight_catalog, parent, false)
        return FlightViewHolder(view)
    }

    override fun onBindViewHolder(holder: FlightViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class FlightViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFlightAirline: TextView = itemView.findViewById(R.id.tvFlightAirline)
        private val tvFlightExtraInfo: TextView = itemView.findViewById(R.id.tvFlightExtraInfo)
        private val tvFlightCode: TextView = itemView.findViewById(R.id.tvFlightCode)
        private val tvFlightDepartTime: TextView = itemView.findViewById(R.id.tvFlightDepartTime)
        private val tvFlightArriveTime: TextView = itemView.findViewById(R.id.tvFlightArriveTime)
        private val tvFlightFromIata: TextView = itemView.findViewById(R.id.tvFlightFromIata)
        private val tvFlightToIata: TextView = itemView.findViewById(R.id.tvFlightToIata)
        private val tvFlightDuration: TextView = itemView.findViewById(R.id.tvFlightDuration)
        private val tvFlightDateBadge: TextView = itemView.findViewById(R.id.tvFlightDateBadge)
        private val tvFlightPrice: TextView = itemView.findViewById(R.id.tvFlightPrice)
        private val btnSelectFlight: TextView = itemView.findViewById(R.id.btnSelectFlight)

        fun bind(item: CatalogItemDto) {
            val airline = extractAirlineName(item)
            val flightCode = extractFlightCode(item)
            val depTime = extractDepartureTime(item)
            val arrTime = extractArrivalTime(item)
            val fromCode = extractFromIata(item, defaultFromIata)
            val toCode = extractToIata(item, defaultToIata)
            val duration = extractDuration(item, depTime, arrTime)
            val extraInfo = extractExtraInfo(item)
            val flightDate = extractDate(item, defaultDate)

            tvFlightAirline.text = airline
            tvFlightCode.text = flightCode
            tvFlightExtraInfo.text = extraInfo
            tvFlightDepartTime.text = depTime
            tvFlightArriveTime.text = arrTime
            tvFlightFromIata.text = fromCode
            tvFlightToIata.text = toCode
            tvFlightDuration.text = duration
            tvFlightDateBadge.text = if (flightDate.isNotBlank()) flightDate else "Bay thẳng"

            val formattedPrice = vndFormatter.format(item.price.roundToLong())
            val currencyLabel = item.currency.ifBlank { "VND" }.uppercase(Locale.ROOT)
            tvFlightPrice.text = "$formattedPrice $currencyLabel"

            itemView.setOnClickListener { onFlightSelected(item) }
            btnSelectFlight.setOnClickListener { onFlightSelected(item) }
        }
    }

    companion object {
        private val TIME_REGEX = Regex("""(\d{1,2}:\d{2})""")
        private val FLIGHT_CODE_REGEX = Regex("""\b([A-Z]{2}\s?-?\s?\d{2,4})\b""")

        fun extractAirlineName(item: CatalogItemDto): String {
            val d = item.details
            val fromDetails = firstNonBlank(
                d.optString("airline"),
                d.optString("airline_name"),
                d.optString("airlineName"),
                d.optString("carrier_name"),
                d.optString("carrier"),
                d.optString("provider_name"),
                d.optString("brand")
            )
            if (fromDetails.isNotBlank()) return mapAirlineCodeToName(fromDetails)

            val mappedProvider = mapAirlineCodeToName(item.providerCode)
            if (mappedProvider.isNotBlank() && !mappedProvider.equals(item.providerCode, ignoreCase = true)) {
                return mappedProvider
            }

            val title = item.title
            return when {
                title.contains("Vietnam Airlines", ignoreCase = true) -> "Vietnam Airlines"
                title.contains("Vietjet", ignoreCase = true) -> "Vietjet Air"
                title.contains("Bamboo", ignoreCase = true) -> "Bamboo Airways"
                title.contains("Vietravel", ignoreCase = true) -> "Vietravel Airlines"
                title.contains("Pacific", ignoreCase = true) -> "Pacific Airlines"
                mappedProvider.isNotBlank() -> mappedProvider
                else -> title.substringBefore(" -").substringBefore(" (").ifBlank { "Hãng hàng không" }
            }
        }

        private fun mapAirlineCodeToName(raw: String): String {
            return when (raw.trim().uppercase(Locale.ROOT)) {
                "VNA", "VN", "VIETNAM_AIRLINES" -> "Vietnam Airlines"
                "VIETJET", "VJ", "VJC", "VIETJET_AIR" -> "Vietjet Air"
                "BAMBOO", "QH", "BAV", "BAMBOO_AIRWAYS" -> "Bamboo Airways"
                "VIETRAVEL", "VU", "VTA", "VIETRAVEL_AIRLINES" -> "Vietravel Airlines"
                "PACIFIC", "BL", "PIC", "PACIFIC_AIRLINES" -> "Pacific Airlines"
                else -> raw.trim()
            }
        }

        fun extractFlightCode(item: CatalogItemDto): String {
            val d = item.details
            val fromDetails = firstNonBlank(
                d.optString("flight_number"),
                d.optString("flightNumber"),
                d.optString("flight_code"),
                d.optString("flightCode"),
                d.optString("flight_no"),
                d.optString("flightNo"),
                d.optString("code"),
                d.optString("number")
            )
            if (fromDetails.isNotBlank()) return fromDetails.uppercase(Locale.ROOT).replace(" ", "")

            FLIGHT_CODE_REGEX.find(item.title)?.groupValues?.getOrNull(1)?.let {
                return it.replace(" ", "").replace("-", "")
            }
            FLIGHT_CODE_REGEX.find(item.catalogItemId.uppercase(Locale.ROOT))?.groupValues?.getOrNull(1)?.let {
                return it.replace(" ", "").replace("-", "")
            }

            val prefix = when (item.providerCode.uppercase(Locale.ROOT)) {
                "VNA", "VN" -> "VN"
                "VIETJET", "VJ" -> "VJ"
                "BAMBOO", "QH" -> "QH"
                "VIETRAVEL", "VU" -> "VU"
                else -> item.providerCode.take(2).uppercase(Locale.ROOT).ifBlank { "FL" }
            }
            return item.catalogItemId.takeLast(4).uppercase(Locale.ROOT).let { "$prefix$it" }
        }

        fun extractDepartureTime(item: CatalogItemDto): String {
            val d = item.details
            val raw = firstNonBlank(
                d.optString("departure_time"),
                d.optString("departureTime"),
                d.optString("depart_time"),
                d.optString("departTime"),
                d.optString("dep_time"),
                d.optString("depTime"),
                d.optString("departure"),
                d.optString("start_time"),
                d.optString("startTime"),
                d.optString("std"),
                d.optString("time")
            )
            normalizeTime(raw)?.let { return it }

            val matches = TIME_REGEX.findAll(item.title).map { it.value }.toList()
            if (matches.isNotEmpty()) {
                return padTime(matches[0])
            }
            return "08:00"
        }

        fun extractArrivalTime(item: CatalogItemDto): String {
            val d = item.details
            val raw = firstNonBlank(
                d.optString("arrival_time"),
                d.optString("arrivalTime"),
                d.optString("arrive_time"),
                d.optString("arriveTime"),
                d.optString("arr_time"),
                d.optString("arrTime"),
                d.optString("arrival"),
                d.optString("end_time"),
                d.optString("endTime"),
                d.optString("sta")
            )
            normalizeTime(raw)?.let { return it }

            val matches = TIME_REGEX.findAll(item.title).map { it.value }.toList()
            if (matches.size >= 2) {
                return padTime(matches[1])
            }

            // Nếu chỉ có giờ khởi hành, tự cộng ~2 giờ làm giờ hạ cánh dự kiến
            val dep = extractDepartureTime(item)
            val depHour = dep.substringBefore(":").toIntOrNull() ?: 8
            val depMin = dep.substringAfter(":").toIntOrNull() ?: 0
            val arrHour = (depHour + 2) % 24
            return String.format(Locale.US, "%02d:%02d", arrHour, depMin)
        }

        fun extractDepartureHour(item: CatalogItemDto): Int {
            val depTime = extractDepartureTime(item)
            return depTime.substringBefore(":").trim().toIntOrNull() ?: 8
        }

        fun extractFromIata(item: CatalogItemDto, fallback: String): String {
            val d = item.details
            val raw = firstNonBlank(
                d.optString("from"),
                d.optString("origin"),
                d.optString("departure_airport"),
                d.optString("departureAirport"),
                d.optString("dep_airport"),
                d.optString("from_airport"),
                d.optString("departure_iata"),
                d.optString("origin_code")
            )
            if (raw.isNotBlank()) return raw.trim().uppercase(Locale.ROOT).take(3)
            return fallback.trim().uppercase(Locale.ROOT).take(3).ifBlank { "SGN" }
        }

        fun extractToIata(item: CatalogItemDto, fallback: String): String {
            val d = item.details
            val raw = firstNonBlank(
                d.optString("to"),
                d.optString("destination"),
                d.optString("arrival_airport"),
                d.optString("arrivalAirport"),
                d.optString("arr_airport"),
                d.optString("to_airport"),
                d.optString("arrival_iata"),
                d.optString("destination_code")
            )
            if (raw.isNotBlank()) return raw.trim().uppercase(Locale.ROOT).take(3)
            return fallback.trim().uppercase(Locale.ROOT).take(3).ifBlank { "HAN" }
        }

        fun extractDuration(item: CatalogItemDto, depTime: String, arrTime: String): String {
            val d = item.details
            val raw = firstNonBlank(
                d.optString("duration"),
                d.optString("flight_duration"),
                d.optString("flightDuration")
            )
            if (raw.isNotBlank()) return raw

            val depH = depTime.substringBefore(":").toIntOrNull()
            val depM = depTime.substringAfter(":", "0").toIntOrNull()
            val arrH = arrTime.substringBefore(":").toIntOrNull()
            val arrM = arrTime.substringAfter(":", "0").toIntOrNull()
            if (depH != null && depM != null && arrH != null && arrM != null) {
                var diffMinutes = (arrH * 60 + arrM) - (depH * 60 + depM)
                if (diffMinutes < 0) diffMinutes += 24 * 60
                if (diffMinutes > 0) {
                    val h = diffMinutes / 60
                    val m = diffMinutes % 60
                    return if (m > 0) "${h}h ${m}m" else "${h}h 00m"
                }
            }
            return "Bay thẳng"
        }

        fun extractExtraInfo(item: CatalogItemDto): String {
            val d = item.details
            val seatClass = firstNonBlank(
                d.optString("seat_class"),
                d.optString("cabin_class"),
                d.optString("cabinClass"),
                d.optString("class"),
                d.optString("ticket_class")
            )
            val aircraft = firstNonBlank(
                d.optString("aircraft"),
                d.optString("airplane"),
                d.optString("plane")
            )
            val baggage = firstNonBlank(
                d.optString("baggage"),
                d.optString("luggage")
            )
            val parts = mutableListOf<String>()
            if (seatClass.isNotBlank()) parts.add(seatClass) else parts.add("Economy")
            if (aircraft.isNotBlank()) parts.add(aircraft)
            if (baggage.isNotBlank()) parts.add(baggage)
            if (parts.size == 1 && item.title.isNotBlank()) {
                parts.add(item.title)
            }
            return parts.joinToString(" • ")
        }

        fun extractDate(item: CatalogItemDto, fallbackDate: String): String {
            val d = item.details
            val raw = firstNonBlank(
                d.optString("date"),
                d.optString("flight_date"),
                d.optString("departure_date"),
                d.optString("departureDate")
            )
            if (raw.isNotBlank()) return raw.take(10)
            return fallbackDate
        }

        private fun normalizeTime(raw: String): String? {
            if (raw.isBlank() || raw == "null") return null
            val match = TIME_REGEX.find(raw) ?: return null
            return padTime(match.value)
        }

        private fun padTime(hhmm: String): String {
            val parts = hhmm.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return String.format(Locale.US, "%02d:%02d", h, m)
        }

        private fun firstNonBlank(vararg values: String?): String {
            for (v in values) {
                if (!v.isNullOrBlank() && v != "null") return v.trim()
            }
            return ""
        }
    }
}
