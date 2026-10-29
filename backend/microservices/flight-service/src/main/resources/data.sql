-- ============================================================================
--  Jeu de donnees de demonstration (uniquement si SPRING_SQL_INIT_MODE=always).
--  INSERT IGNORE : rejouer le script ne cree pas de doublon et ne reinitialise
--  jamais le stock de places deja decremente par les reservations.
-- ============================================================================
INSERT IGNORE INTO flight (id, flight_number, departure_city, arrival_city, departure_date, available_seats, price) VALUES
  (1, 'TU700',  'Tunis',   'Paris',     '2026-12-20', 120, 350.50),
  (2, 'AF1234', 'Paris',   'Dubai',     '2026-11-05',  15, 890.00),
  (3, 'EK777',  'Dubai',   'Tokyo',     '2026-12-01',   8, 1500.00),
  (4, 'TU404',  'Tunis',   'Istanbul',  '2026-11-15',   3, 420.00),
  (5, 'BA202',  'Londres', 'New York',  '2026-12-10',  50, 1250.00);
