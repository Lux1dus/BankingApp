const express = require('express');
const router = express.Router();
const crypto = require('crypto');
const { v4: uuidv4 } = require('uuid');
const { sql, getPool } = require('../db');
const { authenticateJWT, optionalAuthenticateJWT } = require('../middleware/auth');
const paypalService = require('../services/paypal.service');
const { searchFlights } = require('../services/duffel.service');

// Bộ nhớ đệm Idempotency & Giữ chỗ tạm thời (Seat/Room Hold & Stock Inventory)
const idempotencyCache = new Map();
const HOLD_TIMEOUT_MS = 15 * 60 * 1000; // Giữ chỗ tạm thời 15 phút (UC-U03, UC-U04, UC-U05)
const DAILY_TXN_LIMIT = 500000000;      // Hạn mức giao dịch ngày: 500,000,000 VND
const HIGH_VALUE_THRESHOLD = 10000000;  // Ngưỡng giao dịch giá trị cao >= 10,000,000 VND (is_high_value = 1)

// Danh sách mệnh giá nạp điện thoại cố định (UC-U02)
const TOPUP_DENOMINATIONS = [10000, 20000, 50000, 100000, 200000, 500000];

// Danh sách mã giảm giá (Voucher) cho E-commerce / Tiện ích (UC-U06)
const VOUCHERS = {
    GIAM50K: { code: 'GIAM50K', discount_type: 'FIXED', value: 50000, min_order: 200000, description: 'Giảm 50.000đ cho đơn từ 200K' },
    GIAM100K: { code: 'GIAM100K', discount_type: 'FIXED', value: 100000, min_order: 500000, description: 'Giảm 100.000đ cho đơn từ 500K' },
    BANK10: { code: 'BANK10', discount_type: 'PERCENT', value: 10, max_discount: 150000, min_order: 100000, description: 'Giảm 10% tối đa 150.000đ' },
    FREESHIP: { code: 'FREESHIP', discount_type: 'SHIPPING', value: 30000, min_order: 0, description: 'Miễn phí vận chuyển tối đa 30.000đ' }
};

// Dữ liệu Catalog từ đối tác bên ngoài (FLIGHT được lấy trực tiếp từ Duffel API qua duffel.service.js)
const MOCK_CATALOG = {
    FLIGHT: [],
    HOTEL: [
        {
            catalog_item_id: 'HTL-MUONGTHANH-LUXURY-SGN',
            service_type: 'HOTEL',
            provider_code: 'MUONG_THANH',
            title: 'Khách sạn Mường Thanh Luxury Sài Gòn (1 Đêm)',
            price: 1650000,
            currency: 'VND',
            available_rooms: 8,
            details: {
                hotel_name: 'Mường Thanh Luxury Sài Gòn',
                address: '261C Nguyễn Văn Trỗi, Phường 10, Phú Nhuận, TP.HCM',
                city: 'HCM',
                stars: 5,
                room_types: [
                    { type: 'STANDARD', name: 'Deluxe King / Twin', price_per_night: 1650000, available: 5 },
                    { type: 'DELUXE', name: 'Executive Suite', price_per_night: 2800000, available: 3 }
                ],
                amenities: ['Bữa sáng Buffet', 'Hồ bơi vô cực', 'Wi-Fi tốc độ cao', 'Gym & Spa']
            }
        },
        {
            catalog_item_id: 'HTL-MUONGTHANH-HAN',
            service_type: 'HOTEL',
            provider_code: 'MUONG_THANH',
            title: 'Khách sạn Mường Thanh Grand Hà Nội (1 Đêm)',
            price: 1450000,
            currency: 'VND',
            available_rooms: 6,
            details: {
                hotel_name: 'Mường Thanh Grand Hà Nội',
                address: 'Lô CC2, Bắc Linh Đàm, Hoàng Mai, Hà Nội',
                city: 'HAN',
                stars: 4,
                room_types: [
                    { type: 'STANDARD', name: 'Superior Room', price_per_night: 1450000, available: 4 },
                    { type: 'DELUXE', name: 'Deluxe Triple', price_per_night: 2100000, available: 2 }
                ],
                amenities: ['Bữa sáng miễn phí', 'Đưa đón sân bay', 'Hồ bơi bốn mùa']
            }
        },
        {
            catalog_item_id: 'HTL-REX-SGN',
            service_type: 'HOTEL',
            provider_code: 'MUONG_THANH',
            title: 'Rex Hotel Saigon (1 Đêm)',
            price: 3200000,
            currency: 'VND',
            available_rooms: 4,
            details: {
                hotel_name: 'Rex Hotel Saigon',
                address: '141 Nguyễn Huệ, Bến Nghé, Quận 1, TP.HCM',
                city: 'HCM',
                stars: 5,
                room_types: [
                    { type: 'DELUXE', name: 'Rex Suite', price_per_night: 3200000, available: 4 }
                ]
            }
        }
    ],
    MOVIE: [
        {
            catalog_item_id: 'M_VENOM_3',
            service_type: 'MOVIE',
            provider_code: 'CGV',
            title: 'Vé CGV IMAX/2D - Venom 3: Kèo Cuối (The Last Dance)',
            price: 115000,
            currency: 'VND',
            details: {
                movie_id: 'M_VENOM_3',
                movie_name: 'Venom 3: Kèo Cuối',
                cinema_chain: 'CGV',
                cinema: 'CGV Vincom Center Landmark 81',
                city: 'HCM',
                duration_minutes: 110,
                rating: 'T16',
                showtimes: ['18:00', '19:30', '21:15', '22:45'],
                seat_prices: { STANDARD: 115000, VIP: 140000, COUPLE: 260000 },
                combos: [
                    { combo_id: 'CB_MY_COMBO', name: 'My Combo (1 Bắp lớn + 1 Nước siêu lớn)', price: 89000 },
                    { combo_id: 'CB_CGV_COMBO', name: 'CGV Combo (1 Bắp lớn + 2 Nước siêu lớn)', price: 119000 }
                ]
            }
        },
        {
            catalog_item_id: 'CGV-LATMAT8',
            service_type: 'MOVIE',
            provider_code: 'CGV',
            title: 'Vé CGV 2D - Lật Mặt 8: Vòng Tay Nắng',
            price: 110000,
            currency: 'VND',
            details: {
                movie_id: 'CGV-LATMAT8',
                movie_name: 'Lật Mặt 8: Vòng Tay Nắng',
                cinema_chain: 'CGV',
                cinema: 'CGV Vincom Đồng Khởi',
                city: 'HCM',
                duration_minutes: 132,
                rating: 'K',
                showtimes: ['17:30', '19:00', '20:30', '22:00'],
                seat_prices: { STANDARD: 110000, VIP: 130000, COUPLE: 250000 },
                combos: [
                    { combo_id: 'CB_MY_COMBO', name: 'My Combo (1 Bắp lớn + 1 Nước siêu lớn)', price: 89000 },
                    { combo_id: 'CB_CGV_COMBO', name: 'CGV Combo (1 Bắp lớn + 2 Nước siêu lớn)', price: 119000 }
                ]
            }
        }
    ],
    ECOMMERCE: [
        {
            catalog_item_id: 'VOUCHER-SHOPEE-500K',
            service_type: 'ECOMMERCE',
            provider_code: 'SHOPEE',
            title: 'Phiếu mua hàng Shopee 500K (Liên kết Ví Ngân Hàng)',
            price: 480000,
            currency: 'VND',
            stock: 50,
            shipping_fee: 0,
            details: { provider: 'Shopee', category: 'E-Voucher', instant_delivery: true }
        },
        {
            catalog_item_id: 'SHOPEE-AIRPODS-PRO2',
            service_type: 'ECOMMERCE',
            provider_code: 'SHOPEE',
            title: 'Tai nghe Apple AirPods Pro 2 USB-C Chính Hãng (Shopee Mall)',
            price: 5690000,
            currency: 'VND',
            stock: 12,
            shipping_fee: 30000,
            details: { provider: 'Shopee', seller: 'Apple Flagship Store', warranty: '12 tháng' }
        },
        {
            catalog_item_id: 'LAZADA-SAMSUNG-WATCH7',
            service_type: 'ECOMMERCE',
            provider_code: 'LAZADA',
            title: 'Đồng hồ thông minh Samsung Galaxy Watch 7 40mm (LazMall)',
            price: 6490000,
            currency: 'VND',
            stock: 8,
            shipping_fee: 25000,
            details: { provider: 'Lazada', seller: 'Samsung Official', warranty: '12 tháng' }
        },
        {
            catalog_item_id: 'AMAZON-KINDLE-PAPERWHITE',
            service_type: 'ECOMMERCE',
            provider_code: 'AMAZON',
            title: 'Máy đọc sách Amazon Kindle Paperwhite Gen 12 (16GB)',
            price: 4150000,
            currency: 'VND',
            stock: 5,
            shipping_fee: 50000,
            details: { provider: 'Amazon', seller: 'Amazon Devices', warranty: '12 tháng quốc tế' }
        }
    ]
};

// Sơ đồ ghế rạp CGV mặc định: các ghế đã bán sẵn để minh họa trên UI
const DEFAULT_BOOKED_SEATS = {
    M_VENOM_3: ['C4', 'C5', 'D5', 'D6', 'E6', 'E7'],
    'CGV-LATMAT8': ['B3', 'B4', 'D4', 'D5', 'F5', 'F6']
};

// Bản đồ service_id mặc định theo provider_code hoặc service_type
const DEFAULT_SERVICE_ID_BY_TYPE = {
    ELECTRICITY: 'srv-evn-hcm',
    WATER: 'srv-sawaco',
    PHONE_TOPUP: 'srv-viettel',
    FLIGHT: 'srv-vna',
    MOVIE: 'srv-cgv',
    HOTEL: 'srv-muong-thanh',
    ECOMMERCE: 'srv-shopee'
};

/**
 * Chuẩn hóa đối tượng Offer từ Duffel API thành định dạng Catalog Item của hệ thống
 */
function mapDuffelOfferToCatalogItem(offer, defaultOrigin = 'SGN', defaultDestination = 'HAN', defaultDate = '') {
    if (offer && offer.catalog_item_id && offer.service_type === 'FLIGHT') {
        return offer;
    }

    const slice = (Array.isArray(offer.slices) && offer.slices[0]) || {};
    const segments = Array.isArray(slice.segments) ? slice.segments : [];
    const firstSeg = segments[0] || {};
    const lastSeg = segments[segments.length - 1] || firstSeg;

    const carrier = firstSeg.marketing_carrier || firstSeg.operating_carrier || offer.owner || {};
    const iataCode = String(carrier.iata_code || 'VNA').toUpperCase();
    const airlineName = carrier.name || 'Duffel Airways';
    const rawFlightNo = firstSeg.marketing_carrier_flight_number || firstSeg.operating_carrier_flight_number || '100';
    const flightNumber = `${iataCode}${rawFlightNo}`;

    const fromCode = String((slice.origin && slice.origin.iata_code) || defaultOrigin).toUpperCase();
    const fromName = (slice.origin && (slice.origin.name || slice.origin.city_name)) || fromCode;
    const toCode = String((slice.destination && slice.destination.iata_code) || defaultDestination).toUpperCase();
    const toName = (slice.destination && (slice.destination.name || slice.destination.city_name)) || toCode;

    const departingAt = firstSeg.departing_at || '';
    const arrivingAt = lastSeg.arriving_at || '';
    const dateStr = departingAt ? departingAt.slice(0, 10) : defaultDate;
    const departureTime = departingAt && departingAt.includes('T') ? departingAt.slice(11, 16) : '08:00';
    const arrivalTime = arrivingAt && arrivingAt.includes('T') ? arrivingAt.slice(11, 16) : '10:10';

    const rawAmount = Number(offer.total_amount || 0);
    const rawCurrency = String(offer.total_currency || 'USD').toUpperCase();
    const rateToVnd = rawCurrency === 'VND' ? 1 : (Number(process.env.PAYPAL_VND_TO_USD_RATE) || 25000);
    const priceVnd = Math.round(rawAmount * rateToVnd);

    const providerCode = iataCode === 'VJ' ? 'VIETJET' : (iataCode === 'VN' ? 'VNA' : iataCode);

    return {
        catalog_item_id: offer.id,
        service_type: 'FLIGHT',
        provider_code: providerCode,
        title: `${airlineName} ${flightNumber}: ${fromName} (${fromCode}) -> ${toName} (${toCode})`,
        price: priceVnd,
        currency: 'VND',
        available_seats: 20,
        details: {
            duffel_offer_id: offer.id,
            original_amount: rawAmount,
            original_currency: rawCurrency,
            airline: airlineName,
            flight_number: flightNumber,
            from: fromCode,
            from_name: fromName,
            to: toCode,
            to_name: toName,
            date: dateStr,
            departure: departureTime,
            arrival: arrivalTime,
            seat_classes: {
                ECONOMY: priceVnd,
                BUSINESS: Math.round(priceVnd * 1.8)
            },
            baggage_options: [
                { code: 'BG0', label: 'Xách tay 7kg (Miễn phí)', price: 0 },
                { code: 'BG20', label: 'Ký gửi 20kg', price: 250000 },
                { code: 'BG30', label: 'Ký gửi 30kg', price: 380000 }
            ]
        }
    };
}

/**
 * Gọi Duffel API (duffel.service.js) để lấy danh sách chuyến bay và đồng bộ vào MOCK_CATALOG.FLIGHT
 */
async function fetchFlightCatalogFromDuffel({ from = 'SGN', to = 'HAN', date } = {}) {
    const origin = String(from || 'SGN').toUpperCase();
    const destination = String(to || 'HAN').toUpperCase();
    const defaultTomorrow = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
    const departureDate = date || defaultTomorrow;

    const offers = await searchFlights({
        origin,
        destination,
        departureDate
    });

    const mappedFlights = (Array.isArray(offers) ? offers : []).map(offer =>
        mapDuffelOfferToCatalogItem(offer, origin, destination, departureDate)
    );

    // Cập nhật MOCK_CATALOG.FLIGHT để các bước giữ chỗ (hold) / đặt vé tra cứu được theo catalog_item_id
    for (const flightItem of mappedFlights) {
        const existingIdx = MOCK_CATALOG.FLIGHT.findIndex(f => f.catalog_item_id === flightItem.catalog_item_id);
        if (existingIdx >= 0) {
            MOCK_CATALOG.FLIGHT[existingIdx] = flightItem;
        } else {
            MOCK_CATALOG.FLIGHT.push(flightItem);
        }
    }

    return mappedFlights;
}

/**
 * Nhận diện nhà mạng viễn thông Việt Nam qua đầu số điện thoại (UC-U02)
 */
function detectCarrierByPhone(phone) {
    if (!phone) return null;
    const cleaned = String(phone).replace(/\s+/g, '').replace(/^\+84/, '0');
    if (!/^0\d{9}$/.test(cleaned)) {
        return { valid: false, cleaned, carrier: null, message: 'Số điện thoại không đúng định dạng 10 chữ số' };
    }
    const prefix = cleaned.slice(0, 3);
    const viettelPrefixes = ['086', '096', '097', '098', '032', '033', '034', '035', '036', '037', '038', '039'];
    const vinaphonePrefixes = ['088', '091', '094', '081', '082', '083', '084', '085'];
    const mobifonePrefixes = ['089', '090', '093', '070', '079', '077', '076', '078'];

    let carrier = 'VIETTEL';
    let carrierName = 'Viettel Telecom';
    if (vinaphonePrefixes.includes(prefix)) {
        carrier = 'VINAPHONE';
        carrierName = 'VinaPhone (VNPT)';
    } else if (mobifonePrefixes.includes(prefix)) {
        carrier = 'MOBIFONE';
        carrierName = 'MobiFone';
    } else if (!viettelPrefixes.includes(prefix)) {
        carrier = 'VIETTEL';
        carrierName = 'Viettel / Mạng di động VN';
    }

    return {
        valid: true,
        phone_number: cleaned,
        prefix,
        carrier,
        carrier_name: carrierName,
        denominations: TOPUP_DENOMINATIONS
    };
}

/**
 * Hàm tạo mã giao dịch duy nhất: TXN + YYYYMMDD + 6 số ngẫu nhiên
 */
function generateTxnCode() {
    const dateStr = new Date().toISOString().slice(0, 10).replace(/-/g, '');
    const randomDigits = Math.floor(100000 + Math.random() * 900000);
    return `TXN${dateStr}${randomDigits}`;
}

/**
 * Tìm service_id trong bảng utility_services theo provider_code hoặc service_type
 */
async function resolveServiceId(pool, providerCode, serviceType) {
    if (providerCode) {
        const resByProvider = await pool.request()
            .input('provider_code', sql.NVarChar(50), String(providerCode).toUpperCase())
            .query(`SELECT TOP 1 service_id FROM dbo.utility_services WHERE provider_code = @provider_code`);
        if (resByProvider.recordset.length > 0) {
            return resByProvider.recordset[0].service_id;
        }
    }
    if (serviceType) {
        const resByType = await pool.request()
            .input('service_type', sql.NVarChar(20), String(serviceType).toUpperCase())
            .query(`SELECT TOP 1 service_id FROM dbo.utility_services WHERE service_type = @service_type`);
        if (resByType.recordset.length > 0) {
            return resByType.recordset[0].service_id;
        }
    }
    return DEFAULT_SERVICE_ID_BY_TYPE[String(serviceType || '').toUpperCase()] || 'srv-evn-hcm';
}

/**
 * Dọn dẹp các đơn giữ chỗ tạm thời (Seat/Room Hold) đã quá hạn 15 phút theo cột hold_expires_at trong ERD_new.html
 */
async function expireOutdatedHolds(pool) {
    try {
        await pool.request().query(`
            UPDATE dbo.utility_orders
            SET status = 'EXPIRED'
            WHERE status = 'PENDING'
              AND hold_expires_at IS NOT NULL
              AND hold_expires_at < SYSUTCDATETIME();
        `);
    } catch (_) {
        // Bỏ qua nếu bảng đang khởi tạo
    }
}

/**
 * Chuẩn hóa dòng dữ liệu utility_orders + utility_services thành đối tượng Bill
 */
function mapOrderRowToBill(row) {
    let meta = {};
    try {
        meta = row.metadata_payload ? JSON.parse(row.metadata_payload) : {};
    } catch (_) {}

    const billStatus = (row.status === 'PAID' || row.status === 'COMPLETED') ? 'PAID' : 'UNPAID';
    return {
        bill_id: row.order_id,
        order_id: row.order_id,
        bill_type: row.service_type || meta.bill_type || 'ELECTRICITY',
        provider_code: row.provider_code || meta.provider_code || 'EVN_HCM',
        provider_customer_id: row.provider_customer_id,
        customer_name: meta.customer_name || 'Nguyễn Văn A',
        customer_address: meta.customer_address || '123 Nguyễn Huệ, Quận 1, TP.HCM',
        billing_period: meta.billing_period || '11/2026',
        amount: Number(row.total_amount),
        status: billStatus,
        transaction_id: row.transaction_id || null,
        created_at: row.created_at
    };
}

/**
 * Xây dựng sơ đồ ghế ngồi (Seat Map) thời gian thực cho suất chiếu phim CGV (UC-U04)
 */
async function buildMovieSeatMap(pool, catalogItemId, showtime = '19:30') {
    await expireOutdatedHolds(pool);

    const baseBooked = new Set(DEFAULT_BOOKED_SEATS[catalogItemId] || ['D5', 'D6']);
    const heldSeats = new Set();

    // Kiểm tra các ghế đã PAID hoặc đang HELD (PENDING còn hạn) trong bảng utility_orders + utility_services
    const ordersRes = await pool.request()
        .input('service_type', sql.NVarChar(20), 'MOVIE')
        .query(`
            SELECT uo.status, uo.metadata_payload, uo.hold_expires_at
            FROM dbo.utility_orders uo
            JOIN dbo.utility_services us ON uo.service_id = us.service_id
            WHERE us.service_type = @service_type
              AND uo.status IN ('PENDING', 'PROCESSING', 'PAID', 'COMPLETED')
        `);

    for (const row of ordersRes.recordset) {
        try {
            const meta = JSON.parse(row.metadata_payload || '{}');
            const details = meta.booking_details || meta;
            const itemMatch = !meta.catalog_item_id || meta.catalog_item_id === catalogItemId;
            const timeMatch = !details.showtime || details.showtime === showtime;
            if (itemMatch && timeMatch && Array.isArray(details.seats)) {
                for (const seat of details.seats) {
                    const seatCode = String(seat).toUpperCase();
                    if (row.status === 'PAID' || row.status === 'COMPLETED') {
                        baseBooked.add(seatCode);
                    } else if (row.status === 'PENDING' || row.status === 'PROCESSING') {
                        heldSeats.add(seatCode);
                    }
                }
            }
        } catch (_) {}
    }

    const rows = ['A', 'B', 'C', 'D', 'E', 'F'];
    const seats = [];
    for (const r of rows) {
        const seatType = ['A', 'B'].includes(r) ? 'STANDARD' : 'VIP';
        const price = seatType === 'VIP' ? 140000 : 115000;
        for (let num = 1; num <= 8; num++) {
            const seatId = `${r}${num}`;
            let status = 'AVAILABLE';
            if (baseBooked.has(seatId)) status = 'BOOKED';
            else if (heldSeats.has(seatId)) status = 'HELD';
            seats.push({
                seat_id: seatId,
                row: r,
                number: num,
                seat_type: seatType,
                price,
                status
            });
        }
    }

    return {
        catalog_item_id: catalogItemId,
        showtime,
        total_seats: seats.length,
        available_count: seats.filter(s => s.status === 'AVAILABLE').length,
        held_count: seats.filter(s => s.status === 'HELD').length,
        booked_count: seats.filter(s => s.status === 'BOOKED').length,
        seats
    };
}

// ======================= 1. READ & DISCOVERY ENDPOINTS =======================

/**
 * GET /v1/utilities/dashboard
 * API tổng hợp cho màn hình chính / Tiện ích của App Bank:
 * Truy vấn trực tiếp từ Database các bảng: users, accounts, utility_services, utility_orders
 */
router.get('/utilities/dashboard', optionalAuthenticateJWT, async (req, res) => {
    try {
        const pool = await getPool();
        await expireOutdatedHolds(pool);

        const userId = req.user.user_id;

        const [userRes, accountsRes, servicesRes, ordersRes] = await Promise.all([
            pool.request()
                .input('user_id', sql.NVarChar(36), userId)
                .query(`SELECT user_id, username, full_name, email, phone_number, role, status FROM dbo.users WHERE user_id = @user_id`),
            pool.request()
                .input('user_id', sql.NVarChar(36), userId)
                .query(`SELECT account_id, account_number, account_type, balance, currency, status FROM dbo.accounts WHERE user_id = @user_id ORDER BY created_at ASC`),
            pool.request()
                .query(`
                    SELECT service_id, provider_code, service_name, service_type, aggregator_gateway, status, created_at
                    FROM dbo.utility_services
                    ORDER BY service_type, provider_code
                `),
            pool.request()
                .input('user_id', sql.NVarChar(36), userId)
                .query(`
                    SELECT uo.order_id, uo.user_id, uo.service_id, uo.transaction_id,
                           uo.provider_customer_id, uo.total_amount, uo.metadata_payload,
                           uo.status, uo.hold_expires_at, uo.created_at,
                           us.service_type, us.provider_code, us.service_name, us.aggregator_gateway
                    FROM dbo.utility_orders uo
                    JOIN dbo.utility_services us ON uo.service_id = us.service_id
                    WHERE uo.user_id = @user_id
                    ORDER BY uo.created_at DESC
                `)
        ]);

        const orders = ordersRes.recordset.map(r => {
            let meta = {};
            try {
                meta = r.metadata_payload ? JSON.parse(r.metadata_payload) : {};
            } catch (_) {}
            return {
                ...r,
                total_amount: Number(r.total_amount),
                metadata_payload: meta
            };
        });

        const bills = ordersRes.recordset
            .filter(r => ['ELECTRICITY', 'WATER', 'PHONE_TOPUP'].includes(r.service_type))
            .map(mapOrderRowToBill);

        const unpaidBills = bills.filter(b => b.status === 'UNPAID');

        return res.status(200).json({
            user: userRes.recordset[0] || null,
            accounts: accountsRes.recordset.map(a => ({ ...a, balance: Number(a.balance) })),
            services: servicesRes.recordset,
            bills,
            unpaid_bills: unpaidBills,
            orders,
            vouchers: Object.values(VOUCHERS),
            topup_denominations: TOPUP_DENOMINATIONS
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi truy vấn dữ liệu tổng hợp tiện ích từ database' });
    }
});

/**
 * GET /v1/utilities/services
 * Lấy danh sách toàn bộ Dịch vụ & Đối tác Tiện ích (utility_services theo ERD_new.html)
 */
router.get('/utilities/services', optionalAuthenticateJWT, async (req, res) => {
    const { service_type, status } = req.query;
    try {
        const pool = await getPool();
        const request = pool.request();
        let where = 'WHERE 1=1';
        if (service_type) {
            where += ' AND service_type = @service_type';
            request.input('service_type', sql.NVarChar(20), service_type.toUpperCase());
        }
        if (status) {
            where += ' AND status = @status';
            request.input('status', sql.NVarChar(20), status.toUpperCase());
        }
        const result = await request.query(`
            SELECT service_id, provider_code, service_name, service_type, aggregator_gateway, status, created_at
            FROM dbo.utility_services
            ${where}
            ORDER BY service_type, provider_code
        `);
        return res.status(200).json({
            items: result.recordset,
            total: result.recordset.length
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy danh mục dịch vụ tiện ích' });
    }
});

/**
 * GET /v1/utilities/services/:service_id
 * Lấy chi tiết một Dịch vụ Tiện ích từ bảng utility_services kèm danh sách đơn hàng của user
 */
router.get('/utilities/services/:service_id', optionalAuthenticateJWT, async (req, res) => {
    const { service_id } = req.params;
    try {
        const pool = await getPool();
        const srvRes = await pool.request()
            .input('key', sql.NVarChar(50), service_id)
            .query(`
                SELECT service_id, provider_code, service_name, service_type, aggregator_gateway, status, created_at
                FROM dbo.utility_services
                WHERE service_id = @key OR provider_code = @key
            `);

        if (srvRes.recordset.length === 0) {
            return res.status(404).json({ code: 'NOT_FOUND', message: 'Không tìm thấy dịch vụ tiện ích' });
        }

        const service = srvRes.recordset[0];
        const ordersRes = await pool.request()
            .input('service_id', sql.NVarChar(36), service.service_id)
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`
                SELECT order_id, user_id, service_id, transaction_id, provider_customer_id,
                       total_amount, metadata_payload, status, hold_expires_at, created_at
                FROM dbo.utility_orders
                WHERE service_id = @service_id AND user_id = @user_id
                ORDER BY created_at DESC
            `);

        const catalogItems = service.service_type === 'FLIGHT'
            ? await fetchFlightCatalogFromDuffel({
                  from: req.query.from,
                  to: req.query.to,
                  date: req.query.date
              })
            : (MOCK_CATALOG[service.service_type] || []);

        return res.status(200).json({
            service,
            orders: ordersRes.recordset.map(r => ({
                ...r,
                total_amount: Number(r.total_amount),
                metadata_payload: r.metadata_payload ? JSON.parse(r.metadata_payload) : {}
            })),
            catalog: catalogItems
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi truy vấn chi tiết dịch vụ tiện ích' });
    }
});

/**
 * POST /v1/utilities/services
 * Thêm mới hoặc cập nhật một Dịch vụ Tiện ích vào bảng utility_services trong Database
 */
router.post('/utilities/services', optionalAuthenticateJWT, async (req, res) => {
    const {
        provider_code,
        service_name,
        service_type = 'ELECTRICITY',
        aggregator_gateway = 'VNPAY',
        status = 'ACTIVE'
    } = req.body;

    if (!provider_code || !service_name) {
        return res.status(400).json({ code: 'BAD_REQUEST', message: 'Bắt buộc có provider_code và service_name' });
    }

    const validTypes = ['ELECTRICITY', 'WATER', 'PHONE_TOPUP', 'FLIGHT', 'MOVIE', 'HOTEL', 'ECOMMERCE'];
    const normalizedType = validTypes.includes(String(service_type).toUpperCase())
        ? String(service_type).toUpperCase()
        : 'ELECTRICITY';
    const validGateways = ['VNPAY', 'PAYOO', 'NAPAS', 'DIRECT', 'PAYPAL'];
    const normalizedGw = validGateways.includes(String(aggregator_gateway).toUpperCase())
        ? String(aggregator_gateway).toUpperCase()
        : 'VNPAY';
    const normalizedCode = String(provider_code).trim().toUpperCase().replace(/\s+/g, '_');

    try {
        const pool = await getPool();
        const existing = await pool.request()
            .input('provider_code', sql.NVarChar(50), normalizedCode)
            .query(`SELECT * FROM dbo.utility_services WHERE provider_code = @provider_code`);

        if (existing.recordset.length > 0) {
            return res.status(200).json(existing.recordset[0]);
        }

        const serviceId = `srv-${normalizedCode.toLowerCase().replace(/_/g, '-')}-${Date.now().toString().slice(-4)}`;
        const insertRes = await pool.request()
            .input('service_id', sql.NVarChar(36), serviceId)
            .input('provider_code', sql.NVarChar(50), normalizedCode)
            .input('service_name', sql.NVarChar(150), service_name)
            .input('service_type', sql.NVarChar(20), normalizedType)
            .input('aggregator_gateway', sql.NVarChar(20), normalizedGw)
            .input('status', sql.NVarChar(20), status)
            .query(`
                INSERT INTO dbo.utility_services (service_id, provider_code, service_name, service_type, aggregator_gateway, status)
                OUTPUT INSERTED.*
                VALUES (@service_id, @provider_code, @service_name, @service_type, @aggregator_gateway, @status)
            `);

        return res.status(201).json(insertRes.recordset[0]);
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi thêm dịch vụ tiện ích vào database' });
    }
});

/**
 * GET /v1/utilities/saved-context
 * Trả về dữ liệu Context đã lưu của người dùng từ bảng users & utility_orders
 */
router.get('/utilities/saved-context', authenticateJWT, async (req, res) => {
    try {
        const pool = await getPool();
        const userRes = await pool.request()
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`SELECT full_name, phone_number, email FROM dbo.users WHERE user_id = @user_id`);
        const user = userRes.recordset[0] || {};

        const ordersRes = await pool.request()
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`
                SELECT uo.order_id, uo.provider_customer_id, uo.total_amount, uo.metadata_payload, uo.status, uo.transaction_id, uo.created_at,
                       us.service_type, us.provider_code
                FROM dbo.utility_orders uo
                JOIN dbo.utility_services us ON uo.service_id = us.service_id
                WHERE uo.user_id = @user_id
                  AND us.service_type IN ('ELECTRICITY', 'WATER', 'PHONE_TOPUP')
            `);

        const savedBills = ordersRes.recordset.map(mapOrderRowToBill);
        const carrierInfo = detectCarrierByPhone(user.phone_number || '0987654321');

        return res.status(200).json({
            default_phone_topup: {
                phone_number: user.phone_number || '0987654321',
                carrier: carrierInfo ? carrierInfo.carrier : 'VIETTEL',
                denominations: TOPUP_DENOMINATIONS
            },
            saved_customer_codes: savedBills,
            saved_passengers: [
                {
                    full_name: user.full_name || 'NGUYỄN VĂN A',
                    id_card_number: '079095001234',
                    dob: '1995-03-02',
                    phone_number: user.phone_number || '0987654321'
                }
            ],
            available_vouchers: Object.values(VOUCHERS)
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy thông tin context đã lưu' });
    }
});

/**
 * GET /v1/utilities/topup/detect-carrier
 * Tự động nhận diện nhà mạng (Viettel, VinaPhone, MobiFone) từ số điện thoại & trả danh sách mệnh giá (UC-U02)
 */
router.get('/utilities/topup/detect-carrier', authenticateJWT, (req, res) => {
    const { phone } = req.query;
    const info = detectCarrierByPhone(phone);
    if (!info || !info.valid) {
        return res.status(400).json({
            code: 'INVALID_PHONE_NUMBER',
            message: info ? info.message : 'Vui lòng truyền số điện thoại hợp lệ (10 chữ số)'
        });
    }
    return res.status(200).json(info);
});

/**
 * GET /v1/utilities/bills
 * Danh sách hóa đơn của user hiện tại (truy vấn từ utility_orders JOIN utility_services theo ERD_new.html)
 */
router.get('/utilities/bills', authenticateJWT, async (req, res) => {
    const { status, bill_type } = req.query;
    const page = Math.max(1, parseInt(req.query.page, 10) || 1);
    const pageSize = Math.min(100, Math.max(1, parseInt(req.query.page_size, 10) || 20));
    const offset = (page - 1) * pageSize;

    try {
        const pool = await getPool();
        const request = pool.request()
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .input('offset', sql.Int, offset)
            .input('pageSize', sql.Int, pageSize);

        let filterClause = `WHERE uo.user_id = @user_id AND us.service_type IN ('ELECTRICITY', 'WATER', 'PHONE_TOPUP')`;
        if (status) {
            const upperStatus = status.toUpperCase();
            if (upperStatus === 'UNPAID' || upperStatus === 'PENDING') {
                filterClause += ` AND uo.status IN ('PENDING', 'UNPAID')`;
            } else if (upperStatus === 'PAID') {
                filterClause += ` AND uo.status IN ('PAID', 'COMPLETED')`;
            } else {
                filterClause += ` AND uo.status = @status`;
                request.input('status', sql.NVarChar(20), upperStatus);
            }
        }
        if (bill_type) {
            filterClause += ` AND us.service_type = @bill_type`;
            request.input('bill_type', sql.NVarChar(20), bill_type.toUpperCase());
        }

        const query = `
            SELECT uo.order_id, uo.provider_customer_id, uo.total_amount, uo.metadata_payload, uo.status, uo.transaction_id, uo.created_at,
                   us.service_type, us.provider_code
            FROM dbo.utility_orders uo
            JOIN dbo.utility_services us ON uo.service_id = us.service_id
            ${filterClause}
            ORDER BY uo.created_at DESC
            OFFSET @offset ROWS FETCH NEXT @pageSize ROWS ONLY;

            SELECT COUNT(*) AS total
            FROM dbo.utility_orders uo
            JOIN dbo.utility_services us ON uo.service_id = us.service_id
            ${filterClause};
        `;

        const result = await request.query(query);
        const items = result.recordsets[0].map(mapOrderRowToBill);
        return res.status(200).json({
            items,
            page,
            page_size: pageSize,
            total: result.recordsets[1][0].total
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy danh sách hóa đơn' });
    }
});

/**
 * POST /v1/utilities/bills/lookup
 * Tra cứu hóa đơn từ nhà cung cấp (EVNHCMC, SAWACO, Viettel, VinaPhone...) và lưu vào utility_orders (UC-U01 & UC-U02)
 */
router.post('/utilities/bills/lookup', authenticateJWT, async (req, res) => {
    const { bill_type, provider_customer_id, provider_code, topup_amount } = req.body;
    const validTypes = ['ELECTRICITY', 'WATER', 'PHONE_TOPUP'];

    if (!validTypes.includes(bill_type) || !provider_customer_id) {
        return res.status(400).json({ code: 'BAD_REQUEST', message: 'Thiếu bill_type hoặc provider_customer_id hợp lệ' });
    }

    let resolvedProviderCode = provider_code;
    if (bill_type === 'PHONE_TOPUP') {
        const carrierCheck = detectCarrierByPhone(provider_customer_id);
        if (!carrierCheck.valid) {
            return res.status(400).json({ code: 'INVALID_PHONE_FORMAT', message: carrierCheck.message });
        }
        if (!topup_amount || Number(topup_amount) <= 0) {
            return res.status(400).json({ code: 'BAD_REQUEST', message: 'Bắt buộc nhập topup_amount hợp lệ khi nạp điện thoại' });
        }
        resolvedProviderCode = resolvedProviderCode || carrierCheck.carrier;
    } else {
        resolvedProviderCode = resolvedProviderCode || (bill_type === 'ELECTRICITY' ? 'EVN_HCM' : 'SAWACO');
    }

    try {
        const pool = await getPool();

        // Kiểm tra trạng thái hoạt động của Nhà cung cấp trong bảng utility_services
        const srvRes = await pool.request()
            .input('provider_code', sql.NVarChar(50), resolvedProviderCode)
            .query(`SELECT TOP 1 * FROM dbo.utility_services WHERE provider_code = @provider_code`);
        if (srvRes.recordset.length > 0 && srvRes.recordset[0].status === 'MAINTENANCE') {
            return res.status(503).json({
                code: 'PROVIDER_MAINTENANCE',
                message: `Hệ thống đối tác ${srvRes.recordset[0].service_name} đang bảo trì, vui lòng thử lại sau.`
            });
        }

        // Ngoại lệ 3a (UC-U01): Nếu mã KH kết thúc bằng "000" thì giả lập không tồn tại hoặc không có nợ cước
        if (String(provider_customer_id).endsWith('000')) {
            return res.status(404).json({
                code: 'BILL_NOT_FOUND',
                message: 'Mã khách hàng không hợp lệ hoặc chưa phát sinh cước kỳ này'
            });
        }

        const serviceId = srvRes.recordset[0]
            ? srvRes.recordset[0].service_id
            : await resolveServiceId(pool, resolvedProviderCode, bill_type);

        const defaultElectricityAmount = provider_customer_id === 'PE123456' ? 850000 : 650000;
        const amount = bill_type === 'PHONE_TOPUP'
            ? Number(topup_amount)
            : (bill_type === 'ELECTRICITY' ? defaultElectricityAmount : 185000);

        const customerName = 'Nguyễn Văn A';
        const customerAddress = bill_type === 'PHONE_TOPUP'
            ? `Thuê bao ${resolvedProviderCode}: ${provider_customer_id}`
            : '123 Nguyễn Huệ, Phường Bến Nghé, Quận 1, TP.HCM';
        const billingPeriod = '11/2026';

        const metadataJson = JSON.stringify({
            bill_type,
            provider_code: resolvedProviderCode,
            customer_name: customerName,
            customer_address: customerAddress,
            billing_period: billingPeriod
        });

        // Kiểm tra xem đã có đơn hóa đơn PENDING/UNPAID trùng (user_id, service_id, provider_customer_id) chưa
        const existing = await pool.request()
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .input('service_type', sql.NVarChar(20), bill_type)
            .input('provider_customer_id', sql.NVarChar(100), provider_customer_id)
            .query(`
                SELECT TOP 1 uo.*, us.service_type, us.provider_code
                FROM dbo.utility_orders uo
                JOIN dbo.utility_services us ON uo.service_id = us.service_id
                WHERE uo.user_id = @user_id
                  AND us.service_type = @service_type
                  AND uo.provider_customer_id = @provider_customer_id
                  AND uo.status IN ('PENDING', 'UNPAID')
            `);

        let orderRow;
        if (existing.recordset.length > 0) {
            const orderId = existing.recordset[0].order_id;
            const updateRes = await pool.request()
                .input('order_id', sql.NVarChar(36), orderId)
                .input('total_amount', sql.Decimal(18, 2), amount)
                .input('service_id', sql.NVarChar(36), serviceId)
                .input('metadata_payload', sql.NVarChar(sql.MAX), metadataJson)
                .query(`
                    UPDATE dbo.utility_orders
                    SET total_amount = @total_amount, service_id = @service_id, metadata_payload = @metadata_payload
                    OUTPUT INSERTED.*
                    WHERE order_id = @order_id
                `);
            orderRow = {
                ...updateRes.recordset[0],
                service_type: bill_type,
                provider_code: resolvedProviderCode
            };
        } else {
            const insertRes = await pool.request()
                .input('user_id', sql.NVarChar(36), req.user.user_id)
                .input('service_id', sql.NVarChar(36), serviceId)
                .input('provider_customer_id', sql.NVarChar(100), provider_customer_id)
                .input('total_amount', sql.Decimal(18, 2), amount)
                .input('metadata_payload', sql.NVarChar(sql.MAX), metadataJson)
                .query(`
                    INSERT INTO dbo.utility_orders (user_id, service_id, provider_customer_id, total_amount, metadata_payload, status)
                    OUTPUT INSERTED.*
                    VALUES (@user_id, @service_id, @provider_customer_id, @total_amount, @metadata_payload, 'PENDING')
                `);
            orderRow = {
                ...insertRes.recordset[0],
                service_type: bill_type,
                provider_code: resolvedProviderCode
            };
        }

        return res.status(200).json(mapOrderRowToBill(orderRow));
    } catch (err) {
        console.error(err);
        return res.status(502).json({ code: 'PROVIDER_ERROR', message: 'Nhà cung cấp lỗi hoặc timeout' });
    }
});

/**
 * GET /v1/utilities/bills/:bill_id
 * Chi tiết một hóa đơn
 */
router.get('/utilities/bills/:bill_id', authenticateJWT, async (req, res) => {
    try {
        const pool = await getPool();
        const result = await pool.request()
            .input('order_id', sql.NVarChar(36), req.params.bill_id)
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`
                SELECT uo.*, us.service_type, us.provider_code
                FROM dbo.utility_orders uo
                JOIN dbo.utility_services us ON uo.service_id = us.service_id
                WHERE uo.order_id = @order_id AND uo.user_id = @user_id
            `);

        if (result.recordset.length === 0) {
            return res.status(404).json({ code: 'NOT_FOUND', message: 'Không tìm thấy hóa đơn' });
        }
        return res.status(200).json(mapOrderRowToBill(result.recordset[0]));
    } catch (err) {
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi máy chủ' });
    }
});

/**
 * GET /v1/utilities/catalog/:service_type
 * Tìm kiếm dịch vụ từ đối tác: FLIGHT (gọi Duffel API), HOTEL, MOVIE, ECOMMERCE (UC-U03, UC-U04, UC-U05, UC-U06)
 */
router.get('/utilities/catalog/:service_type', authenticateJWT, async (req, res) => {
    const serviceType = req.params.service_type.toUpperCase();
    if (!MOCK_CATALOG[serviceType]) {
        return res.status(400).json({ code: 'INVALID_SERVICE_TYPE', message: 'Loại dịch vụ không hợp lệ (FLIGHT, HOTEL, MOVIE, ECOMMERCE)' });
    }

    const { q, from, to, date, city, provider } = req.query;

    try {
        let items = serviceType === 'FLIGHT'
            ? await fetchFlightCatalogFromDuffel({ from, to, date })
            : [...MOCK_CATALOG[serviceType]];

        if (q) {
            const kw = q.toLowerCase();
            items = items.filter(i =>
                i.title.toLowerCase().includes(kw) ||
                (i.provider_code && i.provider_code.toLowerCase().includes(kw)) ||
                JSON.stringify(i.details || {}).toLowerCase().includes(kw)
            );
        }
        if (from && serviceType === 'FLIGHT') {
            items = items.filter(i => i.details && i.details.from && i.details.from.toUpperCase() === from.toUpperCase());
        }
        if (to && serviceType === 'FLIGHT') {
            items = items.filter(i => i.details && i.details.to && i.details.to.toUpperCase() === to.toUpperCase());
        }
        if (date && serviceType === 'FLIGHT') {
            const dateMatches = items.filter(i => i.details && i.details.date === date);
            if (dateMatches.length > 0) {
                items = dateMatches;
            }
        }
        if (city && ['HOTEL', 'MOVIE'].includes(serviceType)) {
            const cityFiltered = items.filter(i => i.details && i.details.city && i.details.city.toUpperCase() === city.toUpperCase());
            if (cityFiltered.length > 0) items = cityFiltered;
        }
        if (provider) {
            items = items.filter(i => i.provider_code && i.provider_code.toUpperCase() === provider.toUpperCase());
        }

        return res.status(200).json({ items, total: items.length });
    } catch (err) {
        console.error(err);
        return res.status(502).json({
            code: 'DUFFEL_API_ERROR',
            message: 'Lỗi truy vấn danh sách chuyến bay từ Duffel API: ' + err.message
        });
    }
});

/**
 * GET /v1/utilities/movies/:catalog_item_id/seats
 * Truy vấn sơ đồ ghế ngồi (Real-time Seat Map) của suất chiếu phim CGV (UC-U04 Bước 3)
 */
router.get('/utilities/movies/:catalog_item_id/seats', authenticateJWT, async (req, res) => {
    const { catalog_item_id } = req.params;
    const showtime = req.query.showtime || '19:30';
    try {
        const pool = await getPool();
        const seatMap = await buildMovieSeatMap(pool, catalog_item_id, showtime);
        return res.status(200).json(seatMap);
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy sơ đồ ghế rạp phim' });
    }
});

/**
 * POST /v1/utilities/orders/hold
 * Giữ chỗ tạm thời (Seat/Room Hold 15 phút) trong bảng utility_orders theo ERD_new.html
 */
router.post('/utilities/orders/hold', authenticateJWT, async (req, res) => {
    const { service_type, catalog_item_id, quantity = 1, booking_details = {}, voucher_code } = req.body;
    const normalizedType = String(service_type || '').toUpperCase();

    if (!['FLIGHT', 'MOVIE', 'HOTEL', 'ECOMMERCE'].includes(normalizedType)) {
        return res.status(400).json({ code: 'INVALID_SERVICE_TYPE', message: 'service_type phải thuộc FLIGHT, MOVIE, HOTEL, ECOMMERCE' });
    }

    try {
        const pool = await getPool();
        await expireOutdatedHolds(pool);

        let catalogList = MOCK_CATALOG[normalizedType] || [];
        if (
            normalizedType === 'FLIGHT' &&
            (catalogList.length === 0 || (catalog_item_id && !catalogList.some(c => c.catalog_item_id === catalog_item_id)))
        ) {
            await fetchFlightCatalogFromDuffel({
                from: booking_details.from,
                to: booking_details.to,
                date: booking_details.date
            });
            catalogList = MOCK_CATALOG.FLIGHT;
        }
        const item = catalogList.find(c => c.catalog_item_id === catalog_item_id) || catalogList[0];
        if (!item) {
            return res.status(404).json({ code: 'ITEM_NOT_FOUND', message: 'Không tìm thấy dịch vụ/sản phẩm trong danh mục' });
        }

        let baseAmount = Number(item.price) * Number(quantity || 1);
        let shippingFee = 0;
        let discountAmount = 0;

        if (normalizedType === 'FLIGHT') {
            const passengers = Array.isArray(booking_details.passengers) ? booking_details.passengers.length : Number(quantity || 1);
            if (item.available_seats < passengers) {
                return res.status(409).json({ code: 'OUT_OF_SEATS', message: 'Hạng vé trên chuyến bay này vừa hết chỗ trống, vui lòng chọn chuyến khác' });
            }
            const seatClass = (booking_details.seat_class || 'ECONOMY').toUpperCase();
            const unitPrice = (item.details && item.details.seat_classes && item.details.seat_classes[seatClass]) || item.price;
            const baggageFee = Number(booking_details.baggage_fee || 0);
            baseAmount = unitPrice * passengers + baggageFee;
        } else if (normalizedType === 'MOVIE') {
            const selectedSeats = Array.isArray(booking_details.seats) ? booking_details.seats.map(s => String(s).toUpperCase()) : [];
            if (selectedSeats.length === 0) {
                return res.status(400).json({ code: 'MISSING_SEATS', message: 'Vui lòng chọn ít nhất 1 vị trí ghế ngồi' });
            }
            const seatMap = await buildMovieSeatMap(pool, item.catalog_item_id, booking_details.showtime || '19:30');
            const unavailable = selectedSeats.filter(code => {
                const found = seatMap.seats.find(s => s.seat_id === code);
                return !found || found.status !== 'AVAILABLE';
            });
            if (unavailable.length > 0) {
                return res.status(409).json({
                    code: 'SEAT_CONFLICT',
                    message: `Ghế ${unavailable.join(', ')} vừa có người đặt hoặc đang được giữ chỗ. Vui lòng chọn ghế khác!`,
                    unavailable_seats: unavailable,
                    seat_map: seatMap
                });
            }
            const seatTotal = selectedSeats.reduce((sum, code) => {
                const found = seatMap.seats.find(s => s.seat_id === code);
                return sum + (found ? found.price : item.price);
            }, 0);
            const comboTotal = Number(booking_details.combo_amount || 0);
            baseAmount = seatTotal + comboTotal;
        } else if (normalizedType === 'HOTEL') {
            const roomsCount = Number(booking_details.rooms || quantity || 1);
            const nights = Math.max(1, Number(booking_details.nights || 1));
            if (item.available_rooms < roomsCount) {
                return res.status(409).json({
                    code: 'OUT_OF_ROOMS',
                    message: 'Khách sạn không còn đủ phòng trống trong khoảng ngày đã chọn. Vui lòng đổi ngày hoặc hạng phòng.'
                });
            }
            baseAmount = Number(item.price) * roomsCount * nights;
        } else if (normalizedType === 'ECOMMERCE') {
            const qty = Math.max(1, Number(quantity || booking_details.quantity || 1));
            if (item.stock < qty) {
                return res.status(409).json({
                    code: 'OUT_OF_STOCK',
                    message: `Sản phẩm "${item.title}" không đủ tồn kho (Còn lại: ${item.stock})`,
                    available_stock: item.stock
                });
            }
            baseAmount = Number(item.price) * qty;
            shippingFee = Number(item.shipping_fee || 0);
        }

        const appliedVoucherCode = voucher_code || booking_details.voucher_code;
        if (appliedVoucherCode && VOUCHERS[String(appliedVoucherCode).toUpperCase()]) {
            const v = VOUCHERS[String(appliedVoucherCode).toUpperCase()];
            if (baseAmount >= v.min_order) {
                if (v.discount_type === 'FIXED') discountAmount = v.value;
                else if (v.discount_type === 'PERCENT') discountAmount = Math.min(v.max_discount, Math.round(baseAmount * v.value / 100));
                else if (v.discount_type === 'SHIPPING') discountAmount = Math.min(shippingFee, v.value);
            }
        }

        const totalAmount = Math.max(0, baseAmount + shippingFee - discountAmount);
        const holdExpiresAt = new Date(Date.now() + HOLD_TIMEOUT_MS);
        const serviceId = await resolveServiceId(pool, item.provider_code, normalizedType);

        const metadataPayload = {
            service_type: normalizedType,
            catalog_item_id: item.catalog_item_id,
            title: item.title,
            provider_code: item.provider_code,
            quantity: Number(quantity || 1),
            base_amount: baseAmount,
            shipping_fee: shippingFee,
            discount_amount: discountAmount,
            voucher_code: appliedVoucherCode || null,
            booking_details
        };

        const insertRes = await pool.request()
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .input('service_id', sql.NVarChar(36), serviceId)
            .input('provider_customer_id', sql.NVarChar(100), item.catalog_item_id)
            .input('total_amount', sql.Decimal(18, 2), totalAmount)
            .input('metadata_payload', sql.NVarChar(sql.MAX), JSON.stringify(metadataPayload))
            .input('hold_expires_at', sql.DateTime2(0), holdExpiresAt)
            .query(`
                INSERT INTO dbo.utility_orders (
                    user_id, service_id, provider_customer_id,
                    total_amount, metadata_payload, status, hold_expires_at
                )
                OUTPUT INSERTED.*
                VALUES (
                    @user_id, @service_id, @provider_customer_id,
                    @total_amount, @metadata_payload, 'PENDING', @hold_expires_at
                )
            `);

        const orderRow = insertRes.recordset[0];
        return res.status(201).json({
            order_id: orderRow.order_id,
            service_id: orderRow.service_id,
            service_type: normalizedType,
            catalog_item_id: item.catalog_item_id,
            title: item.title,
            pricing: {
                base_amount: baseAmount,
                shipping_fee: shippingFee,
                discount_amount: discountAmount,
                total_amount: totalAmount
            },
            status: orderRow.status,
            hold_expires_at: orderRow.hold_expires_at,
            hold_expired_at: orderRow.hold_expires_at,
            metadata: metadataPayload
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi giữ chỗ / tạo đơn hàng tạm thời' });
    }
});

/**
 * GET /v1/utilities/orders
 * Danh sách đơn hàng tiện ích đa hình (utility_orders JOIN utility_services theo ERD_new.html)
 */
router.get('/utilities/orders', authenticateJWT, async (req, res) => {
    const { service_type, status } = req.query;
    try {
        const pool = await getPool();
        await expireOutdatedHolds(pool);

        const request = pool.request().input('user_id', sql.NVarChar(36), req.user.user_id);
        let where = 'WHERE uo.user_id = @user_id';
        if (service_type) {
            where += ' AND us.service_type = @service_type';
            request.input('service_type', sql.NVarChar(20), service_type.toUpperCase());
        }
        if (status) {
            where += ' AND uo.status = @status';
            request.input('status', sql.NVarChar(20), status.toUpperCase());
        }

        const result = await request.query(`
            SELECT uo.*, us.service_type, us.provider_code, us.service_name, us.aggregator_gateway
            FROM dbo.utility_orders uo
            JOIN dbo.utility_services us ON uo.service_id = us.service_id
            ${where}
            ORDER BY uo.created_at DESC
        `);

        const items = result.recordset.map(r => ({
            ...r,
            metadata_payload: r.metadata_payload ? JSON.parse(r.metadata_payload) : {}
        }));

        return res.status(200).json({ items, total: items.length });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy danh sách đơn hàng tiện ích' });
    }
});

/**
 * GET /v1/utilities/bookings
 * Danh sách đặt dịch vụ (FLIGHT, HOTEL, MOVIE, ECOMMERCE) truy vấn từ utility_orders + utility_services
 */
router.get('/utilities/bookings', authenticateJWT, async (req, res) => {
    const { service_type } = req.query;
    const page = Math.max(1, parseInt(req.query.page, 10) || 1);
    const pageSize = Math.min(100, Math.max(1, parseInt(req.query.page_size, 10) || 20));
    const offset = (page - 1) * pageSize;

    try {
        const pool = await getPool();
        const request = pool.request()
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .input('offset', sql.Int, offset)
            .input('pageSize', sql.Int, pageSize);

        let whereClause = `WHERE uo.user_id = @user_id AND us.service_type IN ('FLIGHT', 'HOTEL', 'MOVIE', 'ECOMMERCE') AND uo.status IN ('PAID', 'COMPLETED')`;
        if (service_type) {
            whereClause += ' AND us.service_type = @service_type';
            request.input('service_type', sql.NVarChar(20), service_type.toUpperCase());
        }

        const result = await request.query(`
            SELECT uo.order_id, uo.transaction_id, uo.metadata_payload, uo.total_amount, uo.created_at,
                   us.service_type, us.provider_code
            FROM dbo.utility_orders uo
            JOIN dbo.utility_services us ON uo.service_id = us.service_id
            ${whereClause}
            ORDER BY uo.created_at DESC
            OFFSET @offset ROWS FETCH NEXT @pageSize ROWS ONLY;

            SELECT COUNT(*) AS total
            FROM dbo.utility_orders uo
            JOIN dbo.utility_services us ON uo.service_id = us.service_id
            ${whereClause};
        `);

        const items = result.recordsets[0].map(row => {
            const meta = row.metadata_payload ? JSON.parse(row.metadata_payload) : {};
            return {
                booking_id: row.order_id,
                order_id: row.order_id,
                transaction_id: row.transaction_id,
                service_type: row.service_type,
                booking_details: meta.fulfillment || meta.booking_details || meta,
                total_amount: Number(row.total_amount),
                created_at: row.created_at
            };
        });

        return res.status(200).json({ items, total: result.recordsets[1][0].total });
    } catch (err) {
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy danh sách booking' });
    }
});

/**
 * GET /v1/utilities/bookings/:booking_id
 * Chi tiết một đơn đặt dịch vụ
 */
router.get('/utilities/bookings/:booking_id', authenticateJWT, async (req, res) => {
    try {
        const pool = await getPool();
        const result = await pool.request()
            .input('order_id', sql.NVarChar(36), req.params.booking_id)
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`
                SELECT uo.*, us.service_type, us.provider_code
                FROM dbo.utility_orders uo
                JOIN dbo.utility_services us ON uo.service_id = us.service_id
                WHERE uo.order_id = @order_id AND uo.user_id = @user_id
            `);

        if (result.recordset.length === 0) {
            return res.status(404).json({ code: 'NOT_FOUND', message: 'Không tìm thấy đơn đặt dịch vụ' });
        }
        const row = result.recordset[0];
        const meta = row.metadata_payload ? JSON.parse(row.metadata_payload) : {};
        return res.status(200).json({
            booking_id: row.order_id,
            order_id: row.order_id,
            transaction_id: row.transaction_id,
            service_type: row.service_type,
            booking_details: meta.fulfillment || meta.booking_details || meta,
            total_amount: Number(row.total_amount),
            created_at: row.created_at
        });
    } catch (err) {
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi máy chủ' });
    }
});

// ======================= 2. WRITE (PAYMENT) ENDPOINTS =======================

/**
 * POST /v1/utilities/payments
 * Bước 1: Khởi tạo thanh toán trong bảng transactions, Giữ chỗ 15m trong utility_orders & Sinh OTP trong otp_challenges (ERD_new.html)
 */
router.post('/utilities/payments', authenticateJWT, async (req, res) => {
    const idempotencyKey = req.headers['idempotency-key'];
    if (!idempotencyKey) {
        return res.status(400).json({ code: 'MISSING_IDEMPOTENCY_KEY', message: 'Bắt buộc có header Idempotency-Key' });
    }

    const cacheKey = `init_${req.user.user_id}_${idempotencyKey}`;
    if (idempotencyCache.has(cacheKey)) {
        return res.status(201).json(idempotencyCache.get(cacheKey));
    }

    const { source_account_id, txn_type, transaction_type, payment_gateway = 'NONE', bill_id, booking, order_id, channel = 'SMS' } = req.body;
    const requestedType = txn_type || transaction_type;
    if (!source_account_id || !['BILL', 'UTILITY', 'BILL_PAYMENT', 'TOPUP', 'TICKET_BOOKING', 'ECOMMERCE'].includes(requestedType)) {
        return res.status(400).json({ code: 'BAD_REQUEST', message: 'source_account_id hoặc txn_type không hợp lệ' });
    }

    const isBillFlow = ['BILL', 'BILL_PAYMENT', 'TOPUP'].includes(requestedType);

    try {
        const pool = await getPool();
        await expireOutdatedHolds(pool);

        // 1. Kiểm tra tài khoản nguồn thuộc user, loại CHECKING, đang ACTIVE
        const accRes = await pool.request()
            .input('account_id', sql.NVarChar(36), source_account_id)
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`SELECT * FROM dbo.accounts WHERE account_id = @account_id AND user_id = @user_id`);

        if (accRes.recordset.length === 0) {
            return res.status(400).json({ code: 'INVALID_ACCOUNT', message: 'Tài khoản nguồn không tồn tại hoặc không thuộc quyền sở hữu' });
        }
        const account = accRes.recordset[0];
        if (account.status !== 'ACTIVE' || account.account_type !== 'CHECKING') {
            return res.status(400).json({ code: 'ACCOUNT_NOT_ELIGIBLE', message: 'Tài khoản phải là CHECKING và đang ACTIVE' });
        }

        let amount = 0;
        let receiverName = '';
        let description = '';
        let utilityOrderId = order_id || bill_id || null;
        let resolvedTxnType = isBillFlow ? 'BILL_PAYMENT' : 'TICKET_BOOKING';

        // 2. Xác thực dữ liệu BILL hoặc UTILITY booking trong bảng utility_orders
        if (isBillFlow) {
            if (!bill_id && !order_id) {
                return res.status(400).json({ code: 'MISSING_BILL_ID', message: 'Bắt buộc có bill_id khi thanh toán hóa đơn' });
            }
            const targetOrderId = bill_id || order_id;
            const billRes = await pool.request()
                .input('order_id', sql.NVarChar(36), targetOrderId)
                .input('user_id', sql.NVarChar(36), req.user.user_id)
                .query(`
                    SELECT uo.*, us.service_type, us.provider_code, us.service_name
                    FROM dbo.utility_orders uo
                    JOIN dbo.utility_services us ON uo.service_id = us.service_id
                    WHERE uo.order_id = @order_id AND uo.user_id = @user_id
                `);

            if (billRes.recordset.length === 0) {
                return res.status(404).json({ code: 'BILL_NOT_FOUND', message: 'Không tìm thấy hóa đơn' });
            }
            const billOrder = billRes.recordset[0];
            if (billOrder.status === 'PAID' || billOrder.status === 'COMPLETED') {
                return res.status(409).json({ code: 'BILL_ALREADY_PAID', message: 'Hóa đơn này đã được thanh toán' });
            }
            if (billOrder.status === 'PROCESSING') {
                return res.status(409).json({ code: 'BILL_PROCESSING', message: 'Hóa đơn này đang được xử lý thanh toán' });
            }
            amount = Number(billOrder.total_amount);
            receiverName = `BILL:${billOrder.service_type}:${billOrder.provider_customer_id}`;
            description = `Thanh toán ${billOrder.service_name} (${billOrder.provider_customer_id})`;
            resolvedTxnType = billOrder.service_type === 'PHONE_TOPUP' ? 'TOPUP' : 'BILL_PAYMENT';
            utilityOrderId = billOrder.order_id;
        } else {
            if (!booking || !booking.service_type || !booking.total_amount || booking.total_amount <= 0) {
                return res.status(422).json({ code: 'INVALID_BOOKING', message: 'Dữ liệu đặt dịch vụ không hợp lệ' });
            }

            const sType = String(booking.service_type).toUpperCase();
            const bDetails = booking.booking_details || {};

            // Kiểm tra xung đột ghế (Double-booking conflict) nếu đặt vé xem phim (UC-U04 5a)
            if (sType === 'MOVIE' && Array.isArray(bDetails.seats) && bDetails.seats.length > 0) {
                const seatMap = await buildMovieSeatMap(pool, booking.catalog_item_id || 'M_VENOM_3', bDetails.showtime || '19:30');
                const conflicts = bDetails.seats
                    .map(s => String(s).toUpperCase())
                    .filter(code => {
                        const found = seatMap.seats.find(st => st.seat_id === code);
                        return !found || found.status === 'BOOKED';
                    });
                if (conflicts.length > 0) {
                    return res.status(409).json({
                        code: 'SEAT_ALREADY_BOOKED',
                        message: `Ghế ${conflicts.join(', ')} đã có người đặt trước. Vui lòng chọn ghế khác!`,
                        seat_map: seatMap
                    });
                }
            }

            // Kiểm tra tồn kho nếu là ECOMMERCE (UC-U06 3a)
            if (sType === 'ECOMMERCE' && booking.catalog_item_id) {
                const ecomItem = MOCK_CATALOG.ECOMMERCE.find(i => i.catalog_item_id === booking.catalog_item_id);
                const reqQty = Number(bDetails.quantity || 1);
                if (ecomItem && ecomItem.stock < reqQty) {
                    return res.status(409).json({
                        code: 'OUT_OF_STOCK',
                        message: `Sản phẩm ${ecomItem.title} không đủ tồn kho (Còn lại: ${ecomItem.stock})`
                    });
                }
            }

            amount = Number(booking.total_amount);
            receiverName = `BOOKING:${sType}`;
            description = `Thanh toán tiện ích ${sType} (${booking.catalog_item_id || sType})`;
            resolvedTxnType = sType === 'ECOMMERCE' ? 'ECOMMERCE' : 'TICKET_BOOKING';

            const holdExpiresAt = ['FLIGHT', 'MOVIE', 'HOTEL'].includes(sType)
                ? new Date(Date.now() + HOLD_TIMEOUT_MS)
                : null;
            const serviceId = await resolveServiceId(pool, booking.provider_code, sType);

            if (!utilityOrderId) {
                const orderInsert = await pool.request()
                    .input('user_id', sql.NVarChar(36), req.user.user_id)
                    .input('service_id', sql.NVarChar(36), serviceId)
                    .input('provider_customer_id', sql.NVarChar(100), booking.catalog_item_id || sType)
                    .input('total_amount', sql.Decimal(18, 2), amount)
                    .input('metadata_payload', sql.NVarChar(sql.MAX), JSON.stringify({
                        service_type: sType,
                        catalog_item_id: booking.catalog_item_id,
                        booking_details: bDetails
                    }))
                    .input('hold_expires_at', sql.DateTime2(0), holdExpiresAt)
                    .query(`
                        INSERT INTO dbo.utility_orders (user_id, service_id, provider_customer_id, total_amount, metadata_payload, status, hold_expires_at)
                        OUTPUT INSERTED.order_id
                        VALUES (@user_id, @service_id, @provider_customer_id, @total_amount, @metadata_payload, 'PENDING', @hold_expires_at)
                    `);
                utilityOrderId = orderInsert.recordset[0].order_id;
            }
        }

        // Kiểm tra số dư tài khoản nguồn (UC-U01 6a)
        if (Number(account.balance) < amount) {
            return res.status(402).json({ code: 'INSUFFICIENT_BALANCE', message: 'Số dư tài khoản không đủ. Vui lòng nạp thêm tiền để thực hiện giao dịch.' });
        }

        // Kiểm tra hạn mức giao dịch trong ngày
        if (amount > DAILY_TXN_LIMIT) {
            return res.status(400).json({ code: 'LIMIT_EXCEEDED', message: `Số tiền vượt quá hạn mức giao dịch trong ngày (${DAILY_TXN_LIMIT.toLocaleString('vi-VN')} VND)` });
        }

        // 3. Tạo giao dịch PENDING trong bảng transactions (chuẩn 17 cột của ERD_new.html)
        const txnCode = generateTxnCode();
        const isHighValue = amount >= HIGH_VALUE_THRESHOLD;

        await pool.request()
            .input('transaction_id', sql.NVarChar(36), txnCode)
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .input('transaction_type', sql.NVarChar(30), resolvedTxnType)
            .input('source_account_id', sql.NVarChar(36), source_account_id)
            .input('target_account_number', sql.NVarChar(36), utilityOrderId)
            .input('target_bank_code', sql.NVarChar(20), payment_gateway || 'INTERNAL')
            .input('receiver_name', sql.NVarChar(150), receiverName)
            .input('amount', sql.Decimal(18, 2), amount)
            .input('currency', sql.NVarChar(10), account.currency || 'VND')
            .input('description', sql.NVarChar(500), description)
            .input('is_high_value', sql.Bit, isHighValue ? 1 : 0)
            .query(`
                INSERT INTO dbo.transactions (
                    transaction_id, user_id, transaction_type, source_account_id,
                    target_account_number, target_bank_code, receiver_name,
                    amount, currency, description, status, is_high_value
                )
                VALUES (
                    @transaction_id, @user_id, @transaction_type, @source_account_id,
                    @target_account_number, @target_bank_code, @receiver_name,
                    @amount, @currency, @description, 'PENDING', @is_high_value
                )
            `);

        // Liên kết transaction_id vào utility_orders
        if (utilityOrderId) {
            await pool.request()
                .input('order_id', sql.NVarChar(36), utilityOrderId)
                .input('transaction_id', sql.NVarChar(36), txnCode)
                .query(`UPDATE dbo.utility_orders SET transaction_id = @transaction_id WHERE order_id = @order_id`);
        }

        idempotencyCache.set(`meta_${txnCode}`, {
            bill_id: isBillFlow ? utilityOrderId : null,
            booking,
            order_id: utilityOrderId,
            txn_type: isBillFlow ? 'BILL' : 'UTILITY',
            payment_gateway
        });

        // 4. Sinh mã OTP 6 số và lưu vào bảng otp_challenges (chuẩn 11 cột của ERD_new.html)
        const challengeId = uuidv4();
        const otpCode = Math.floor(100000 + Math.random() * 900000).toString();
        const otpHash = crypto.createHash('sha256').update(otpCode).digest('hex');
        const expiresAt = new Date(Date.now() + 3 * 60 * 1000); // Hết hạn sau 3 phút
        const validChannel = ['SMS', 'EMAIL', 'SMART_OTP'].includes(String(channel).toUpperCase()) ? String(channel).toUpperCase() : 'SMS';

        await pool.request()
            .input('challenge_id', sql.NVarChar(36), challengeId)
            .input('transaction_id', sql.NVarChar(36), txnCode)
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .input('otp_hash', sql.NVarChar(255), otpHash)
            .input('channel', sql.NVarChar(20), validChannel)
            .input('expires_at', sql.DateTime2(0), expiresAt)
            .query(`
                INSERT INTO dbo.otp_challenges (
                    challenge_id, transaction_id, user_id, otp_hash,
                    channel, attempts_count, max_attempts, is_used, expires_at, status
                )
                VALUES (
                    @challenge_id, @transaction_id, @user_id, @otp_hash,
                    @channel, 0, 3, 0, @expires_at, 'PENDING'
                )
            `);

        console.log(`🔑 [OTP GENERATED] User: ${req.user.user_id} | Txn: ${txnCode} | Challenge: ${challengeId} | OTP: ${otpCode}`);

        let gatewayRedirectUrl = null;
        if (payment_gateway === 'VNPAY') {
            gatewayRedirectUrl = `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=${txnCode}&vnp_Amount=${amount * 100}`;
        } else if (payment_gateway === 'STRIPE') {
            gatewayRedirectUrl = `https://checkout.stripe.com/pay/${txnCode}`;
        } else if (payment_gateway === 'PAYOO') {
            gatewayRedirectUrl = `https://sandbox.payoo.vn/v2/pay?order_no=${txnCode}&amount=${amount}`;
        } else if (payment_gateway === 'PAYPAL') {
            gatewayRedirectUrl = `https://www.sandbox.paypal.com/checkoutnow?token=${txnCode}`;
        }

        const responsePayload = {
            transaction_id: txnCode,
            txn_code: txnCode,
            challenge_id: challengeId,
            order_id: utilityOrderId,
            status: 'PENDING',
            amount,
            is_high_value: isHighValue,
            requires_otp: true,
            requires_biometric: isHighValue,
            otp_expires_at: expiresAt.toISOString(),
            hold_expires_at: new Date(Date.now() + HOLD_TIMEOUT_MS).toISOString(),
            gateway_redirect_url: gatewayRedirectUrl,
            dev_otp_hint: otpCode
        };

        idempotencyCache.set(cacheKey, responsePayload);
        return res.status(201).json(responsePayload);
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi khởi tạo giao dịch thanh toán' });
    }
});

/**
 * POST /v1/utilities/payments/:txn_code/confirm
 * Bước 2: Xác nhận OTP (otp_challenges) & Sinh trắc học theo mô hình 2 pha an toàn (Two-Phase Commit / Saga):
 *   - Pha 1 (DB Transaction ngắn): Khóa dòng tài khoản (UPDLOCK) -> Trừ tiền -> Đánh dấu OTP đã dùng -> Đặt trạng thái PROCESSING -> COMMIT ngay để giải phóng khóa DB.
 *   - Pha 2 (Ngoài DB Transaction): Gọi API đối tác ngoài (PayPal / Nhà cung cấp dịch vụ) mà không giữ khóa DB.
 *   - Pha 3 (Chốt kết quả): Nếu đối tác thành công -> Cập nhật SUCCESS & PAID; Nếu đối tác lỗi/timeout -> Mở giao dịch bù trừ (Compensating Transaction) hoàn tiền (REFUNDED).
 */
router.post('/utilities/payments/:txn_code/confirm', authenticateJWT, async (req, res) => {
    const { txn_code } = req.params;
    const { otp_code, biometric_token } = req.body;

    try {
        const pool = await getPool();
        await expireOutdatedHolds(pool);

        // 1. Lấy thông tin giao dịch từ bảng transactions
        const txnRes = await pool.request()
            .input('transaction_id', sql.NVarChar(36), txn_code)
            .query(`SELECT * FROM dbo.transactions WHERE transaction_id = @transaction_id`);

        if (txnRes.recordset.length === 0) {
            return res.status(404).json({ code: 'NOT_FOUND', message: 'Không tìm thấy giao dịch' });
        }
        const txn = txnRes.recordset[0];

        if (txn.user_id !== req.user.user_id) {
            return res.status(403).json({ code: 'FORBIDDEN', message: 'Giao dịch không thuộc về bạn' });
        }

        // Idempotent: Nếu đã SUCCESS/COMPLETED trước đó thì trả về kết quả luôn
        if (txn.status === 'SUCCESS' || txn.status === 'COMPLETED') {
            return res.status(200).json({
                transaction_id: txn.transaction_id,
                txn_code: txn.transaction_id,
                status: 'SUCCESS',
                amount: Number(txn.amount),
                transaction_type: txn.transaction_type,
                txn_type: txn.transaction_type,
                created_at: txn.created_at,
                completed_at: txn.completed_at
            });
        }
        if (txn.status === 'PROCESSING') {
            return res.status(409).json({
                code: 'TRANSACTION_PROCESSING',
                message: 'Giao dịch đang được xử lý với đối tác, vui lòng chờ kết quả'
            });
        }
        if (txn.status !== 'PENDING' && txn.status !== 'OTP_SENT') {
            return res.status(409).json({ code: 'INVALID_STATE', message: `Giao dịch không còn ở trạng thái chờ xác nhận (Hiện tại: ${txn.status})` });
        }

        // Kiểm tra thời gian giữ chỗ (Seat/Room Hold Timeout 15m - UC-U03 7a)
        const orderCheck = await pool.request()
            .input('transaction_id', sql.NVarChar(36), txn.transaction_id)
            .query(`
                SELECT TOP 1 uo.*, us.service_type, us.provider_code
                FROM dbo.utility_orders uo
                JOIN dbo.utility_services us ON uo.service_id = us.service_id
                WHERE uo.transaction_id = @transaction_id
            `);
        const linkedOrder = orderCheck.recordset[0] || null;

        if (linkedOrder && linkedOrder.status === 'EXPIRED') {
            await pool.request()
                .input('transaction_id', sql.NVarChar(36), txn.transaction_id)
                .query(`UPDATE dbo.transactions SET status = 'FAILED' WHERE transaction_id = @transaction_id`);
            return res.status(409).json({
                code: 'HOLD_EXPIRED',
                message: 'Đã quá thời gian giữ chỗ 15 phút. Đơn hàng đã tự động hủy, vui lòng đặt lại.'
            });
        }

        // Kiểm tra yêu cầu sinh trắc học đối với giao dịch giá trị cao (is_high_value = 1 hoặc >= 10 triệu VND)
        if ((txn.is_high_value || Number(txn.amount) >= HIGH_VALUE_THRESHOLD) && !biometric_token) {
            return res.status(403).json({
                code: 'BIOMETRIC_REQUIRED',
                message: 'Giao dịch từ 10.000.000đ trở lên bắt buộc xác thực Sinh trắc học (biometric_token)'
            });
        }

        // 2. Kiểm tra mã OTP trong bảng otp_challenges (ERD_new.html)
        if (!otp_code) {
            return res.status(400).json({ code: 'MISSING_OTP', message: 'Vui lòng nhập mã OTP' });
        }
        const otpHash = crypto.createHash('sha256').update(String(otp_code)).digest('hex');
        const otpRes = await pool.request()
            .input('transaction_id', sql.NVarChar(36), txn.transaction_id)
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`
                SELECT TOP 1 * FROM dbo.otp_challenges
                WHERE transaction_id = @transaction_id AND user_id = @user_id AND is_used = 0
                ORDER BY created_at DESC
            `);

        if (otpRes.recordset.length === 0) {
            return res.status(400).json({ code: 'OTP_NOT_FOUND', message: 'Không tìm thấy thử thách OTP hợp lệ' });
        }
        const otpChallenge = otpRes.recordset[0];

        if (otpChallenge.attempts_count >= otpChallenge.max_attempts) {
            return res.status(400).json({ code: 'OTP_MAX_ATTEMPTS_EXCEEDED', message: 'Bạn đã nhập sai OTP quá số lần cho phép (3 lần)' });
        }

        if (new Date() > new Date(otpChallenge.expires_at) || otpChallenge.otp_hash !== otpHash) {
            const newAttempts = otpChallenge.attempts_count + 1;
            const nextStatus = newAttempts >= otpChallenge.max_attempts ? 'FAILED' : 'PENDING';
            await pool.request()
                .input('challenge_id', sql.NVarChar(36), otpChallenge.challenge_id)
                .input('attempts_count', sql.Int, newAttempts)
                .input('status', sql.NVarChar(20), nextStatus)
                .query(`
                    UPDATE dbo.otp_challenges
                    SET attempts_count = @attempts_count, status = @status
                    WHERE challenge_id = @challenge_id
                `);
            return res.status(400).json({ code: 'INVALID_OTP', message: 'Mã OTP sai hoặc đã hết hạn' });
        }

        // 3. PHA 1: Mở DB Transaction ngắn để khóa dòng tài khoản (UPDLOCK), trừ tiền, đặt trạng thái PROCESSING và COMMIT ngay
        // Tránh giữ khóa DB trong suốt thời gian chờ API đối tác bên ngoài (PayPal / Nhà cung cấp dịch vụ)
        let newBalance = 0;
        const debitTransaction = new sql.Transaction(pool);
        await debitTransaction.begin(sql.ISOLATION_LEVEL.READ_COMMITTED);

        try {
            // Khóa dòng giao dịch để chống race-condition khi 2 request confirm gửi đồng thời
            const txnLockRes = await new sql.Request(debitTransaction)
                .input('transaction_id', sql.NVarChar(36), txn.transaction_id)
                .query(`
                    SELECT status FROM dbo.transactions WITH (UPDLOCK, ROWLOCK)
                    WHERE transaction_id = @transaction_id
                `);

            const currentTxnStatus = txnLockRes.recordset[0] ? txnLockRes.recordset[0].status : null;
            if (currentTxnStatus !== 'PENDING' && currentTxnStatus !== 'OTP_SENT') {
                await debitTransaction.rollback();
                return res.status(409).json({
                    code: currentTxnStatus === 'PROCESSING' ? 'TRANSACTION_PROCESSING' : 'INVALID_STATE',
                    message: `Giao dịch không còn ở trạng thái chờ xác nhận (Hiện tại: ${currentTxnStatus})`
                });
            }

            const accLockRes = await new sql.Request(debitTransaction)
                .input('account_id', sql.NVarChar(36), txn.source_account_id)
                .query(`
                    SELECT balance, status FROM dbo.accounts WITH (UPDLOCK, ROWLOCK)
                    WHERE account_id = @account_id
                `);

            const lockedAccount = accLockRes.recordset[0];
            if (!lockedAccount || lockedAccount.status !== 'ACTIVE') {
                await debitTransaction.rollback();
                return res.status(423).json({ code: 'ACCOUNT_LOCKED', message: 'Tài khoản nguồn đang bị khóa hoặc đóng băng' });
            }
            if (Number(lockedAccount.balance) < Number(txn.amount)) {
                await debitTransaction.rollback();
                return res.status(402).json({ code: 'INSUFFICIENT_BALANCE', message: 'Số dư không đủ tại thời điểm xác nhận' });
            }

            // Trừ số dư tài khoản
            newBalance = Number(lockedAccount.balance) - Number(txn.amount);
            await new sql.Request(debitTransaction)
                .input('account_id', sql.NVarChar(36), txn.source_account_id)
                .input('new_balance', sql.Decimal(18, 2), newBalance)
                .query(`UPDATE dbo.accounts SET balance = @new_balance WHERE account_id = @account_id`);

            // Đánh dấu OTP trong bảng otp_challenges đã sử dụng
            await new sql.Request(debitTransaction)
                .input('challenge_id', sql.NVarChar(36), otpChallenge.challenge_id)
                .query(`
                    UPDATE dbo.otp_challenges
                    SET is_used = 1, status = 'VERIFIED', attempts_count = attempts_count + 1
                    WHERE challenge_id = @challenge_id
                `);

            // Đặt trạng thái giao dịch & đơn hàng sang PROCESSING trước khi gọi API ngoài
            await new sql.Request(debitTransaction)
                .input('transaction_id', sql.NVarChar(36), txn.transaction_id)
                .input('is_bio', sql.Bit, biometric_token ? 1 : 0)
                .query(`
                    UPDATE dbo.transactions
                    SET status = 'PROCESSING', is_2fa_verified = 1, is_biometric_verified = @is_bio
                    WHERE transaction_id = @transaction_id
                `);

            await new sql.Request(debitTransaction)
                .input('transaction_id', sql.NVarChar(36), txn.transaction_id)
                .query(`
                    UPDATE dbo.utility_orders
                    SET status = 'PROCESSING'
                    WHERE transaction_id = @transaction_id
                `);

            // Commit ngay Pha 1 để nhả khóa UPDLOCK trên bảng accounts
            await debitTransaction.commit();
        } catch (debitErr) {
            await debitTransaction.rollback();
            throw debitErr;
        }

        // Hàm hỗ trợ hoàn tiền bù trừ (Compensating Transaction) khi API đối tác (PayPal) lỗi hoặc timeout
        const refundProcessingTransaction = async (reasonDetails) => {
            const refundTransaction = new sql.Transaction(pool);
            await refundTransaction.begin(sql.ISOLATION_LEVEL.READ_COMMITTED);
            try {
                const refundAccRes = await new sql.Request(refundTransaction)
                    .input('account_id', sql.NVarChar(36), txn.source_account_id)
                    .input('refund_amount', sql.Decimal(18, 2), Number(txn.amount))
                    .query(`
                        UPDATE dbo.accounts WITH (UPDLOCK, ROWLOCK)
                        SET balance = balance + @refund_amount
                        OUTPUT INSERTED.balance
                        WHERE account_id = @account_id
                    `);

                const refundedBalance = refundAccRes.recordset[0]
                    ? Number(refundAccRes.recordset[0].balance)
                    : newBalance + Number(txn.amount);

                await new sql.Request(refundTransaction)
                    .input('transaction_id', sql.NVarChar(36), txn.transaction_id)
                    .query(`
                        UPDATE dbo.transactions
                        SET status = 'REFUNDED', completed_at = SYSUTCDATETIME()
                        WHERE transaction_id = @transaction_id
                    `);

                await new sql.Request(refundTransaction)
                    .input('transaction_id', sql.NVarChar(36), txn.transaction_id)
                    .query(`UPDATE dbo.utility_orders SET status = 'REFUNDED' WHERE transaction_id = @transaction_id`);

                await new sql.Request(refundTransaction)
                    .input('log_id', sql.NVarChar(36), uuidv4())
                    .input('actor_id', sql.NVarChar(36), req.user.user_id)
                    .input('entity_id', sql.NVarChar(100), txn.transaction_id)
                    .input('details', sql.NVarChar(sql.MAX), reasonDetails || 'Đối tác gián đoạn, tự động hoàn tiền 100% cho khách hàng')
                    .query(`
                        INSERT INTO dbo.security_audit_logs (log_id, actor_id, action, entity_name, entity_id, status, details)
                        VALUES (@log_id, @actor_id, 'AUTO_REFUND_PARTNER_ERROR', 'transactions', @entity_id, 'REFUNDED', @details)
                    `);

                await refundTransaction.commit();
                return refundedBalance;
            } catch (refundErr) {
                await refundTransaction.rollback();
                throw refundErr;
            }
        };

        const cachedMeta = idempotencyCache.get(`meta_${txn_code}`) || {};
        const existingOrderMeta = linkedOrder && linkedOrder.metadata_payload
            ? JSON.parse(linkedOrder.metadata_payload)
            : {};

        const isBillTxn = ['BILL_PAYMENT', 'TOPUP', 'BILL'].includes(txn.transaction_type) ||
            (linkedOrder && ['ELECTRICITY', 'WATER', 'PHONE_TOPUP'].includes(linkedOrder.service_type));

        // 4. PHA 2: Gọi API đối tác PayPal (paypal.service) ngoài DB Transaction (không giữ khóa UPDLOCK)
        // Thành công thì tiếp tục luồng PAID/SUCCESS, lỗi hoặc timeout thì chạy luồng hoàn tiền (refundProcessingTransaction) đã có
        let partnerResult = null;
        try {
            const serviceType = linkedOrder ? linkedOrder.service_type : txn.transaction_type;
            const paymentPayload = {
                partnerRefId: txn.transaction_id,
                transaction_id: txn.transaction_id,
                order_id: linkedOrder ? linkedOrder.order_id : null,
                service_type: serviceType,
                provider_code: linkedOrder ? linkedOrder.provider_code : null,
                provider_customer_id: linkedOrder ? linkedOrder.provider_customer_id : null,
                phoneNumber: linkedOrder ? linkedOrder.provider_customer_id : null,
                telco: linkedOrder && linkedOrder.provider_code ? String(linkedOrder.provider_code).toLowerCase() : 'viettel',
                amount: Number(txn.amount),
                currency: txn.currency || 'VND',
                description: txn.description,
                metadata: existingOrderMeta
            };

            if (typeof paypalService === 'function') {
                partnerResult = await paypalService(paymentPayload);
            } else if (serviceType === 'PHONE_TOPUP' && typeof paypalService.topup === 'function') {
                partnerResult = await paypalService.topup(paymentPayload);
            } else if (['ELECTRICITY', 'WATER'].includes(serviceType) && typeof paypalService.payBill === 'function') {
                partnerResult = await paypalService.payBill(paymentPayload);
            } else if (typeof paypalService.processPayment === 'function') {
                partnerResult = await paypalService.processPayment(paymentPayload);
            } else {
                throw new Error('Chưa cấu hình hàm xử lý thanh toán trong paypal.service');
            }

            if (
                !partnerResult ||
                partnerResult.success === false ||
                (partnerResult.errorCode !== undefined && Number(partnerResult.errorCode) !== 0)
            ) {
                throw new Error(
                    (partnerResult && (partnerResult.message || partnerResult.errorMessage)) ||
                    'PayPal phản hồi giao dịch thất bại'
                );
            }
        } catch (partnerErr) {
            const refundedBalance = await refundProcessingTransaction(
                `Lỗi gọi PayPal (${partnerErr.message}), tự động hoàn tiền 100% cho khách hàng`
            );
            return res.status(502).json({
                code: 'PARTNER_ERROR_AUTO_REFUNDED',
                message: 'Kết nối nhà cung cấp gián đoạn. Hệ thống đã tự động hoàn tiền (Rollback/Refund) 100% về tài khoản của bạn.',
                transaction_id: txn.transaction_id,
                txn_code: txn.transaction_id,
                status: 'REFUNDED',
                refunded_amount: Number(txn.amount),
                balance_after: refundedBalance
            });
        }

        let updatedBill = null;
        let createdBooking = null;
        let fulfillmentReceipt = {};
        let mergedMeta = '';
        let sType = 'FLIGHT';
        let rawDetails = {};

        try {
            if (isBillTxn) {
                fulfillmentReceipt = {
                    receipt_no: `RCPT-${txn.transaction_id}`,
                    bill_type: linkedOrder ? linkedOrder.service_type : 'ELECTRICITY',
                    provider_customer_id: linkedOrder ? linkedOrder.provider_customer_id : '',
                    partner_response: partnerResult,
                    settled_at: new Date().toISOString()
                };

                mergedMeta = JSON.stringify({
                    ...existingOrderMeta,
                    fulfillment: fulfillmentReceipt
                });
            } else {
                const bookingData = cachedMeta.booking || existingOrderMeta;
                sType = String(bookingData.service_type || (linkedOrder && linkedOrder.service_type) || 'FLIGHT').toUpperCase();
                rawDetails = {
                    ...(bookingData.booking_details || existingOrderMeta.booking_details || {}),
                    partner_response: partnerResult
                };

                if (sType === 'FLIGHT') {
                    rawDetails.pnr_code = `PNR-${Math.random().toString(36).substring(2, 8).toUpperCase()}`;
                    rawDetails.e_ticket_number = `738-${Math.floor(1000000000 + Math.random() * 9000000000)}`;
                    rawDetails.ticket_status = 'ISSUED';
                } else if (sType === 'MOVIE') {
                    const ticketCode = `CGV-${Math.floor(10000000 + Math.random() * 90000000)}`;
                    rawDetails.booking_code = ticketCode;
                    rawDetails.qr_code_payload = `https://api.qrserver.com/v1/create-qr-code/?size=250x250&data=${encodeURIComponent(ticketCode + '|' + txn.transaction_id)}`;
                    rawDetails.ticket_status = 'CONFIRMED';
                } else if (sType === 'HOTEL') {
                    rawDetails.voucher_code = `HTL-MT-${Math.floor(100000 + Math.random() * 900000)}`;
                    rawDetails.reservation_status = 'CONFIRMED';
                } else if (sType === 'ECOMMERCE') {
                    rawDetails.order_code = `ORD-${Math.floor(10000000 + Math.random() * 90000000)}`;
                    rawDetails.tracking_number = `VNX${Math.floor(100000000 + Math.random() * 900000000)}`;
                    rawDetails.shipping_status = 'PROCESSING';
                    const ecomItem = MOCK_CATALOG.ECOMMERCE.find(i => i.catalog_item_id === bookingData.catalog_item_id);
                    if (ecomItem) {
                        ecomItem.stock = Math.max(0, ecomItem.stock - Number(rawDetails.quantity || 1));
                    }
                }

                fulfillmentReceipt = rawDetails;
                mergedMeta = JSON.stringify({
                    ...existingOrderMeta,
                    service_type: sType,
                    booking_details: rawDetails,
                    fulfillment: fulfillmentReceipt
                });
            }
        } catch (partnerErr) {
            const refundedBalance = await refundProcessingTransaction(`Lỗi xử lý đối tác (${partnerErr.message}), tự động hoàn tiền 100% cho khách hàng`);
            return res.status(502).json({
                code: 'PARTNER_ERROR_AUTO_REFUNDED',
                message: 'Kết nối nhà cung cấp gián đoạn. Hệ thống đã tự động hoàn tiền (Rollback/Refund) 100% về tài khoản của bạn.',
                transaction_id: txn.transaction_id,
                txn_code: txn.transaction_id,
                status: 'REFUNDED',
                refunded_amount: Number(txn.amount),
                balance_after: refundedBalance
            });
        }

        // 5. PHA 3: Mở DB Transaction ngắn để cập nhật trạng thái cuối cùng (SUCCESS / PAID) và ghi audit log
        const finalizeTransaction = new sql.Transaction(pool);
        await finalizeTransaction.begin(sql.ISOLATION_LEVEL.READ_COMMITTED);

        try {
            await new sql.Request(finalizeTransaction)
                .input('transaction_id', sql.NVarChar(36), txn.transaction_id)
                .query(`
                    UPDATE dbo.transactions
                    SET status = 'SUCCESS', completed_at = SYSUTCDATETIME()
                    WHERE transaction_id = @transaction_id
                `);

            const orderUpdate = await new sql.Request(finalizeTransaction)
                .input('transaction_id', sql.NVarChar(36), txn.transaction_id)
                .input('metadata_payload', sql.NVarChar(sql.MAX), mergedMeta)
                .query(`
                    UPDATE dbo.utility_orders
                    SET status = 'PAID', metadata_payload = @metadata_payload
                    OUTPUT INSERTED.*
                    WHERE transaction_id = @transaction_id
                `);

            if (isBillTxn) {
                const updatedRow = orderUpdate.recordset[0] || linkedOrder;
                if (updatedRow) {
                    updatedBill = mapOrderRowToBill({
                        ...updatedRow,
                        status: 'PAID',
                        service_type: linkedOrder ? linkedOrder.service_type : 'ELECTRICITY',
                        provider_code: linkedOrder ? linkedOrder.provider_code : 'EVN_HCM'
                    });
                }
            } else {
                const row = orderUpdate.recordset[0] || linkedOrder || { order_id: uuidv4() };
                createdBooking = {
                    booking_id: row.order_id,
                    order_id: row.order_id,
                    transaction_id: txn.transaction_id,
                    service_type: sType,
                    booking_details: rawDetails,
                    total_amount: Number(txn.amount)
                };
            }

            // Ghi nhận log bảo mật vào bảng security_audit_logs
            await new sql.Request(finalizeTransaction)
                .input('log_id', sql.NVarChar(36), uuidv4())
                .input('actor_id', sql.NVarChar(36), req.user.user_id)
                .input('entity_id', sql.NVarChar(100), txn.transaction_id)
                .input('details', sql.NVarChar(sql.MAX), `Xác nhận thanh toán thành công giao dịch ${txn.transaction_id}, số tiền ${txn.amount} VND`)
                .query(`
                    INSERT INTO dbo.security_audit_logs (log_id, actor_id, action, entity_name, entity_id, status, details)
                    VALUES (@log_id, @actor_id, 'CONFIRM_UTILITY_PAYMENT', 'transactions', @entity_id, 'SUCCESS', @details)
                `);

            await finalizeTransaction.commit();

            return res.status(200).json({
                transaction_id: txn.transaction_id,
                txn_code: txn.transaction_id,
                status: 'SUCCESS',
                amount: Number(txn.amount),
                transaction_type: txn.transaction_type,
                txn_type: isBillTxn ? 'BILL' : 'UTILITY',
                created_at: txn.created_at,
                completed_at: new Date().toISOString(),
                balance_after: newBalance,
                bill: updatedBill,
                booking: createdBooking,
                fulfillment: fulfillmentReceipt,
                failure_reason: null
            });
        } catch (finalizeErr) {
            await finalizeTransaction.rollback();
            throw finalizeErr;
        }
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'CONFIRM_FAILED', message: 'Lỗi xác nhận giao dịch: ' + err.message });
    }
});

/**
 * GET /v1/utilities/payments/:txn_code
 * Tra cứu trạng thái giao dịch từ bảng transactions
 */
router.get('/utilities/payments/:txn_code', authenticateJWT, async (req, res) => {
    try {
        const pool = await getPool();
        const result = await pool.request()
            .input('transaction_id', sql.NVarChar(36), req.params.txn_code)
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`
                SELECT t.*, a.balance AS current_balance
                FROM dbo.transactions t
                LEFT JOIN dbo.accounts a ON t.source_account_id = a.account_id
                WHERE t.transaction_id = @transaction_id AND t.user_id = @user_id
            `);

        if (result.recordset.length === 0) {
            return res.status(404).json({ code: 'NOT_FOUND', message: 'Không tìm thấy giao dịch' });
        }
        const txn = result.recordset[0];
        return res.status(200).json({
            transaction_id: txn.transaction_id,
            txn_code: txn.transaction_id,
            status: txn.status,
            amount: Number(txn.amount),
            currency: txn.currency,
            transaction_type: txn.transaction_type,
            txn_type: txn.transaction_type,
            description: txn.description,
            is_high_value: txn.is_high_value,
            created_at: txn.created_at,
            completed_at: txn.completed_at,
            balance_after: txn.status === 'SUCCESS' ? Number(txn.current_balance) : null
        });
    } catch (err) {
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi tra cứu giao dịch' });
    }
});

/**
 * POST /v1/utilities/payments/:txn_code/cancel
 * Hủy giao dịch đang PENDING & Giải phóng ghế/phòng giữ tạm thời trong utility_orders
 */
router.post('/utilities/payments/:txn_code/cancel', authenticateJWT, async (req, res) => {
    try {
        const pool = await getPool();
        const checkRes = await pool.request()
            .input('transaction_id', sql.NVarChar(36), req.params.txn_code)
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`
                SELECT * FROM dbo.transactions
                WHERE transaction_id = @transaction_id AND user_id = @user_id
            `);

        if (checkRes.recordset.length === 0) {
            return res.status(404).json({ code: 'NOT_FOUND', message: 'Không tìm thấy giao dịch' });
        }
        const txn = checkRes.recordset[0];
        if (txn.status === 'SUCCESS' || txn.status === 'COMPLETED' || txn.status === 'PROCESSING') {
            return res.status(409).json({
                code: txn.status === 'PROCESSING' ? 'TRANSACTION_PROCESSING' : 'ALREADY_SUCCESS',
                message: txn.status === 'PROCESSING'
                    ? 'Giao dịch đang trong quá trình xử lý thanh toán, không thể hủy'
                    : 'Giao dịch đã thành công, không thể hủy'
            });
        }

        await pool.request()
            .input('transaction_id', sql.NVarChar(36), txn.transaction_id)
            .query(`
                UPDATE dbo.transactions SET status = 'FAILED', completed_at = SYSUTCDATETIME() WHERE transaction_id = @transaction_id;
                UPDATE dbo.utility_orders SET status = 'FAILED' WHERE transaction_id = @transaction_id;
            `);

        return res.status(200).json({
            transaction_id: txn.transaction_id,
            txn_code: txn.transaction_id,
            status: 'FAILED',
            amount: Number(txn.amount),
            transaction_type: txn.transaction_type,
            txn_type: txn.transaction_type,
            created_at: txn.created_at,
            failure_reason: 'USER_CANCELLED'
        });
    } catch (err) {
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi hủy giao dịch' });
    }
});

/**
 * POST /v1/webhooks/payment-gateway/:gateway
 * Webhook bất đồng bộ nhận kết quả từ VNPAY / Payoo / Napas / Stripe (Server-to-Server)
 */
router.post('/webhooks/payment-gateway/:gateway', async (req, res) => {
    const { gateway } = req.params;
    const payload = req.body;
    const targetTxnId = payload && (payload.txn_code || payload.transaction_id);

    if (!targetTxnId) {
        return res.status(400).json({ code: 'INVALID_WEBHOOK_PAYLOAD', message: 'Thiếu thông tin giao dịch txn_code / transaction_id' });
    }

    try {
        const pool = await getPool();
        const txnRes = await pool.request()
            .input('transaction_id', sql.NVarChar(36), targetTxnId)
            .query(`SELECT * FROM dbo.transactions WHERE transaction_id = @transaction_id`);

        if (txnRes.recordset.length === 0) {
            return res.status(200).json({ received: true, gateway, note: 'Transaction not found' });
        }
        const txn = txnRes.recordset[0];
        if (txn.status !== 'PENDING' && txn.status !== 'OTP_SENT' && txn.status !== 'PROCESSING') {
            return res.status(200).json({ received: true, gateway, status: txn.status, idempotent: true });
        }

        if (!payload.success && txn.status === 'PROCESSING') {
            const refundTxn = new sql.Transaction(pool);
            await refundTxn.begin(sql.ISOLATION_LEVEL.READ_COMMITTED);
            try {
                await new sql.Request(refundTxn)
                    .input('account_id', sql.NVarChar(36), txn.source_account_id)
                    .input('refund_amount', sql.Decimal(18, 2), Number(txn.amount))
                    .query(`
                        UPDATE dbo.accounts WITH (UPDLOCK, ROWLOCK)
                        SET balance = balance + @refund_amount
                        WHERE account_id = @account_id
                    `);
                await new sql.Request(refundTxn)
                    .input('transaction_id', sql.NVarChar(36), txn.transaction_id)
                    .query(`
                        UPDATE dbo.transactions SET status = 'REFUNDED', completed_at = SYSUTCDATETIME() WHERE transaction_id = @transaction_id;
                        UPDATE dbo.utility_orders SET status = 'REFUNDED' WHERE transaction_id = @transaction_id;
                    `);
                await refundTxn.commit();
                return res.status(200).json({ received: true, gateway, txn_code: targetTxnId, transaction_id: targetTxnId, status: 'REFUNDED' });
            } catch (refundErr) {
                await refundTxn.rollback();
                throw refundErr;
            }
        }

        const newStatus = payload.success ? 'SUCCESS' : 'FAILED';
        await pool.request()
            .input('transaction_id', sql.NVarChar(36), txn.transaction_id)
            .input('status', sql.NVarChar(20), newStatus)
            .query(`
                UPDATE dbo.transactions SET status = @status, completed_at = SYSUTCDATETIME() WHERE transaction_id = @transaction_id;
                UPDATE dbo.utility_orders SET status = CASE WHEN @status = 'SUCCESS' THEN 'PAID' ELSE 'FAILED' END
                WHERE transaction_id = @transaction_id;
            `);

        return res.status(200).json({ received: true, gateway, txn_code: targetTxnId, transaction_id: targetTxnId, status: newStatus });
    } catch (err) {
        return res.status(500).json({ code: 'WEBHOOK_ERROR', message: err.message });
    }
});

module.exports = router;
module.exports.MOCK_CATALOG = MOCK_CATALOG;
module.exports.detectCarrierByPhone = detectCarrierByPhone;
