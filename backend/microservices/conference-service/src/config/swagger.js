/**
 * Specification OpenAPI 3 du service Conferences (Node.js / Express).
 *
 * Servie en JSON sur /v3/api-docs et en interface sur /swagger-ui.
 * Chaque route reellement implementee est documentee ici : si une route
 * apparait dans src/routes, elle doit apparaitre dans `paths`, et inversement.
 *
 * Authentification : les ecritures (POST/PUT/DELETE) sont protegees par
 * l'API Gateway (Keycloak, role ADMIN). Le service Node, lui, ne valide pas
 * le jeton : il n'est joignable que via la Gateway en pratique.
 */
const PORT = Number(process.env.PORT) || 3000;

// Keycloak : le bouton "Authorize" du Swagger ouvre la page de connexion du
// realm nomadix (client public nomadix-swagger, Authorization Code + PKCE).
const KEYCLOAK_URL = (process.env.NOMADIX_KEYCLOAK_URL || 'http://localhost:8180').replace(/\/$/, '');
const KEYCLOAK_REALM = process.env.NOMADIX_KEYCLOAK_REALM || 'nomadix';
const OIDC_BASE = `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect`;

module.exports = {
  openapi: '3.0.3',
  info: {
    title: 'Nomadix Conference Service API',
    version: '2.0.0',
    description:
      'Service de conferences Nomadix (Node.js / Express, stockage en memoire).\n\n' +
      '**Securite** : en passant par la Gateway (`http://localhost:9089` en Docker ' +
      'Compose, `http://localhost:8089` hors Docker), les lectures ' +
      'sont publiques et les ecritures exigent un jeton Keycloak avec le role `ADMIN`.\n\n' +
      '**Comptes de test** : `admin` / `admin123` (ADMIN), `sami` / `sami123` (TRAVELER).',
    contact: { name: 'Equipe Nomadix' },
  },
  // Le premier serveur est celui selectionne par defaut dans l'UI : en Docker
  // Compose la Gateway est publiee sur 9089 (8089 n'est pas expose cote hote).
  servers: [
    { url: 'http://localhost:9089', description: 'Via l API Gateway (Docker Compose - recommande)' },
    { url: 'http://localhost:3001', description: 'Acces direct au conteneur conference' },
    { url: 'http://localhost:8089', description: 'Via l API Gateway (services hors Docker)' },
    { url: `http://localhost:${PORT}`, description: 'Acces direct au service (dev local)' },
  ],
  tags: [
    { name: 'Conferences', description: 'CRUD des conferences (memoire)' },
    { name: 'Meetings', description: 'Squelette pedagogique du service meeting' },
  ],
  paths: {
    '/api/meetings/hello': {
      get: {
        tags: ['Meetings'],
        summary: 'Verifier que le service repond',
        operationId: 'hello',
        responses: {
          200: {
            description: 'Message de bienvenue',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/HelloResponse' },
                example: { message: "hello I'm microservice meeting" },
              },
            },
          },
        },
      },
    },

    '/api/conferences': {
      get: {
        tags: ['Conferences'],
        summary: 'Lister les conferences',
        operationId: 'findAllConferences',
        description: 'Retourne le tableau complet (ordre d insertion).',
        responses: {
          200: {
            description: 'Liste des conferences',
            content: {
              'application/json': {
                schema: { type: 'array', items: { $ref: '#/components/schemas/Conference' } },
                example: [
                  { id: 1, title: 'Tech Summit Paris', location: 'Paris', date: '2026-11-15', flightId: 101 },
                  { id: 2, title: 'AI Expo Dubai', location: 'Dubai', date: '2026-12-01', flightId: 102 },
                ],
              },
            },
          },
        },
      },
      post: {
        tags: ['Conferences'],
        summary: 'Creer une conference',
        operationId: 'createConference',
        security: [{ keycloak: [] }],
        description: 'Role ADMIN requis via la Gateway. `id` est genere par le serveur.',
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: { $ref: '#/components/schemas/ConferenceInput' },
              example: { title: 'DevOps Days', location: 'Tunis', date: '2027-01-20', flightId: 12 },
            },
          },
        },
        responses: {
          201: {
            description: 'Conference creee',
            content: {
              'application/json': { schema: { $ref: '#/components/schemas/Conference' } },
            },
          },
          400: {
            description: 'title, location ou date manquant',
            content: { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } },
          },
          401: { description: 'Jeton absent (via la Gateway)' },
          403: { description: 'Role ADMIN manquant (via la Gateway)' },
        },
      },
    },

    '/api/conferences/{id}': {
      parameters: [
        {
          name: 'id',
          in: 'path',
          required: true,
          description: 'Identifiant de la conference',
          schema: { type: 'integer', example: 1 },
        },
      ],
      get: {
        tags: ['Conferences'],
        summary: 'Obtenir une conference',
        operationId: 'findConferenceById',
        responses: {
          200: {
            description: 'Conference trouvee',
            content: {
              'application/json': { schema: { $ref: '#/components/schemas/Conference' } },
            },
          },
          404: {
            description: 'Introuvable',
            content: {
              'application/json': {
                schema: { $ref: '#/components/schemas/Error' },
                example: { status: 404, error: 'Not Found', message: 'Conference 42 not found' },
              },
            },
          },
        },
      },
      put: {
        tags: ['Conferences'],
        summary: 'Mettre a jour une conference',
        operationId: 'updateConference',
        security: [{ keycloak: [] }],
        description: 'Role ADMIN requis via la Gateway. Remplacement complet des champs.',
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: { $ref: '#/components/schemas/ConferenceInput' },
              example: { title: 'Tech Summit Paris 2027', location: 'Paris', date: '2027-11-15', flightId: 101 },
            },
          },
        },
        responses: {
          200: {
            description: 'Conference mise a jour',
            content: {
              'application/json': { schema: { $ref: '#/components/schemas/Conference' } },
            },
          },
          400: {
            description: 'Champ obligatoire manquant',
            content: { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } },
          },
          404: {
            description: 'Introuvable',
            content: { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } },
          },
        },
      },
      delete: {
        tags: ['Conferences'],
        summary: 'Supprimer une conference',
        operationId: 'deleteConference',
        security: [{ keycloak: [] }],
        description: 'Role ADMIN requis via la Gateway.',
        responses: {
          204: { description: 'Supprimee (aucun corps)' },
          404: {
            description: 'Introuvable',
            content: { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } },
          },
        },
      },
    },
  },
  components: {
    securitySchemes: {
      keycloak: {
        type: 'oauth2',
        description:
          'Keycloak (realm nomadix). Comptes de test : admin/admin123 (ADMIN), sami/sami123 (TRAVELER).',
        flows: {
          authorizationCode: {
            authorizationUrl: `${OIDC_BASE}/auth`,
            tokenUrl: `${OIDC_BASE}/token`,
            scopes: {
              openid: 'Identite de l utilisateur',
              profile: 'Nom et prenom',
              email: 'Adresse email',
            },
          },
        },
      },
    },
    schemas: {
      HelloResponse: {
        type: 'object',
        required: ['message'],
        properties: {
          message: { type: 'string', example: "hello I'm microservice meeting" },
        },
      },
      Conference: {
        type: 'object',
        properties: {
          id: { type: 'integer', example: 1 },
          title: { type: 'string', example: 'Tech Summit Paris' },
          location: { type: 'string', example: 'Paris' },
          date: { type: 'string', example: '2026-11-15', description: 'Date ISO 8601 (AAAA-MM-JJ)' },
          flightId: { type: 'integer', nullable: true, example: 101 },
        },
      },
      ConferenceInput: {
        type: 'object',
        required: ['title', 'location', 'date'],
        properties: {
          title: { type: 'string', example: 'Tech Summit Paris' },
          location: { type: 'string', example: 'Paris' },
          date: { type: 'string', example: '2026-11-15' },
          flightId: { type: 'integer', nullable: true, example: 101 },
        },
      },
      Error: {
        type: 'object',
        properties: {
          status: { type: 'integer', example: 404 },
          error: { type: 'string', example: 'Not Found' },
          message: { type: 'string', example: 'Conference 42 not found' },
        },
      },
      // TODO (etudiants) : ajouter Meeting et les 5 routes CRUD dans `paths`
      // lorsque src/controllers/meeting.controller.js sera implemente.
    },
  },
};
