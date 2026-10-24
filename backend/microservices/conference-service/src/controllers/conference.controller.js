/**
 * Conference controller.
 *
 * Stockage en memoire (pas de base pour ce service, conformement au sujet).
 * Le CRUD est expose sous /api/conferences par routes/conference.routes.js,
 * ce qui correspond exactement a la route de l'API Gateway et au frontend.
 */

let conferences = [
  { id: 1, title: 'Tech Summit Paris', location: 'Paris', date: '2026-11-15', flightId: 101 },
  { id: 2, title: 'AI Expo Dubai', location: 'Dubai', date: '2026-12-01', flightId: 102 },
];
let nextId = 3;

const badRequest = (res, message) =>
  res.status(400).json({ status: 400, error: 'Bad Request', message });

const notFound = (res, id) =>
  res.status(404).json({ status: 404, error: 'Not Found', message: `Conference ${id} not found` });

const findAll = (req, res) => {
  res.status(200).json(conferences);
};

const findById = (req, res) => {
  const conference = conferences.find((c) => c.id === Number(req.params.id));
  if (!conference) {
    return notFound(res, req.params.id);
  }
  return res.status(200).json(conference);
};

const create = (req, res) => {
  const { title, location, date, flightId } = req.body || {};
  if (!title || !location || !date) {
    return badRequest(res, 'title, location et date sont obligatoires');
  }
  const conference = {
    id: nextId++,
    title,
    location,
    date,
    flightId: flightId === undefined ? null : flightId,
  };
  conferences.push(conference);
  return res.status(201).json(conference);
};

const update = (req, res) => {
  const conference = conferences.find((c) => c.id === Number(req.params.id));
  if (!conference) {
    return notFound(res, req.params.id);
  }
  const { title, location, date, flightId } = req.body || {};
  if (!title || !location || !date) {
    return badRequest(res, 'title, location et date sont obligatoires');
  }
  conference.title = title;
  conference.location = location;
  conference.date = date;
  conference.flightId = flightId === undefined ? conference.flightId : flightId;
  return res.status(200).json(conference);
};

const remove = (req, res) => {
  const index = conferences.findIndex((c) => c.id === Number(req.params.id));
  if (index === -1) {
    return notFound(res, req.params.id);
  }
  conferences.splice(index, 1);
  return res.status(204).send();
};

module.exports = { findAll, findById, create, update, remove };
