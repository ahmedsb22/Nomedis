/**
 * Application Express du service Conferences.
 *
 * Point d'entree unique reutilise par server.js et par les tests : une seule
 * table de routage, donc plus de divergence entre "npm start" et "npm test".
 */
const express = require('express');
const swaggerUi = require('swagger-ui-express');
const openapiSpec = require('./config/swagger');
const meetingRoutes = require('./routes/meeting.routes');
const conferenceRoutes = require('./routes/conference.routes');

const app = express();
app.disable('x-powered-by');
app.use(express.json());

// ---------------------------------------------------------------- Swagger
app.get('/v3/api-docs', (req, res) => res.json(openapiSpec));
// Ancienne URL conservee en simple redirection (compatibilite)
app.get('/api-docs', (req, res) => res.redirect('/swagger-ui'));
app.use('/swagger-ui', swaggerUi.serve, swaggerUi.setup(openapiSpec, {
  customSiteTitle: 'Nomadix Conference Service API',
  swaggerOptions: {
    // Bouton "Authorize" : Authorization Code + PKCE sur le client public
    // Keycloak nomadix-swagger. Le jeton obtenu peut ensuite etre envoye a la
    // Gateway (serveur http://localhost:9089) pour les ecritures ADMIN.
    // swagger-ui-express attend la cle `oauth` (il appelle ui.initOAuth(oauth)).
    // Une cle `initOAuth` serait passee telle quelle a SwaggerUIBundle et ignoree.
    oauth: {
      clientId: process.env.NOMADIX_SWAGGER_CLIENT_ID || 'nomadix-swagger',
      appName: 'Nomadix Conference Service',
      usePkceWithAuthorizationCodeGrant: true,
      scopes: 'openid profile email',
    },
    persistAuthorization: true,
    displayRequestDuration: true,
  },
}));

// ---------------------------------------------------------------- Metier
app.get('/', (req, res) => res.json({
  service: 'conference-service',
  status: 'UP',
  port: Number(process.env.PORT) || 3000,
  swaggerUi: '/swagger-ui',
  openapi: '/v3/api-docs',
}));

app.use('/api/meetings', meetingRoutes);
app.use('/api/conferences', conferenceRoutes);

// ---------------------------------------------------------------- Erreurs
app.use((req, res) => {
  res.status(404).json({
    status: 404,
    error: 'Not Found',
    message: `Route ${req.method} ${req.originalUrl} not found`,
  });
});

// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  const status = Number.isInteger(err.status) && err.status >= 400 ? err.status : 500;
  res.status(status).json({
    status,
    error: status === 500 ? 'Internal Server Error' : (err.name || 'Error'),
    message: err.message,
  });
});

module.exports = app;
