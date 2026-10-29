-- ============================================================================
--  Nomadix - initialisation MySQL (premier demarrage du conteneur)
--  Regle database-per-service : une base par microservice, aucune jointure
--  SQL entre les services (les liens sont des identifiants logiques).
-- ============================================================================
CREATE DATABASE IF NOT EXISTS nomadix_traveler_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS nomadix_flight_db   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS nomadix_booking_db  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Compte applicatif dedie (les services Java s'y connectent).
CREATE USER IF NOT EXISTS 'nomadix'@'%' IDENTIFIED BY 'nomadix123';
GRANT ALL PRIVILEGES ON nomadix_traveler_db.* TO 'nomadix'@'%';
GRANT ALL PRIVILEGES ON nomadix_flight_db.*   TO 'nomadix'@'%';
GRANT ALL PRIVILEGES ON nomadix_booking_db.*  TO 'nomadix'@'%';
FLUSH PRIVILEGES;
