// ============================================================================
// Conference Service - server.js (corrige : import swaggerSpec robuste)
// ============================================================================
const express = require('express');
const swaggerUi = require('swagger-ui-express');

const app = express();
app.use(express.json());

// --- Import robuste de la specification Swagger ----------------------------
// Fonctionne quel que soit le format d'export de la configuration.
let swaggerSpec = null;
try {
    const loaded = require('./config/swagger');
    swaggerSpec = loaded.swaggerSpec || loaded.default || loaded;
} catch (e1) {
    try {
        const loaded2 = require('./config/swagger.config');
        swaggerSpec = loaded2.swaggerSpec || loaded2.default || loaded2;
    } catch (e2) {
        console.warn('[WARN] Config Swagger introuvable, /api-docs desactive :', e2.message);
    }
}

if (swaggerSpec) {
    app.use('/api-docs', swaggerUi.serve, swaggerUi.setup(swaggerSpec));
    console.log('[OK] Swagger UI disponible sur /api-docs');
}

// --- Routes metier -----------------------------------------------------------
let conferenceRoutes = null;
try { conferenceRoutes = require('./routes/meeting.routes'); } catch (e) { }
let meetingController = null;
try { meetingController = require('./controllers/meeting.controller'); } catch (e) { }
let conferenceController = null;
try { conferenceController = require('./controllers/conference.controller'); } catch (e) { }

if (conferenceRoutes) {
    app.use('/', conferenceRoutes);
} else {
    // Routes de secours (garantissent un endpoint repondant)
    app.get('/', (req, res) => res.json({ service: 'conference-service', status: 'UP' }));
    app.get('/api/conferences', (req, res) => {
        if (conferenceController && typeof conferenceController.getAll === 'function') {
            return conferenceController.getAll(req, res);
        }
        res.json([]);
    });
}

// --- Enregistrement Eureka + demarrage ----------------------------------------
const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
    console.log(`[OK] Conference Service demarre sur le port ${PORT}`);
    try {
        const eurekaClient = require('./eureka-client');
        if (eurekaClient && typeof eurekaClient.start === 'function') {
            eurekaClient.start();
            console.log('[OK] Enregistrement Eureka lance');
        }
    } catch (e) {
        console.warn('[WARN] Eureka client indisponible :', e.message);
    }
});