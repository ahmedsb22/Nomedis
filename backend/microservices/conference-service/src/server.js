// ============================================================================
// Conference Service - server.js
// Routes reellement exposees (celles de l'API Gateway et du frontend) :
//   /api/conferences  -> CRUD complet
//   /api/meetings     -> hello (squelette du sujet)
// ============================================================================
const express = require('express');
const swaggerUi = require('swagger-ui-express');

const PORT = Number(process.env.PORT) || 3000;
const app = express();
app.use(express.json());

// --- Import robuste de la specification Swagger ----------------------------
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
    app.get('/v3/api-docs', (req, res) => res.json(swaggerSpec));
    console.log('[OK] Swagger UI disponible sur /api-docs');
}

// --- Routes metier -----------------------------------------------------------
let meetingRoutes = null;
try { meetingRoutes = require('./routes/meeting.routes'); } catch (e) { }
let conferenceRoutes = null;
try { conferenceRoutes = require('./routes/conference.routes'); } catch (e) { }

app.get('/', (req, res) => res.json({ service: 'conference-service', status: 'UP', port: PORT }));

if (meetingRoutes) {
    app.use('/api/meetings', meetingRoutes);
}

if (conferenceRoutes) {
    app.use('/api/conferences', conferenceRoutes);
} else {
    // Garde-fou : l'API doit repondre meme si le routeur n'a pas pu etre charge.
    app.get('/api/conferences', (req, res) => res.json([]));
}

// --- Enregistrement Eureka + demarrage ----------------------------------------
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
