const { Duffel } = require('@duffel/api');

const duffel = new Duffel({
    token: process.env.DUFFEL_ACCESS_TOKEN
});

async function searchFlights({ origin = 'SGN', destination = 'HAN', departureDate }) {
    const response = await duffel.offerRequests.create({
        slices: [
            {
                origin,
                destination,
                departure_date: departureDate // VD: '2026-12-20'
            }
        ],
        passengers: [{ type: 'adult' }],
        cabin_class: 'economy'
    });

    return response.data.offers; // Mảng JSON các chuyến bay tìm được
}

module.exports = { searchFlights };