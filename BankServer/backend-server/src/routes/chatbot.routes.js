const express = require('express');
const router = express.Router();
const { v4: uuidv4 } = require('uuid');
const { sql, getPool } = require('../db');
const { authenticateJWT, optionalAuthenticateJWT } = require('../middleware/auth');
const { detectCarrierByPhone, MOCK_CATALOG } = require('./utilities.routes');
const { calculateHaversineKm } = require('./navigation.routes');
const geminiService = require('../services/gemini.service');

// Danh sách Suggestion Chips mặc định khi mở khung chat (Mục 3 AI.txt & Mục 4 Chatbot_Analysis.docx)
const DEFAULT_SUGGESTION_CHIPS = [
    'Thanh toán tiền điện tháng này giúp tôi',
    'Chi nhánh nào gần đây nhất?',
    'Cho tôi chuyển 500 cành cho mẹ',
    'Nạp 100k điện thoại cho số của tôi',
    'Đặt vé máy bay đi Hà Nội cuối tuần này',
    'Đặt vé xem phim Venom 3 tại CGV',
    'Lãi suất gửi tiết kiệm 6 tháng là bao nhiêu?',
    'Xem số dư tài khoản'
];

// Danh bạ thụ hưởng mẫu của khách hàng (phục vụ kịch bản "Cho tôi chuyển 500 cành cho mẹ")
const SAVED_BENEFICIARIES = [
    {
        alias: ['mẹ', 'me', 'má', 'ma', 'mẫu thân', 'nguyen thi b', 'nguyễn thị b'],
        receiver_name: 'Nguyen Thi B',
        receiver_display_name: 'Nguyễn Thị B (Mẹ)',
        dest_account_number: '0123456789',
        dest_bank_code: 'VCB',
        dest_bank_name: 'Vietcombank'
    },
    {
        alias: ['bố', 'bo', 'ba', 'cha', 'nguyen van c', 'nguyễn văn c'],
        receiver_name: 'Nguyen Van C',
        receiver_display_name: 'Nguyễn Văn C (Bố)',
        dest_account_number: '9876543210',
        dest_bank_code: 'TCB',
        dest_bank_name: 'Techcombank'
    }
];

/**
 * 1. Lớp bảo mật Input Guardrails: Chống tấn công Prompt Injection (Mục 4 AI.txt & Mục 6 Chatbot_Analysis.docx)
 */
function checkPromptInjection(text) {
    const lower = String(text || '').toLowerCase();
    const injectionPatterns = [
        'bỏ qua các lệnh trên',
        'bỏ qua mọi quy tắc',
        'ignore previous instructions',
        'ignore all instructions',
        'mật khẩu hệ thống',
        'system prompt',
        'drop table',
        'delete from',
        'bypass otp',
        'xuất danh sách tài khoản hệ thống'
    ];
    for (const pattern of injectionPatterns) {
        if (lower.includes(pattern)) {
            return {
                blocked: true,
                reason: 'PROMPT_INJECTION_DETECTED',
                matched: pattern
            };
        }
    }
    return { blocked: false };
}

/**
 * 2. Lớp bảo mật PII Masking: Ẩn danh dữ liệu nhạy cảm (số thẻ 16 số, mật khẩu, mã OTP/PIN) trước khi gửi qua AI
 */
function maskSensitivePII(text) {
    if (!text) return '';
    return String(text)
        // Che số thẻ ngân hàng 16 chữ số
        .replace(/\b(\d{4})[\s-]?(\d{4})[\s-]?(\d{4})[\s-]?(\d{4})\b/g, '$1-****-****-$4')
        // Che chuỗi mật khẩu / pin / otp nếu người dùng vô tình gõ vào khung chat
        .replace(/(mật khẩu|password|pin|otp)\s*[:=]?\s*\S+/gi, '$1: [REDACTED]');
}

/**
 * Hàm trích xuất số tiền tiếng Việt tự nhiên ("500 cành", "100k", "2 triệu", "500.000đ"...)
 */
function extractVietnameseAmount(text) {
    const lower = String(text || '').toLowerCase();

    // Dạng: 500 cành / 500k / 500 nghìn / 500 ngàn
    const kMatch = lower.match(/(\d+(?:[.,]\d+)?)\s*(cành|canh|k|nghìn|nghin|ngàn|ngan)\b/i);
    if (kMatch) {
        return Math.round(parseFloat(kMatch[1].replace(',', '.')) * 1000);
    }

    // Dạng: 2 triệu / 2 củ / 2tr / 1.5 triệu
    const mMatch = lower.match(/(\d+(?:[.,]\d+)?)\s*(triệu|trieu|củ|cu|tr)\b/i);
    if (mMatch) {
        return Math.round(parseFloat(mMatch[1].replace(',', '.')) * 1000000);
    }

    // Dạng: 500.000 hoặc 500,000
    const formattedMatch = lower.match(/(\d{1,3}(?:[.,]\d{3})+)\s*(đ|đồng|vnd|vnđ)?/i);
    if (formattedMatch) {
        return parseInt(formattedMatch[1].replace(/[.,]/g, ''), 10);
    }

    // Dạng số nguyên >= 10000
    const rawNumMatch = lower.match(/\b(\d{5,9})\b/);
    if (rawNumMatch) {
        return parseInt(rawNumMatch[1], 10);
    }

    return null;
}

/**
 * Hàm trích xuất phương tiện di chuyển từ câu hỏi người dùng
 */
function extractTransportMode(text) {
    const lower = String(text || '').toLowerCase();
    if (lower.includes('xe máy') || lower.includes('xe may') || lower.includes('mô tô') || lower.includes('two_wheeler')) {
        return { mode: 'two_wheeler', label: 'xe máy' };
    }
    if (lower.includes('ô tô') || lower.includes('o to') || lower.includes('xe hơi') || lower.includes('driving')) {
        return { mode: 'driving', label: 'ô tô' };
    }
    if (lower.includes('xe buýt') || lower.includes('xe bus') || lower.includes('buýt') || lower.includes('transit')) {
        return { mode: 'transit', label: 'xe buýt' };
    }
    // Mặc định ưu tiên đường đi bộ theo yêu cầu phân tích Navigation & UC-N03
    return { mode: 'walking', label: 'đi bộ' };
}

/**
 * Hàm tính ngày khởi hành tự động từ ngôn ngữ tự nhiên ("ngày mai", "cuối tuần này", "thứ Bảy"...)
 */
function extractTargetDate(text) {
    const lower = String(text || '').toLowerCase();
    const explicitDate = lower.match(/\b(202\d-\d{2}-\d{2})\b/);
    if (explicitDate) return explicitDate[1];

    const now = new Date();
    if (lower.includes('ngày mai') || lower.includes('ngay mai')) {
        const tomorrow = new Date(now.getTime() + 24 * 60 * 60 * 1000);
        return tomorrow.toISOString().slice(0, 10);
    }
    if (lower.includes('cuối tuần') || lower.includes('cuoi tuan') || lower.includes('thứ bảy') || lower.includes('thu bay')) {
        const day = now.getDay();
        const daysUntilSat = (6 - day + 7) % 7 || 7;
        const sat = new Date(now.getTime() + daysUntilSat * 24 * 60 * 60 * 1000);
        return sat.toISOString().slice(0, 10);
    }
    // Mặc định thứ Bảy tuần này (2026-11-14 theo thời gian hiện tại 2026-11-12)
    return '2026-11-14';
}

/**
 * 3. Bộ phân tích Ngôn ngữ Tự nhiên (NLP Engine) & Kiểm tra Ngữ cảnh DB (Context Validation)
 * Xử lý trọn vẹn các kịch bản trong AI.txt, Chatbot_Analysis.docx và Mô tả usecase.txt (UC-C01)
 */
async function processChatbotNLP(pool, user, rawText, userLocation) {
    const maskedText = maskSensitivePII(rawText);
    const lower = maskedText.toLowerCase();

    // =========================================================================
    // KỊCH BẢN 1: THANH TOÁN HÓA ĐƠN ĐIỆN / NƯỚC (Pay_Utility_Bill)
    // =========================================================================
    if (
        lower.includes('tiền điện') || lower.includes('tien dien') ||
        lower.includes('hóa đơn điện') || lower.includes('đóng điện') ||
        lower.includes('tiền nước') || lower.includes('tien nuoc') ||
        lower.includes('hóa đơn nước') || lower.includes('thanh toán hóa đơn') ||
        lower.includes('đóng tiền điện') || lower.includes('đóng tiền nước')
    ) {
        const isWater = lower.includes('nước') || lower.includes('nuoc') || lower.includes('sawaco');
        const serviceType = isWater ? 'WATER' : 'ELECTRICITY';
        const defaultProvider = isWater ? 'SAWACO' : 'EVN_HCM';
        const serviceLabel = isWater ? 'tiền nước' : 'tiền điện';

        // Truy vấn hóa đơn PENDING/UNPAID thực tế của người dùng trong bảng utility_orders + utility_services (ERD_new.html)
        const billRes = await pool.request()
            .input('user_id', sql.NVarChar(36), user.user_id)
            .input('service_type', sql.NVarChar(20), serviceType)
            .query(`
                SELECT TOP 1 uo.order_id AS bill_id, uo.provider_customer_id, uo.total_amount AS amount, uo.metadata_payload, us.provider_code
                FROM dbo.utility_orders uo
                JOIN dbo.utility_services us ON uo.service_id = us.service_id
                WHERE uo.user_id = @user_id AND us.service_type = @service_type AND uo.status IN ('PENDING', 'UNPAID')
                ORDER BY uo.created_at DESC
            `);

        const bill = billRes.recordset[0] || {
            bill_id: isWater ? 'b9f1c2d3-0000-0000-0000-000000000002' : 'b9f1c2d3-0000-0000-0000-000000000001',
            provider_customer_id: isWater ? 'SW987654' : 'PE123456',
            provider_code: defaultProvider,
            amount: isWater ? 185000 : 850000,
            billing_period: '11/2026'
        };

        const formattedAmount = Number(bill.amount).toLocaleString('vi-VN');
        const messageText = `Em tìm thấy hóa đơn ${serviceLabel} tháng này của mã khách hàng ${bill.provider_customer_id}. Số tiền cần thanh toán là ${formattedAmount}đ. Anh/chị xác nhận nhé!`;
        const deepLink = `app://banking/utilities/bill?type=${serviceType}&billId=${bill.bill_id}&customerCode=${bill.provider_customer_id}`;

        return {
            intent: 'Pay_Utility_Bill',
            entities: {
                service_type: serviceType,
                provider_code: bill.provider_code || defaultProvider,
                customer_code: bill.provider_customer_id,
                bill_id: bill.bill_id,
                amount: Number(bill.amount)
            },
            message_text: messageText,
            action: {
                type: 'NAVIGATE',
                action_code: 'OPEN_BILL_PAYMENT',
                action_label: `Thanh toán ${serviceLabel} (${formattedAmount}đ)`,
                destination: 'app://utilities/bill-payment',
                action_deep_link: deepLink,
                params: {
                    service_type: serviceType,
                    provider_code: bill.provider_code || defaultProvider,
                    customer_code: bill.provider_customer_id,
                    bill_id: bill.bill_id,
                    amount: Number(bill.amount),
                    auto_fetch_debt: true
                }
            },
            suggestions: ['Nạp tiền điện thoại', 'Xem số dư tài khoản', 'Lịch sử giao dịch']
        };
    }

    // =========================================================================
    // KỊCH BẢN 2: NẠP TIỀN ĐIỆN THOẠI (Topup_Phone / TOPUP_PHONE)
    // =========================================================================
    if (
        lower.includes('nạp tiền điện thoại') || lower.includes('nap tien dien thoai') ||
        lower.includes('nạp điện thoại') || lower.includes('nap dien thoai') ||
        lower.includes('nạp thẻ') || lower.includes('topup') ||
        (lower.includes('nạp') && (lower.includes('viettel') || lower.includes('vinaphone') || lower.includes('mobifone') || lower.includes('số điện thoại') || lower.includes('số của tôi')))
    ) {
        // Bóc tách số điện thoại trong câu chat hoặc lấy SĐT chủ tài khoản từ DB
        const phoneMatch = maskedText.match(/\b(0\d{9})\b/);
        let targetPhone = phoneMatch ? phoneMatch[1] : null;
        if (!targetPhone) {
            const uRes = await pool.request()
                .input('user_id', sql.NVarChar(36), user.user_id)
                .query(`SELECT phone_number FROM dbo.users WHERE user_id = @user_id`);
            targetPhone = (uRes.recordset[0] && uRes.recordset[0].phone_number) || '0901234567';
        }

        const carrierInfo = detectCarrierByPhone(targetPhone);
        let telco = carrierInfo && carrierInfo.carrier ? carrierInfo.carrier : 'VIETTEL';
        if (lower.includes('viettel')) telco = 'VIETTEL';
        else if (lower.includes('vinaphone') || lower.includes('vina')) telco = 'VINAPHONE';
        else if (lower.includes('mobifone') || lower.includes('mobi')) telco = 'MOBIFONE';

        const amount = extractVietnameseAmount(maskedText) || 100000;
        const shortAmount = amount >= 1000 ? `${Math.round(amount / 1000)}K` : `${amount}đ`;
        const messageText = `Lệnh nạp ${shortAmount} vào số điện thoại ${targetPhone} (${telco}) của anh/chị đã sẵn sàng.`;
        const deepLink = `app://banking/utilities/topup?telco=${telco}&amount=${amount}&phone=${targetPhone}`;

        return {
            intent: 'TOPUP_PHONE',
            entities: {
                phone_number: targetPhone,
                telco,
                amount
            },
            message_text: messageText,
            action: {
                type: 'NAVIGATE',
                action_code: 'OPEN_TOPUP_SCREEN',
                action_label: `Nạp ${ Number(amount).toLocaleString('vi-VN') }đ cho ${targetPhone}`,
                destination: 'app://utilities/mobile-topup',
                action_deep_link: deepLink,
                params: {
                    phone_number: targetPhone,
                    telco,
                    amount
                }
            },
            suggestions: ['Nạp 50.000đ', 'Nạp 200.000đ', 'Thanh toán tiền điện']
        };
    }

    // =========================================================================
    // KỊCH BẢN 3: ĐẶT VÉ MÁY BAY (Book_Flight / BOOK_FLIGHT)
    // =========================================================================
    if (
        lower.includes('vé máy bay') || lower.includes('ve may bay') ||
        lower.includes('chuyến bay') || lower.includes('chuyen bay') ||
        lower.includes('vietnam airlines') || lower.includes('vietjet') ||
        (lower.includes('đặt vé') && (lower.includes('hà nội') || lower.includes('đà nẵng') || lower.includes('sài gòn')))
    ) {
        let destCode = 'HAN';
        let destName = 'Hà Nội';
        if (lower.includes('đà nẵng') || lower.includes('da nang') || lower.includes('dad')) {
            destCode = 'DAD';
            destName = 'Đà Nẵng';
        } else if (lower.includes('sài gòn') || lower.includes('hồ chí minh') || lower.includes('sgn')) {
            destCode = 'SGN';
            destName = 'TP.HCM';
        }

        const originCode = destCode === 'SGN' ? 'HAN' : 'SGN';
        const departureDate = extractTargetDate(maskedText);
        const flights = MOCK_CATALOG.FLIGHT.filter(f => f.details.to === destCode);

        const messageText = `Em đã lọc các chuyến bay đến ${destName} (${destCode}) vào ngày ${departureDate}. Anh/chị tham khảo các hãng bên dưới nhé.`;
        const deepLink = `app://banking/utilities/flight?origin=${originCode}&dest=${destCode}&date=${departureDate}&passengers=1`;

        return {
            intent: 'Book_Flight',
            entities: {
                origin: originCode,
                destination: destCode,
                departure_date: departureDate,
                passenger_count: 1
            },
            message_text: messageText,
            cards: flights.map(f => ({
                card_id: f.catalog_item_id,
                title: f.title,
                subtitle: `Giờ khởi hành: ${f.details.departure} - Giá vé: ${f.price.toLocaleString('vi-VN')}đ`,
                price: f.price,
                action_deep_link: `${deepLink}&catalogItemId=${f.catalog_item_id}`
            })),
            action: {
                type: 'NAVIGATE',
                action_code: 'OPEN_FLIGHT_BOOKING',
                action_label: `Tìm chuyến bay đi ${destName}`,
                destination: 'app://booking/flights',
                action_deep_link: deepLink,
                params: {
                    origin: originCode,
                    destination: destCode,
                    departure_date: departureDate,
                    passenger_count: 1
                }
            },
            suggestions: ['Đặt khách sạn Mường Thanh', 'Mua vé xem phim CGV', 'Xem số dư']
        };
    }

    // =========================================================================
    // KỊCH BẢN 4: ĐẶT VÉ XEM PHIM CGV (Book_Movie / BOOK_ENTERTAINMENT)
    // =========================================================================
    if (
        lower.includes('xem phim') || lower.includes('vé phim') || lower.includes('ve phim') ||
        lower.includes('cgv') || lower.includes('venom') || lower.includes('lật mặt') || lower.includes('lat mat') ||
        lower.includes('rạp phim')
    ) {
        const isLatMat = lower.includes('lật mặt') || lower.includes('lat mat');
        const movieId = isLatMat ? 'CGV-LATMAT8' : 'M_VENOM_3';
        const movieName = isLatMat ? 'Lật Mặt 8: Vòng Tay Nắng' : 'Venom 3';
        const messageText = `Đây là lịch chiếu phim ${movieName} tại cụm rạp CGV gần anh/chị nhất. Anh/chị chọn suất chiếu và ghế ngồi nhé!`;
        const deepLink = `app://banking/utilities/movie?provider=CGV&movieId=${movieId}&sortBy=nearest_location`;

        return {
            intent: 'BOOK_ENTERTAINMENT',
            entities: {
                service: 'MOVIE',
                movie_id: movieId,
                cinema_chain: 'CGV',
                sort_by: 'nearest_location'
            },
            message_text: messageText,
            action: {
                type: 'NAVIGATE',
                action_code: 'OPEN_MOVIE_BOOKING',
                action_label: `Chọn ghế phim ${movieName} (CGV)`,
                destination: 'app://booking/movies',
                action_deep_link: deepLink,
                params: {
                    movie_id: movieId,
                    cinema_chain: 'CGV',
                    sort_by: 'nearest_location'
                }
            },
            suggestions: ['Đặt vé máy bay đi Hà Nội', 'Đặt khách sạn Mường Thanh', 'Tìm chi nhánh gần nhất']
        };
    }

    // =========================================================================
    // KỊCH BẢN 5: ĐẶT PHÒNG KHÁCH SẠN (Book_Hotel / BOOK_ENTERTAINMENT)
    // =========================================================================
    if (
        lower.includes('khách sạn') || lower.includes('khach san') ||
        lower.includes('mường thanh') || lower.includes('muong thanh') ||
        lower.includes('đặt phòng') || lower.includes('dat phong') || lower.includes('hotel')
    ) {
        const checkInDate = extractTargetDate(maskedText);
        const messageText = `Em đã tìm thấy danh sách phòng trống tại chuỗi Khách sạn Mường Thanh. Anh/chị bấm bên dưới để chọn hạng phòng Standard/Deluxe và nhận ưu đãi nhé!`;
        const deepLink = `app://banking/utilities/hotel?provider=MUONG_THANH&checkIn=${checkInDate}`;

        return {
            intent: 'BOOK_ENTERTAINMENT',
            entities: {
                service: 'HOTEL',
                provider: 'MUONG_THANH',
                check_in: checkInDate
            },
            message_text: messageText,
            action: {
                type: 'NAVIGATE',
                action_code: 'OPEN_HOTEL_BOOKING',
                action_label: 'Đặt phòng Khách sạn Mường Thanh',
                destination: 'app://booking/hotel',
                action_deep_link: deepLink,
                params: {
                    provider: 'MUONG_THANH',
                    catalog_item_id: 'HTL-MUONGTHANH-LUXURY-SGN',
                    check_in: checkInDate,
                    guests: 2
                }
            },
            suggestions: ['Đặt vé máy bay', 'Mua vé xem phim CGV', 'Xem số dư']
        };
    }

    // =========================================================================
    // KỊCH BẢN 6: MUA SẮM TRỰC TUYẾN / E-COMMERCE (Shopee, Lazada, Amazon)
    // =========================================================================
    if (
        lower.includes('shopee') || lower.includes('lazada') || lower.includes('amazon') ||
        lower.includes('mua sắm') || lower.includes('thanh toán sản phẩm') || lower.includes('sàn tmđt')
    ) {
        let platform = 'SHOPEE';
        if (lower.includes('lazada')) platform = 'LAZADA';
        else if (lower.includes('amazon')) platform = 'AMAZON';

        const messageText = `Em đã mở cổng thanh toán sản phẩm & mua sắm liên kết ${platform}. Anh/chị có thể áp dụng mã GIAM50K hoặc FREESHIP khi thanh toán nhé!`;
        const deepLink = `app://banking/utilities/ecommerce?platform=${platform}`;

        return {
            intent: 'ECOMMERCE_PAYMENT',
            entities: {
                service: 'ECOMMERCE',
                platform
            },
            message_text: messageText,
            action: {
                type: 'NAVIGATE',
                action_code: 'OPEN_ECOMMERCE_SHOP',
                action_label: `Mua sắm & Thanh toán ${platform}`,
                destination: 'app://utilities/ecommerce',
                action_deep_link: deepLink,
                params: {
                    platform,
                    recommended_voucher: 'GIAM50K'
                }
            },
            suggestions: ['Thanh toán tiền điện', 'Nạp tiền điện thoại', 'Xem số dư']
        };
    }

    // =========================================================================
    // KỊCH BẢN 7 & 8: TÌM CHI NHÁNH HOẶC CÂY ATM GẦN NHẤT & CHỈ ĐƯỜNG (Navigation)
    // =========================================================================
    if (
        lower.includes('chi nhánh') || lower.includes('chi nhanh') ||
        lower.includes('atm') || lower.includes('rút tiền') ||
        lower.includes('gần đây') || lower.includes('gần nhất') ||
        lower.includes('chỉ đường') || lower.includes('bản đồ') || lower.includes('pgd')
    ) {
        const isAtmOnly = lower.includes('atm') || lower.includes('cây rút tiền') || lower.includes('rút tiền mặt');
        const transport = extractTransportMode(maskedText);
        // Với ATM khẩn cấp, nếu người dùng không chỉ định phương tiện khác thì ưu tiên đi bộ (walking)
        const mode = isAtmOnly && !lower.includes('xe') && !lower.includes('ô tô') ? 'walking' : transport.mode;
        const modeLabel = mode === 'walking' ? 'đi bộ' : transport.label;

        const uLat = (userLocation && Number(userLocation.latitude)) || 10.776889;
        const uLng = (userLocation && Number(userLocation.longitude)) || 106.706139;

        const brRes = await pool.request().query(`SELECT * FROM dbo.bank_branches WHERE status = 'OPEN'`);
        const allPoints = brRes.recordset
            .filter(b => isAtmOnly ? ['ATM', 'BOTH'].includes(b.branch_type) : ['BRANCH', 'BOTH'].includes(b.branch_type))
            .map(b => {
                const distKm = calculateHaversineKm(uLat, uLng, Number(b.latitude), Number(b.longitude));
                return {
                    ...b,
                    distance_km: Number(distKm.toFixed(2)),
                    distance_meters: Math.round(distKm * 1000)
                };
            })
            .sort((a, b) => a.distance_km - b.distance_km);

        const nearest = allPoints[0] || {
            branch_id: 'br-q1-saigon',
            name: 'Chi nhánh Sài Gòn (Quận 1)',
            address: '2 Công trường Mê Linh, Quận 1, TP.HCM',
            latitude: 10.776889,
            longitude: 106.706139,
            distance_km: 0.5,
            distance_meters: 500,
            opening_hours: '08:00 - 17:00'
        };

        const nearbyList = allPoints.slice(0, 3);
        const cards = nearbyList.map(item => ({
            branch_id: item.branch_id,
            title: item.name,
            subtitle: `${item.address} (${item.distance_km} km)`,
            opening_hours: item.opening_hours,
            distance_km: item.distance_km,
            latitude: Number(item.latitude),
            longitude: Number(item.longitude),
            action_label: 'Chỉ đường ngay',
            action_deep_link: `app://banking/navigation/branches?branchId=${item.branch_id}&mode=${mode}&autoRoute=true`
        }));

        if (isAtmOnly) {
            const distDisplay = nearest.distance_meters < 1000 ? `${nearest.distance_meters}m` : `${nearest.distance_km}km`;
            const messageText = `Cây ATM gần nhất (${nearest.name}) cách đây khoảng ${distDisplay}. Em đã thiết lập đường ${modeLabel} trên bản đồ cho anh/chị.`;
            return {
                intent: 'Find_Nearest_ATM',
                entities: {
                    target_type: 'ATM',
                    transport_mode: mode,
                    nearest_branch_id: nearest.branch_id
                },
                message_text: messageText,
                cards,
                action: {
                    type: 'NAVIGATE',
                    action_code: 'OPEN_ATM_MAP',
                    action_label: `Chỉ đường ${modeLabel} đến ${nearest.name}`,
                    destination: 'app://navigation/atm-map',
                    action_deep_link: `app://banking/navigation/branches?type=ATM&mode=${mode}&autoRoute=true&branchId=${nearest.branch_id}`,
                    params: {
                        action_trigger: 'find_nearest',
                        target_type: 'ATM',
                        transport_mode: mode,
                        auto_draw_route: true,
                        nearest_branch_id: nearest.branch_id,
                        destination_lat: Number(nearest.latitude),
                        destination_lng: Number(nearest.longitude)
                    }
                },
                suggestions: ['Tìm chi nhánh giao dịch', 'Đi xe máy tới chi nhánh', 'Xem số dư']
            };
        }

        const messageText = `Có ${nearbyList.length} chi nhánh gần vị trí của anh/chị. Em đã vẽ sẵn đường ${modeLabel} đến ${nearest.name} gần nhất (${nearest.distance_km}km), anh/chị đi theo bản đồ nhé!`;
        return {
            intent: 'Find_Nearest_Branch',
            entities: {
                target_type: 'BRANCH',
                transport_mode: mode,
                nearest_branch_id: nearest.branch_id
            },
            message_text: messageText,
            cards,
            action: {
                type: 'NAVIGATE',
                action_code: 'OPEN_BRANCH_MAP',
                action_label: `Chỉ đường ${modeLabel} đến ${nearest.name}`,
                destination: 'app://navigation/branch-map',
                action_deep_link: `app://banking/navigation/branches?mode=${mode}&autoRoute=true&branchId=${nearest.branch_id}`,
                params: {
                    action_trigger: 'find_nearest',
                    transport_mode: mode,
                    auto_draw_route: true,
                    nearest_branch_id: nearest.branch_id,
                    destination_lat: Number(nearest.latitude),
                    destination_lng: Number(nearest.longitude)
                }
            },
            suggestions: ['Tìm cây ATM gần nhất', 'Đổi sang đường đi bộ', 'Thanh toán tiền điện']
        };
    }

    // =========================================================================
    // KỊCH BẢN 9: CHUYỂN TIỀN (Transfer_Money / TRANSFER_MONEY)
    // Ví dụ: "Cho tôi chuyển 500 cành cho mẹ"
    // =========================================================================
    if (lower.includes('chuyển') || lower.includes('chuyen') || lower.includes('transfer') || lower.includes('bắn tiền')) {
        const amount = extractVietnameseAmount(maskedText) || 500000;

        // Tìm người nhận trong danh bạ thụ hưởng
        let matchedBeneficiary = SAVED_BENEFICIARIES[0]; // Mặc định là Mẹ (Nguyễn Thị B)
        for (const ben of SAVED_BENEFICIARIES) {
            if (ben.alias.some(a => lower.includes(a))) {
                matchedBeneficiary = ben;
                break;
            }
        }

        // Kiểm tra số dư khả dụng trong bảng accounts (Bước 3 - Backend Context Validation)
        const accRes = await pool.request()
            .input('user_id', sql.NVarChar(36), user.user_id)
            .query(`SELECT TOP 1 * FROM dbo.accounts WHERE user_id = @user_id AND account_type = 'CHECKING' AND status = 'ACTIVE'`);
        const checkingAcc = accRes.recordset[0];
        const currentBalance = checkingAcc ? Number(checkingAcc.balance) : 50000000;
        const hasEnoughBalance = currentBalance >= amount;

        const formattedAmount = amount.toLocaleString('vi-VN');
        const messageText = hasEnoughBalance
            ? `Em đã chuẩn bị sẵn lệnh chuyển ${formattedAmount} VNĐ cho ${matchedBeneficiary.receiver_display_name} (${matchedBeneficiary.dest_bank_name} - ${matchedBeneficiary.dest_account_number}). Anh/chị bấm nút bên dưới để kiểm tra và xác nhận giao dịch nhé!`
            : `Tài khoản thanh toán của anh/chị hiện còn ${currentBalance.toLocaleString('vi-VN')} VNĐ, chưa đủ để chuyển ${formattedAmount} VNĐ cho ${matchedBeneficiary.receiver_display_name}. Anh/chị có muốn điều chỉnh lại số tiền không ạ?`;

        const deepLink = `app://banking/transfer?amount=${amount}&destAccount=${matchedBeneficiary.dest_account_number}&bankCode=${matchedBeneficiary.dest_bank_code}&receiverName=${encodeURIComponent(matchedBeneficiary.receiver_name)}`;

        return {
            intent: 'TRANSFER_MONEY',
            entities: {
                amount,
                recipient_alias: matchedBeneficiary.receiver_display_name,
                dest_account_number: matchedBeneficiary.dest_account_number,
                dest_bank_code: matchedBeneficiary.dest_bank_code,
                receiver_name: matchedBeneficiary.receiver_name,
                balance_sufficient: hasEnoughBalance
            },
            message_text: messageText,
            action: {
                type: 'NAVIGATE',
                action_code: 'OPEN_TRANSFER_SCREEN',
                action_label: `Chuyển ${formattedAmount}đ cho ${matchedBeneficiary.receiver_display_name}`,
                destination: 'app://banking/transfer',
                action_deep_link: deepLink,
                params: {
                    amount,
                    dest_account_number: matchedBeneficiary.dest_account_number,
                    dest_bank_code: matchedBeneficiary.dest_bank_code,
                    receiver_name: matchedBeneficiary.receiver_name
                }
            },
            suggestions: ['Kiểm tra số dư', 'Lịch sử giao dịch', 'Hủy yêu cầu']
        };
    }

    // =========================================================================
    // KỊCH BẢN 10: TRA CỨU LÃI SUẤT TIẾT KIỆM (ASK_SAVING_RATE - RAG / DB Lookup)
    // =========================================================================
    if (lower.includes('lãi suất') || lower.includes('lai suat') || lower.includes('tiết kiệm') || lower.includes('tiet kiem')) {
        const termMatch = lower.match(/(\d{1,2})\s*tháng/);
        const termMonths = termMatch ? parseInt(termMatch[1], 10) : 6;

        const rateRes = await pool.request()
            .input('term_months', sql.Int, termMonths)
            .query(`SELECT TOP 1 * FROM dbo.saving_rates WHERE term_months = @term_months`);

        const rateRow = rateRes.recordset[0] || { term_months: termMonths, interest_rate: 5.1 };
        const ratePercent = Number(rateRow.interest_rate).toFixed(2);
        const messageText = `Lãi suất gửi tiết kiệm trực tuyến kỳ hạn ${rateRow.term_months} tháng hiện tại của Ngân hàng là ${ratePercent}%/năm. Anh/chị có muốn mở sổ tiết kiệm ngay để hưởng lãi suất ưu đãi không ạ?`;
        const deepLink = `app://banking/saving/open?term=${rateRow.term_months}&rate=${ratePercent}`;

        return {
            intent: 'ASK_SAVING_RATE',
            entities: {
                term_months: rateRow.term_months,
                interest_rate: Number(ratePercent)
            },
            message_text: messageText,
            action: {
                type: 'NAVIGATE',
                action_code: 'OPEN_SAVING_ACCOUNT',
                action_label: `Mở sổ tiết kiệm ${rateRow.term_months} tháng (${ratePercent}%/năm)`,
                destination: 'app://banking/saving/open',
                action_deep_link: deepLink,
                params: {
                    term_months: rateRow.term_months,
                    interest_rate: Number(ratePercent)
                }
            },
            suggestions: ['Lãi suất kỳ hạn 12 tháng', 'Xem số dư tài khoản', 'Tìm chi nhánh gần nhất']
        };
    }

    // =========================================================================
    // KỊCH BẢN 11: KIỂM TRA SỐ DƯ TÀI KHOẢN (CHECK_BALANCE)
    // =========================================================================
    if (lower.includes('số dư') || lower.includes('so du') || lower.includes('còn bao nhiêu tiền') || lower.includes('tài khoản của tôi')) {
        const accRes = await pool.request()
            .input('user_id', sql.NVarChar(36), user.user_id)
            .query(`SELECT account_number, account_type, balance, currency FROM dbo.accounts WHERE user_id = @user_id AND status = 'ACTIVE'`);

        const checking = accRes.recordset.find(a => a.account_type === 'CHECKING') || accRes.recordset[0];
        const balanceStr = checking ? Number(checking.balance).toLocaleString('vi-VN') : '50.000.000';
        const accNum = checking ? checking.account_number : '190088889999';

        const messageText = `Tài khoản thanh toán (${accNum}) của anh/chị hiện có số dư khả dụng là ${balanceStr} VNĐ.`;
        return {
            intent: 'CHECK_BALANCE',
            entities: {
                account_number: accNum,
                balance: checking ? Number(checking.balance) : 50000000
            },
            message_text: messageText,
            action: {
                type: 'NAVIGATE',
                action_code: 'OPEN_ACCOUNT_DETAIL',
                action_label: 'Xem chi tiết tài khoản & lịch sử',
                destination: 'app://banking/accounts',
                action_deep_link: `app://banking/accounts?accountNumber=${accNum}`,
                params: {
                    account_number: accNum
                }
            },
            suggestions: ['Thanh toán tiền điện tháng này', 'Chuyển 500 cành cho mẹ', 'Nạp tiền điện thoại']
        };
    }

    // =========================================================================
    // KỊCH BẢN 12: FALLBACK THÔNG MINH KHI CHƯA RÕ Ý ĐỊNH (UC-C01 3a)
    // =========================================================================
    return {
        intent: 'FALLBACK_UNKNOWN',
        entities: {},
        message_text: 'Xin lỗi, em chưa rõ ý anh/chị. Anh/chị đang muốn hỗ trợ về Chuyển tiền, Thanh toán Tiện ích, Đặt vé giải trí hay Tìm đường đến chi nhánh gần nhất ạ?',
        action: null,
        suggestions: [
            'Thanh toán tiền điện tháng này giúp tôi',
            'Chi nhánh nào gần đây nhất?',
            'Cho tôi chuyển 500 cành cho mẹ',
            'Đặt vé máy bay đi Hà Nội cuối tuần này'
        ]
    };
}

// ======================= API ENDPOINTS CHO AI CHATBOT =======================

/**
 * GET /v1/chatbot/suggestions
 * Lấy danh sách Suggestion Chips (Gợi ý thao tác nhanh) theo ngữ cảnh của user
 */
router.get('/suggestions', authenticateJWT, (req, res) => {
    return res.status(200).json({
        suggestions: DEFAULT_SUGGESTION_CHIPS
    });
});

/**
 * POST /v1/chatbot/sessions
 * Khởi tạo một phiên trò chuyện mới với AI Chatbot (chatbot_sessions theo ERD_new.html)
 */
router.post('/sessions', authenticateJWT, async (req, res) => {
    try {
        const pool = await getPool();
        const sessionId = uuidv4();

        await pool.request()
            .input('session_id', sql.NVarChar(36), sessionId)
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`
                INSERT INTO dbo.chatbot_sessions (session_id, user_id, status)
                VALUES (@session_id, @user_id, 'ACTIVE')
            `);

        // Tạo tin nhắn chào mừng từ BOT
        const welcomeId = uuidv4();
        const welcomeText = 'Xin chào anh/chị! Em là Trợ lý ảo Ngân hàng số AI. Em có thể giúp anh/chị thanh toán hóa đơn điện/nước, nạp điện thoại, đặt vé máy bay/xem phim/khách sạn, chuyển tiền nhanh hoặc chỉ đường đến chi nhánh/ATM gần nhất ạ!';

        await pool.request()
            .input('message_id', sql.NVarChar(36), welcomeId)
            .input('session_id', sql.NVarChar(36), sessionId)
            .input('sender', sql.NVarChar(10), 'BOT')
            .input('input_type', sql.NVarChar(20), 'TEXT')
            .input('message_text', sql.NVarChar(sql.MAX), welcomeText)
            .input('detected_intent', sql.NVarChar(50), 'WELCOME')
            .input('action_type', sql.NVarChar(20), 'NONE')
            .query(`
                INSERT INTO dbo.chat_messages (message_id, session_id, sender, input_type, message_text, detected_intent, action_type)
                VALUES (@message_id, @session_id, @sender, @input_type, @message_text, @detected_intent, @action_type)
            `);

        return res.status(201).json({
            session_id: sessionId,
            status: 'ACTIVE',
            welcome_message: {
                message_id: welcomeId,
                sender: 'BOT',
                message: welcomeText,
                message_text: welcomeText
            },
            suggestions: DEFAULT_SUGGESTION_CHIPS
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi khởi tạo phiên trò chuyện Chatbot' });
    }
});

/**
 * GET /v1/chatbot/sessions
 * Danh sách các phiên trò chuyện của người dùng
 */
router.get('/sessions', authenticateJWT, async (req, res) => {
    try {
        const pool = await getPool();
        const result = await pool.request()
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`
                SELECT session_id, user_id, status, started_at, ended_at
                FROM dbo.chatbot_sessions
                WHERE user_id = @user_id
                ORDER BY started_at DESC
            `);
        return res.status(200).json({
            items: result.recordset,
            total: result.recordset.length
        });
    } catch (err) {
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy danh sách phiên chat' });
    }
});

/**
 * GET /v1/chatbot/sessions/:session_id/messages
 * Lấy lịch sử tin nhắn (chat_messages) của một phiên trò chuyện
 */
router.get('/sessions/:session_id/messages', authenticateJWT, async (req, res) => {
    const { session_id } = req.params;
    try {
        const pool = await getPool();
        const sessCheck = await pool.request()
            .input('session_id', sql.NVarChar(36), session_id)
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`SELECT * FROM dbo.chatbot_sessions WHERE session_id = @session_id AND user_id = @user_id`);

        if (sessCheck.recordset.length === 0) {
            return res.status(404).json({ code: 'SESSION_NOT_FOUND', message: 'Không tìm thấy phiên trò chuyện' });
        }

        const msgRes = await pool.request()
            .input('session_id', sql.NVarChar(36), session_id)
            .query(`
                SELECT * FROM dbo.chat_messages
                WHERE session_id = @session_id
                ORDER BY created_at ASC
            `);

        const items = msgRes.recordset.map(m => {
            const parsedParams = m.action_params_json ? JSON.parse(m.action_params_json) : {};
            return {
                message_id: m.message_id,
                session_id: m.session_id,
                sender: m.sender,
                input_type: m.input_type,
                message: m.message_text,
                message_text: m.message_text,
                intent: m.detected_intent,
                entities: m.extracted_entities_json ? JSON.parse(m.extracted_entities_json) : null,
                action: m.action_type === 'NAVIGATE' ? {
                    type: m.action_type,
                    action_code: parsedParams._action_code || null,
                    action_label: parsedParams._action_label || null,
                    destination: m.action_destination,
                    action_deep_link: parsedParams._action_deep_link || m.action_destination,
                    params: parsedParams
                } : null,
                created_at: m.created_at
            };
        });

        return res.status(200).json({
            session_id,
            status: sessCheck.recordset[0].status,
            items,
            total: items.length
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi lấy lịch sử tin nhắn' });
    }
});

/**
 * POST /v1/chatbot/sessions/:session_id/end
 * Kết thúc phiên trò chuyện
 */
router.post('/sessions/:session_id/end', authenticateJWT, async (req, res) => {
    const { session_id } = req.params;
    try {
        const pool = await getPool();
        await pool.request()
            .input('session_id', sql.NVarChar(36), session_id)
            .input('user_id', sql.NVarChar(36), req.user.user_id)
            .query(`
                UPDATE dbo.chatbot_sessions
                SET status = 'ENDED', ended_at = SYSUTCDATETIME()
                WHERE session_id = @session_id AND user_id = @user_id
            `);
        return res.status(200).json({ session_id, status: 'ENDED' });
    } catch (err) {
        return res.status(500).json({ code: 'INTERNAL_ERROR', message: 'Lỗi kết thúc phiên chat' });
    }
});

/**
 * Truy vấn ngữ cảnh thực tế từ SQL Server để truyền kèm vào Gemini API
 * (danh sách dịch vụ tiện ích, hóa đơn chưa thanh toán, chi nhánh/ATM gần nhất, số dư, lãi suất)
 */
async function loadChatbotDbContext(pool, user, userLocation) {
    if (!pool) return {};
    const uLat = (userLocation && Number(userLocation.latitude)) || 10.776889;
    const uLng = (userLocation && Number(userLocation.longitude)) || 106.706139;
    const userId = user && user.user_id ? user.user_id : 'u1111111-1111-1111-1111-111111111111';

    try {
        const [servicesRes, billsRes, branchesRes, accRes, ratesRes] = await Promise.all([
            pool.request().query(`SELECT service_id, provider_code, service_name, service_type, aggregator_gateway, status FROM dbo.utility_services WHERE status = 'ACTIVE'`),
            pool.request()
                .input('user_id', sql.NVarChar(36), userId)
                .query(`
                    SELECT uo.order_id, uo.provider_customer_id, uo.total_amount, us.service_type, us.provider_code
                    FROM dbo.utility_orders uo
                    JOIN dbo.utility_services us ON uo.service_id = us.service_id
                    WHERE uo.user_id = @user_id AND uo.status IN ('PENDING', 'UNPAID')
                    ORDER BY uo.created_at DESC
                `),
            pool.request().query(`SELECT branch_id, name, branch_type, address, latitude, longitude, opening_hours, status FROM dbo.bank_branches WHERE status = 'OPEN'`),
            pool.request()
                .input('user_id', sql.NVarChar(36), userId)
                .query(`SELECT TOP 1 account_id, account_number, account_type, balance, currency FROM dbo.accounts WHERE user_id = @user_id AND account_type = 'CHECKING' AND status = 'ACTIVE'`),
            pool.request().query(`SELECT term_months, interest_rate FROM dbo.saving_rates ORDER BY term_months ASC`)
        ]);

        const nearbyBranches = branchesRes.recordset
            .map(b => {
                const distKm = calculateHaversineKm(uLat, uLng, Number(b.latitude), Number(b.longitude));
                return {
                    ...b,
                    distance_km: Number(distKm.toFixed(2)),
                    distance_meters: Math.round(distKm * 1000)
                };
            })
            .sort((a, b) => a.distance_km - b.distance_km);

        return {
            services: servicesRes.recordset,
            unpaidBills: billsRes.recordset,
            nearbyBranches: nearbyBranches.slice(0, 5),
            allBranches: nearbyBranches,
            checkingAccount: accRes.recordset[0] || null,
            savingRates: ratesRes.recordset
        };
    } catch (err) {
        console.warn('⚠️ Không thể tải đầy đủ DB context cho Gemini:', err.message);
        return {};
    }
}

/**
 * Chuẩn hóa kết quả Structured Output từ Gemini thành định dạng ChatbotResponse kèm ChatbotAction & Deep Link
 */
function buildChatbotResultFromGemini(geminiResult, dbContext = {}, rawText = '') {
    const intent = geminiResult.intent || 'GENERAL_HELP';
    const isNavIntent = ['Find_Nearest_ATM', 'Find_Nearest_Branch', 'FIND_BRANCH', 'FIND_NEAREST_BRANCH'].includes(intent)
        || (geminiResult.deep_link && geminiResult.deep_link.includes('navigation'))
        || (geminiResult.tool_title && geminiResult.tool_title.toLowerCase().includes('chi nhánh'));

    let toolTitle = geminiResult.tool_title || null;
    let actionLabel = geminiResult.action_label || null;
    let deepLink = geminiResult.deep_link || null;
    let cards = [];
    const params = {};

    if (isNavIntent) {
        const extractedTransport = extractTransportMode(rawText);
        const mode = ['walking', 'two_wheeler', 'driving', 'transit'].includes(geminiResult.transport_mode)
            ? geminiResult.transport_mode
            : extractedTransport.mode;
        const lowerRaw = String(rawText || '').toLowerCase();
        const isAtm = geminiResult.target_type === 'ATM'
            || intent === 'Find_Nearest_ATM'
            || lowerRaw.includes('atm')
            || lowerRaw.includes('rút tiền');
        const targetType = isAtm ? 'ATM' : (geminiResult.target_type || 'BOTH');

        toolTitle = 'Chi nhánh & ATM';
        actionLabel = actionLabel || 'Mở Chi nhánh & ATM';

        const allBranches = Array.isArray(dbContext.allBranches) ? dbContext.allBranches : [];
        const filteredBranches = allBranches.filter(b =>
            targetType === 'ATM' ? ['ATM', 'BOTH'].includes(b.branch_type) : true
        );
        const topBranches = (filteredBranches.length > 0 ? filteredBranches : allBranches).slice(0, 3);
        const nearest = topBranches[0] || null;

        deepLink = `app://navigation/branch-map?mode=${mode}&type=${targetType}&autoRoute=true`
            + (nearest ? `&branchId=${nearest.branch_id}` : '');

        params.action_trigger = 'find_nearest';
        params.target_type = targetType;
        params.transport_mode = mode;
        params.auto_draw_route = true;
        if (nearest) {
            params.nearest_branch_id = nearest.branch_id;
            params.destination_lat = Number(nearest.latitude);
            params.destination_lng = Number(nearest.longitude);
        }

        cards = topBranches.map(item => ({
            branch_id: item.branch_id,
            title: item.name,
            subtitle: `${item.address} (${item.distance_km} km)`,
            opening_hours: item.opening_hours,
            distance_km: item.distance_km,
            latitude: Number(item.latitude),
            longitude: Number(item.longitude),
            action_label: 'Chỉ đường ngay',
            action_deep_link: `app://navigation/branch-map?branchId=${item.branch_id}&mode=${mode}&type=${targetType}&autoRoute=true`
        }));
    }

    const action = (deepLink || toolTitle) ? {
        type: 'NAVIGATE',
        action_code: isNavIntent ? (params.target_type === 'ATM' ? 'OPEN_ATM_MAP' : 'OPEN_BRANCH_MAP') : `OPEN_${intent.toUpperCase()}`,
        action_label: actionLabel || (toolTitle ? `Mở ${toolTitle}` : 'Mở tiện ích'),
        tool_title: toolTitle,
        destination: deepLink ? deepLink.split('?')[0] : 'app://utilities/dashboard',
        action_deep_link: deepLink || 'app://utilities/dashboard',
        params
    } : null;

    return {
        intent,
        tool_title: toolTitle,
        entities: {
            ...(geminiResult.transport_mode ? { transport_mode: geminiResult.transport_mode } : {}),
            ...(geminiResult.target_type ? { target_type: geminiResult.target_type } : {}),
            ...(toolTitle ? { tool_title: toolTitle } : {})
        },
        message_text: geminiResult.reply,
        cards,
        action,
        suggestions: (geminiResult.suggestions && geminiResult.suggestions.length > 0)
            ? geminiResult.suggestions
            : DEFAULT_SUGGESTION_CHIPS.slice(0, 4),
        ai_provider: 'gemini',
        ai_model: geminiResult.model || geminiService.GEMINI_MODEL
    };
}

/**
 * POST /v1/chat & POST /v1/chatbot/message (và POST /v1/chatbot/sessions/:session_id/messages)
 * Gửi câu hỏi tới AI Chatbot -> Guardrails/PII -> Tải ngữ cảnh DB -> Gọi gemini.service -> Trả lời kèm nút điều hướng Deep Link
 */
const handleChatMessage = async (req, res) => {
    const sessionIdParam = req.params.session_id || req.body.session_id;
    const rawMessage = req.body.message || req.body.message_text || req.body.text;
    const inputType = (req.body.input_type || 'TEXT').toUpperCase();
    const userLocation = req.body.user_location || null;

    if (!rawMessage || String(rawMessage).trim() === '') {
        return res.status(400).json({ code: 'EMPTY_MESSAGE', message: 'Vui lòng nhập nội dung câu hỏi cho Chatbot' });
    }

    const validInputTypes = ['TEXT', 'VOICE', 'SUGGESTION_CHIP'];
    const normalizedInputType = validInputTypes.includes(inputType) ? inputType : 'TEXT';
    const currentUser = req.user || { user_id: 'u1111111-1111-1111-1111-111111111111', username: 'nguyenvana', role: 'CUSTOMER' };

    try {
        let pool = null;
        try {
            pool = await getPool();
        } catch (dbErr) {
            console.warn('⚠️ Không thể kết nối SQL Server, tiếp tục xử lý Gemini ở chế độ không lưu DB:', dbErr.message);
        }

        // 1. Đảm bảo có session_id hợp lệ trong bảng chatbot_sessions
        let sessionId = sessionIdParam || uuidv4();
        if (pool) {
            const checkSess = await pool.request()
                .input('session_id', sql.NVarChar(36), sessionId)
                .query(`SELECT session_id FROM dbo.chatbot_sessions WHERE session_id = @session_id`);
            if (checkSess.recordset.length === 0) {
                await pool.request()
                    .input('session_id', sql.NVarChar(36), sessionId)
                    .input('user_id', sql.NVarChar(36), currentUser.user_id)
                    .query(`INSERT INTO dbo.chatbot_sessions (session_id, user_id, status) VALUES (@session_id, @user_id, 'ACTIVE')`);
            }
        }

        // 2. Lưu tin nhắn của USER (đã qua PII Masking để bảo vệ dữ liệu nhạy cảm) vào bảng chat_messages
        const maskedUserText = maskSensitivePII(rawMessage);
        if (pool) {
            const userMsgId = uuidv4();
            await pool.request()
                .input('message_id', sql.NVarChar(36), userMsgId)
                .input('session_id', sql.NVarChar(36), sessionId)
                .input('sender', sql.NVarChar(10), 'USER')
                .input('input_type', sql.NVarChar(20), normalizedInputType)
                .input('message_text', sql.NVarChar(sql.MAX), maskedUserText)
                .input('action_type', sql.NVarChar(20), 'NONE')
                .query(`
                    INSERT INTO dbo.chat_messages (message_id, session_id, sender, input_type, message_text, action_type)
                    VALUES (@message_id, @session_id, @sender, @input_type, @message_text, @action_type)
                `);
        }

        // 3. Kiểm tra bảo mật chống Prompt Injection & ghi vào security_audit_logs
        const injectionCheck = checkPromptInjection(rawMessage);
        if (injectionCheck.blocked) {
            const botMsgId = uuidv4();
            const securityReply = 'Yêu cầu của anh/chị chứa cú pháp không được phép theo chính sách bảo mật của Ngân hàng. Em chỉ hỗ trợ các nghiệp vụ ngân hàng, thanh toán tiện ích và tìm kiếm chi nhánh hợp lệ ạ.';
            if (pool) {
                await pool.request()
                    .input('message_id', sql.NVarChar(36), botMsgId)
                    .input('session_id', sql.NVarChar(36), sessionId)
                    .input('sender', sql.NVarChar(10), 'BOT')
                    .input('input_type', sql.NVarChar(20), 'TEXT')
                    .input('message_text', sql.NVarChar(sql.MAX), securityReply)
                    .input('detected_intent', sql.NVarChar(50), 'SECURITY_BLOCKED_PROMPT_INJECTION')
                    .input('action_type', sql.NVarChar(20), 'NONE')
                    .query(`
                        INSERT INTO dbo.chat_messages (message_id, session_id, sender, input_type, message_text, detected_intent, action_type)
                        VALUES (@message_id, @session_id, @sender, @input_type, @message_text, @detected_intent, @action_type)
                    `);

                await pool.request()
                    .input('log_id', sql.NVarChar(36), uuidv4())
                    .input('actor_id', sql.NVarChar(36), currentUser.user_id)
                    .input('entity_id', sql.NVarChar(100), sessionId)
                    .input('details', sql.NVarChar(sql.MAX), `Chặn Prompt Injection từ người dùng (${injectionCheck.matched})`)
                    .query(`
                        INSERT INTO dbo.security_audit_logs (log_id, actor_id, action, entity_name, entity_id, status, details)
                        VALUES (@log_id, @actor_id, 'BLOCK_PROMPT_INJECTION', 'chatbot_sessions', @entity_id, 'BLOCKED', @details)
                    `);
            }

            return res.status(200).json({
                session_id: sessionId,
                message_id: botMsgId,
                sender: 'BOT',
                message: securityReply,
                message_text: securityReply,
                intent: 'SECURITY_BLOCKED_PROMPT_INJECTION',
                security_guardrail_triggered: true,
                action: null,
                suggestions: DEFAULT_SUGGESTION_CHIPS.slice(0, 4)
            });
        }

        // 4. Tải ngữ cảnh thực tế từ DB & Gọi Gemini API thông qua gemini.service.js
        const dbContext = await loadChatbotDbContext(pool, currentUser, userLocation);
        let nlpResult = null;

        if (geminiService.isGeminiConfigured()) {
            try {
                const geminiRes = await geminiService.generateBankingAssistantReply({
                    userMessage: maskedUserText,
                    sessionId,
                    dbContext
                });
                if (geminiRes) {
                    nlpResult = buildChatbotResultFromGemini(geminiRes, dbContext, maskedUserText);
                }
            } catch (geminiErr) {
                console.error('⚠️ Gemini API error, chuyển sang fallback NLP cục bộ:', geminiErr.message);
            }
        }

        // Fallback về bộ NLP cục bộ nếu Gemini API chưa cấu hình hoặc gặp lỗi mạng/quota
        if (!nlpResult) {
            if (pool) {
                nlpResult = await processChatbotNLP(pool, currentUser, rawMessage, userLocation);
            } else {
                nlpResult = {
                    intent: 'FALLBACK_UNKNOWN',
                    entities: {},
                    message_text: 'Hệ thống AI tạm thời gián đoạn. Anh/chị vui lòng thử lại sau ít phút nhé!',
                    action: null,
                    suggestions: DEFAULT_SUGGESTION_CHIPS.slice(0, 4)
                };
            }
            nlpResult.ai_provider = 'fallback_nlp';
        }

        // 5. Lưu phản hồi của BOT kèm lệnh điều hướng vào bảng chat_messages (đúng 11 cột trong ERD_new.html)
        const botMsgId = uuidv4();
        const hasAction = Boolean(nlpResult.action);
        const actionParamsPayload = hasAction ? {
            ...(nlpResult.action.params || {}),
            _action_code: nlpResult.action.action_code,
            _action_label: nlpResult.action.action_label,
            _action_deep_link: nlpResult.action.action_deep_link,
            _tool_title: nlpResult.action.tool_title || nlpResult.tool_title || null
        } : null;

        if (pool) {
            await pool.request()
                .input('message_id', sql.NVarChar(36), botMsgId)
                .input('session_id', sql.NVarChar(36), sessionId)
                .input('sender', sql.NVarChar(10), 'BOT')
                .input('input_type', sql.NVarChar(20), 'TEXT')
                .input('message_text', sql.NVarChar(sql.MAX), nlpResult.message_text)
                .input('detected_intent', sql.NVarChar(50), nlpResult.intent)
                .input('extracted_entities_json', sql.NVarChar(sql.MAX), JSON.stringify(nlpResult.entities || {}))
                .input('action_type', sql.NVarChar(20), hasAction ? 'NAVIGATE' : 'NONE')
                .input('action_destination', sql.NVarChar(200), hasAction ? nlpResult.action.destination : null)
                .input('action_params_json', sql.NVarChar(sql.MAX), actionParamsPayload ? JSON.stringify(actionParamsPayload) : null)
                .query(`
                    INSERT INTO dbo.chat_messages (
                        message_id, session_id, sender, input_type, message_text,
                        detected_intent, extracted_entities_json, action_type,
                        action_destination, action_params_json
                    )
                    VALUES (
                        @message_id, @session_id, @sender, @input_type, @message_text,
                        @detected_intent, @extracted_entities_json, @action_type,
                        @action_destination, @action_params_json
                    )
                `);
        }

        return res.status(200).json({
            session_id: sessionId,
            message_id: botMsgId,
            sender: 'BOT',
            message: nlpResult.message_text,
            message_text: nlpResult.message_text,
            intent: nlpResult.intent,
            tool_title: nlpResult.tool_title || (nlpResult.action && nlpResult.action.tool_title) || null,
            entities: nlpResult.entities,
            cards: nlpResult.cards || [],
            action: nlpResult.action,
            suggestions: nlpResult.suggestions || DEFAULT_SUGGESTION_CHIPS.slice(0, 4),
            ai_provider: nlpResult.ai_provider || 'gemini',
            ai_model: nlpResult.ai_model || null,
            created_at: new Date().toISOString()
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ code: 'CHATBOT_ERROR', message: 'Lỗi xử lý tin nhắn AI Chatbot: ' + err.message });
    }
};

router.post('/message', optionalAuthenticateJWT, handleChatMessage);
router.post('/sessions/:session_id/messages', optionalAuthenticateJWT, handleChatMessage);

router.handleChatMessage = handleChatMessage;
module.exports = router;
