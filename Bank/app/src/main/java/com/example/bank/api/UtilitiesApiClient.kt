package com.example.bank.api

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Client gọi REST API từ ứng dụng Android `Bank` tới `BankServer` (`/v1/utilities/...`)
 * để truy vấn và cập nhật dữ liệu Utilities từ SQL Server Database.
 */
object UtilitiesApiClient {

    /**
     * Địa chỉ mặc định khi chạy trên Android Emulator trỏ về cổng 8080 của máy host chạy BankServer.
     * Có thể thay đổi thông qua [setServerBaseUrl] nếu chạy trên thiết bị thật (LAN IP).
     */
    @Volatile
    var baseUrl: String = "http://10.0.2.2:3030/v1"
        private set

    @Volatile
    var accessToken: String? = null
        private set

    @Volatile
    var currentUserId: String = "u1111111-1111-1111-1111-111111111111"
        private set

    @Volatile
    var defaultCheckingAccountId: String = "a1b2c3d4-0000-0000-0000-000000000001"
        private set

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setServerBaseUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        if (cleaned.isNotEmpty()) {
            baseUrl = if (cleaned.endsWith("/v1")) cleaned else "$cleaned/v1"
        }
    }

    fun setAuthToken(token: String?) {
        accessToken = token
    }

    // ======================= AUTH & SEED =======================

    /**
     * Đăng nhập lấy JWT Token từ `POST /v1/auth/login` (hoặc fallback `POST /v1/dev/seed` nếu DB chưa seed).
     */
    @Throws(Exception::class)
    fun ensureAuthenticatedSync(username: String = "nguyenvana"): String {
        accessToken?.let { if (it.isNotBlank()) return it }

        return try {
            val body = JSONObject().put("username", username)
            val res = requestJsonSync("POST", "/auth/login", body = body, requireAuth = false)
            val token = res.optString("access_token")
            if (token.isNotBlank()) {
                accessToken = token
            }
            res.optJSONObject("user")?.let { userObj ->
                val uid = userObj.optString("user_id")
                if (uid.isNotBlank()) currentUserId = uid
            }
            res.optJSONArray("accounts")?.let { accArr ->
                for (i in 0 until accArr.length()) {
                    val acc = accArr.optJSONObject(i) ?: continue
                    if (acc.optString("account_type") == "CHECKING") {
                        defaultCheckingAccountId = acc.optString("account_id", defaultCheckingAccountId)
                        break
                    }
                }
            }
            token
        } catch (e: Exception) {
            // Fallback gọi /v1/dev/seed để tự động khởi tạo dữ liệu mẫu và lấy test_jwt_token
            val seedRes = requestJsonSync("POST", "/dev/seed", body = JSONObject(), requireAuth = false)
            val token = seedRes.optString("test_jwt_token")
            if (token.isNotBlank()) {
                accessToken = token
            }
            val uid = seedRes.optString("test_user_id")
            if (uid.isNotBlank()) currentUserId = uid
            val accId = seedRes.optString("test_account_id")
            if (accId.isNotBlank()) defaultCheckingAccountId = accId
            token
        }
    }

    // ======================= 1. READ & DISCOVERY APIs =======================

    /**
     * Truy vấn tổng hợp dữ liệu Utilities từ Database (`GET /v1/utilities/dashboard`):
     * Bao gồm `utility_services`, `utility_orders`, `unpaid_bills`, `accounts`, `users`.
     */
    fun fetchDashboard(callback: (Result<UtilitiesDashboardDto>) -> Unit) {
        runAsync({
            ensureAuthenticatedSync()
            val json = requestJsonSync("GET", "/utilities/dashboard")
            parseDashboard(json)
        }, callback)
    }

    /**
     * Truy vấn danh sách Dịch vụ & Đối tác Tiện ích từ bảng `dbo.utility_services` (`GET /v1/utilities/services`).
     *
     * @param serviceType Lọc theo nhóm tiện ích: ELECTRICITY, WATER, PHONE_TOPUP, FLIGHT, MOVIE, HOTEL, ECOMMERCE
     * @param status Lọc theo trạng thái: ACTIVE, MAINTENANCE, SUSPENDED
     */
    fun fetchUtilityServices(
        serviceType: String? = null,
        status: String? = null,
        callback: (Result<List<UtilityServiceDto>>) -> Unit
    ) {
        runAsync({
            ensureAuthenticatedSync()
            val queryParams = mutableMapOf<String, String>()
            if (!serviceType.isNullOrBlank()) queryParams["service_type"] = serviceType
            if (!status.isNullOrBlank()) queryParams["status"] = status

            val json = requestJsonSync("GET", "/utilities/services", queryParams = queryParams)
            val itemsArr = json.optJSONArray("items") ?: JSONArray()
            parseServiceList(itemsArr)
        }, callback)
    }

    /**
     * Truy vấn chi tiết một Dịch vụ Tiện ích kèm danh sách đơn hàng `utility_orders` từ Database
     * (`GET /v1/utilities/services/{service_id}`).
     */
    fun fetchServiceDetail(
        serviceIdOrCode: String,
        callback: (Result<Pair<UtilityServiceDto, List<UtilityOrderDto>>>) -> Unit
    ) {
        runAsync({
            ensureAuthenticatedSync()
            val encoded = URLEncoder.encode(serviceIdOrCode, "UTF-8")
            val json = requestJsonSync("GET", "/utilities/services/$encoded")
            val service = parseService(json.getJSONObject("service"))
            val orders = parseOrderList(json.optJSONArray("orders") ?: JSONArray())
            Pair(service, orders)
        }, callback)
    }

    /**
     * Thêm mới một Dịch vụ Tiện ích vào bảng `dbo.utility_services` trong Database (`POST /v1/utilities/services`).
     */
    fun createUtilityService(
        providerCode: String,
        serviceName: String,
        serviceType: String = "ELECTRICITY",
        aggregatorGateway: String = "VNPAY",
        callback: (Result<UtilityServiceDto>) -> Unit
    ) {
        runAsync({
            ensureAuthenticatedSync()
            val body = JSONObject().apply {
                put("provider_code", providerCode)
                put("service_name", serviceName)
                put("service_type", serviceType)
                put("aggregator_gateway", aggregatorGateway)
                put("status", "ACTIVE")
            }
            val json = requestJsonSync("POST", "/utilities/services", body = body)
            parseService(json)
        }, callback)
    }

    /**
     * Truy vấn danh sách hóa đơn (Điện, Nước, Viễn thông) của người dùng từ bảng `dbo.utility_orders`
     * (`GET /v1/utilities/bills`).
     */
    fun fetchBills(
        status: String? = null,
        billType: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
        callback: (Result<List<UtilityBillDto>>) -> Unit
    ) {
        runAsync({
            ensureAuthenticatedSync()
            val params = mutableMapOf(
                "page" to page.toString(),
                "page_size" to pageSize.toString()
            )
            if (!status.isNullOrBlank()) params["status"] = status
            if (!billType.isNullOrBlank()) params["bill_type"] = billType

            val json = requestJsonSync("GET", "/utilities/bills", queryParams = params)
            parseBillList(json.optJSONArray("items") ?: JSONArray())
        }, callback)
    }

    /**
     * Tra cứu nợ cước hóa đơn theo Mã khách hàng (`POST /v1/utilities/bills/lookup`)
     * và đồng bộ vào bảng `dbo.utility_orders`.
     */
    fun lookupBill(
        billType: String,
        providerCustomerId: String,
        providerCode: String? = null,
        topupAmount: Double? = null,
        callback: (Result<UtilityBillDto>) -> Unit
    ) {
        runAsync({
            ensureAuthenticatedSync()
            val body = JSONObject().apply {
                put("bill_type", billType)
                put("provider_customer_id", providerCustomerId)
                if (!providerCode.isNullOrBlank()) put("provider_code", providerCode)
                if (topupAmount != null) put("topup_amount", topupAmount)
            }
            val json = requestJsonSync("POST", "/utilities/bills/lookup", body = body)
            parseBill(json)
        }, callback)
    }

    /**
     * Tự động nhận diện nhà mạng (Viettel, VinaPhone, MobiFone) qua số điện thoại
     * và trả về các mệnh giá nạp (`GET /v1/utilities/topup/detect-carrier`).
     */
    fun detectCarrier(
        phone: String,
        callback: (Result<CarrierDetectDto>) -> Unit
    ) {
        runAsync({
            ensureAuthenticatedSync()
            val json = requestJsonSync(
                "GET",
                "/utilities/topup/detect-carrier",
                queryParams = mapOf("phone" to phone)
            )
            val denoms = mutableListOf<Int>()
            val arr = json.optJSONArray("denominations")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    denoms.add(arr.optInt(i))
                }
            }
            CarrierDetectDto(
                valid = json.optBoolean("valid", true),
                phoneNumber = json.optString("phone_number", phone),
                prefix = json.optString("prefix", ""),
                carrier = json.optString("carrier", "VIETTEL"),
                carrierName = json.optString("carrier_name", "Viettel Telecom"),
                denominations = denoms
            )
        }, callback)
    }

    /**
     * Truy vấn danh sách đơn hàng tiện ích đa hình từ bảng `dbo.utility_orders` (`GET /v1/utilities/orders`).
     */
    fun fetchUtilityOrders(
        serviceType: String? = null,
        status: String? = null,
        callback: (Result<List<UtilityOrderDto>>) -> Unit
    ) {
        runAsync({
            ensureAuthenticatedSync()
            val params = mutableMapOf<String, String>()
            if (!serviceType.isNullOrBlank()) params["service_type"] = serviceType
            if (!status.isNullOrBlank()) params["status"] = status

            val json = requestJsonSync("GET", "/utilities/orders", queryParams = params)
            parseOrderList(json.optJSONArray("items") ?: JSONArray())
        }, callback)
    }

    /**
     * Tìm kiếm danh mục dịch vụ đối tác: FLIGHT, HOTEL, MOVIE, ECOMMERCE (`GET /v1/utilities/catalog/{service_type}`).
     */
    fun fetchCatalog(
        serviceType: String,
        query: String? = null,
        from: String? = null,
        to: String? = null,
        date: String? = null,
        city: String? = null,
        provider: String? = null,
        callback: (Result<List<CatalogItemDto>>) -> Unit
    ) {
        runAsync({
            ensureAuthenticatedSync()
            val params = mutableMapOf<String, String>()
            if (!query.isNullOrBlank()) params["q"] = query
            if (!from.isNullOrBlank()) params["from"] = from
            if (!to.isNullOrBlank()) params["to"] = to
            if (!date.isNullOrBlank()) params["date"] = date
            if (!city.isNullOrBlank()) params["city"] = city
            if (!provider.isNullOrBlank()) params["provider"] = provider

            val typePath = URLEncoder.encode(serviceType.uppercase(), "UTF-8")
            val json = requestJsonSync("GET", "/utilities/catalog/$typePath", queryParams = params)
            val arr = json.optJSONArray("items") ?: JSONArray()
            val result = mutableListOf<CatalogItemDto>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                result.add(
                    CatalogItemDto(
                        catalogItemId = obj.optString("catalog_item_id"),
                        serviceType = obj.optString("service_type", serviceType),
                        providerCode = obj.optString("provider_code"),
                        title = obj.optString("title"),
                        price = obj.optDouble("price", 0.0),
                        currency = obj.optString("currency", "VND"),
                        details = obj.optJSONObject("details") ?: JSONObject()
                    )
                )
            }
            result
        }, callback)
    }

    /**
     * Truy vấn sơ đồ ghế ngồi rạp CGV theo thời gian thực (`GET /v1/utilities/movies/{catalog_item_id}/seats`).
     */
    fun fetchMovieSeatMap(
        catalogItemId: String,
        showtime: String = "19:30",
        callback: (Result<MovieSeatMapDto>) -> Unit
    ) {
        runAsync({
            ensureAuthenticatedSync()
            val encodedId = URLEncoder.encode(catalogItemId, "UTF-8")
            val json = requestJsonSync(
                "GET",
                "/utilities/movies/$encodedId/seats",
                queryParams = mapOf("showtime" to showtime)
            )
            val seatsArr = json.optJSONArray("seats") ?: JSONArray()
            val seats = mutableListOf<MovieSeatDto>()
            for (i in 0 until seatsArr.length()) {
                val s = seatsArr.optJSONObject(i) ?: continue
                seats.add(
                    MovieSeatDto(
                        seatId = s.optString("seat_id"),
                        row = s.optString("row"),
                        number = s.optInt("number"),
                        seatType = s.optString("seat_type", "STANDARD"),
                        price = s.optDouble("price", 115000.0),
                        status = s.optString("status", "AVAILABLE")
                    )
                )
            }
            MovieSeatMapDto(
                catalogItemId = json.optString("catalog_item_id", catalogItemId),
                showtime = json.optString("showtime", showtime),
                totalSeats = json.optInt("total_seats", seats.size),
                availableCount = json.optInt("available_count", 0),
                heldCount = json.optInt("held_count", 0),
                bookedCount = json.optInt("booked_count", 0),
                seats = seats
            )
        }, callback)
    }

    // ======================= 2. HOLD & PAYMENT APIs =======================

    /**
     * Giữ chỗ tạm thời 15 phút (Seat/Room Hold) hoặc kiểm tra tồn kho (`POST /v1/utilities/orders/hold`).
     */
    fun holdUtilityOrder(
        serviceType: String,
        catalogItemId: String,
        quantity: Int = 1,
        voucherCode: String? = null,
        bookingDetails: JSONObject = JSONObject(),
        callback: (Result<HoldOrderResponseDto>) -> Unit
    ) {
        runAsync({
            ensureAuthenticatedSync()
            val body = JSONObject().apply {
                put("service_type", serviceType.uppercase())
                put("catalog_item_id", catalogItemId)
                put("quantity", quantity)
                if (!voucherCode.isNullOrBlank()) put("voucher_code", voucherCode)
                put("booking_details", bookingDetails)
            }
            val json = requestJsonSync("POST", "/utilities/orders/hold", body = body)
            val pricing = json.optJSONObject("pricing") ?: JSONObject()
            HoldOrderResponseDto(
                orderId = json.optString("order_id"),
                serviceId = json.optString("service_id"),
                serviceType = json.optString("service_type", serviceType),
                catalogItemId = json.optString("catalog_item_id", catalogItemId),
                title = json.optString("title"),
                baseAmount = pricing.optDouble("base_amount", 0.0),
                shippingFee = pricing.optDouble("shipping_fee", 0.0),
                discountAmount = pricing.optDouble("discount_amount", 0.0),
                totalAmount = pricing.optDouble("total_amount", 0.0),
                status = json.optString("status", "PENDING"),
                holdExpiresAt = json.optString("hold_expires_at").takeIf { it.isNotBlank() }
            )
        }, callback)
    }

    /**
     * Bước 1: Khởi tạo giao dịch thanh toán hóa đơn / tiện ích (`POST /v1/utilities/payments`).
     * Tự động đính kèm `Idempotency-Key` (UUID) để chống trừ tiền trùng.
     */
    fun initiatePayment(
        sourceAccountId: String = defaultCheckingAccountId,
        txnType: String,                // "BILL" hoặc "UTILITY"
        billId: String? = null,
        orderId: String? = null,
        bookingPayload: JSONObject? = null,
        paymentGateway: String = "NONE",
        callback: (Result<PaymentInitResponseDto>) -> Unit
    ) {
        runAsync({
            ensureAuthenticatedSync()
            val body = JSONObject().apply {
                put("source_account_id", sourceAccountId)
                put("txn_type", txnType)
                put("payment_gateway", paymentGateway)
                if (!billId.isNullOrBlank()) put("bill_id", billId)
                if (!orderId.isNullOrBlank()) put("order_id", orderId)
                if (bookingPayload != null) put("booking", bookingPayload)
            }
            val headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString())
            val json = requestJsonSync("POST", "/utilities/payments", body = body, extraHeaders = headers)
            PaymentInitResponseDto(
                txnCode = json.optString("txn_code"),
                challengeId = json.optString("challenge_id").takeIf { it.isNotBlank() },
                orderId = json.optString("order_id").takeIf { it.isNotBlank() },
                status = json.optString("status", "PENDING"),
                amount = json.optDouble("amount", 0.0),
                requiresOtp = json.optBoolean("requires_otp", true),
                requiresBiometric = json.optBoolean("requires_biometric", false),
                otpExpiresAt = json.optString("otp_expires_at").takeIf { it.isNotBlank() },
                holdExpiresAt = json.optString("hold_expires_at").takeIf { it.isNotBlank() },
                gatewayRedirectUrl = json.optString("gateway_redirect_url").takeIf { it.isNotBlank() && it != "null" },
                devOtpHint = json.optString("dev_otp_hint").takeIf { it.isNotBlank() }
            )
        }, callback)
    }

    /**
     * Bước 2: Xác nhận thanh toán bằng mã OTP / Sinh trắc học (`POST /v1/utilities/payments/{txn_code}/confirm`).
     */
    fun confirmPayment(
        txnCode: String,
        otpCode: String,
        biometricToken: String? = null,
        simulatePartnerError: Boolean = false,
        callback: (Result<PaymentResultDto>) -> Unit
    ) {
        runAsync({
            ensureAuthenticatedSync()
            val body = JSONObject().apply {
                put("otp_code", otpCode)
                if (!biometricToken.isNullOrBlank()) put("biometric_token", biometricToken)
                if (simulatePartnerError) put("simulate_partner_error", true)
            }
            val encodedTxn = URLEncoder.encode(txnCode, "UTF-8")
            val json = requestJsonSync("POST", "/utilities/payments/$encodedTxn/confirm", body = body)
            val billObj = json.optJSONObject("bill")
            PaymentResultDto(
                txnCode = json.optString("txn_code", txnCode),
                status = json.optString("status", "SUCCESS"),
                amount = json.optDouble("amount", 0.0),
                txnType = json.optString("txn_type", ""),
                balanceAfter = if (json.has("balance_after") && !json.isNull("balance_after")) json.optDouble("balance_after") else null,
                bill = if (billObj != null) parseBill(billObj) else null,
                fulfillment = json.optJSONObject("fulfillment"),
                failureReason = json.optString("failure_reason").takeIf { it.isNotBlank() && it != "null" }
            )
        }, callback)
    }

    // ======================= INTERNAL HTTP & PARSERS =======================

    private fun <T> runAsync(block: () -> T, callback: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching { block() }
            mainHandler.post { callback(result) }
        }
    }

    @Throws(Exception::class)
    private fun requestJsonSync(
        method: String,
        path: String,
        queryParams: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        requireAuth: Boolean = true
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
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 25000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            if (requireAuth && !accessToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
            for ((k, v) in extraHeaders) {
                setRequestProperty(k, v)
            }
            if (body != null && (method == "POST" || method == "PUT" || method == "PATCH")) {
                doOutput = true
            }
        }

        try {
            if (body != null && (method == "POST" || method == "PUT" || method == "PATCH")) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body.toString())
                    writer.flush()
                }
            }

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

    private fun parseDashboard(json: JSONObject): UtilitiesDashboardDto {
        val userObj = json.optJSONObject("user")
        val accountsArr = json.optJSONArray("accounts") ?: JSONArray()
        val accounts = mutableListOf<BankAccountDto>()
        for (i in 0 until accountsArr.length()) {
            val a = accountsArr.optJSONObject(i) ?: continue
            val acc = BankAccountDto(
                accountId = a.optString("account_id"),
                accountNumber = a.optString("account_number"),
                accountType = a.optString("account_type", "CHECKING"),
                balance = a.optDouble("balance", 0.0),
                currency = a.optString("currency", "VND"),
                status = a.optString("status", "ACTIVE")
            )
            accounts.add(acc)
            if (acc.accountType == "CHECKING" && acc.accountId.isNotBlank()) {
                defaultCheckingAccountId = acc.accountId
            }
        }

        val services = parseServiceList(json.optJSONArray("services") ?: JSONArray())
        val bills = parseBillList(json.optJSONArray("bills") ?: JSONArray())
        val unpaidBills = parseBillList(json.optJSONArray("unpaid_bills") ?: JSONArray())
        val orders = parseOrderList(json.optJSONArray("orders") ?: JSONArray())

        val denomsArr = json.optJSONArray("topup_denominations")
        val denoms = mutableListOf<Int>()
        if (denomsArr != null) {
            for (i in 0 until denomsArr.length()) {
                denoms.add(denomsArr.optInt(i))
            }
        }

        return UtilitiesDashboardDto(
            userId = userObj?.optString("user_id")?.takeIf { it.isNotBlank() },
            userFullName = userObj?.optString("full_name")?.takeIf { it.isNotBlank() },
            userPhone = userObj?.optString("phone_number")?.takeIf { it.isNotBlank() },
            accounts = accounts,
            services = services,
            bills = bills,
            unpaidBills = unpaidBills,
            orders = orders,
            topupDenominations = denoms.ifEmpty { listOf(10000, 20000, 50000, 100000, 200000, 500000) }
        )
    }

    private fun parseServiceList(arr: JSONArray): List<UtilityServiceDto> {
        val list = mutableListOf<UtilityServiceDto>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(parseService(obj))
        }
        return list
    }

    private fun parseService(obj: JSONObject): UtilityServiceDto {
        return UtilityServiceDto(
            serviceId = obj.optString("service_id"),
            providerCode = obj.optString("provider_code"),
            serviceName = obj.optString("service_name"),
            serviceType = obj.optString("service_type"),
            aggregatorGateway = obj.optString("aggregator_gateway", "VNPAY"),
            status = obj.optString("status", "ACTIVE"),
            createdAt = obj.optString("created_at").takeIf { it.isNotBlank() }
        )
    }

    private fun parseBillList(arr: JSONArray): List<UtilityBillDto> {
        val list = mutableListOf<UtilityBillDto>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(parseBill(obj))
        }
        return list
    }

    private fun parseBill(obj: JSONObject): UtilityBillDto {
        return UtilityBillDto(
            billId = obj.optString("bill_id", obj.optString("order_id")),
            orderId = obj.optString("order_id", obj.optString("bill_id")),
            billType = obj.optString("bill_type", "ELECTRICITY"),
            providerCode = obj.optString("provider_code", "EVN_HCM"),
            providerCustomerId = obj.optString("provider_customer_id"),
            customerName = obj.optString("customer_name", "Nguyễn Văn A"),
            customerAddress = obj.optString("customer_address", ""),
            billingPeriod = obj.optString("billing_period", "11/2026"),
            amount = obj.optDouble("amount", 0.0),
            status = obj.optString("status", "UNPAID"),
            transactionId = obj.optString("transaction_id").takeIf { it.isNotBlank() && it != "null" },
            createdAt = obj.optString("created_at").takeIf { it.isNotBlank() }
        )
    }

    private fun parseOrderList(arr: JSONArray): List<UtilityOrderDto> {
        val list = mutableListOf<UtilityOrderDto>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val meta = obj.optJSONObject("metadata_payload") ?: run {
                val raw = obj.optString("metadata_payload")
                if (raw.isNotBlank() && raw.startsWith("{")) {
                    try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                } else {
                    JSONObject()
                }
            }
            list.add(
                UtilityOrderDto(
                    orderId = obj.optString("order_id"),
                    userId = obj.optString("user_id"),
                    serviceId = obj.optString("service_id"),
                    transactionId = obj.optString("transaction_id").takeIf { it.isNotBlank() && it != "null" },
                    providerCustomerId = obj.optString("provider_customer_id"),
                    totalAmount = obj.optDouble("total_amount", 0.0),
                    metadataPayload = meta,
                    status = obj.optString("status", "PENDING"),
                    holdExpiresAt = obj.optString("hold_expires_at").takeIf { it.isNotBlank() && it != "null" },
                    createdAt = obj.optString("created_at").takeIf { it.isNotBlank() },
                    serviceType = obj.optString("service_type").takeIf { it.isNotBlank() } ?: meta.optString("service_type"),
                    providerCode = obj.optString("provider_code").takeIf { it.isNotBlank() } ?: meta.optString("provider_code"),
                    serviceName = obj.optString("service_name").takeIf { it.isNotBlank() }
                )
            )
        }
        return list
    }
}
