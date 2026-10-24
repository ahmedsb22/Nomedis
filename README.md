<div align="center">

# 🌍 Nomadix

**Plateforme de Réservation de Voyages en Architecture Microservices**  
*Projet final du module Applications Web Distribuées (MT-41) - ESPRIT 2026/2027*

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.java.com/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Angular](https://img.shields.io/badge/Angular-18-red.svg)](https://angular.io/)
[![Python](https://img.shields.io/badge/Python-FastAPI-blue.svg)](https://fastapi.tiangolo.com/)
[![Node.js](https://img.shields.io/badge/Node.js-Express-green.svg)](https://nodejs.org/)

</div>

---

##  Sommaire
1. [À propos du projet](#-à-propos-du-projet)
2. [Notre équipe et répartition](#-notre-équipe-et-répartition)
3. [Choix d'architecture](#-choix-darchitecture)
4. [Détail des Microservices](#-détail-des-microservices)
5. [Guide d'installation et de lancement](#-guide-dinstallation-et-de-lancement)
6. [Qualité et Documentation](#-qualité-et-documentation)
7. [Axes d'amélioration (Perspectives)](#-axes-damélioration-perspectives)

---

##  À propos du projet

Dans le cadre du module **MT-41**, nous avons développé **Nomadix**, une plateforme de réservation de vols, de gestion des voyageurs et de conférences.

L'objectif principal n'était pas seulement de faire une application de réservation, mais de relever le défi technique d'une **architecture distribuée** : gérer des stocks limités (places d'avion) et assurer la cohérence des données même en cas de pic de trafic ou d'échec d'une étape (ex: annulation automatique si le paiement échoue, via une ébauche de Pattern Saga).

L'application est découpée en services indépendants, chacun avec sa propre base de données, communiquant via une **API Gateway** centrale et enregistrés dynamiquement sur **Eureka**.

---

##  Notre équipe et répartition

Pour garantir un développement parallèle efficace, nous avons réparti les responsabilités de manière équilibrée. Chaque membre est propriétaire de ses services (du code à la documentation Swagger).

| Membre | Rôle principal | Services & Composants sous sa responsabilité |
| :--- | :--- | :--- |
| **Ahmed** (`ahmedsb22`) | Architecture & Intégration | API Gateway, Eureka Server, **Booking Service** (Orchestration), Configuration Git/CI. |
| **Sami** (`samibaazaoui`) | Cœur Métier & Frontend | **Flight Service** (Gestion des stocks), **Conference Service** (Node.js), Interface Angular. |
| **Mohamed Hazem** (`Mohamedhazem.Amor`) | Services Support & Qualité | **Traveler Service** (voyageurs, favoris, profiling), **Notification Service** (Python/FastAPI), Tests d'intégration, README. |

---

## Choix d'architecture

Nous avons suivi les bonnes pratiques du module pour garantir un découpage propre et maintenable :

- **Database-per-service** : Règle d'or respectée. Chaque microservice a sa propre base MySQL (`nomadix_traveler_db`, `nomadix_flight_db`, `nomadix_booking_db`). Aucune jointure SQL n'est faite entre les services ; les liens se font uniquement via des identifiants (ex: `flightId` dans la réservation).
- **Service Discovery (Eureka)** : Pour éviter de coder les URLs en dur. La Gateway interroge Eureka pour savoir où router les requêtes, ce qui facilite l'ajout futur de nouvelles instances (scalabilité).
- **API Gateway (Spring Cloud Gateway)** : Point d'entrée unique (**port 8089**). Elle gère le routage dynamique (`lb://`) et la configuration CORS pour autoriser notre frontend Angular. Le frontend ne connaît qu'une seule adresse : la Gateway.
- **Stack Polyglotte** : Nous avons volontairement utilisé **Java/Spring Boot** pour le cœur métier, **Node.js** pour le service de conférences (léger et rapide pour les I/O), et **Python/FastAPI** pour les notifications, afin de démontrer notre capacité à intégrer des technologies hétérogènes.

>  **Documentation visuelle** : Les diagrammes d'architecture (logique, physique) et le diagramme de classes UML sont disponibles dans le dossier [`documentation/`](documentation/).

---

## 🧩 Détail des Microservices

| Service | Port | Base de données | Rôle clé | Lien Swagger |
| :--- | :---: | :--- | :--- | :--- |
| **Eureka Server** | 8761 | - | Registre des services | [Dashboard](http://localhost:8761) |
| **API Gateway** | 8089 | - | Routage, CORS, Point d'entrée | [Routes](http://localhost:8089/actuator/gateway/routes) |
| **Traveler Service** | 8081 | `nomadix_traveler_db` | Voyageurs, favoris et Smart Profiling (score/segments) | [Swagger](http://localhost:8081/swagger-ui.html) |
| **Flight Service** | 8082 | `nomadix_flight_db` | Catalogue des vols et gestion du stock (`availableSeats`) | [Swagger](http://localhost:8082/swagger-ui.html) |
| **Booking Service** | 8083 | `nomadix_booking_db` | Réservations et orchestration (OpenFeign + Circuit Breaker) | [Swagger](http://localhost:8083/swagger-ui.html) |
| **Conference Service** | 3000 | - (mémoire) | CRUD des conférences | [Swagger](http://localhost:3000/api-docs) |
| **Notification Service**| 8084 | - (mémoire) | Envoi de confirmations + consommateur RabbitMQ | [Swagger](http://localhost:8084/swagger-ui) |

---

##  Guide d'installation et de lancement

### 1. Prérequis
Assurez-vous d'avoir installé sur votre machine :
- **JDK 17** et **Maven**
- **Node.js** (v18 ou v20) et **npm**
- **Python 3.10+**
- **XAMPP** (MySQL doit tourner sur le port `3306` avec l'utilisateur `root` et mot de passe vide).

### 2. Initialisation des bases de données
1. Lancez MySQL depuis le panneau de contrôle XAMPP (`root`, mot de passe vide).
2. Aucun script SQL n'est nécessaire : chaque service crée sa base au premier démarrage
   (`createDatabaseIfNotExist=true`) et Hibernate génère les tables (`ddl-auto=update`).
   Les trois bases attendues sont `nomadix_traveler_db`, `nomadix_flight_db` et `nomadix_booking_db`.

### 3. Lancement du Backend 
Ouvrez un terminal séparé pour chaque étape :
```bash
# Étape A : Le registre (doit être lancé en premier)
cd backend/discovery/eureka-server
mvn spring-boot:run

# Étape B : La Gateway
cd backend/gateway
mvn spring-boot:run

# Étape C : Les microservices Java (dans 3 terminaux différents)
cd backend/microservices/traveler-service && mvn spring-boot:run
cd backend/microservices/flight-service && mvn spring-boot:run
cd backend/microservices/booking-service && mvn spring-boot:run

# Étape D : Le service Node.js (Conférences)
cd backend/microservices/conference-service
npm install
npm start

# Étape E : Le service Python (Notification)
cd backend/microservices/notification
py -m pip install -r requirements.txt
py -m uvicorn app.main:app --reload --port 8084
```

### 4. Lancement du Frontend
```bash
cd frontEnd/NomadixWeb
npm install
npm start   # ng serve --port 4200 --proxy-config proxy.conf.json
```

Le proxy de développement envoie **toutes** les requêtes `/api/**` vers la Gateway
(`http://localhost:8089`) : le frontend n'appelle jamais un microservice en direct.


