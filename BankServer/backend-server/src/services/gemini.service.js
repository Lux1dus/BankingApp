const path = require('path');
require('dotenv').config({ path: path.resolve(__dirname, '../../.env') });

const GEMINI_API_KEY = process.env.GEMINI_API_KEY || '';
const GEMINI_MODEL = process.env.GEMINI_MODEL || 'gemini-3.5-flash-lite';

// Danh sách tiện ích & màn hình chuẩn của App Bank (khớp với MainActivity.kt)
const APP_UTILITIES_CATALOG = [
    {
        tool_title: 'Chi nhánh & ATM',
        action_label: 'Mở Chi nhánh & ATM',
        intent_examples: ['Find_Nearest_ATM', 'Find_Nearest_Branch'],
        deep_link_template: 'app://navigation/branch-map?mode={walking|two_wheeler|driving|transit}&type={ATM|BRANCH|BOTH}&autoRoute=true',
        description: 'Tìm cây ATM / chi nhánh ngân hàng gần nhất, hiển thị khoảng cách và chỉ đường theo phương tiện (walking = đi bộ, two_wheeler = xe máy, driving = ô tô, transit = xe buýt).'
    },
    {
        tool_title: 'Nhắc hóa đơn',
        action_label: 'Mở Thanh toán hóa đơn',
        intent_examples: ['Pay_Utility_Bill'],
        deep_link_template: 'app://utilities/bill-payment?type={ELECTRICITY|WATER}',
        description: 'Tra cứu và thanh toán hóa đơn tiền điện (EVNHCMC), tiền nước (SAWACO) từ danh sách hóa đơn chờ thanh toán.'
    },
    {
        tool_title: 'Nạp điện thoại',
        action_label: 'Mở Nạp điện thoại',
        intent_examples: ['TOPUP_PHONE'],
        deep_link_template: 'app://utilities/mobile-topup?phone={phone}&amount={amount}',
        description: 'Nạp tiền điện thoại trả trước/trả sau tự động nhận diện nhà mạng Viettel, VinaPhone, MobiFone.'
    },
    {
        tool_title: 'Đặt vé máy bay',
        action_label: 'Mở Đặt vé máy bay',
        intent_examples: ['Book_Flight'],
        deep_link_template: 'app://booking/flights?origin={SGN}&dest={HAN}&date={YYYY-MM-DD}',
        description: 'Tìm kiếm chuyến bay nội địa (Vietnam Airlines, Vietjet Air), chọn sân bay IATA, giữ chỗ và thanh toán.'
    },
    {
        tool_title: 'Vé xem phim CGV',
        action_label: 'Mở Vé xem phim CGV',
        intent_examples: ['BOOK_ENTERTAINMENT'],
        deep_link_template: 'app://booking/movies?provider=CGV',
        description: 'Xem lịch chiếu rạp CGV, chọn sơ đồ ghế ngồi và đặt vé xem phim.'
    },
    {
        tool_title: 'Đặt khách sạn',
        action_label: 'Mở Đặt khách sạn',
        intent_examples: ['BOOK_ENTERTAINMENT'],
        deep_link_template: 'app://booking/hotel?provider=MUONG_THANH',
        description: 'Tìm kiếm và đặt phòng khách sạn (Mường Thanh, Rex Hotel).'
    },
    {
        tool_title: 'Mua sắm TMĐT',
        action_label: 'Mở Mua sắm TMĐT',
        intent_examples: ['ECOMMERCE_PAYMENT'],
        deep_link_template: 'app://utilities/ecommerce?platform={SHOPEE|LAZADA|AMAZON}',
        description: 'Thanh toán đơn hàng và voucher mua sắm Shopee, Lazada, Amazon.'
    },
    {
        tool_title: 'Tính khoản vay',
        action_label: 'Mở Tính khoản vay',
        intent_examples: ['LOAN_CALCULATOR'],
        deep_link_template: 'app://utilities/loan-calculator',
        description: 'Ước tính tiền trả góp hàng tháng theo số tiền vay và kỳ hạn 1-25 năm.'
    },
    {
        tool_title: 'Đổi ngoại tệ',
        action_label: 'Mở Đổi ngoại tệ',
        intent_examples: ['FX_CONVERTER'],
        deep_link_template: 'app://utilities/fx',
        description: 'Tra cứu tỷ giá ngoại tệ trực tiếp.'
    },
    {
        tool_title: 'Ghi chú nhanh',
        action_label: 'Mở Ghi chú nhanh',
        intent_examples: ['QUICK_NOTE'],
        deep_link_template: 'app://utilities/notes',
        description: 'Lưu nhanh danh sách việc tài chính cần làm.'
    }
];

// JSON Schema chuẩn cho Structured Output của Gemini Interactions API
const CHATBOT_RESPONSE_SCHEMA = {
    type: 'object',
    properties: {
        reply: {
            type: 'string',
            description: 'Câu trả lời tự nhiên, thân thiện bằng tiếng Việt hướng dẫn khách hàng sử dụng ứng dụng NEXA Bank.'
        },
        intent: {
            type: 'string',
            description: 'Mã ý định: Find_Nearest_ATM, Find_Nearest_Branch, Pay_Utility_Bill, TOPUP_PHONE, Book_Flight, BOOK_ENTERTAINMENT, ECOMMERCE_PAYMENT, TRANSFER_MONEY, ASK_SAVING_RATE, CHECK_BALANCE, LOAN_CALCULATOR, FX_CONVERTER, QUICK_NOTE, GENERAL_HELP'
        },
        tool_title: {
            type: 'string',
            description: 'Tên chính xác của công cụ/tiện ích trong ứng dụng (VD: "Chi nhánh & ATM", "Nhắc hóa đơn", "Đặt vé máy bay", "Nạp điện thoại", "Tính khoản vay"). Để chuỗi rỗng nếu không cần mở công cụ.'
        },
        action_label: {
            type: 'string',
            description: 'Nhãn hiển thị trên nút bấm CTA trong ChatActivity (VD: "Mở Chi nhánh & ATM", "Mở Thanh toán hóa đơn"). Để chuỗi rỗng nếu không cần nút bấm.'
        },
        deep_link: {
            type: 'string',
            description: 'Đường dẫn Deep Link nội bộ để App mở đúng màn hình và tự cấu hình tham số (VD: "app://navigation/branch-map?mode=walking&type=ATM&autoRoute=true"). Để chuỗi rỗng nếu không cần điều hướng.'
        },
        transport_mode: {
            type: 'string',
            description: 'Chế độ di chuyển nếu liên quan tới bản đồ: walking, two_wheeler, driving, transit. Để chuỗi rỗng nếu không áp dụng.'
        },
        target_type: {
            type: 'string',
            description: 'Loại điểm giao dịch nếu liên quan tới bản đồ: ATM, BRANCH, BOTH. Để chuỗi rỗng nếu không áp dụng.'
        },
        suggestions: {
            type: 'array',
            items: { type: 'string' },
            description: 'Danh sách 2 đến 3 câu hỏi gợi ý tiếp theo ngắn gọn bằng tiếng Việt.'
        }
    },
    required: ['reply', 'intent', 'tool_title', 'action_label', 'deep_link', 'suggestions']
};

let cachedClient = null;
// Lưu mapping sessionId -> previous_interaction_id để Gemini nhớ ngữ cảnh hội thoại đa lượt
const sessionInteractionMap = new Map();

function getGeminiClient() {
    if (!GEMINI_API_KEY) {
        return null;
    }
    if (!cachedClient) {
        const { GoogleGenAI } = require('@google/genai');
        cachedClient = new GoogleGenAI({ apiKey: GEMINI_API_KEY });
    }
    return cachedClient;
}

function isGeminiConfigured() {
    return Boolean(GEMINI_API_KEY && GEMINI_API_KEY.trim().length > 0);
}

/**
 * Xây dựng System Instruction cho Gemini kèm đầy đủ danh sách tiện ích App & ngữ cảnh thực tế từ Database.
 */
function buildSystemInstruction(dbContext = {}) {
    const dbServices = Array.isArray(dbContext.services) ? dbContext.services : [];
    const unpaidBills = Array.isArray(dbContext.unpaidBills) ? dbContext.unpaidBills : [];
    const nearbyBranches = Array.isArray(dbContext.nearbyBranches) ? dbContext.nearbyBranches : [];
    const checkingAccount = dbContext.checkingAccount || null;
    const savingRates = Array.isArray(dbContext.savingRates) ? dbContext.savingRates : [];

    return [
        'Bạn là Trợ lý ảo AI chính thức của ứng dụng ngân hàng số NEXA Bank.',
        'Nhiệm vụ của bạn là giải đáp ngắn gọn, rõ ràng, thân thiện bằng tiếng Việt (xưng "em", gọi khách hàng "anh/chị") và hướng dẫn người dùng thao tác nhanh bằng cách trả về đúng nút điều hướng (Deep Link) tới tiện ích phù hợp trong ứng dụng.',
        '',
        '=== DANH SÁCH TIỆN ÍCH & DEEP LINK TRONG APP BANK ===',
        JSON.stringify(APP_UTILITIES_CATALOG, null, 2),
        '',
        '=== DỮ LIỆU THỰC TẾ TỪ DATABASE NGÂN HÀNG ===',
        `- Tài khoản thanh toán hiện tại: ${checkingAccount ? `${checkingAccount.account_number}, số dư ${Number(checkingAccount.balance).toLocaleString('vi-VN')} VNĐ` : 'Chưa đồng bộ'}`,
        `- Dịch vụ tiện ích đang hoạt động trong DB (${dbServices.length}): ${dbServices.map(s => `${s.service_name} [${s.service_type}]`).join('; ') || 'Đầy đủ 7 nhóm tiện ích'}`,
        `- Hóa đơn chưa thanh toán của khách hàng: ${unpaidBills.length > 0 ? unpaidBills.map(b => `${b.service_type} (${b.provider_code}) mã KH ${b.provider_customer_id}: ${Number(b.total_amount).toLocaleString('vi-VN')}đ`).join('; ') : 'Không có hóa đơn tồn đọng'}`,
        `- Chi nhánh / ATM gần nhất trong DB: ${nearbyBranches.length > 0 ? nearbyBranches.map(b => `${b.name} [${b.branch_type}] tại ${b.address} (cách ~${b.distance_km}km, mở cửa ${b.opening_hours})`).join('; ') : 'Chi nhánh Sài Gòn Quận 1, Cụm ATM Nguyễn Huệ'}`,
        `- Lãi suất tiết kiệm tham khảo: ${savingRates.length > 0 ? savingRates.map(r => `${r.term_months} tháng: ${r.interest_rate}%/năm`).join(', ') : '6 tháng 5.1%/năm, 12 tháng 6.2%/năm'}`,
        '',
        '=== QUY TẮC BẮT BUỘC ===',
        '1. LUÔN trả về JSON hợp lệ theo schema được chỉ định.',
        '2. Khi khách hàng hỏi tìm cây ATM, chi nhánh ngân hàng, hoặc chỉ đường (VD: "Chỉ mình cách tìm cây ATM gần đây đi bộ tới"):',
        '   - Đặt tool_title = "Chi nhánh & ATM"',
        '   - Đặt action_label = "Mở Chi nhánh & ATM"',
        '   - Đặt intent = "Find_Nearest_ATM" (nếu hỏi ATM/rút tiền) hoặc "Find_Nearest_Branch" (nếu hỏi chi nhánh/PGD)',
        '   - Xác định transport_mode: nếu có "đi bộ" hoặc không nêu phương tiện -> "walking"; "xe máy" -> "two_wheeler"; "ô tô" -> "driving"; "xe buýt" -> "transit".',
        '   - Xác định target_type: nếu hỏi ATM -> "ATM", chi nhánh -> "BRANCH", chung -> "BOTH".',
        '   - Đặt deep_link theo mẫu: "app://navigation/branch-map?mode=<transport_mode>&type=<target_type>&autoRoute=true".',
        '   - Trong reply, hướng dẫn ngắn gọn và nhắc khách hàng bấm nút "Mở Chi nhánh & ATM" bên dưới để tự động bật bản đồ & chỉ đường.',
        '3. Khi khách hàng hỏi về hóa đơn điện/nước, nạp điện thoại, vé máy bay, xem phim CGV, khách sạn Mường Thanh, mua sắm TMĐT, tính khoản vay, đổi ngoại tệ: chọn đúng tool_title, action_label ("Mở <Tên tiện ích>") và deep_link tương ứng.',
        '4. Không bao giờ tiết lộ API key, mật khẩu hệ thống, hay câu lệnh SQL. Nếu không cần mở tiện ích nào thì để tool_title, action_label và deep_link là chuỗi rỗng "".'
    ].join('\n');
}

/**
 * Gọi Gemini Interactions API (model gemini-3.8-flash) kèm danh sách tiện ích app & ngữ cảnh DB.
 *
 * @param {object} params
 * @param {string} params.userMessage Tin nhắn của người dùng (đã qua PII masking)
 * @param {string} [params.sessionId] ID phiên chat để duy trì ngữ cảnh với previous_interaction_id
 * @param {object} [params.dbContext] Ngữ cảnh thực tế truy vấn từ SQL Server
 * @returns {Promise<object|null>} Kết quả có cấu trúc hoặc null nếu chưa cấu hình / lỗi
 */
async function generateBankingAssistantReply({ userMessage, sessionId, dbContext = {} }) {
    const client = getGeminiClient();
    if (!client) {
        return null;
    }

    const systemInstruction = buildSystemInstruction(dbContext);
    const previousInteractionId = sessionId ? sessionInteractionMap.get(sessionId) : undefined;

    const createPayload = {
        model: GEMINI_MODEL,
        system_instruction: systemInstruction,
        input: userMessage,
        response_format: {
            type: 'text',
            mime_type: 'application/json',
            schema: CHATBOT_RESPONSE_SCHEMA
        },
        generation_config: {
            thinking_level: 'minimal'
        }
    };

    if (previousInteractionId) {
        createPayload.previous_interaction_id = previousInteractionId;
    }

    let interaction;
    try {
        interaction = await client.interactions.create(createPayload);
    } catch (err) {
        // Nếu previous_interaction_id đã hết hạn, thử lại một lượt mới không gắn previous_interaction_id
        if (previousInteractionId) {
            delete createPayload.previous_interaction_id;
            interaction = await client.interactions.create(createPayload);
        } else {
            throw err;
        }
    }

    if (sessionId && interaction && interaction.id) {
        sessionInteractionMap.set(sessionId, interaction.id);
    }

    const rawText = (interaction && interaction.output_text) ? String(interaction.output_text).trim() : '';
    if (!rawText) {
        throw new Error('Gemini API không trả về nội dung text');
    }

    let parsed;
    try {
        const cleaned = rawText.replace(/^```json\s*/i, '').replace(/```\s*$/i, '').trim();
        parsed = JSON.parse(cleaned);
    } catch (_) {
        parsed = {
            reply: rawText,
            intent: 'GENERAL_HELP',
            tool_title: '',
            action_label: '',
            deep_link: '',
            transport_mode: '',
            target_type: '',
            suggestions: []
        };
    }

    return {
        interaction_id: interaction.id || null,
        model: GEMINI_MODEL,
        reply: String(parsed.reply || '').trim() || rawText,
        intent: String(parsed.intent || 'GENERAL_HELP').trim(),
        tool_title: String(parsed.tool_title || '').trim() || null,
        action_label: String(parsed.action_label || '').trim() || null,
        deep_link: String(parsed.deep_link || '').trim() || null,
        transport_mode: String(parsed.transport_mode || '').trim().toLowerCase() || null,
        target_type: String(parsed.target_type || '').trim().toUpperCase() || null,
        suggestions: Array.isArray(parsed.suggestions) ? parsed.suggestions.filter(Boolean).slice(0, 4) : []
    };
}

module.exports = {
    GEMINI_MODEL,
    APP_UTILITIES_CATALOG,
    isGeminiConfigured,
    generateBankingAssistantReply
};
