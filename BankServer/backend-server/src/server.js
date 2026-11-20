require('dotenv').config();
const express = require('express');
const cors = require('cors');
const jwt = require('jsonwebtoken');
const { getPool, seedDefaultData } = require('./db');
const { JWT_SECRET, optionalAuthenticateJWT } = require('./middleware/auth');

const coreRoutes = require('./routes/core.routes');
const utilitiesRoutes = require('./routes/utilities.routes');
const navigationRoutes = require('./routes/navigation.routes');
const chatbotRoutes = require('./routes/chatbot.routes');

const app = express();
app.use(cors());
app.use(express.json());

// Gắn các nhóm API theo chuẩn /v1
app.use('/v1', coreRoutes);
app.use('/v1', utilitiesRoutes);
app.use('/v1/navigation', navigationRoutes);
app.use('/v1/chatbot', chatbotRoutes);
// Alias rút gọn POST /v1/chat cho App Bank gọi trực tiếp Gemini Chatbot
app.post('/v1/chat', optionalAuthenticateJWT, chatbotRoutes.handleChatMessage);

// Healthcheck endpoint
app.get('/health', (req, res) => res.status(200).json({
    status: 'OK',
    modules: ['core', 'utilities', 'navigation', 'chatbot']
}));

/**
 * POST /v1/dev/seed
 * API hỗ trợ khởi tạo dữ liệu mẫu toàn diện (Users, Accounts, Saving Rates, Utility Services, Bills, Bank Branches & ATMs)
 * và trả về JWT Token để test ngay trên Postman / Android
 */
app.post('/v1/dev/seed', async (req, res) => {
    try {
        const pool = await getPool();
        await seedDefaultData(pool);

        const token = jwt.sign(
            { user_id: 'u1111111-1111-1111-1111-111111111111', username: 'nguyenvana', role: 'CUSTOMER' },
            JWT_SECRET,
            { expiresIn: '7d' }
        );

        return res.status(200).json({
            message: 'Khởi tạo dữ liệu mẫu toàn diện thành công!',
            test_jwt_token: token,
            test_user_id: 'u1111111-1111-1111-1111-111111111111',
            test_account_id: 'a1b2c3d4-0000-0000-0000-000000000001',
            sample_electricity_bill_id: 'b9f1c2d3-0000-0000-0000-000000000001',
            sample_water_bill_id: 'b9f1c2d3-0000-0000-0000-000000000002',
            sample_branch_id: 'br-q1-saigon'
        });
    } catch (err) {
        console.error(err);
        return res.status(500).json({ error: err.message });
    }
});

const PORT = process.env.PORT || 8080;
if (require.main === module) {
    app.listen(PORT, () => {
        console.log(`🚀 BankServer API listening on port ${PORT}`);
    });
}

module.exports = app;
