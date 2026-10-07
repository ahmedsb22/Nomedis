const swaggerJsdoc = require('swagger-jsdoc');

const options = {
  definition: {
    openapi: '3.0.0',
    info: {
      title: 'Nomadix Conference Service API',
      version: '1.0.0',
      description: 'API complète pour la gestion des conférences liées aux voyages Nomadix',
    },
    servers: [{ url: 'http://localhost:3000', description: 'Local Development Server' }],
  },
  apis: ['./src/controllers/*.js'], // Chemin vers vos contrôleurs
};

module.exports = swaggerJsdoc(options);
