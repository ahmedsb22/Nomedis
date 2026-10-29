// Run with: npm test   (Node's built-in test runner, no extra dependency)
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const app = require('../src/app');

let server;
let baseUrl;

before(async () => {
  server = app.listen(0);
  await new Promise((resolve) => server.once('listening', resolve));
  baseUrl = `http://localhost:${server.address().port}`;
});

after(() => server.close());

test('GET /api/meetings/hello returns the greeting', async () => {
  const res = await fetch(`${baseUrl}/api/meetings/hello`);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { message: "hello I'm microservice meeting" });
});

test('GET /v3/api-docs returns the OpenAPI spec', async () => {
  const res = await fetch(`${baseUrl}/v3/api-docs`);
  assert.equal(res.status, 200);
  const spec = await res.json();
  assert.equal(spec.info.title, 'Nomadix Conference Service API');
  assert.ok(spec.paths['/api/meetings/hello']);
  // Toutes les routes conference doivent etre documentees (Swagger = verite)
  for (const p of ['/api/conferences', '/api/conferences/{id}']) {
    assert.ok(spec.paths[p], `chemin manquant dans Swagger : ${p}`);
  }
  assert.ok(spec.components.schemas.Conference);
  assert.ok(spec.components.schemas.ConferenceInput);
});

test('GET /swagger-ui/ serves the Swagger UI page', async () => {
  const res = await fetch(`${baseUrl}/swagger-ui/`);
  assert.equal(res.status, 200);
  assert.match(await res.text(), /swagger/i);
});

test('GET /api/conferences returns the in-memory list', async () => {
  const res = await fetch(`${baseUrl}/api/conferences`);
  assert.equal(res.status, 200);
  const list = await res.json();
  assert.ok(Array.isArray(list));
  assert.ok(list.length >= 2);
  assert.ok(list[0].title);
});

test('POST /api/conferences rejects an incomplete body with 400', async () => {
  const res = await fetch(`${baseUrl}/api/conferences`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: 'Sans lieu' }),
  });
  assert.equal(res.status, 400);
  const body = await res.json();
  assert.equal(body.status, 400);
  assert.ok(body.message);
});

test('conference lifecycle: create, read, update, delete', async () => {
  const created = await fetch(`${baseUrl}/api/conferences`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: 'Test Conference', location: 'Tunis', date: '2027-03-01' }),
  });
  assert.equal(created.status, 201);
  const conference = await created.json();
  assert.ok(conference.id);
  assert.equal(conference.flightId, null);

  const read = await fetch(`${baseUrl}/api/conferences/${conference.id}`);
  assert.equal(read.status, 200);
  assert.equal((await read.json()).title, 'Test Conference');

  const updated = await fetch(`${baseUrl}/api/conferences/${conference.id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: 'Test Conference v2', location: 'Sfax', date: '2027-03-02' }),
  });
  assert.equal(updated.status, 200);
  assert.equal((await updated.json()).location, 'Sfax');

  const deleted = await fetch(`${baseUrl}/api/conferences/${conference.id}`, { method: 'DELETE' });
  assert.equal(deleted.status, 204);

  const gone = await fetch(`${baseUrl}/api/conferences/${conference.id}`);
  assert.equal(gone.status, 404);
});

test('unknown route returns 404 JSON', async () => {
  const res = await fetch(`${baseUrl}/api/unknown`);
  assert.equal(res.status, 404);
  assert.equal((await res.json()).status, 404);
});
