-- ============================================================================
--  Jeu de donnees de demonstration (uniquement si SPRING_SQL_INIT_MODE=always).
--  Les vols favoris sont volontairement vides : ils s'ajoutent depuis le
--  frontend pour demontrer l'appel OpenFeign vers le Flight-Service.
-- ============================================================================
INSERT IGNORE INTO travelers (id, first_name, last_name, email, role) VALUES
  (1, 'Sami',     'Trabelsi', 'sami@test.tn',     'USER'),
  (2, 'Mohamed',  'Amor',     'mohamed@test.tn',  'USER'),
  (3, 'Admin',    'Nomadix',  'admin@test.tn',    'ADMIN');
