package com.example.bank.api

import org.json.JSONObject

/**
 * Mô hình dịch vụ & đối tác tiện ích đồng bộ với bảng `dbo.utility_services` trong Database
 * và lớp `[M3 - Internal] UtilityService` trong Class_Unified.html.
 */
data class UtilityServiceDto(
    val serviceId: String,
    val providerCode: String,
    val serviceName: String,
    val serviceType: String,        // ELECTRICITY, WATER, PHONE_TOPUP, FLIGHT, MOVIE, HOTEL, ECOMMERCE
    val aggregatorGateway: String,  // VNPAY, PAYOO, NAPAS, DIRECT
    val status: String,             // ACTIVE, MAINTENANCE, SUSPENDED
    val createdAt: String? = null
) {
    fun isUnderMaintenance(): Boolean = status.equals("MAINTENANCE", ignoreCase = true)
}

/**
 * Mô hình đơn hàng tiện ích đa hình đồng bộ với bảng `dbo.utility_orders` trong Database
 * và lớp `[M3 - Internal] UtilityOrder` trong Class_Unified.html.
 */
data class UtilityOrderDto(
    val orderId: String,
    val userId: String,
    val serviceId: String,
    val transactionId: String?,
    val providerCustomerId: String,
    val totalAmount: Double,
    val metadataPayload: JSONObject,
    val status: String,             // PENDING, UNPAID, PAID, COMPLETED, FAILED, EXPIRED, REFUNDED
    val holdExpiresAt: String? = null,
    val createdAt: String? = null,
    val serviceType: String? = null,
    val providerCode: String? = null,
    val serviceName: String? = null
)

/**
 * Mô hình hóa đơn tiện ích (Điện, Nước, Nạp điện thoại) truy vấn từ `utility_orders` JOIN `utility_services`.
 */
data class UtilityBillDto(
    val billId: String,
    val orderId: String,
    val billType: String,           // ELECTRICITY, WATER, PHONE_TOPUP
    val providerCode: String,
    val providerCustomerId: String,
    val customerName: String,
    val customerAddress: String,
    val billingPeriod: String,
    val amount: Double,
    val status: String,             // UNPAID, PAID
    val transactionId: String? = null,
    val createdAt: String? = null
)

/**
 * Mô hình tài khoản ngân hàng truy vấn từ bảng `dbo.accounts`.
 */
data class BankAccountDto(
    val accountId: String,
    val accountNumber: String,
    val accountType: String,        // CHECKING, SAVING, MORTGAGE
    val balance: Double,
    val currency: String = "VND",
    val status: String = "ACTIVE"
)

/**
 * Kết quả nhận diện nhà mạng viễn thông từ đầu số điện thoại (UC-U02).
 */
data class CarrierDetectDto(
    val valid: Boolean,
    val phoneNumber: String,
    val prefix: String,
    val carrier: String,
    val carrierName: String,
    val denominations: List<Int>
)

/**
 * Sản phẩm / chuyến bay / khách sạn / suất chiếu phim trong danh mục đối tác (`CatalogItem`).
 */
data class CatalogItemDto(
    val catalogItemId: String,
    val serviceType: String,
    val providerCode: String,
    val title: String,
    val price: Double,
    val currency: String,
    val details: JSONObject
)

/**
 * Thông tin một ghế ngồi trong rạp phim CGV.
 */
data class MovieSeatDto(
    val seatId: String,
    val row: String,
    val number: Int,
    val seatType: String,           // STANDARD, VIP
    val price: Double,
    val status: String              // AVAILABLE, HELD, BOOKED
)

/**
 * Sơ đồ ghế ngồi thời gian thực của suất chiếu phim CGV.
 */
data class MovieSeatMapDto(
    val catalogItemId: String,
    val showtime: String,
    val totalSeats: Int,
    val availableCount: Int,
    val heldCount: Int,
    val bookedCount: Int,
    val seats: List<MovieSeatDto>
)

/**
 * Kết quả giữ chỗ tạm thời 15 phút (`POST /v1/utilities/orders/hold`).
 */
data class HoldOrderResponseDto(
    val orderId: String,
    val serviceId: String,
    val serviceType: String,
    val catalogItemId: String,
    val title: String,
    val baseAmount: Double,
    val shippingFee: Double,
    val discountAmount: Double,
    val totalAmount: Double,
    val status: String,
    val holdExpiresAt: String?
)

/**
 * Kết quả bước 1 khởi tạo giao dịch thanh toán tiện ích (`POST /v1/utilities/payments`).
 */
data class PaymentInitResponseDto(
    val txnCode: String,
    val challengeId: String?,
    val orderId: String?,
    val status: String,
    val amount: Double,
    val requiresOtp: Boolean,
    val requiresBiometric: Boolean,
    val otpExpiresAt: String?,
    val holdExpiresAt: String?,
    val gatewayRedirectUrl: String?,
    val devOtpHint: String?
)

/**
 * Kết quả bước 2 xác nhận giao dịch thanh toán tiện ích (`POST /v1/utilities/payments/{txn_code}/confirm`).
 */
data class PaymentResultDto(
    val txnCode: String,
    val status: String,             // PENDING, SUCCESS, FAILED, REFUNDED
    val amount: Double,
    val txnType: String,
    val balanceAfter: Double?,
    val bill: UtilityBillDto?,
    val fulfillment: JSONObject?,
    val failureReason: String?
)

/**
 * Dữ liệu tổng hợp Utilities truy vấn từ Database (`GET /v1/utilities/dashboard`).
 */
data class UtilitiesDashboardDto(
    val userId: String?,
    val userFullName: String?,
    val userPhone: String?,
    val accounts: List<BankAccountDto>,
    val services: List<UtilityServiceDto>,
    val bills: List<UtilityBillDto>,
    val unpaidBills: List<UtilityBillDto>,
    val orders: List<UtilityOrderDto>,
    val topupDenominations: List<Int>
)
