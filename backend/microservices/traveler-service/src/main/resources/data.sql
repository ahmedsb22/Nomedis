-- ============================================================================
--  Jeu de donnees de demonstration (uniquement si SPRING_SQL_INIT_MODE=always).
--  Les vols favoris sont volontairement vides : ils s'ajoutent depuis le
--  frontend pour demontrer l'appel OpenFeign vers le Flight-Service.
--
--  Les quatre champs de profiling sont renseignes de facon volontairement
--  inegale afin que le demo montre les trois segments des le premier demarrage :
--    #1 profil complet + coherent -> ~100 pts, PLATINUM
--    #2 passeport manquant        -> ~68 pts,  GOLD
--    #3 passeport invalide + date manquante -> ~45 pts, SILVER
--  Le score reel est calcule par l'aspect AOP a chaque sauvegarde :
--  POST /api/travelers/profiles/{id}/recompute force le recalcul.
-- ============================================================================
INSERT IGNORE INTO travelers
  (id, first_name, last_name, email, role, phone, passport_number, nationality, date_of_birth) VALUES
  (1, 'Sami',    'Trabelsi', 'sami@test.tn',    'USER',  '+216 55 123 456', 'TN1234567', 'Tunisienne', '1998-04-12'),
  (2, 'Mohamed', 'Amor',     'mohamed@test.tn', 'USER',  '+216 98 765 432', NULL,        'Tunisienne', '1990-01-20'),
  (3, 'Admin',   'Nomadix',  'admin@test.tn',   'ADMIN', NULL,              'A1',        'Tunisienne', NULL);
