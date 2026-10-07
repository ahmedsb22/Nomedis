const express = require('express');
const router = express.Router();
router.get('/api/conferences', (req, res) => {
  res.json([
    { id: 1, title: "Tech Summit Paris", location: "Paris", date: "2026-11-15", flightId: 101 },
    { id: 2, title: "AI Expo Dubai", location: "Dubai", date: "2026-12-01", flightId: 102 }
  ]);
});
router.get('/hello', (req, res) => res.json({ message: "Nomadix Conference Service UP ✈️" }));
module.exports = router;
