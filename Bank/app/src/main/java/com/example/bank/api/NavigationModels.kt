package com.example.bank.api

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Các phương tiện di chuyển hỗ trợ bởi API `GET /v1/navigation/directions` (UC-N03).
 */
enum class TransportMode(val apiValue: String, val labelVi: String) {
    WALKING("walking", "Đi bộ"),
    TWO_WHEELER("two_wheeler", "Xe máy"),
    DRIVING("driving", "Ô tô"),
    TRANSIT("transit", "Xe buýt");

    companion object {
        fun fromApiValue(value: String?): TransportMode {
            return entries.find { it.apiValue.equals(value, ignoreCase = true) } ?: WALKING
        }
    }
}

/**
 * Loại điểm giao dịch trong bảng `dbo.bank_branches`.
 */
enum class BranchType(val apiValue: String) {
    BRANCH("BRANCH"),
    ATM("ATM"),
    BOTH("BOTH");

    companion object {
        fun fromApiValue(value: String?): BranchType {
            return entries.find { it.apiValue.equals(value, ignoreCase = true) } ?: BRANCH
        }
    }
}

/**
 * Trạng thái hoạt động của chi nhánh / ATM trong bảng `dbo.bank_branches`.
 */
enum class BranchStatus(val apiValue: String) {
    OPEN("OPEN"),
    CLOSED("CLOSED"),
    MAINTENANCE("MAINTENANCE");

    companion object {
        fun fromApiValue(value: String?): BranchStatus {
            return entries.find { it.apiValue.equals(value, ignoreCase = true) } ?: OPEN
        }
    }
}

/**
 * Tọa độ địa lý (Vĩ độ / Kinh độ).
 */
data class GeoPointDto(
    val latitude: Double,
    val longitude: Double
)

/**
 * Vị trí hiện tại của người dùng do `GET /v1/navigation/nearby` trả về.
 */
data class UserLocationDto(
    val latitude: Double,
    val longitude: Double,
    val isFallback: Boolean = false
)

/**
 * Mô hình Chi nhánh / ATM ngân hàng đồng bộ với bảng `dbo.bank_branches`
 * và lớp `[M3 - Internal] BankBranch` trong Class_Unified.html.
 */
data class BankBranchDto(
    val branchId: String,
    val name: String,
    val branchType: String,               // BRANCH, ATM, BOTH
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val openingHours: String?,
    val status: String,                   // OPEN, CLOSED, MAINTENANCE
    // Các trường bổ sung khi truy vấn từ `/v1/navigation/nearby`
    val distanceKm: Double? = null,
    val distanceMeters: Int? = null,
    val isNearest: Boolean = false,
    val recommendedMode: String? = null,  // walking, two_wheeler
    val estimatedWalkingMinutes: Int? = null
) {
    /**
     * Tính khoảng cách đường chim bay (km) theo công thức Haversine từ vị trí (lat, lng).
     */
    fun calculateDistanceFrom(lat: Double, lng: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(latitude - lat)
        val dLng = Math.toRadians(longitude - lng)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(latitude)) * sin(dLng / 2).pow(2)
        return earthRadiusKm * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    fun isOpenNow(): Boolean = status.equals("OPEN", ignoreCase = true)
}

/**
 * Kết quả trả về của `GET /v1/navigation/branches`.
 */
data class BranchListResponseDto(
    val items: List<BankBranchDto>,
    val total: Int
)

/**
 * Tóm tắt khoảng cách, thời gian (ETA) và kiểu hiển thị đường vẽ Polyline.
 */
data class RouteSummaryDto(
    val distanceMeters: Int,
    val distanceKm: Double,
    val distanceText: String,
    val durationSeconds: Int,
    val durationMinutes: Int,
    val durationText: String,
    val polylineColor: String,
    val polylineStyle: String             // DOTTED, SOLID
)

/**
 * Khung giới hạn tọa độ (dùng cho `LatLngBounds` trên Google Maps SDK).
 */
data class RouteBoundsDto(
    val northeast: GeoPointDto,
    val southwest: GeoPointDto
)

/**
 * Một chặng hướng dẫn di chuyển trong lộ trình (`steps`).
 */
data class RouteStepDto(
    val stepNumber: Int,
    val instruction: String,
    val distanceText: String,
    val durationText: String,
    val startLocation: GeoPointDto? = null,
    val endLocation: GeoPointDto? = null
)

/**
 * Kết quả lộ trình chỉ đường trả về từ `GET /v1/navigation/directions`
 * (hoặc trường `walking_route_to_nearest` của `GET /v1/navigation/nearby`).
 */
data class RouteProposalDto(
    val status: String,                   // OK | ZERO_RESULTS
    val mode: String,                     // walking | two_wheeler | driving | transit
    val modeLabel: String? = null,
    val cached: Boolean = false,
    val message: String? = null,
    val suggestedFallbackModes: List<String> = emptyList(),
    val origin: GeoPointDto? = null,
    val destination: GeoPointDto? = null,
    val branch: BankBranchDto? = null,
    val summary: RouteSummaryDto? = null,
    val bounds: RouteBoundsDto? = null,
    val encodedPolyline: String? = null,
    val decodedPath: List<GeoPointDto> = emptyList(),
    val steps: List<RouteStepDto> = emptyList(),
    val googleMapsIntentUri: String? = null
) {
    fun isOk(): Boolean = status.equals("OK", ignoreCase = true)
    fun isZeroResults(): Boolean = status.equals("ZERO_RESULTS", ignoreCase = true)
}

/**
 * Kết quả trả về của `GET /v1/navigation/nearby`.
 */
data class NearbyBranchesResponseDto(
    val userLocation: UserLocationDto,
    val radiusKm: Double,
    val radiusExpanded: Boolean,
    val message: String? = null,
    val nearestBranch: BankBranchDto? = null,
    val walkingRouteToNearest: RouteProposalDto? = null,
    val items: List<BankBranchDto>
)
