/**
 * OpenAPI 3 specification of the meeting microservice.
 * Served as JSON at /v3/api-docs and as Swagger UI at /swagger-ui.
 *
 * TODO (students): document every new route in `paths` and every new object in `components.schemas`.
 */
const PORT = process.env.PORT || 3000;

module.exports = {
  openapi: '3.0.3',
  info: {
    title: 'Meeting Microservice API',
    version: '1.0.0',
    description: 'Conference microservice (Node.js / Express, no database). ' +
      'Conference CRUD available under /api/conferences; the meeting logic is a student exercise.',
    contact: { name: 'Badia Abouhdid' },
  },
  servers: [{ url: `http://localhost:${PORT}`, description: 'Local' }],
  tags: [
    { name: 'Meetings', description: 'Meeting endpoints' },
    { name: 'Conferences', description: 'Conference CRUD' },
  ],
  paths: {
    '/api/meetings/hello': {
      get: {
        tags: ['Meetings'],
        summary: 'Hello from the meeting microservice',
        description: 'Checks that the microservice is up and returns a greeting message.',
        operationId: 'hello',
        responses: {
          200: {
            description: 'Greeting message',
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
        responses: {
          200: {
            description: 'Liste des conferences',
            content: {
              'application/json': {
                schema: { type: 'array', items: { $ref: '#/components/schemas/Conference' } },
              },
            },
          },
        },
      },
      post: {
        tags: ['Conferences'],
        summary: 'Creer une conference',
        requestBody: {
          required: true,
          content: {
            'application/json': { schema: { $ref: '#/components/schemas/ConferenceInput' } },
          },
        },
        responses: {
          201: { description: 'Conference creee' },
          400: { description: 'Champs obligatoires manquants' },
        },
      },
    },
    '/api/conferences/{id}': {
      parameters: [
        { name: 'id', in: 'path', required: true, schema: { type: 'integer' } },
      ],
      get: {
        tags: ['Conferences'],
        summary: 'Obtenir une conference',
        responses: { 200: { description: 'Conference trouvee' }, 404: { description: 'Introuvable' } },
      },
      put: {
        tags: ['Conferences'],
        summary: 'Mettre a jour une conference',
        requestBody: {
          required: true,
          content: {
            'application/json': { schema: { $ref: '#/components/schemas/ConferenceInput' } },
          },
        },
        responses: { 200: { description: 'Conference mise a jour' }, 404: { description: 'Introuvable' } },
      },
      delete: {
        tags: ['Conferences'],
        summary: 'Supprimer une conference',
        responses: { 204: { description: 'Supprimee' }, 404: { description: 'Introuvable' } },
      },
    },
  },
  components: {
    schemas: {
      HelloResponse: {
        type: 'object',
        properties: {
          message: { type: 'string', example: "hello I'm microservice meeting" },
        },
        required: ['message'],
      },

      Conference: {
        type: 'object',
        properties: {
          id: { type: 'integer', example: 1 },
          title: { type: 'string', example: 'Tech Summit Paris' },
          location: { type: 'string', example: 'Paris' },
          date: { type: 'string', example: '2026-11-15' },
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

      // TODO (students): add the Meeting schema, for example:
      // Meeting: {
      //   type: 'object',
      //   properties: {
      //     id: { type: 'integer', example: 1 },
      //     title: { type: 'string', example: 'Technical interview' },
      //     date: { type: 'string', format: 'date-time', example: '2026-10-05T10:00:00Z' },
      //     candidateId: { type: 'integer', example: 1 },
      //     jobId: { type: 'integer', example: 1 },
      //   },
      // },

      Error: {
        type: 'object',
        properties: {
          status: { type: 'integer', example: 404 },
          error: { type: 'string', example: 'Not Found' },
          message: { type: 'string', example: 'Meeting 42 not found' },
        },
      },
    },
  },
};
