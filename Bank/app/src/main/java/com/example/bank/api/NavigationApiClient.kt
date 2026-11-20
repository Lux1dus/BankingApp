package com.example.bank.api

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Client điều hướng & bản đồ Chi nhánh / ATM:
 * - Gọi trực tiếp **OSRM Route Service** (`https://router.project-osrm.org/route/v1/...` và
 *   `https://routing.openstreetmap.de/...`) để lấy đường đi thực tế (encoded polyline, danh sách tọa độ
 *   `decodedPath`, khoảng cách, thời gian ETA và hướng dẫn từng chặng `steps`) để vẽ lên bản đồ `osmdroid`.
 * - Truy vấn danh sách chi nhánh & ATM từ `BankServer` (`/v1/navigation/branches`, `/v1/navigation/nearby`)
 *   kèm danh sách chi nhánh dự phòng khi chưa bật server.
 * - Hỗ trợ Client Route Cache và Debounce (500ms) khi người dùng chuyển đổi phương tiện liên tục.
 */
object NavigationApiClient {

    @Volatile
    private var customBaseUrl: String? = null

    val baseUrl: String
        get() = customBaseUrl ?: UtilitiesApiClient.baseUrl

    private const val OSRM_PRIMARY_BASE_URL = "https://router.project-osrm.org/route/v1"
    private const val OSRM_OSMDE_FOOT_URL = "https://routing.openstreetmap.de/routed-foot/route/v1/foot"
    private const val OSRM_OSMDE_BIKE_URL = "https://routing.openstreetmap.de/routed-bike/route/v1/bike"
    private const val OSRM_OSMDE_CAR_URL = "https://routing.openstreetmap.de/routed-car/route/v1/driving"
    private const val HTTP_USER_AGENT = "NEXABank-Android/1.0 (osmdroid; OSRM)"

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Client-side Route Cache theo cặp (origin, dest/branchId, mode)
    private val clientRouteCache = ConcurrentHashMap<String, RouteProposalDto>()
    // Lưu thông tin chi nhánh đã biết theo branchId để tra cứu tọa độ nhanh khi gọi trực tiếp OSRM
    private val knownBranchesById = ConcurrentHashMap<String, BankBranchDto>()
    private var pendingDebounceRunnable: Runnable? = null

    private val fallbackBranches = listOf(
        BankBranchDto(
            branchId = "BR_HCM_Q1_01",
            name = "NEXA Bank Hội Sở Quận 1",
            branchType = "BOTH",
            address = "68 Nguyễn Huệ, P. Bến Nghé, Quận 1, TP.HCM",
            latitude = 10.774512,
            longitude = 106.703928,
            openingHours = "08:00 - 17:30",
            status = "OPEN"
        ),
        BankBranchDto(
            branchId = "BR_HCM_Q1_02",
            name = "NEXA Bank Chi nhánh Lê Lợi",
            branchType = "BRANCH",
            address = "45 Lê Lợi, P. Bến Nghé, Quận 1, TP.HCM",
            latitude = 10.773105,
            longitude = 106.700112,
            openingHours = "08:00 - 17:00",
            status = "OPEN"
        ),
        BankBranchDto(
            branchId = "ATM_HCM_Q1_03",
            name = "ATM NEXA Bitexco Financial Tower",
            branchType = "ATM",
            address = "2 Hải Triều, P. Bến Nghé, Quận 1, TP.HCM",
            latitude = 10.771688,
            longitude = 106.704395,
            openingHours = "24/7",
            status = "OPEN"
        ),
        BankBranchDto(
            branchId = "BR_HCM_Q3_04",
            name = "NEXA Bank Chi nhánh Pasteur",
            branchType = "BOTH",
            address = "180 Pasteur, P. Võ Thị Sáu, Quận 3, TP.HCM",
            latitude = 10.782410,
            longitude = 106.693520,
            openingHours = "08:00 - 17:00",
            status = "OPEN"
        ),
        BankBranchDto(
            branchId = "BR_HCM_BT_05",
            name = "NEXA Bank Landmark 81",
            branchType = "BOTH",
            address = "720A Điện Biên Phủ, Quận Bình Thạnh, TP.HCM",
            latitude = 10.795120,
            longitude = 106.721850,
            openingHours = "08:30 - 18:00",
            status = "OPEN"
        ),
        BankBranchDto(
            branchId = "BR_HAN_HK_01",
            name = "NEXA Bank Chi nhánh Hoàn Kiếm",
            branchType = "BOTH",
            address = "24 Tràng Tiền, Quận Hoàn Kiếm, Hà Nội",
            latitude = 21.024510,
            longitude = 105.855240,
            openingHours = "08:00 - 17:00",
            status = "OPEN"
        )
    )

    init {
        fallbackBranches.forEach { knownBranchesById[it.branchId] = it }
    }

    fun setServerBaseUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        if (cleaned.isNotEmpty()) {
            customBaseUrl = if (cleaned.endsWith("/v1")) cleaned else "$cleaned/v1"
        }
    }

    fun clearRouteCache() {
        clientRouteCache.clear()
    }

    // ======================= 1. GET /v1/navigation/branches =======================

    fun fetchBranches(
        query: String? = null,
        type: String? = null,
        status: String? = null,
        callback: (Result<BranchListResponseDto>) -> Unit
    ) {
        runAsync({ fetchBranchesSync(query, type, status) }, callback)
    }

    @Throws(Exception::class)
    fun fetchBranchesSync(
        query: String? = null,
        type: String? = null,
        status: String? = null
    ): BranchListResponseDto {
        return try {
            val params = mutableMapOf<String, String>()
            if (!query.isNullOrBlank()) params["q"] = query.trim()
            if (!type.isNullOrBlank()) params["type"] = type.trim().uppercase(Locale.ROOT)
            if (!status.isNullOrBlank()) params["status"] = status.trim().uppercase(Locale.ROOT)

            val json = requestGetJsonSync("/navigation/branches", params)
            val items = parseBranchList(json.optJSONArray("items") ?: JSONArray())
            items.forEach { knownBranchesById[it.branchId] = it }
            BranchListResponseDto(
                items = items,
                total = json.optInt("total", items.size)
            )
        } catch (_: Exception) {
            val filtered = fallbackBranches.filter { br ->
                val matchQuery = query.isNullOrBlank() ||
                    br.name.contains(query, ignoreCase = true) ||
                    br.address.contains(query, ignoreCase = true)
                val matchType = type.isNullOrBlank() ||
                    br.branchType.equals(type, ignoreCase = true) ||
                    br.branchType.equals("BOTH", ignoreCase = true)
                val matchStatus = status.isNullOrBlank() ||
                    br.status.equals(status, ignoreCase = true)
                matchQuery && matchType && matchStatus
            }
            BranchListResponseDto(items = filtered, total = filtered.size)
        }
    }

    // ======================= 2. GET /v1/navigation/nearby =======================

    fun fetchNearbyBranches(
        lat: Double? = null,
        lng: Double? = null,
        fallbackCity: String? = "HCM",
        radiusKm: Double = 10.0,
        limit: Int = 5,
        type: String? = null,
        callback: (Result<NearbyBranchesResponseDto>) -> Unit
    ) {
        runAsync({
            fetchNearbyBranchesSync(lat, lng, fallbackCity, radiusKm, limit, type)
        }, callback)
    }

    @Throws(Exception::class)
    fun fetchNearbyBranchesSync(
        lat: Double? = null,
        lng: Double? = null,
        fallbackCity: String? = "HCM",
        radiusKm: Double = 10.0,
        limit: Int = 5,
        type: String? = null
    ): NearbyBranchesResponseDto {
        val defaultLat = if (fallbackCity.equals("HAN", ignoreCase = true)) 21.028511 else 10.772100
        val defaultLng = if (fallbackCity.equals("HAN", ignoreCase = true)) 105.850100 else 106.698300
        val resolvedLat = lat ?: defaultLat
        val resolvedLng = lng ?: defaultLng

        return try {
            val params = mutableMapOf(
                "radius_km" to radiusKm.toString(),
                "limit" to limit.coerceIn(1, 20).toString()
            )
            if (lat != null && lng != null) {
                params["lat"] = lat.toString()
                params["lng"] = lng.toString()
            } else if (!fallbackCity.isNullOrBlank()) {
                params["fallback_city"] = fallbackCity.trim().uppercase(Locale.ROOT)
            }
            if (!type.isNullOrBlank()) {
                params["type"] = type.trim().uppercase(Locale.ROOT)
            }

            val json = requestGetJsonSync("/navigation/nearby", params)
            val userLocObj = json.optJSONObject("user_location")
            val userLocation = UserLocationDto(
                latitude = userLocObj?.optDouble("latitude", resolvedLat) ?: resolvedLat,
                longitude = userLocObj?.optDouble("longitude", resolvedLng) ?: resolvedLng,
                isFallback = userLocObj?.optBoolean("is_fallback", lat == null || lng == null) ?: false
            )

            val items = parseBranchList(json.optJSONArray("items") ?: JSONArray())
            items.forEach { knownBranchesById[it.branchId] = it }
            val nearestBranch = json.optJSONObject("nearest_branch")?.let { parseBranch(it) }
                ?: items.firstOrNull()
            nearestBranch?.let { knownBranchesById[it.branchId] = it }

            // Tự động gọi thẳng OSRM Route Service cho chi nhánh gần nhất để có đường đi thực tế trên bản đồ
            val osrmWalkingRoute = if (nearestBranch != null) {
                runCatching {
                    fetchOsrmRouteSync(
                        originLat = userLocation.latitude,
                        originLng = userLocation.longitude,
                        destLat = nearestBranch.latitude,
                        destLng = nearestBranch.longitude,
                        mode = TransportMode.WALKING,
                        branch = nearestBranch
                    )
                }.getOrNull()
            } else {
                null
            }

            val walkingRouteObj = json.optJSONObject("walking_route_to_nearest")
            NearbyBranchesResponseDto(
                userLocation = userLocation,
                radiusKm = json.optDouble("radius_km", radiusKm),
                radiusExpanded = json.optBoolean("radius_expanded", false),
                message = json.optString("message").takeIf { it.isNotBlank() && it != "null" },
                nearestBranch = nearestBranch,
                walkingRouteToNearest = osrmWalkingRoute ?: walkingRouteObj?.let { parseRouteProposal(it) },
                items = items
            )
        } catch (_: Exception) {
            buildLocalNearbyBranchesWithOsrm(
                userLat = resolvedLat,
                userLng = resolvedLng,
                isFallback = lat == null || lng == null,
                radiusKm = radiusKm,
                limit = limit,
                type = type
            )
        }
    }

    private fun buildLocalNearbyBranchesWithOsrm(
        userLat: Double,
        userLng: Double,
        isFallback: Boolean,
        radiusKm: Double,
        limit: Int,
        type: String?
    ): NearbyBranchesResponseDto {
        val candidates = fallbackBranches.filter { br ->
            type.isNullOrBlank() ||
                br.branchType.equals(type, ignoreCase = true) ||
                br.branchType.equals("BOTH", ignoreCase = true)
        }
        val sorted = candidates.map { br ->
            val distKm = haversineKm(userLat, userLng, br.latitude, br.longitude)
            val distMeters = (distKm * 1000.0).roundToInt()
            val walkMins = ((distMeters / 80.0).roundToInt()).coerceAtLeast(1)
            br.copy(
                distanceKm = (distKm * 100.0).roundToInt() / 100.0,
                distanceMeters = distMeters,
                recommendedMode = if (distKm <= 1.5) TransportMode.WALKING.apiValue else TransportMode.TWO_WHEELER.apiValue,
                estimatedWalkingMinutes = walkMins
            )
        }.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
            .take(limit.coerceIn(1, 20))
            .mapIndexed { idx, br -> br.copy(isNearest = idx == 0) }

        sorted.forEach { knownBranchesById[it.branchId] = it }
        val nearest = sorted.firstOrNull()
        val walkingRoute = nearest?.let {
            runCatching {
                fetchOsrmRouteSync(
                    originLat = userLat,
                    originLng = userLng,
                    destLat = it.latitude,
                    destLng = it.longitude,
                    mode = TransportMode.WALKING,
                    branch = it
                )
            }.getOrNull()
        }

        return NearbyBranchesResponseDto(
            userLocation = UserLocationDto(userLat, userLng, isFallback),
            radiusKm = radiusKm,
            radiusExpanded = false,
            message = null,
            nearestBranch = nearest,
            walkingRouteToNearest = walkingRoute,
            items = sorted
        )
    }

    // ======================= 3. DIRECT OSRM ROUTE SERVICE & DIRECTIONS =======================

    /**
     * Gọi trực tiếp **OSRM Route Service** (`router.project-osrm.org` / `routing.openstreetmap.de`)
     * để lấy đường đi thực tế giữa 2 tọa độ theo phương tiện di chuyển.
     */
    fun fetchOsrmRoute(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        mode: TransportMode = TransportMode.WALKING,
        branch: BankBranchDto? = null,
        useClientCache: Boolean = true,
        callback: (Result<RouteProposalDto>) -> Unit
    ) {
        val cacheKey = buildRouteCacheKey(originLat, originLng, branch?.branchId, destLat, destLng, mode.apiValue)
        if (useClientCache) {
            clientRouteCache[cacheKey]?.let { cachedRoute ->
                mainHandler.post { callback(Result.success(cachedRoute.copy(cached = true))) }
                return
            }
        }

        runAsync({
            val route = fetchOsrmRouteSync(
                originLat = originLat,
                originLng = originLng,
                destLat = destLat,
                destLng = destLng,
                mode = mode,
                branch = branch
            )
            if (useClientCache && route.isOk()) {
                clientRouteCache[cacheKey] = route
            }
            route
        }, callback)
    }

    /**
     * Gọi đồng bộ tới OSRM Route API:
     * `GET https://router.project-osrm.org/route/v1/{profile}/{originLng},{originLat};{destLng},{destLat}?overview=full&geometries=polyline&steps=true`
     */
    @Throws(Exception::class)
    fun fetchOsrmRouteSync(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        mode: TransportMode = TransportMode.WALKING,
        branch: BankBranchDto? = null
    ): RouteProposalDto {
        val coordsSegment = String.format(
            Locale.US,
            "%.6f,%.6f;%.6f,%.6f",
            originLng,
            originLat,
            destLng,
            destLat
        )
        val osrmProfile = when (mode) {
            TransportMode.WALKING -> "foot"
            TransportMode.TWO_WHEELER -> "bike"
            TransportMode.DRIVING, TransportMode.TRANSIT -> "driving"
        }
        val query = "overview=full&geometries=polyline&steps=true"

        val candidateUrls = listOf(
            "$OSRM_PRIMARY_BASE_URL/$osrmProfile/$coordsSegment?$query",
            when (mode) {
                TransportMode.WALKING -> "$OSRM_OSMDE_FOOT_URL/$coordsSegment?$query"
                TransportMode.TWO_WHEELER -> "$OSRM_OSMDE_BIKE_URL/$coordsSegment?$query"
                TransportMode.DRIVING, TransportMode.TRANSIT -> "$OSRM_OSMDE_CAR_URL/$coordsSegment?$query"
            }
        )

        var lastError: Exception? = null
        for (urlStr in candidateUrls) {
            try {
                val json = requestExternalJsonSync(urlStr)
                val code = json.optString("code")
                val routes = json.optJSONArray("routes")
                if (code.equals("Ok", ignoreCase = true) && routes != null && routes.length() > 0) {
                    return parseOsrmRouteResponse(
                        osrmJson = json,
                        originLat = originLat,
                        originLng = originLng,
                        destLat = destLat,
                        destLng = destLng,
                        mode = mode,
                        branch = branch,
                        fromOsmDeProfile = urlStr.contains("openstreetmap.de")
                    )
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        // Nếu thiết bị không có mạng tới OSRM, dựng lộ trình dự phòng theo tọa độ để bản đồ vẫn hiển thị được
        return buildFallbackStraightRoute(
            originLat = originLat,
            originLng = originLng,
            destLat = destLat,
            destLng = destLng,
            mode = mode,
            branch = branch,
            note = lastError?.message
        )
    }

    /**
     * Lấy lộ trình chỉ đường đến chi nhánh theo phương tiện.
     * Ưu tiên tự gọi thẳng **OSRM Route Service** để lấy hình học đường đi thực tế trên bản đồ `osmdroid`.
     */
    fun fetchDirections(
        originLat: Double,
        originLng: Double,
        branchId: String? = null,
        destLat: Double? = null,
        destLng: Double? = null,
        mode: TransportMode = TransportMode.WALKING,
        useClientCache: Boolean = true,
        callback: (Result<RouteProposalDto>) -> Unit
    ) {
        val cacheKey = buildRouteCacheKey(originLat, originLng, branchId, destLat, destLng, mode.apiValue)
        if (useClientCache) {
            clientRouteCache[cacheKey]?.let { cachedRoute ->
                mainHandler.post { callback(Result.success(cachedRoute.copy(cached = true))) }
                return
            }
        }

        runAsync({
            val route = fetchDirectionsSync(originLat, originLng, branchId, destLat, destLng, mode.apiValue)
            if (useClientCache && route.isOk()) {
                clientRouteCache[cacheKey] = route
            }
            route
        }, callback)
    }

    /**
     * Gọi chỉ đường có cơ chế **Debounce** (mặc định 500ms) để chống spam API khi đổi phương tiện liên tục.
     */
    fun fetchDirectionsDebounced(
        originLat: Double,
        originLng: Double,
        branchId: String? = null,
        destLat: Double? = null,
        destLng: Double? = null,
        mode: TransportMode = TransportMode.WALKING,
        debounceMs: Long = 500L,
        useClientCache: Boolean = true,
        callback: (Result<RouteProposalDto>) -> Unit
    ) {
        pendingDebounceRunnable?.let { mainHandler.removeCallbacks(it) }

        val cacheKey = buildRouteCacheKey(originLat, originLng, branchId, destLat, destLng, mode.apiValue)
        if (useClientCache) {
            clientRouteCache[cacheKey]?.let { cachedRoute ->
                callback(Result.success(cachedRoute.copy(cached = true)))
                return
            }
        }

        val task = Runnable {
            fetchDirections(
                originLat = originLat,
                originLng = originLng,
                branchId = branchId,
                destLat = destLat,
                destLng = destLng,
                mode = mode,
                useClientCache = useClientCache,
                callback = callback
            )
        }
        pendingDebounceRunnable = task
        mainHandler.postDelayed(task, debounceMs.coerceAtLeast(0L))
    }

    @Throws(Exception::class)
    fun fetchDirectionsSync(
        originLat: Double,
        originLng: Double,
        branchId: String? = null,
        destLat: Double? = null,
        destLng: Double? = null,
        mode: String = TransportMode.WALKING.apiValue
    ): RouteProposalDto {
        val transportMode = TransportMode.fromApiValue(mode)
        var targetBranch = branchId?.let { knownBranchesById[it] }
        var resolvedDestLat = destLat ?: targetBranch?.latitude
        var resolvedDestLng = destLng ?: targetBranch?.longitude

        if (resolvedDestLat == null || resolvedDestLng == null) {
            val allBranches = runCatching { fetchBranchesSync().items }.getOrDefault(fallbackBranches)
            targetBranch = if (!branchId.isNullOrBlank()) {
                allBranches.find { it.branchId.equals(branchId, ignoreCase = true) }
            } else {
                allBranches.minByOrNull { haversineKm(originLat, originLng, it.latitude, it.longitude) }
            }
            resolvedDestLat = targetBranch?.latitude
            resolvedDestLng = targetBranch?.longitude
        }

        if (resolvedDestLat != null && resolvedDestLng != null) {
            return fetchOsrmRouteSync(
                originLat = originLat,
                originLng = originLng,
                destLat = resolvedDestLat,
                destLng = resolvedDestLng,
                mode = transportMode,
                branch = targetBranch
            )
        }

        // Fallback gọi qua BankServer nếu không xác định được tọa độ đích cục bộ
        val params = mutableMapOf(
            "origin_lat" to originLat.toString(),
            "origin_lng" to originLng.toString(),
            "mode" to mode.lowercase(Locale.ROOT)
        )
        if (!branchId.isNullOrBlank()) {
            params["branch_id"] = branchId.trim()
        }
        val json = requestGetJsonSync("/navigation/directions", params)
        return parseRouteProposal(json)
    }

    // ======================= OSRM PARSER =======================

    private fun parseOsrmRouteResponse(
        osrmJson: JSONObject,
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        mode: TransportMode,
        branch: BankBranchDto?,
        fromOsmDeProfile: Boolean
    ): RouteProposalDto {
        val routes = osrmJson.getJSONArray("routes")
        val routeObj = routes.getJSONObject(0)

        val encodedPolyline = routeObj.optString("geometry").takeIf { it.isNotBlank() && it != "null" }
        val decodedPath = if (!encodedPolyline.isNullOrBlank()) {
            decodePolyline(encodedPolyline).toMutableList()
        } else {
            mutableListOf()
        }
        if (decodedPath.isEmpty()) {
            decodedPath.add(GeoPointDto(originLat, originLng))
            decodedPath.add(GeoPointDto(destLat, destLng))
        }

        val distanceMeters = routeObj.optDouble("distance", 0.0).roundToInt().coerceAtLeast(1)
        val distanceKm = ((distanceMeters / 10.0).roundToInt()) / 100.0
        val distanceText = if (distanceMeters < 1000) {
            "$distanceMeters m"
        } else {
            String.format(Locale.US, "%.2f km", distanceMeters / 1000.0)
        }

        val rawOsrmDurationSec = routeObj.optDouble("duration", 0.0).roundToInt()
        val durationSeconds = when (mode) {
            TransportMode.WALKING -> {
                if (fromOsmDeProfile && rawOsrmDurationSec > 0) rawOsrmDurationSec
                else (distanceMeters / 1.35).roundToInt().coerceAtLeast(60)
            }
            TransportMode.TWO_WHEELER -> {
                if (fromOsmDeProfile && rawOsrmDurationSec > 0) rawOsrmDurationSec
                else (distanceMeters / 7.8).roundToInt().coerceAtLeast(45)
            }
            TransportMode.DRIVING -> {
                if (rawOsrmDurationSec > 0) rawOsrmDurationSec
                else (distanceMeters / 8.5).roundToInt().coerceAtLeast(45)
            }
            TransportMode.TRANSIT -> {
                ((distanceMeters / 5.5).roundToInt() + 180).coerceAtLeast(120)
            }
        }
        val durationMinutes = (durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
        val durationText = formatDurationVi(durationMinutes)

        val (polylineColor, polylineStyle) = styleForTransportMode(mode)

        val summary = RouteSummaryDto(
            distanceMeters = distanceMeters,
            distanceKm = distanceKm,
            distanceText = distanceText,
            durationSeconds = durationSeconds,
            durationMinutes = durationMinutes,
            durationText = durationText,
            polylineColor = polylineColor,
            polylineStyle = polylineStyle
        )

        val steps = mutableListOf<RouteStepDto>()
        val legsArr = routeObj.optJSONArray("legs")
        if (legsArr != null && legsArr.length() > 0) {
            val firstLeg = legsArr.optJSONObject(0)
            val stepsArr = firstLeg?.optJSONArray("steps")
            if (stepsArr != null) {
                var stepCounter = 1
                for (i in 0 until stepsArr.length()) {
                    val s = stepsArr.optJSONObject(i) ?: continue
                    val stepDist = s.optDouble("distance", 0.0).roundToInt()
                    val maneuver = s.optJSONObject("maneuver")
                    val maneuverType = maneuver?.optString("type").orEmpty()
                    val modifier = maneuver?.optString("modifier").orEmpty()
                    val roadName = s.optString("name").takeIf { it.isNotBlank() && it != "null" }

                    if (stepDist <= 2 && !maneuverType.equals("arrive", ignoreCase = true) && i > 0) {
                        continue
                    }

                    val stepDistText = if (stepDist < 1000) {
                        "$stepDist m"
                    } else {
                        String.format(Locale.US, "%.1f km", stepDist / 1000.0)
                    }
                    val stepDurSec = when (mode) {
                        TransportMode.WALKING -> (stepDist / 1.35).roundToInt()
                        TransportMode.TWO_WHEELER -> (stepDist / 7.8).roundToInt()
                        TransportMode.DRIVING -> s.optDouble("duration", stepDist / 8.5).roundToInt()
                        TransportMode.TRANSIT -> (stepDist / 5.5).roundToInt()
                    }.coerceAtLeast(5)
                    val stepDurText = if (stepDurSec < 60) "${stepDurSec}s" else "${(stepDurSec / 60.0).roundToInt()} phút"

                    val locArr = maneuver?.optJSONArray("location")
                    val startLoc = if (locArr != null && locArr.length() >= 2) {
                        GeoPointDto(
                            latitude = locArr.optDouble(1, originLat),
                            longitude = locArr.optDouble(0, originLng)
                        )
                    } else {
                        null
                    }

                    val instruction = buildVietnameseManeuverInstruction(
                        type = maneuverType,
                        modifier = modifier,
                        roadName = roadName,
                        destName = branch?.name
                    )

                    steps.add(
                        RouteStepDto(
                            stepNumber = stepCounter++,
                            instruction = instruction,
                            distanceText = stepDistText,
                            durationText = stepDurText,
                            startLocation = startLoc,
                            endLocation = null
                        )
                    )
                }
            }
        }

        val bounds = computeBounds(decodedPath, originLat, originLng, destLat, destLng)
        val googleMapsTravelMode = when (mode) {
            TransportMode.WALKING -> "walking"
            TransportMode.TWO_WHEELER -> "two-wheeler"
            TransportMode.DRIVING -> "driving"
            TransportMode.TRANSIT -> "transit"
        }
        val googleMapsUri = String.format(
            Locale.US,
            "https://www.google.com/maps/dir/?api=1&origin=%.6f,%.6f&destination=%.6f,%.6f&travelmode=%s",
            originLat,
            originLng,
            destLat,
            destLng,
            googleMapsTravelMode
        )

        return RouteProposalDto(
            status = "OK",
            mode = mode.apiValue,
            modeLabel = "${mode.labelVi} (OSRM)",
            cached = false,
            message = null,
            suggestedFallbackModes = emptyList(),
            origin = GeoPointDto(originLat, originLng),
            destination = GeoPointDto(destLat, destLng),
            branch = branch,
            summary = summary,
            bounds = bounds,
            encodedPolyline = encodedPolyline,
            decodedPath = decodedPath,
            steps = steps,
            googleMapsIntentUri = googleMapsUri
        )
    }

    private fun buildFallbackStraightRoute(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        mode: TransportMode,
        branch: BankBranchDto?,
        note: String?
    ): RouteProposalDto {
        val midLat = (originLat + destLat) / 2.0 + 0.0008
        val midLng = (originLng + destLng) / 2.0 - 0.0006
        val path = listOf(
            GeoPointDto(originLat, originLng),
            GeoPointDto(midLat, midLng),
            GeoPointDto(destLat, destLng)
        )
        val distKm = haversineKm(originLat, originLng, destLat, destLng) * 1.25
        val distMeters = (distKm * 1000.0).roundToInt().coerceAtLeast(50)
        val distText = if (distMeters < 1000) "$distMeters m" else String.format(Locale.US, "%.2f km", distKm)
        val durSec = when (mode) {
            TransportMode.WALKING -> (distMeters / 1.35).roundToInt()
            TransportMode.TWO_WHEELER -> (distMeters / 7.8).roundToInt()
            TransportMode.DRIVING -> (distMeters / 8.5).roundToInt()
            TransportMode.TRANSIT -> (distMeters / 5.5).roundToInt() + 180
        }.coerceAtLeast(60)
        val durMin = (durSec / 60.0).roundToInt().coerceAtLeast(1)
        val (color, style) = styleForTransportMode(mode)

        return RouteProposalDto(
            status = "OK",
            mode = mode.apiValue,
            modeLabel = mode.labelVi,
            cached = false,
            message = note,
            suggestedFallbackModes = emptyList(),
            origin = GeoPointDto(originLat, originLng),
            destination = GeoPointDto(destLat, destLng),
            branch = branch,
            summary = RouteSummaryDto(
                distanceMeters = distMeters,
                distanceKm = (distKm * 100.0).roundToInt() / 100.0,
                distanceText = distText,
                durationSeconds = durSec,
                durationMinutes = durMin,
                durationText = formatDurationVi(durMin),
                polylineColor = color,
                polylineStyle = style
            ),
            bounds = computeBounds(path, originLat, originLng, destLat, destLng),
            encodedPolyline = null,
            decodedPath = path,
            steps = listOf(
                RouteStepDto(
                    stepNumber = 1,
                    instruction = "Xuất phát từ vị trí hiện tại của bạn",
                    distanceText = distText,
                    durationText = formatDurationVi(durMin),
                    startLocation = GeoPointDto(originLat, originLng),
                    endLocation = GeoPointDto(destLat, destLng)
                ),
                RouteStepDto(
                    stepNumber = 2,
                    instruction = "Đến ${branch?.name ?: "điểm giao dịch NEXA Bank"}",
                    distanceText = "0 m",
                    durationText = "0 phút",
                    startLocation = GeoPointDto(destLat, destLng),
                    endLocation = GeoPointDto(destLat, destLng)
                )
            ),
            googleMapsIntentUri = String.format(
                Locale.US,
                "https://www.google.com/maps/dir/?api=1&origin=%.6f,%.6f&destination=%.6f,%.6f",
                originLat,
                originLng,
                destLat,
                destLng
            )
        )
    }

    private fun styleForTransportMode(mode: TransportMode): Pair<String, String> {
        return when (mode) {
            TransportMode.WALKING -> "#08755F" to "DOTTED"
            TransportMode.TWO_WHEELER -> "#2563EB" to "SOLID"
            TransportMode.DRIVING -> "#08755F" to "SOLID"
            TransportMode.TRANSIT -> "#D97706" to "SOLID"
        }
    }

    private fun formatDurationVi(minutes: Int): String {
        return if (minutes >= 60) {
            val h = minutes / 60
            val m = minutes % 60
            if (m > 0) "$h giờ $m phút" else "$h giờ"
        } else {
            "$minutes phút"
        }
    }

    private fun buildVietnameseManeuverInstruction(
        type: String,
        modifier: String,
        roadName: String?,
        destName: String?
    ): String {
        val roadSuffix = if (!roadName.isNullOrBlank()) " vào $roadName" else ""
        val onRoadSuffix = if (!roadName.isNullOrBlank()) " trên $roadName" else ""
        val modVi = when (modifier.lowercase(Locale.ROOT)) {
            "left" -> "Rẽ trái"
            "right" -> "Rẽ phải"
            "slight left" -> "Chếch sang trái"
            "slight right" -> "Chếch sang phải"
            "sharp left" -> "Rẽ gắt sang trái"
            "sharp right" -> "Rẽ gắt sang phải"
            "uturn" -> "Quay đầu"
            "straight" -> "Đi thẳng"
            else -> "Di chuyển"
        }

        return when (type.lowercase(Locale.ROOT)) {
            "depart" -> "Xuất phát$onRoadSuffix"
            "arrive" -> "Đến đích: ${destName ?: "Chi nhánh / ATM NEXA Bank"}"
            "turn", "end of road", "fork" -> "$modVi$roadSuffix"
            "continue", "new name" -> "Tiếp tục đi thẳng$roadSuffix"
            "roundabout", "rotary" -> "Đi vào vòng xuyến và ra$roadSuffix"
            "merge" -> "Nhập làn$roadSuffix"
            else -> "$modVi$roadSuffix"
        }
    }

    private fun computeBounds(
        path: List<GeoPointDto>,
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double
    ): RouteBoundsDto {
        var minLat = minOf(originLat, destLat)
        var maxLat = maxOf(originLat, destLat)
        var minLng = minOf(originLng, destLng)
        var maxLng = maxOf(originLng, destLng)
        for (pt in path) {
            if (pt.latitude < minLat) minLat = pt.latitude
            if (pt.latitude > maxLat) maxLat = pt.latitude
            if (pt.longitude < minLng) minLng = pt.longitude
            if (pt.longitude > maxLng) maxLng = pt.longitude
        }
        return RouteBoundsDto(
            northeast = GeoPointDto(maxLat, maxLng),
            southwest = GeoPointDto(minLat, minLng)
        )
    }

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return r * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    // ======================= POLYLINE DECODER (GOOGLE FORMAT) =======================

    /**
     * Giải mã chuỗi `geometry` / `overview_polyline.points` chuẩn Google Encoded Polyline (precision 5)
     * từ OSRM Route Service thành danh sách tọa độ [GeoPointDto].
     */
    fun decodePolyline(encoded: String): List<GeoPointDto> {
        val points = mutableListOf<GeoPointDto>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            val dLat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dLat

            if (index >= len) break
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            val dLng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dLng

            points.add(GeoPointDto(lat / 1e5, lng / 1e5))
        }
        return points
    }

    // ======================= INTERNAL HTTP & PARSERS =======================

    private fun buildRouteCacheKey(
        originLat: Double,
        originLng: Double,
        branchId: String?,
        destLat: Double?,
        destLng: Double?,
        mode: String
    ): String {
        val oLat = String.format(Locale.US, "%.4f", originLat)
        val oLng = String.format(Locale.US, "%.4f", originLng)
        val destPart = when {
            destLat != null && destLng != null ->
                "${String.format(Locale.US, "%.4f", destLat)}_${String.format(Locale.US, "%.4f", destLng)}"
            !branchId.isNullOrBlank() -> "br_${branchId.trim()}"
            else -> "nearest"
        }
        return "${oLat}_${oLng}_${destPart}_${mode.lowercase(Locale.ROOT)}"
    }

    private fun <T> runAsync(block: () -> T, callback: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching { block() }
            mainHandler.post { callback(result) }
        }
    }

    @Throws(Exception::class)
    private fun requestExternalJsonSync(fullUrlString: String): JSONObject {
        val url = URL(fullUrlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", HTTP_USER_AGENT)
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val rawResponse = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }.orEmpty()

            if (code !in 200..299) {
                throw RuntimeException("OSRM HTTP $code: $rawResponse")
            }
            return if (rawResponse.isBlank()) JSONObject() else JSONObject(rawResponse)
        } finally {
            conn.disconnect()
        }
    }

    @Throws(Exception::class)
    private fun requestGetJsonSync(
        path: String,
        queryParams: Map<String, String> = emptyMap()
    ): JSONObject {
        val queryString = if (queryParams.isEmpty()) {
            ""
        } else {
            "?" + queryParams.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
        }

        val fullUrl = URL("$baseUrl$path$queryString")
        val conn = (fullUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            UtilitiesApiClient.accessToken?.takeIf { it.isNotBlank() }?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val rawResponse = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }.orEmpty()

            if (code !in 200..299) {
                val errMsg = try {
                    val errJson = JSONObject(rawResponse)
                    errJson.optString("message").ifBlank { errJson.optString("error", "HTTP $code") }
                } catch (_: Exception) {
                    "HTTP $code: $rawResponse"
                }
                throw RuntimeException(errMsg)
            }

            return if (rawResponse.isBlank()) JSONObject() else JSONObject(rawResponse)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseBranchList(arr: JSONArray): List<BankBranchDto> {
        val list = mutableListOf<BankBranchDto>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(parseBranch(obj))
        }
        return list
    }

    private fun parseBranch(obj: JSONObject): BankBranchDto {
        return BankBranchDto(
            branchId = obj.optString("branch_id"),
            name = obj.optString("name"),
            branchType = obj.optString("branch_type", "BRANCH"),
            address = obj.optString("address"),
            latitude = obj.optDouble("latitude", 0.0),
            longitude = obj.optDouble("longitude", 0.0),
            openingHours = obj.optString("opening_hours").takeIf { it.isNotBlank() && it != "null" },
            status = obj.optString("status", "OPEN"),
            distanceKm = if (obj.has("distance_km") && !obj.isNull("distance_km")) obj.optDouble("distance_km") else null,
            distanceMeters = if (obj.has("distance_meters") && !obj.isNull("distance_meters")) obj.optInt("distance_meters") else null,
            isNearest = obj.optBoolean("is_nearest", false),
            recommendedMode = obj.optString("recommended_mode").takeIf { it.isNotBlank() && it != "null" },
            estimatedWalkingMinutes = if (obj.has("estimated_walking_minutes") && !obj.isNull("estimated_walking_minutes")) {
                obj.optInt("estimated_walking_minutes")
            } else {
                null
            }
        )
    }

    private fun parseGeoPoint(obj: JSONObject?): GeoPointDto? {
        if (obj == null) return null
        return GeoPointDto(
            latitude = obj.optDouble("latitude", 0.0),
            longitude = obj.optDouble("longitude", 0.0)
        )
    }

    private fun parseRouteProposal(json: JSONObject): RouteProposalDto {
        val summaryObj = json.optJSONObject("summary")
        val summary = summaryObj?.let {
            RouteSummaryDto(
                distanceMeters = it.optInt("distance_meters", 0),
                distanceKm = it.optDouble("distance_km", 0.0),
                distanceText = it.optString("distance_text"),
                durationSeconds = it.optInt("duration_seconds", 0),
                durationMinutes = it.optInt("duration_minutes", 0),
                durationText = it.optString("duration_text"),
                polylineColor = it.optString("polyline_color", "#22C55E"),
                polylineStyle = it.optString("polyline_style", "SOLID")
            )
        }

        val boundsObj = json.optJSONObject("bounds")
        val bounds = if (boundsObj != null) {
            val ne = parseGeoPoint(boundsObj.optJSONObject("northeast"))
            val sw = parseGeoPoint(boundsObj.optJSONObject("southwest"))
            if (ne != null && sw != null) RouteBoundsDto(ne, sw) else null
        } else {
            null
        }

        val encodedPoints = json.optJSONObject("overview_polyline")
            ?.optString("points")
            ?.takeIf { it.isNotBlank() && it != "null" }

        val decodedPathArr = json.optJSONArray("decoded_path")
        val decodedPath = mutableListOf<GeoPointDto>()
        if (decodedPathArr != null) {
            for (i in 0 until decodedPathArr.length()) {
                parseGeoPoint(decodedPathArr.optJSONObject(i))?.let { decodedPath.add(it) }
            }
        } else if (!encodedPoints.isNullOrBlank()) {
            decodedPath.addAll(decodePolyline(encodedPoints))
        }

        val stepsArr = json.optJSONArray("steps")
        val steps = mutableListOf<RouteStepDto>()
        if (stepsArr != null) {
            for (i in 0 until stepsArr.length()) {
                val s = stepsArr.optJSONObject(i) ?: continue
                steps.add(
                    RouteStepDto(
                        stepNumber = s.optInt("step_number", i + 1),
                        instruction = s.optString("instruction"),
                        distanceText = s.optString("distance_text"),
                        durationText = s.optString("duration_text"),
                        startLocation = parseGeoPoint(s.optJSONObject("start_location")),
                        endLocation = parseGeoPoint(s.optJSONObject("end_location"))
                    )
                )
            }
        }

        val fallbackModesArr = json.optJSONArray("suggested_fallback_modes")
        val fallbackModes = mutableListOf<String>()
        if (fallbackModesArr != null) {
            for (i in 0 until fallbackModesArr.length()) {
                val m = fallbackModesArr.optString(i)
                if (m.isNotBlank()) fallbackModes.add(m)
            }
        }

        return RouteProposalDto(
            status = json.optString("status", "OK"),
            mode = json.optString("mode", TransportMode.WALKING.apiValue),
            modeLabel = json.optString("mode_label").takeIf { it.isNotBlank() && it != "null" },
            cached = json.optBoolean("cached", false),
            message = json.optString("message").takeIf { it.isNotBlank() && it != "null" },
            suggestedFallbackModes = fallbackModes,
            origin = parseGeoPoint(json.optJSONObject("origin")),
            destination = parseGeoPoint(json.optJSONObject("destination")),
            branch = json.optJSONObject("branch")?.let { parseBranch(it) },
            summary = summary,
            bounds = bounds,
            encodedPolyline = encodedPoints,
            decodedPath = decodedPath,
            steps = steps,
            googleMapsIntentUri = json.optString("google_maps_intent_uri")
                .takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
