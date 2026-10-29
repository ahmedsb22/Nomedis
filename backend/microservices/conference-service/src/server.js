/**
 * Demarrage du service Conferences : HTTP + enregistrement Eureka.
 *
 * Toute la configuration Express (Swagger, routes, erreurs) vit dans app.js.
 */
const app = require('./app');

const PORT = Number(process.env.PORT) || 3000;

app.listen(PORT, () => {
  console.log(`[OK] Conference Service demarre sur le port ${PORT}`);
  console.log(`[OK] Swagger UI   : http://localhost:${PORT}/swagger-ui`);
  console.log(`[OK] OpenAPI JSON : http://localhost:${PORT}/v3/api-docs`);
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
