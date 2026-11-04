# 📘 KNOWLEDGE2.md — Nomadix : architecture, code, dépendances et audit des scénarios

> **Statut** : ce document **remplace** `knowledge.md` (conservé pour historique).
> Il ne décrit pas ce que le projet *devrait* faire : il décrit ce que le code **fait réellement**,
> avec les extraits exacts, les ports réels et — section 4 — un **audit honnête** de ce que les
> scénarios prouvent, et de ce qu'ils ne prouvent pas.
>
> **Méthode** : lecture intégrale des sources (Java, Python, JS/TS, YAML, SQL, Dockerfile),
> confrontation avec les logs d'exécution du runbook `documentation/scripts/run-scenarios.sh`.

---

## Sommaire

| § | Contenu | À utiliser quand… |
|---|---|---|
| [0](#0-comment-lire-ce-document) | Ce qui change par rapport à `knowledge.md` | tu cherches une réponse précise |
| [1](#1-vue-densemble-et-workflow-de-communication) | Big picture, ports, cycle d'une requête, figures | question d'ouverture du jury |
| [2](#2-les-quatre-mécanismes-clés-avec-le-code) | AOP, Saga Feign, Circuit Breaker, RabbitMQ | cœur de la soutenance |
| [3](#3-fichiers-et-dépendances) | Rôle de chaque fichier / dépendance / variable | « à quoi sert ce fichier ? » |
| [4](#4-audit-des-scénarios-sont-ils-corrects-et-complets) | **Audit S1→S12 : corrects ? complets ?** | « tes tests prouvent quoi ? » |
| [5](#5-les-50-questions-de-soutenance) | 50 questions + réponses | entraînement |
| [6](#6-règles-dor-pour-la-démonstration) | 5 pièges à éviter | jour J |
| [7](#7-les-chiffres-à-connaître-par-cœur) | valeurs exactes à citer | révision express |

---

## 0. Comment lire ce document

`knowledge.md` expliquait surtout **comment lancer** les scénarios. `knowledge2.md` explique
**pourquoi le code est écrit ainsi**, et surtout **ce que la démonstration ne prouve pas** :
c'est la différence entre un mode d'emploi et une défense technique.

| Sujet | `knowledge.md` | `knowledge2.md` |
|---|---|---|
| Public | opérateur qui lance le script | étudiant qui défend l'architecture |
| Code | citations courtes | extraits intégraux des points critiques |
| Scénarios | « voilà ce que fait S7 » | « S7 prouve X, ne prouve pas Y, voici pourquoi » |
| Dépendances | liste | liste + **justification de chaque artefact** |
| Limites | mentionnées | **cartographiées** (§4.3 et §4.4) |

Trois règles de lecture :

1. Tout ce qui est entre ``` est **copié du dépôt**, jamais réécrit de mémoire.
2. Les chiffres (ports, seuils, scores) sont ceux de la configuration actuelle.
3. Quand un comportement est une **limite assumée**, c'est écrit « limite » — ne le présente
   jamais au jury comme une fonctionnalité.

---

## 1. Vue d'ensemble et workflow de communication

### 1.1 Nomadix en trois paragraphes

**Nomadix** est une plateforme de voyage découpée en microservices : un catalogue de vols
(`flight-service`), des voyageurs et leurs favoris (`traveler-service`), des réservations
(`booking-service`), des conférences (`conference-service`, Node/Express), des notifications
(`notification-service`, Python/FastAPI) et un frontend Angular servi par nginx. Chaque service
possède **sa propre base MySQL** : aucun service ne lit la base d'un autre, les liens
(`travelerId`, `flightId`) sont des **références logiques**, pas des clés étrangères.

Le projet démontre quatre mécanismes transverses : un **profilage automatique par AOP** côté
voyageurs (le score est calculé par un aspect, jamais par le contrôleur), une **saga synchrone
OpenFeign** côté réservations (valider le voyageur → lire le vol → décrémenter le stock → écrire,
avec compensation), une **résilience Resilience4j** (timeouts courts, fallback, circuit breaker
qui expose son état dans Actuator) et une **messagerie asynchrone RabbitMQ** (événements métier
publiés en *fire-and-forget*, file durable, *dead-letter queue* pour les messages empoisonnés).

L'infrastructure est celle d'un vrai système distribué : **Eureka** pour l'annuaire,
**Spring Cloud Gateway** comme point d'entrée unique, **Keycloak** pour l'identité (OAuth2 /
OpenID Connect, JWT signés, rôles `ADMIN` / `TRAVELER`), **nginx** pour servir le SPA et proxifier
`/api`, le tout décrit dans un unique `docker-compose.yml`.

### 1.2 Les ports (à connaître par cœur)

| Composant | Port hôte | Port interne | Rôle |
|---|---|---|---|
| Frontend (nginx) | **8090** | 80 | sert Angular, proxifie `/api` → gateway |
| API Gateway | **9089** | 8089 | point d'entrée unique, sécurité, routage `lb://` |
| Eureka | **9761** | 8761 | annuaire de services |
| Traveler-Service | **9081** | 8081 | voyageurs + profilage + favoris |
| Flight-Service | **9082** | 8082 | catalogue + stock de places |
| Booking-Service | **9083** | 8083 | saga de réservation + événements |
| Notification-Service | **9084** | 8084 | API FastAPI (lecture de l'archive RabbitMQ) |
| Conference-Service | **3001** | 3000 | CRUD conférences (Node) |
| Keycloak | **8180** | 8180 | émission et signature des JWT |
| RabbitMQ | 5672 / **15672** | — | broker / console de gestion |
| MySQL | **3307** | 3306 | 3 bases : traveler, flight, booking |

La distinction **port hôte / port interne** n'est pas cosmétique : c'est elle qui explique
la configuration Eureka (§5, question 10) et la cause du bug « toutes les routes en 500 ».

### 1.3 Cycle complet d'une requête (12 étapes)

Exemple : l'utilisateur clique sur « Réserver » pour le vol 27 et le voyageur 23.

1. **Angular** (`booking.service.ts`) construit `POST /api/bookings?travelerId=23&flightId=27`.
2. L'**intercepteur HTTP** (`auth.interceptor.ts`) voit que l'URL commence par `/api`, appelle
   `keycloak.ensureFreshToken()` (rafraîchit si < 30 s de validité) et ajoute
   `Authorization: Bearer <jwt>`.
3. Le navigateur envoie la requête à `http://localhost:8090/api/bookings…` : c'est **nginx**.
4. **nginx** (`location /api/`) fait `proxy_pass http://gateway:8089` **sans slash final**,
   donc le préfixe `/api` est conservé, et relaie l'en-tête `Authorization`.
5. La **Gateway** décode le JWT avec le JWKS de Keycloak (signature, `iss`, `aud`), construit
   les autorités depuis `realm_access.roles`, puis applique sa matrice de droits.
6. La Gateway cherche une route : `Path=/api/bookings/**` → `lb://BOOKING-SERVICE`.
7. Le **LoadBalancer** interroge le registre **Eureka**, obtient `booking-service:8083`
   (nom + port **interne**), et forwarde la requête.
8. **Booking-Service** (port 8083) démarre la saga : `validateTraveler` (Feign → traveler),
   `checkFlightAvailability` puis `reserveSeat` (Feign → flight).
9. **Flight-Service** exécute un `UPDATE` conditionnel (`available_seats > 0`) : décrément atomique.
10. **Booking-Service** écrit la réservation dans `nomadix_booking_db` (commit JPA).
11. Il publie `booking.created` dans l'exchange `nomadix.events` (**après** l'écriture,
    fire-and-forget : un broker éteint ne peut pas annuler une réservation).
12. Le **consommateur Python** reçoit le message, l'écrit dans `/data/notifications.json`
    (volume partagé) puis `basic_ack`. La réponse HTTP `201` est déjà repartie vers Angular.

### 1.4 Figure 1 — Chaîne synchrone (navigateur → base de données)

Lecture : on descend, chaque encadré est un saut réseau. Les flèches latérales sont les
dépendances appelées depuis l'encadré.

```
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ ① NAVIGATEUR          Angular 21 + keycloak-js (Authorization Code + PKCE S256)               │
│                       http://localhost:8090                                                   │
└───────────────────────────────────────┬──────────────────────────────────────────────────────┘
                                        │  GET /api/**   +   Authorization: Bearer <jwt>
                                        ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ ② NGINX               conteneur « frontend »  (80 interne, publié 8090)                       │
│      location /api/  →  proxy_pass http://gateway:8089      le préfixe /api est CONSERVÉ      │
│      location /      →  index.html                           routes du SPA (fallback)         │
└───────────────────────────────────────┬──────────────────────────────────────────────────────┘
                                        ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ ③ API GATEWAY         8089 interne / 9089 hôte                                                │
│      [a] valide la signature du JWT   ──── JWKS (clés en cache) ────▶  Keycloak :8180         │
│      [b] applique la matrice de droits  ────────────────────────────▶  401 | 403 | 200        │
│      [c] choisit la route               Path=/api/xxx ──────────────▶  lb://XXX-SERVICE       │
└───────────────────────────────────────┬──────────────────────────────────────────────────────┘
                                        ▼  (Spring Cloud LoadBalancer)
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ ④ EUREKA              8761 interne / 9761 hôte                                                │
│      annuaire : quelle instance est UP, sur quel hostName:port  (toujours le port INTERNE)    │
└───┬───────────────┬───────────────┬───────────────┬────────────────┬─────────────────────────┘
    ▼               ▼               ▼               ▼                ▼
┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌──────────────┐ ┌──────────────────┐
│ traveler    │ │ flight      │ │ booking     │ │ notification │ │ conference       │
│ :8081       │ │ :8082       │ │ :8083       │ │ :8084        │ │ :3000            │
├─────────────┤ ├─────────────┤ ├─────────────┤ ├──────────────┤ ├──────────────────┤
│ MySQL       │ │ MySQL       │ │ MySQL       │ │ JSON         │ │ mémoire          │
│ traveler_db │ │ flight_db   │ │ booking_db  │ │ /data        │ │ (Node/Express)   │
│ + AOP       │ │ + stock     │ │ + SAGA      │ │ FastAPI      │ │                  │
│ + Feign     │ │   atomique  │ │ + Feign     │ │ + Eureka     │ │ + Eureka         │
└─────────────┘ └─────────────┘ └─────────────┘ └──────────────┘ └──────────────────┘
        │                ▲                │
        │  Feign (favoris enrichis)       │  Feign (saga : traveler + flight)
        └────────────────┴────────────────┘
```

### 1.5 Figure 2 — Chaîne asynchrone (booking → RabbitMQ → Python → fichier → API)

Lecture : rien ici n'est dans le chemin de la réponse HTTP. Le `201` est déjà parti quand
le message est consommé.

```
   booking-service :8083                                    RabbitMQ :5672                     consommateur Python
   ─────────────────────                                    ─────────────                     ────────────────────
   bookingRepository.save(booking)
            │
            │ commit JPA  →  nomadix_booking_db.bookings
            ▼
   publisher.bookingCreated(saved)
            │
            │ convertAndSend("nomadix.events", "booking.created", json)
            ▼
   ┌────────────────────────────────────────────────────────────────────┐
   │  exchange  nomadix.events     type = topic, durable = true         │
   └───────────────┬────────────────────────────────────────────────────┘
                   │  bindings :  flight.#   traveler.#   booking.#
                   ▼
   ┌────────────────────────────────────────────────────────────────────┐
   │  queue  notification.events   durable = true                       │
   │  arguments : x-dead-letter-exchange     = nomadix.dlx              │
   │              x-dead-letter-routing-key  = notification.events.dlq  │
   └───────────────┬────────────────────────────────────────────────────┘
                   │  pull — prefetch_count = 10
                   ▼
   ┌────────────────────────────────────────────────────────────────────┐
   │  packh_rabbit_consumer.py      (pika, un processus séparé)         │
   └───────────────┬────────────────────────────────┬───────────────────┘
                   │                                │
        JSON valide │                                │ JSON invalide, ou simulateFailure=true
                   ▼                                ▼
   ┌───────────────────────────────┐   ┌──────────────────────────────────────────┐
   │ save_notification(entry)      │   │ basic_nack(requeue = false)              │
   │ /data/notifications.json      │   │ → RabbitMQ applique la dead-letter policy│
   │ puis basic_ack                │   └──────────────────┬───────────────────────┘
   └───────────────┬───────────────┘                      ▼
                   │                        ┌──────────────────────────────────────┐
                   │                        │ exchange  nomadix.dlx  (topic)       │
                   │                        └──────────────────┬───────────────────┘
                   │                                           ▼
                   │                        ┌──────────────────────────────────────┐
                   │                        │ queue  notification.events.dlq       │
                   │                        │ en-tête x-death : reason=rejected,   │
                   │                        │ queue=notification.events            │
                   │                        └──────────────────────────────────────┘
                   ▼
   ┌────────────────────────────────────────────────────────────────────┐
   │ GET /api/notifications/events?limit=N   (via la Gateway, JWT)      │
   │ volume Docker nomadix-notifications monté dans les DEUX conteneurs │
   └────────────────────────────────────────────────────────────────────┘
```

### 1.6 Figure 3 — La saga de réservation, étape par étape

```
POST /api/bookings?travelerId=23&flightId=27
      │
      │  BearerTokens.current()  →  "Bearer eyJ..."  capturé AVANT tout appel asynchrone
      ▼
 ①  TravelerClient.getTravelerById  ──Feign──▶  traveler-service :8081
      │                                            404 ──▶ 404 « Voyageur 999999 inexistant »
      │                                            null ─▶ 503 (fallback = panne)
      ▼
 ②  FlightClient.getFlightById      ──Feign──▶  flight-service :8082
      │                                            404 ──▶ 404 « Vol inexistant »
      │                                            null ─▶ 503 (fallback = panne)
      │                                            seats ≤ 0 ──▶ 409 « Vol complet »
      ▼
 ③  FlightClient.reserveSeat        ──Feign──▶  UPDATE flight
      │                                         SET available_seats = available_seats - 1
      │                                         WHERE id = ? AND available_seats > 0
      │                                         0 ligne modifiée ──▶ 409 (anti-surréservation)
      ▼
 ④  bookingRepository.save(booking) ──JDBC───▶  nomadix_booking_db.bookings   (commit local)
      │           totalPrice = flight.getPrice()   ← le client ne l'envoie jamais
      ▼
 ⑤  publisher.bookingCreated(saved)  ──AMQP──▶  nomadix.events / booking.created
      │
      └─── si ④ ou ⑤ lève une RuntimeException :
              releaseSeat()   =  COMPENSATION de ③  (+1 place)
              puis l'exception est relancée → l'appelant voit 500/503
```

---

## 2. Les quatre mécanismes clés avec le code

### Mécanisme A — Smart Profiling par AOP

**Concept** : le score du voyageur est calculé par un aspect interceptant les `save()` du
repository, donc **aucune ligne du contrôleur ni du service métier ne parle de scoring**.

**Code — le point de coupe** (`TravelerProfilerAspect.java`) :

```java
@Aspect
@Component
public class TravelerProfilerAspect {

    private final TravelerProfilerService profilerService;

    /** Apres chaque save() d'un traveler (l'entite retournee porte l'id genere). */
    @AfterReturning(
            pointcut = "execution(* com.nomadix.traveler..*Repository+.save(..))",
            returning = "entity")
    public void onTravelerSaved(JoinPoint jp, Object entity) {
        if (entity == null) return;
        if (entity.getClass().getSimpleName().equals("TravelerProfile")) return; // anti-recursivite
        profilerService.profileAndSave(entity);
    }
```

**Code — le calcul** (`ProfilingRulesEngine.java`, extrait du barème) :

```java
private static final int PTS_COMPLETENESS   = 40; // 6 champs -> 40 pts au prorata
private static final int PTS_EMAIL_VALID    = 10;
private static final int PTS_EMAIL_CLEAN    = 5;  // non jetable
private static final int PTS_PASSPORT_VALID = 15;
private static final int PTS_PHONE_VALID    = 10;
private static final int PTS_NAME_QUALITY   = 5;
private static final int PTS_NATIONALITY    = 5;
private static final int PTS_COHERENCE      = 10; // email ET passeport valides
...
r.setCompleteness((int) Math.round(filled * 100 / 6.0));
score += (int) Math.round(filled * PTS_COMPLETENESS / 6.0);
...
if (r.isValidEmail() && r.isValidPassport()) {
    score += PTS_COHERENCE;
}
r.setScore(Math.max(0, Math.min(100, score)));
if (r.getScore() >= 80)      r.setSegment("PLATINUM");
else if (r.getScore() >= 65) r.setSegment("GOLD");
else if (r.getScore() >= 45) r.setSegment("SILVER");
else                         r.setSegment("BRONZE");
if (r.getScore() < 45) r.addRiskFlag("PROFIL_A_RISQUE");
```

**Annotations expliquées** :

| Annotation | Ce qu'elle fait vraiment |
|---|---|
| `@Aspect` | déclare la classe comme aspect ; Spring la détecte via `@Component` et crée un proxy autour des beans ciblés |
| `@AfterReturning` | conseil exécuté **après le retour normal** de la méthode ; si le `save()` lève une exception, le conseil ne s'exécute pas |
| `returning = "entity"` | injecte la valeur de retour dans `Object entity` — indispensable ici, car **l'id n'existe qu'après l'INSERT** |
| `execution(* ..*Repository+.save(..))` | pointcut : n'importe quelle méthode `save` de n'importe quel repository du package `com.nomadix.traveler` |
| `@Component` | enregistre l'aspect ; sans lui, `@Aspect` est ignoré |

**Barème appliqué à la main (à savoir refaire au tableau)** :

| Profil | Détail | Score | Segment |
|---|---|---|---|
| complet (6/6, email valide non jetable, passeport valide, tél valide, nom avec espace, nationalité) | 40 + 10 + 5 + 15 + 10 + 5 + 5 + 10 | **100** | PLATINUM |
| incomplet (`Karim X`, `karim@mailinator.com`, rien d'autre) | complétude = round(2×100/6) = **33** ; points = round(2×40/6) = 13 ; email valide +10 ; jetable donc 0 ; nom avec espace +5 | **28** | BRONZE |

**Pourquoi c'est une bonne pratique** : le profiling est une **préoccupation transverse**.
Le mettre dans `TravelerService.create()` l'aurait dupliqué dans `update()`, dans
`FavoriteFlightService.saveFavoriteFlight()` (qui sauvegarde aussi un voyageur) et l'aurait
couplé au métier. Ici, ajouter un point d'écriture = zéro ligne à modifier.

**Limites assumées** : le pointcut est plus large que nécessaire (il attrape *tous* les
repositories du package, d'où le garde-fou anti-récursivité) ; le profiling s'exécute dans
la même requête HTTP (quelques ms) ; et une erreur de profiling ne fait **jamais** échouer
le métier — c'est un `try/catch` explicite avec `log.warn`.

---

### Mécanisme B — Saga synchrone avec OpenFeign

**Concept** : `booking-service` n'écrit jamais un prix ou un stock : il **orchestre** deux appels
synchrones puis compense si sa propre écriture échoue.

**Code — les clients** (`FlightClient.java`) :

```java
@FeignClient(name = "flight-service", fallbackFactory = FlightClientFallbackFactory.class)
public interface FlightClient {

    @GetMapping("/api/flights/{id}")
    FlightDTO getFlightById(@PathVariable("id") Long id,
                            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);

    /** Decrement atomique du stock cote Flight-Service (409 si complet). */
    @PostMapping("/api/flights/{id}/seats/reserve")
    FlightDTO reserveSeat(@PathVariable("id") Long id,
                          @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);

    /** Restitution d'une place lors d'une annulation (compensation). */
    @PostMapping("/api/flights/{id}/seats/release")
    FlightDTO releaseSeat(@PathVariable("id") Long id,
                          @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization);
}
```

**Code — l'orchestration** (`BookingService.java`) :

```java
public Booking createBooking(Long travelerId, Long flightId) {
    String authorization = BearerTokens.current();

    validateTraveler(travelerId, authorization);                          // 1. Feign traveler
    FlightDTO flight = checkFlightAvailability(flightId, authorization);  // 2. Feign flight
    reserveSeat(flightId, authorization);                                 // 3. UPDATE atomique

    Booking booking = new Booking();
    booking.setTravelerId(travelerId);
    booking.setFlightId(flightId);
    booking.setTotalPrice(flight.getPrice());            // prix = source de verite Flight
    booking.setStatus("CONFIRMED");

    try {
        Booking saved = bookingRepository.save(booking); // 4. ecriture locale
        eventPublisher.bookingCreated(saved);            // 5. evenement APRES le commit
        return saved;
    } catch (RuntimeException e) {
        releaseQuietly(flightId, authorization);         // COMPENSATION
        throw e;
    }
}
```

**Annotations expliquées** :

| Annotation | Rôle |
|---|---|
| `@FeignClient(name = "flight-service")` | génère un proxy HTTP ; `name` = identifiant **Eureka**, jamais une URL |
| `fallbackFactory = …` | fabrique le fallback **avec la cause** de l'échec (permet de distinguer 404 / 409 / panne) |
| `@GetMapping` / `@PostMapping` | contrat HTTP copié du contrôleur distant |
| `@PathVariable("id")` | lie le paramètre au segment `{id}` |
| `@RequestHeader(AUTHORIZATION)` | transmet le jeton **explicitement** (voir mécanisme C) |

**Pourquoi cette architecture** : c'est une **saga par orchestration** — un seul service connaît
la séquence, contrairement à une chorégraphie où chaque service réagit à des événements. Elle
évite le 2PC (*two-phase commit*), impossible entre bases hétérogènes et indisponible en HTTP,
au prix d'une **cohérence à terme** et d'une compensation explicite.

**Point faible à assumer** : si le processus meurt **entre** `reserveSeat` et `save`, aucune
compensation ne s'exécute (pas de journal de saga durable, pas de *transactional outbox*).
C'est une saga **au mieux** ; la version industrielle utiliserait un état `PENDING` persistant
+ un job de réconciliation.

---

### Mécanisme C — Résilience : timeouts courts, fallback, circuit breaker

**Concept** : quand Flight est en panne, l'utilisateur reçoit un **503 en millisecondes** au lieu
d'attendre le timeout TCP, et le circuit s'ouvre pour ne plus marteler le service mort.

**Code — configuration** (`booking-service/application.properties`) :

```properties
spring.cloud.openfeign.circuitbreaker.enabled=true
spring.cloud.openfeign.client.config.default.connect-timeout=2000
spring.cloud.openfeign.client.config.default.read-timeout=3000
spring.cloud.openfeign.client.config.flight-service.connect-timeout=2000
spring.cloud.openfeign.client.config.flight-service.read-timeout=3000
# TimeLimiter Resilience4j : superieur au read-timeout Feign - 4 s > 3 s
resilience4j.timelimiter.instances.flight-service.timeout-duration=4s
resilience4j.circuitbreaker.instances.flight-service.sliding-window-type=COUNT_BASED
resilience4j.circuitbreaker.instances.flight-service.sliding-window-size=10
resilience4j.circuitbreaker.instances.flight-service.minimum-number-of-calls=5
resilience4j.circuitbreaker.instances.flight-service.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.flight-service.wait-duration-in-open-state=10s
resilience4j.circuitbreaker.instances.flight-service.permitted-number-of-calls-in-half-open-state=3

management.health.circuitbreakers.enabled=true
management.endpoint.health.show-details=always
```

**Code — le fallback** (`FlightClientFallbackFactory.java`) :

```java
@Override
public FlightClient create(final Throwable cause) {
    log.warn("[CIRCUIT BREAKER] Flight-Service indisponible -> fallback. Cause : {}", cause.toString());
    return new FlightClient() {
        @Override
        public FlightDTO getFlightById(Long id, String authorization) {
            // Un 404 est une reponse metier, pas une panne : on la propage telle quelle.
            if (cause instanceof FeignException.NotFound) {
                throw (FeignException) cause;
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Flight-Service indisponible : timeout ou circuit breaker OPEN");
        }
        ...
        @Override
        public FlightDTO releaseSeat(Long id, String authorization) {
            log.error("[COMPENSATION] Impossible de restituer la place du vol {} : {}", id, cause.toString());
            return null;
        }
    };
}
```

**Code — le jeton hors du thread de requête** (`BearerTokens.java`) :

```java
public static String current() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getCredentials() instanceof AbstractOAuth2Token token) {
        return "Bearer " + token.getTokenValue();
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
            "Aucun jeton utilisateur disponible pour l'appel inter-services");
}
```

**Annotations / propriétés expliquées** :

| Élément | Effet |
|---|---|
| `spring.cloud.openfeign.circuitbreaker.enabled` | enveloppe chaque méthode Feign dans un `CircuitBreaker` Resilience4j |
| `fallbackFactory` (vs `fallback`) | reçoit la `Throwable` : indispensable pour **ne pas transformer un 404 en 503** |
| `failure-rate-threshold=50` | ≥ 50 % d'échecs sur la fenêtre → ouverture |
| `minimum-number-of-calls=5` | pas d'ouverture avant 5 appels (évite l'ouverture sur un seul échec au démarrage) |
| `wait-duration-in-open-state=10s` | durée avant `HALF_OPEN` |
| `permitted-number-of-calls-in-half-open-state=3` | 3 appels de sonde pour décider |
| `CircuitBreakerNameResolver` | sans ce bean, le CB s'appelle `client#methode(...)` et **aucune** propriété `instances.flight-service.*` ne s'applique |

**Pourquoi c'est une bonne pratique** : trois niveaux de protection en cascade —
*timeout* (borner l'attente), *fallback* (répondre utile), *circuit breaker* (protéger le
service fautif et le thread appelant). Le contexte Spring Security n'étant pas propagé dans
le thread du `CompletableFuture` Resilience4j, le jeton est **capturé avant** l'appel et
transmis en en-tête : c'est la raison d'être de `BearerTokens`.

**Valeurs observées en démonstration** (S7, Flight arrêté) : appels 1→4 ≈ **4,5 s** (timeout
Feign 3 s + TimeLimiter 4 s), appels 5 et 6 ≈ **0,06 s** (circuit ouvert, fallback immédiat).

---

### Mécanisme D — Messagerie asynchrone, durabilité et DLQ

**Concept** : les événements métier partent dans un *exchange topic* et sont consommés par un
processus Python indépendant ; un message invalide ne bloque pas la file, il part en **DLQ**.

**Topologie** (`backend/messaging/rabbitmq/definitions.json`, importée au démarrage) :

```json
{
  "exchanges": [
    { "name": "nomadix.events", "type": "topic", "durable": true },
    { "name": "nomadix.dlx",    "type": "topic", "durable": true }
  ],
  "queues": [
    {
      "name": "notification.events",
      "durable": true,
      "arguments": {
        "x-queue-type": "classic",
        "x-dead-letter-exchange": "nomadix.dlx",
        "x-dead-letter-routing-key": "notification.events.dlq"
      }
    },
    { "name": "notification.events.dlq", "durable": true }
  ],
  "bindings": [
    { "source": "nomadix.events", "destination": "notification.events", "routing_key": "flight.#" },
    { "source": "nomadix.events", "destination": "notification.events", "routing_key": "traveler.#" },
    { "source": "nomadix.events", "destination": "notification.events", "routing_key": "booking.#" },
    { "source": "nomadix.dlx", "destination": "notification.events.dlq", "routing_key": "notification.events.dlq" }
  ]
}
```

**Code — publication fire-and-forget** (`BookingEventPublisher.java`) :

```java
/** Utilise par l'endpoint de demonstration /api/packh/booking-events/emit. */
public boolean publishRaw(String routingKey, String jsonPayload) {
    try {
        rabbitTemplate.convertAndSend(BookingRabbitConfig.EVENTS_EXCHANGE, routingKey, jsonPayload);
        log.info("[RABBITMQ] publie {} -> {}", routingKey, jsonPayload);
        return true;
    } catch (Exception e) {
        log.warn("[RABBITMQ] publication {} impossible (service toujours disponible) : {}",
                routingKey, e.getMessage());
        return false;
    }
}
```

**Code — consommation et refus** (`packh_rabbit_consumer.py`) :

```python
def callback(ch, method, properties, body):
    raw = body.decode("utf-8", errors="replace")

    # --- Validation : un message invalide part en DLQ, il ne bloque pas la file
    try:
        payload = json.loads(raw)
        if not isinstance(payload, dict):
            raise ValueError("le corps JSON doit etre un objet")
        if payload.get("simulateFailure") is True:
            raise ValueError("simulateFailure=true demande par l'emetteur")
    except Exception as exc:
        print("[notif][DLQ] message refuse sur '%s' (%s) -> %s via %s"
              % (method.routing_key, exc, DLQ, DLX))
        # requeue=False : RabbitMQ applique la dead-letter policy de la file.
        ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
        return

    try:
        entry = {"routingKey": method.routing_key,
                 "receivedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
                 "payload": payload}
        save_notification(entry)
    except Exception as exc:
        print("[notif][DLQ] echec d'enregistrement (%s) -> %s" % (exc, DLQ))
        ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
        return

    kind = payload.get("type", "?")
    detail = ""
    for key in ("flightNumber", "bookingId", "travelerId", "path"):
        if key in payload:
            detail = str(payload[key])
            break
    print("[notif] recu %s : %s %s" % (method.routing_key, kind, detail))
    ch.basic_ack(delivery_tag=method.delivery_tag)
```

**Annotations / primitives expliquées** :

| Élément | Signification |
|---|---|
| `convertAndSend(exchange, routingKey, payload)` | publie dans l'exchange **topic** avec la clé de routage ; aucune file n'est nommée côté producteur (découplage total) |
| `basic_ack` | acquittement : le message est supprimé du broker **après** l'écriture disque |
| `basic_nack(requeue=false)` | refus **sans** remise en file : RabbitMQ applique `x-dead-letter-exchange` |
| `durable: true` (exchange + queue) | survit au redémarrage du broker |
| `x-dead-letter-routing-key` | réécrit la clé de routage en entrant dans le DLX — c'est pour cela que le message arrive dans `notification.events.dlq` même s'il a été publié en `booking.poison` |
| `prefetch_count=10` | au plus 10 messages non acquittés par consommateur (pression mémoire bornée) |

**Pourquoi c'est une bonne pratique** : découplage **temporel** (le consommateur peut être
arrêté : la file stocke) et **spatial** (le producteur ignore qui consomme). La DLQ empêche
qu'un message empoisonné boucle indéfiniment : `requeue=false` + `x-death` rendent l'incident
**observable** au lieu d'être un blocage silencieux.

**Limite importante (à ne pas cacher)** : `convertAndSend` sans `publisher-confirm` ni
`mandatory` ne lève rien si aucun binding ne matche la clé. `publishRaw` renvoie donc
`true` alors que le message a été **silencieusement jeté**. Voir §4.4.

---

## 3. Fichiers et dépendances

### 3.1 Arborescence commentée

```
AWD-Training/
├── docker-compose.yml                   11 conteneurs, ancrage x-java-common, healthchecks
├── docker/mysql/init/01-databases.sql   3 bases + utilisateur applicatif nomadix
├── backend/
│   ├── discovery/eureka-server/         annuaire (ne s'auto-enregistre pas)
│   ├── gateway/                         Spring Cloud Gateway + OAuth2 Resource Server
│   ├── messaging/rabbitmq/              definitions.json (topologie) + rabbitmq.conf
│   ├── keycloak/nomadix-realm.json      realm importé au démarrage (rôles, clients)
│   └── microservices/
│       ├── traveler-service/            Java + AOP + Feign + AMQP (producteur)
│       ├── flight-service/              Java + stock atomique + AMQP (producteur)
│       ├── booking-service/             Java + saga Feign + Resilience4j + AMQP
│       ├── conference-service/          Node/Express + eureka-js-client
│       └── notification/                FastAPI (API) + pika (consommateur), même image
├── frontEnd/NomadixWeb/                 Angular 21, nginx.conf, proxy.conf.json (dev)
└── documentation/
    ├── scenario.md                      runbook de soutenance (narratif)
    └── scripts/run-scenarios.sh         runbook exécutable S1..S12 + assertions
```

### 3.2 Rôle de chaque fichier

**Infrastructure**

| Fichier | Rôle précis |
|---|---|
| `docker-compose.yml` | 11 services, ancrage `x-java-common` (Eureka, Keycloak, RabbitMQ), `mem_limit`, `JAVA_OPTS` (SerialGC, `TieredStopAtLevel=1`), `depends_on: condition: service_healthy`, volumes nommés |
| `docker/mysql/init/01-databases.sql` | crée `nomadix_traveler_db`, `nomadix_flight_db`, `nomadix_booking_db` + utilisateur `nomadix` (règle database-per-service dès l'init) |
| `backend/messaging/rabbitmq/definitions.json` | topologie complète : 2 exchanges, 2 files, 4 bindings, arguments DLX |
| `backend/messaging/rabbitmq/rabbitmq.conf` | charge `definitions.json` au boot du broker |
| `backend/keycloak/nomadix-realm.json` | realm `nomadix` : clients `nomadix-cli` / `nomadix-swagger`, rôles `ADMIN` / `TRAVELER`, audience `nomadix-api` |

**Gateway** (`backend/gateway/`)

| Fichier | Rôle |
|---|---|
| `application.yml` | 9 routes (`/api/flights`, `/api/travelers`, `/api/bookings`, `/api/conferences`, `/api/notifications`, 4 routes `packh`), `lb://`, `globalcors` |
| `security/SecurityConfig.java` | matrice 401/403/200, `JwtDecoder` construit sur le JWKS (pas d'`issuer-uri` seul), JSON d'erreur homogène |
| `security/KeycloakRoles.java` | convertit `realm_access.roles` en `ROLE_ADMIN` / `ROLE_TRAVELER` |
| `config/CorsConfig.java` | `CorsWebFilter` en `HIGHEST_PRECEDENCE` : sans lui, les 401/403 n'ont pas d'en-tête CORS |

**Traveler-Service**

| Fichier | Rôle |
|---|---|
| `controller/TravelerController.java` | CRUD `/api/travelers` (201 en création) |
| `controller/FavoriteFlightController.java` | favoris : stocke des ids, enrichit par Feign |
| `service/TravelerService.java` | DTO → entité, seul endroit qui décide des champs écrits |
| `service/FavoriteFlightService.java` | valide le vol par Feign **avant** d'ajouter le favori |
| `profiling/TravelerProfilerAspect.java` | point de coupe AOP (`@AfterReturning`) |
| `profiling/TravelerProfilerService.java` | extraction des attributs, persistance, `recompute`, fail-safe |
| `profiling/ProfilingRulesEngine.java` | barème pur, `RULES_VERSION = "1.1"`, segments |
| `profiling/TravelerProfileController.java` | `/profiles`, `/top`, `/segments`, `/statistics`, `/risky`, `/recompute` |
| `client/FlightClient.java` + `FallbackFactory` | lecture des vols ; fallback = `null` / liste vide |
| `client/CircuitBreakerNameResolverConfig.java` | nomme le CB `FLIGHT-SERVICE` (sinon config inopérante) |
| `security/BearerTokens.java` | capture le JWT avant le changement de thread |
| `messaging/PackHTravelerRabbitConfig.java` | déclare l'exchange, clé `traveler.registered` |
| `resources/data.sql` | 3 voyageurs de démonstration (`INSERT IGNORE`, joué si `SPRING_SQL_INIT_MODE=always`) |

**Flight-Service**

| Fichier | Rôle |
|---|---|
| `controller/FlightController.java` | CRUD + `/seats/reserve` + `/seats/release` (renvoie **200**, pas 201) |
| `service/FlightStockService.java` | `@Transactional` + `UPDATE` conditionnel : anti-surréservation |
| `security/SecurityConfig.java` | lecture publique, écriture ADMIN, `seats/*` authentifié (appel interne) |
| `messaging/PackHFlightRabbitConfig.java` | exchange + clé `flight.created` |
| `resources/data.sql` | 5 vols (`INSERT IGNORE`, ne réinitialise jamais un stock décrémenté) |

**Booking-Service**

| Fichier | Rôle |
|---|---|
| `controller/BookingController.java` | `POST` 201, `PUT ?status=` 200, `DELETE` 204 (idempotent) |
| `controller/FeignIntegrationController.java` | diagnostic `/integrations/feign` : rend OpenFeign **observable** |
| `service/BookingService.java` | la saga (étapes 1→5) + compensation + idempotence |
| `client/FlightClient.java` / `TravelerClient.java` | 3 méthodes / 1 méthode |
| `client/*FallbackFactory.java` | 404 et 409 propagés, panne → 503 |
| `security/BearerTokens.java` | jeton relayé aux deux services |
| `messaging/BookingEventPublisher.java` | `booking.created` / `booking.cancelled`, fire-and-forget |
| `messaging/RabbitScenarioController.java` | `/api/packh/rabbitmq/poison` (démo DLQ) |
| `messaging/PackHBookingEventController.java` | `/api/packh/booking-events/emit` |
| `resources/application.properties` | timeouts, CB, health-details, `server.error.include-message=always` |

**Notification (Python)**

| Fichier | Rôle |
|---|---|
| `app/main.py` | FastAPI, Swagger avec bouton Authorize, enregistrement Eureka + superviseur anti-éviction |
| `app/routers/notification.py` | CRUD mémoire + `GET /events` (lit `/data/notifications.json`) |
| `packh_rabbit_consumer.py` | consommateur pika : ack / nack, `prefetch=10`, reconnexion |
| `Dockerfile` | **une image, deux processus** : uvicorn (défaut) ou le consommateur (surchargé par Compose) |
| `requirements.txt` | `fastapi`, `uvicorn[standard]`, `py-eureka-client`, `httpx`, `pika==1.3.2` |

**Frontend**

| Fichier | Rôle |
|---|---|
| `nginx.conf` | proxy `/api/` → `gateway:8089` (sans slash final), `Authorization` relayé, SPA fallback, cache 30 j |
| `app.config.ts` | `provideAppInitializer` (Keycloak avant le 1er rendu), intercepteur HTTP, locale `fr-FR` |
| `auth/keycloak.service.ts` | PKCE S256, `check-sso`, `ensureFreshToken()` (`updateToken(30)`) |
| `auth/auth.interceptor.ts` | ajoute `Authorization` **uniquement** pour `/api` |
| `services/*.service.ts` | un service Angular par domaine (flight, traveler, booking, conference, notification) |

### 3.3 Dépendances : pourquoi chacune est là

| Dépendance | Où | Sans elle |
|---|---|---|
| `spring-boot-starter-parent` **3.2.4**, Java **17** | tous les modules Java | versions Spring cohérentes non garanties |
| `spring-cloud-dependencies` **2023.0.1** (BOM) | tous | OpenFeign/Gateway/Eureka incompatibles entre eux |
| `spring-cloud-starter-openfeign` | booking, traveler | pas de `@FeignClient`, donc HTTP manuel + gestion d'erreurs à la main |
| `spring-cloud-starter-circuitbreaker-resilience4j` | booking, traveler | pas de `fallbackFactory`, pas d'état `OPEN` dans Actuator |
| `spring-cloud-starter-loadbalancer` | gateway | `lb://` non résolu (`Unable to find instance`) |
| `spring-cloud-starter-netflix-eureka-client` | gateway, 3 services Java | enregistrement impossible, `defaultZone` ignoré |
| `spring-boot-starter-aop` | traveler | `@Aspect` ignoré silencieusement : **aucun profil créé** |
| `spring-boot-starter-amqp` | booking, flight, traveler | pas de `RabbitTemplate`, pas d'`TopicExchange` |
| `spring-boot-starter-oauth2-resource-server` + `security` | gateway + 3 services | aucun JWT validé (403 sur tout) |
| `spring-boot-starter-data-jpa` + `mysql-connector-j` | 3 services | pas d'entités, pas de repositories |
| `springdoc-openapi-starter-webmvc-ui` **2.3.0** | 3 services | pas de Swagger, donc démo `/swagger-ui.html` impossible |
| `spring-boot-starter-actuator` | tous | pas de `/actuator/health`, donc pas de preuve du circuit breaker |
| `pika==1.3.2` | notification | pas de consommateur AMQP (ni ack ni nack) |
| `py-eureka-client`, `httpx` | notification | instance Python absente du registre → 503 via la Gateway |
| `express` **5**, `eureka-js-client`, `swagger-*` | conference | service Node non routable et sans doc |
| `@angular/core` **21.2**, `keycloak-js` **26.2**, `rxjs` **7.8** | frontend | pas de SPA, pas de PKCE |
| `node:22-alpine` (build) + `nginx:1.27-alpine` (runtime) | frontend | image finale lourde (node en prod) |

### 3.4 Variables d'environnement qui changent le comportement

| Variable | Effet |
|---|---|
| `EUREKA_INSTANCE_HOSTNAME=traveler-service` | publie un **nom** résolvable (pas `localhost`, pas une IP) |
| `EUREKA_INSTANCE_INSTANCE_ID=traveler-service:8081` | identifiant lisible dans le dashboard |
| `EUREKA_INSTANCE_STATUS_PAGE_URL=http://localhost:9081/…` | liens du dashboard (port **hôte**) — **jamais** utilisés pour le routage |
| `NOMADIX_KEYCLOAK_ISSUER=http://localhost:8180/realms/nomadix` | doit être l'URL **publique** : c'est un *claim* dans le jeton |
| `NOMADIX_KEYCLOAK_JWK_SET_URI=http://keycloak:8180/…/certs` | URL **interne** : c'est un appel réseau du conteneur |
| `SPRING_SQL_INIT_MODE=always` | rejoue `data.sql` (sinon `never` en local) |
| `SPRING_RABBITMQ_HOST=rabbitmq` | broker par nom Docker (pas `localhost`) |
| `NOTIFICATIONS_STORE=/data/notifications.json` | même volume monté dans l'API et le consommateur |

---

## 4. Audit des scénarios : sont-ils corrects et complets ?

### 4.1 Ce qui a été vérifié, et comment

| Vérification | Méthode | Résultat |
|---|---|---|
| Contrats HTTP | lecture des contrôleurs, confrontés aux 151 assertions du script | alignés (ex. création d'un vol = **200**, pas 201 ; `DELETE` booking = **204** ; `PUT ?status=` = **200**) |
| Requêtes SQL | lecture des entités + `SHOW TABLES` réel | noms de colonnes corrects (`travelers`, `traveler_profiles`, `traveler_favorite_flights`, `flight`, `bookings`) |
| Messages de log | `grep -F` sur les chaînes réellement présentes dans les `log.info/warn/error` | `[PROFILING] Traveler`, `[CIRCUIT BREAKER] Flight-Service indisponible`, `[RABBITMQ] publie`, `[RABBITMQ] publication`, `[notif] recu`, `[notif][DLQ] message refuse` |
| Scores 100 / 28 / 33 | recalcul à la main depuis `ProfilingRulesEngine` | exacts (voir §2, tableau du barème) |
| Format Eureka | lecture du XML réellement renvoyé | le script cherchait du JSON : **corrigé** |
| Synchronisation des logs | `docker compose logs --since` + horodatage | `2>/dev/null` jetait le stderr des JVM : **corrigé** |

**Ce qui n'a pas pu être exécuté ici** : les 12 scénarios contre une stack vivante (Docker
n'est pas disponible dans l'environnement de rédaction). Les corrections ont été validées par
`bash -n` et par lecture, pas par un run complet. La commande de validation reste :

```bash
docker compose up -d --build
bash documentation/scripts/run-scenarios.sh
```

### 4.2 Verdict scénario par scénario

Légende : **Solide** = prouve réellement ce qu'il annonce, sur le nominal *et* sur un cas
négatif. **Partiel** = la preuve existe mais un pan entier du mécanisme reste non testé.
**Réparé** = le scénario était juste, ce sont les assertions qui étaient fausses (6 + 4 + 3 + 1
échecs du dernier run, tous de cause script, pas de cause applicative).

| # | Sujet | Ce que l'assertion prouve vraiment | Verdict | Ce qui n'est pas couvert |
|---|---|---|---|---|
| S1 | Preflight | conteneurs up, realm Keycloak joignable, 2 jetons obtenus, 4 services `UP` | **Solide** | jeton expiré, mauvaise audience, Keycloak arrêté |
| S2 | Eureka | 6 applications enregistrées, instance avec **nom** + port interne, aucune adresse `localhost` publiée | **Réparé** → solide | 2 instances / répartition de charge, éviction après arrêt, `HALF_OPEN` |
| S3 | Gateway | table de routage réelle, `200` via gateway **et** en direct, preflight CORS, `401` sans jeton, `403` rôle insuffisant, `200` ADMIN | **Solide** | route inexistante (404), token d'un autre realm, 503 via la gateway |
| S4 | CRUD local Flight | écriture réelle en base, `PUT` sans doublon, **zéro** événement RabbitMQ émis | **Solide** | `PUT` sur id inexistant (404), `DELETE` (204) |
| S5 | Profiling AOP | 100/PLATINUM/100 **sans** `/recompute`, 28/BRONZE/33 + 3 flags, remontée à 100 après `PUT`, KPIs, top N | **Réparé** → solide | suppression du voyageur → profil supprimé (aspect `delete`), `/recompute` sur voyageur jamais profilé |
| S6 | OpenFeign / saga | diagnostic 2/2, `totalPrice` venu du vol, stock −1 puis restitué, `DELETE` deux fois = `204` + `204`, favoris enrichis, table d'ids seule | **Réparé** → solide | compensation **provoquée**, 409 de `reserveSeat` concurrent, arrêt de **traveler** |
| S7 | Résilience | `503` rapide, latence 4,5 s → 0,06 s, `CIRCUIT_OPEN` visible, aucun booking orphelin, retour à `CLOSED` | **Réparé** → partiel | `HALF_OPEN` explicite, **timeout** réel (ici : connexion refusée), panne de traveler |
| S8 | RabbitMQ métier | 5 routing keys publiées **et** consommées, archive qui grossit, relecture par l'API | **Solide** | contenu exact du payload archivé, ordre de consommation |
| S9 | RabbitMQ technique | `Ready` monte consommateur arrêté, 3 messages survivent au **redémarrage du broker**, rattrapage, DLQ + `x-death`, topologie | **Solide** (le plus complet) | rejeu depuis la DLQ, re-livraison après crash entre traitement et ack |
| S10 | Mode dégradé | stock décrémenté et booking écrit **malgré** broker éteint, `WARN [RABBITMQ] publication` | **Partiel** | le `HTTP 000` est un artefact du client AMQP bloquant ; `published=false` de `/poison` non testé |
| S11 | Isolation + observabilité | 1 base par service, **aucune** table croisée, health de chacun, frontend, Node, Python | **Solide** | impossibilité *technique* de joindre la base d'un autre (test de credentials) |
| S12 | Erreurs métier | 409 vol complet sans surbooking, 404 voyageur propagé par Feign, retrait de favori, `PUT` statut idempotent | **Solide** | 400 statut inconnu, 404 `DELETE` booking, 403 `DELETE` traveler |

### 4.3 OpenFeign : ce qui n'est **pas** testé

Les scénarios prouvent le chemin heureux de bout en bout et **une** panne d'infrastructure
(Flight arrêté). C'est nécessaire, ce n'est pas suffisant pour dire « OpenFeign est validé ».
Huit angles morts, du plus grave au plus anodin :

| # | Angle mort | Pourquoi c'est grave | Test à ajouter |
|---|---|---|---|
| 1 | **Aucun scénario n'arrête `traveler-service`** | le circuit breaker `traveler-service` est configuré (mêmes seuils) mais **jamais exercé** : rien ne prouve que `validateTraveler` renvoie 503 | `docker compose stop traveler-service` puis `POST /api/bookings` → attendre **503** et `"traveler-service"` `CIRCUIT_OPEN` dans `:9083/actuator/health` |
| 2 | **Fallback des favoris jamais vu** | `FlightClientFallbackFactory` côté traveler renvoie `null` / liste vide : le test de la liste filtrée n'existe pas | Flight arrêté puis `GET /api/travelers/23/favorite-flights` → attendre **200** + `[]` |
| 3 | **404 non testé via le diagnostic Feign** | S12 teste le 404 métier, pas la propagation `FeignException.NotFound` dans `FeignIntegrationController` | `GET /api/bookings/integrations/feign?travelerId=999999&flightId=1` → `reachable:true, found:false, httpStatus:404` |
| 4 | **409 de `reserveSeat` jamais atteint** | le 409 de S12 vient de `checkFlightAvailability` (`seats <= 0`), pas du `UPDATE` concurrent : la branche `FeignException.Conflict` du fallback n'est pas exercée | 2 réservations simultanées sur un vol à **1 place** → exactement **un** 201 et **un** 409 |
| 5 | **Timeout jamais provoqué** | conteneur arrêté = connexion **refusée** (immédiate), pas un dépassement des 3 s : le `TimeLimiter` 4 s n'est pas mesuré | latence injectée (`tc netem` ou proxy) > 3 s, mesurer un 503 en ≈ 3–4 s |
| 6 | **Compensation impossible jamais vue** | `releaseSeat` en fallback journalise `[COMPENSATION] Impossible de restituer` : ce chemin n'est jamais déclenché | Flight arrêté **après** `reserveSeat` (fenêtre étroite) : difficile, à défaut le mentionner comme non testé |
| 7 | **Répartition de charge non testée** | 1 instance par service : le `LoadBalancer` ne fait jamais de round-robin | `docker compose up --scale flight-service=2` puis 10 appels : 2 `instanceId` distincts dans les logs |
| 8 | **Jeton non conforme non testé** | `iss`/`aud` sont validés par le `JwtDecoder`, mais aucun test ne présente un jeton d'un autre realm | token `account` de Keycloak (audience différente) → **401** |

### 4.4 RabbitMQ : ce qui n'est **pas** testé

S9 est le scénario le mieux construit du projet (durabilité + DLQ + topologie). Il reste
neuf trous, dont deux réellement importants :

| # | Angle mort | Pourquoi c'est grave | Test à ajouter |
|---|---|---|---|
| 1 | **Aucun *publisher confirm* ni `mandatory`** | `convertAndSend` ne lève pas si la clé ne matche aucun binding : `publishRaw` renvoie `true` et le message est **perdu silencieusement** | publier avec `routingKey="unknown.key"` → vérifier que `publishRaw` renvoie **false** (nécessite `publisher-confirm-type=correlated`) |
| 2 | **Aucune re-livraison testée** | le consommateur acquitte **après** écriture ; s'il meurt entre les deux, le message revient → **doublon** dans `notifications.json` (at-least-once **sans** déduplication) | `docker compose kill notification-consumer` pendant un lot, redémarrer, compter les doublons |
| 3 | **DLQ non rejouée** | on prouve l'entrée en DLQ, jamais la sortie (republication / purge) : le message y reste indéfiniment | extraire avec `ack_requeue=false`, republier dans `nomadix.events`, vérifier la consommation |
| 4 | **`simulateFailure` non utilisé** | le consommateur sait refuser un JSON **valide** marqué `simulateFailure: true`, mais aucun scénario ne l'exerce (seul le non-JSON est testé) | `POST /api/packh/booking-events/emit` avec `{"simulateFailure": true}` → même chemin DLQ |
| 5 | **Contenu du payload non vérifié** | S8 fait un `grep` sur un identifiant : si le corps était tronqué ou mal sérialisé, le test passerait quand même | `GET /api/notifications/events?limit=1` puis valider `routingKey`, `receivedAt`, `payload.type`, `payload.<id>` |
| 6 | **Ordre de consommation non testé** | `flight.created` puis `booking.created` : rien ne garantit l'ordre observé dans `notifications.json` | publier 3 messages numérotés, vérifier l'ordre des `receivedAt` |
| 7 | **Aucun test de débit / prefetch** | `prefetch=10` n'est jamais saturé : on ignore le comportement sous 100 messages | publier 100 messages consommateur arrêté, redémarrer, mesurer le temps de vidage |
| 8 | **Pas de *quorum queue*** | `x-queue-type: classic` : durabilité correcte, mais pas de réplication (mono-nœud, donc non testable ici) | à mentionner comme choix d'infrastructure, pas comme test |
| 9 | **Pas de `@RabbitListener` Java** | le consommateur est Python : il n'y a **aucun** listener Spring dans le projet, donc pas de test de `@RabbitListener`/`MessageConverter` | ne pas prétendre le contraire en soutenance |

**Ce qui est en revanche réellement prouvé** (et c'est rare) : la **persistance du message**
au-delà du redémarrage du broker — S9 arrête RabbitMQ avec 3 messages dans la file, le
redémarre, et retrouve `Ready = 3`. Cela valide `durable: true` **et** la persistance par
défaut de Spring AMQP (`deliveryMode = 2`).

### 4.5 Verdict global

**« Corrects » : oui.** Chaque assertion correspond à un contrat réel du code (codes HTTP,
colonnes SQL, chaînes de log, barème). Les 13 échecs du dernier run complet n'étaient pas des
bugs applicatifs : 10 venaient du format **XML** d'Eureka lu comme du JSON, 3 du `2>/dev/null`
qui jetait les logs **stderr** des JVM. Ces deux causes sont corrigées, et les deux dernières
(égalité de score dans le top 3, circuit encore `OPEN` après redémarrage) sont des
**fragilités de test** désormais traitées (limite portée à 50, sonde jusqu'à `CLOSED`).

**« Complets » : non — et il faut le dire ainsi.** Les scénarios couvrent le chemin nominal,
les erreurs métier (404/409), une panne d'infrastructure (Flight), le mode dégradé du broker,
la durabilité et la DLQ. Ils ne couvrent **pas** ce qui définit un système distribué :

- **concurrence** (2 réservations simultanées sur la dernière place) ;
- **panne partielle** (1 instance sur 2, latence, réseau) plutôt qu'arrêt total ;
- **perte de message** (publisher confirms, binding absent) ;
- **livraison multiple** (crash entre traitement et ack) ;
- **panne du service de validation** (traveler), alors que son circuit breaker existe ;
- **charge** (débit, prefetch, ordre).

Autrement dit : **8 scénarios sur 12 sont solides**, 2 sont partiels (S7, S10) et 2 dépendent de
fragilités de timing (S2, S5). Les 10 ajouts listés en §4.3 et §4.4 combleraient l'écart — ils
ne sont **pas** implémentés ici, et il vaut mieux l'annoncer au jury que de le laisser découvrir.

---

## 5. Les 50 questions de soutenance

### 5.1 Frontend, Nginx et CORS (Q1 → Q8)

**Q1. Pourquoi Nginx devant Angular en production ?**
**Court :** pour servir des fichiers statiques et supprimer le CORS.
Angular compilé n'est plus qu'un dossier (`dist/NomadixWeb/browser`) : nginx le sert avec gzip et cache long. Surtout, nginx proxifie `/api/` vers la Gateway, donc **l'origine du navigateur et celle de l'API sont identiques** (`localhost:8090`) : il n'y a plus de requête cross-origin en production.

**Q2. Quelle est la différence entre nginx et la Gateway ?**
**Court :** nginx sert l'UI et proxifie ; la Gateway route et sécurise.
nginx ne connaît ni les services, ni Eureka, ni les JWT : il fait un `proxy_pass` bête et transmet `Authorization`. La Gateway, elle, valide la signature du JWT, applique la matrice de droits, choisit la route `lb://` et répartit la charge — c'est le seul composant qui sait où sont les microservices.

**Q3. Pourquoi `proxy_pass http://gateway:8089;` sans slash final ?**
**Court :** pour conserver le préfixe `/api`.
Avec `proxy_pass http://gateway:8089/;`, nginx remplace le préfixe `location /api/` par `/` : la Gateway recevrait `/flights` au lieu de `/api/flights` et répondrait **404** (aucune route ne matche).

**Q4. Comment le JWT arrive-t-il jusqu'à la Gateway ?**
**Court :** intercepteur Angular → en-tête HTTP → `proxy_set_header`.
`auth.interceptor.ts` ajoute `Authorization` aux appels `/api`, nginx le relaie explicitement (`proxy_set_header Authorization $http_authorization`) puis la Gateway en fait un `BearerTokenAuthenticationToken` via son `ReactiveJwtDecoder`.

**Q5. Pourquoi le jeton n'est-il pas dans `localStorage` ?**
**Court :** pour réduire la surface XSS.
`keycloak-js` le garde en **mémoire** et le renouvelle par *refresh token* (PKCE). Un jeton en `localStorage` est lisible par n'importe quel script injecté ; en mémoire, il disparaît au rechargement — d'où `onLoad: 'check-sso'`, qui récupère la session SSO sans interaction.

**Q6. À quoi sert `ensureFreshToken()` avec `updateToken(30)` ?**
**Court :** éviter les 401 aléatoires en session longue.
L'intercepteur demande un jeton valide **au moins 30 s** : si l'expiration est plus proche, Keycloak le rafraîchit avant l'appel. Sans cela, une requête partie avec 2 s de validité peut être rejetée par la Gateway (horloges légèrement décalées).

**Q7. Pourquoi un `CorsWebFilter` en plus de `spring.cloud.gateway.globalcors` ?**
**Court :** parce que `globalcors` s'applique **après** Spring Security.
Quand la Gateway refuse (401/403), la réponse est écrite par le filtre de sécurité et ne traverse jamais le `RoutePredicateHandlerMapping` : aucun en-tête `Access-Control-Allow-Origin` n'est ajouté, et le navigateur masque le vrai statut derrière `TypeError: NetworkError`. Le filtre en `HIGHEST_PRECEDENCE` répond aussi aux preflights `OPTIONS`.

**Q8. Pourquoi `allowedOriginPatterns` et pas `allowedOrigins("*")` ?**
**Court :** `"*"` est incompatible avec `allowCredentials: true`.
Spring refuse la combinaison (exception au démarrage ou en-têtes incohérents). Les patterns `http://localhost:*` couvrent Angular (4200 en dev, 8090 en Docker) et les Swagger UI, dont les ports changent selon le mode de lancement.

### 5.2 Service discovery et routage (Q9 → Q16)

**Q9. Pourquoi Eureka plutôt que des URLs en dur ?**
**Court :** pour que l'adresse d'un service ne soit pas dans le code.
`@FeignClient(name = "flight-service")` et `lb://FLIGHT-SERVICE` ne contiennent aucun hôte : on peut changer un port, ajouter une instance ou passer d'un poste local à Docker **sans recompiler**. C'est aussi ce qui rend le *scaling* horizontal possible.

**Q10. Différence entre `hostName`/`port` et `statusPageUrl` dans Eureka ?**
**Court :** le premier sert au **routage**, les seconds à l'**affichage**.
`hostName=flight-service` + `port=8082` sont utilisés par la Gateway et Feign, **depuis l'intérieur** du réseau Docker (un nom, un port interne). `statusPageUrl=http://localhost:9082/...` est un lien cliquable dans le dashboard, ouvert depuis le navigateur de l'hôte — il n'est jamais utilisé pour router.

**Q11. Pourquoi le serveur Eureka ne s'enregistre-t-il pas lui-même ?**
**Court :** parce qu'il n'appelle personne.
`eureka.client.register-with-eureka=false` et `fetch-registry=false` : un annuaire qui s'annonce à lui-même n'apporte rien et retarde le démarrage (et le dashboard afficherait une instance fantôme).

**Q12. Pourquoi `lb://FLIGHT-SERVICE` et non `http://localhost:8082` ?**
**Court :** pour bénéficier du registre et du load-balancing.
`lb://` délègue la résolution au `ReactiveLoadBalancer` : il interroge Eureka, choisit une instance **UP** et applique le round-robin. Une URL en dur figerait l'instance, ignorerait les pannes et casserait dès qu'on change de port.

**Q13. À quoi sert `registry-fetch-interval-seconds: 5` ?**
**Court :** à voir une nouvelle instance en quelques secondes.
La Gateway rafraîchit son cache de registre toutes les 5 s : après un `docker compose up`, une route devient fonctionnelle en ≤ 5 s au lieu des 30 s par défaut — sans redémarrer la Gateway.

**Q14. Pourquoi `spring-cloud-starter-loadbalancer` est-il indispensable à la Gateway ?**
**Court :** parce que `lb://` n'est résolu par personne sans lui.
Spring Cloud Gateway 4 a retiré Ribbon ; sans `spring-cloud-starter-loadbalancer`, toute requête vers une URI `lb://` échoue avec `Unable to find instance for FLIGHT-SERVICE` (souvent déguisé en 500).

**Q15. Pourquoi forcer `EUREKA_INSTANCE_HOSTNAME=localhost` cassait tout ?**
**Court :** parce que `localhost` dans un conteneur désigne **ce conteneur**.
La Gateway recevait `localhost:9082` et appelait... elle-même (ou rien). Résultat : toutes les routes en 500. Les IP `172.20.x.x` échouent pour une autre raison : elles ne sont pas joignables depuis l'hôte Windows et changent à chaque recréation.

**Q16. Le dashboard Eureka est-il la source de vérité du routage ?**
**Court :** non, c'est le registre qui l'est.
Le dashboard est une vue HTML sur `/eureka/apps` ; les clients lisent l'API REST (XML ou JSON). On peut voir une instance `UP` avec un lien cassé dans le dashboard et un routage parfaitement fonctionnel — les deux informations sont indépendantes.

### 5.3 OpenFeign et communication synchrone (Q17 → Q25)

**Q17. Qu'est-ce qu'OpenFeign ?**
**Court :** un client HTTP déclaratif.
On décrit une interface annotée (`@FeignClient`, `@GetMapping`) et Spring génère le proxy : encodage de l'URL, sérialisation Jackson, désérialisation, levée de `FeignException` typées (`NotFound`, `Conflict`). Zéro `RestTemplate`, zéro `try/catch` de transport.

**Q18. Comment Feign trouve-t-il `flight-service` ?**
**Court :** par Eureka, via le LoadBalancer.
Le `name` du `@FeignClient` devient un `serviceId` ; `FeignBlockingLoadBalancerClient` demande une instance au `DiscoveryClient` (Eureka) et compose `http://flight-service:8082`. Les deux casses (minuscule côté booking, majuscule côté traveler) fonctionnent, comme le montre S6.

**Q19. Pourquoi passer le jeton en `@RequestHeader` explicite ?**
**Court :** parce que le contexte de sécurité change de thread.
Resilience4j exécute l'appel dans un `CompletableFuture` : `SecurityContextHolder` (thread-local) y est vide. `BearerTokens.current()` capture le jeton **sur le thread de la requête**, puis on le transmet en paramètre — c'est volontairement explicite plutôt que magique.

**Q20. `fallback` ou `fallbackFactory` ?**
**Court :** `fallbackFactory` quand on a besoin de la cause.
Une fabrique reçoit la `Throwable` : elle peut propager un `FeignException.NotFound` (404 métier) et ne transformer en 503 **que** les vraies pannes. Un simple `fallback` renverrait 503 pour tout, y compris pour un voyageur inexistant.

**Q21. Pourquoi une saga et pas une transaction distribuée (2PC) ?**
**Court :** parce que 2PC suppose des ressources transactionnelles homogènes.
Ici, trois bases MySQL distinctes + du HTTP : aucun coordinateur XA ne peut verrouiller l'ensemble. La saga enchaîne des transactions **locales** et compense l'effet déjà produit (`releaseSeat`) en cas d'échec : cohérence à terme, pas d'atomicité globale.

**Q22. D'où vient le prix de la réservation ?**
**Court :** du Flight-Service, par Feign.
Le client n'envoie que `travelerId` et `flightId` ; `booking.setTotalPrice(flight.getPrice())` utilise la réponse de `GET /api/flights/{id}`. C'est la **preuve observable** que l'appel inter-services a eu lieu (et qu'un prix ne peut pas être falsifié par l'appelant).

**Q23. À quoi sert `/api/bookings/integrations/feign` ?**
**Court :** à rendre OpenFeign visible et testable.
Un appel Feign est interne : il n'apparaît pas dans Swagger. Ce contrôleur expose les deux appels (`reachable`, `found`, `data`) et renvoie **toujours 200** : même en panne, la réponse décrit l'état au lieu d'échouer — pratique pour diagnostiquer sans lire les logs.

**Q24. Pourquoi un 404 doit-il remonter et pas être avalé ?**
**Court :** parce que c'est une information métier, pas une panne.
Si le voyageur n'existe pas, l'utilisateur doit voir « inexistant » (404), pas « service indisponible » (503). Le fallback distingue donc `FeignException.NotFound` (propagé) de tout le reste (503) — sinon le diagnostic serait mensonger.

**Q25. Que se passe-t-il si le Traveler-Service est arrêté ?**
**Court :** 503 après le fallback, sans écriture.
`travelerClient` renvoie `null` (fallback `TravelerClientFallbackFactory`), `validateTraveler` lève `503 SERVICE_UNAVAILABLE`, et l'orchestration s'arrête **avant** `reserveSeat` : aucune place n'est décrémentée, aucune ligne n'est écrite. *(Ce chemin est configuré mais pas couvert par un scénario : voir §4.3, angle mort n°1.)*

### 5.4 Résilience et gestion des pannes (Q26 → Q34)

**Q26. Quels sont les trois états d'un circuit breaker ?**
**Court :** `CLOSED`, `OPEN`, `HALF_OPEN`.
`CLOSED` : tout passe, on mesure le taux d'échec. `OPEN` : tout est refusé immédiatement (fallback) pendant `wait-duration-in-open-state`. `HALF_OPEN` : quelques appels de sonde sont autorisés ; succès → `CLOSED`, échec → `OPEN`.

**Q27. Comment le circuit s'ouvre-t-il concrètement ici ?**
**Court :** 5 appels minimum, 50 % d'échecs sur une fenêtre de 10.
`COUNT_BASED`, `sliding-window-size=10`, `minimum-number-of-calls=5`, `failure-rate-threshold=50`. En S7, les 4 premiers appels échouent en ~4,5 s ; dès que le seuil est atteint, les appels suivants échouent en ~0,06 s.

**Q28. Pourquoi `TimeLimiter` (4 s) est-il supérieur au `read-timeout` Feign (3 s) ?**
**Court :** pour laisser Feign produire **sa** `SocketTimeoutException`.
Si le TimeLimiter était plus court, il interromprait l'appel avant le timeout Feign et l'exception observée serait un `TimeoutException` du `CompletableFuture`, moins lisible. 4 > 3 garantit que le message d'erreur vient de la couche réseau.

**Q29. Pourquoi 503 et pas 500 ou 502 ?**
**Court :** 503 dit « dépendance temporairement indisponible ».
`503 Service Unavailable` signifie que le serveur existe mais qu'un service dont il dépend ne répond pas : c'est exactement le cas. Un 500 laisserait croire à un bug du booking-service, un 502 à un problème de proxy.

**Q30. Qu'est-ce qu'un fallback ?**
**Court :** le comportement de secours quand l'appel échoue.
Ici, trois stratégies différentes selon la méthode : `getFlightById` → exception 503 (on ne peut pas continuer), `releaseSeat` → `null` + `log.error` (compensation impossible, à traiter manuellement), favoris → liste vide (dégradation acceptable).

**Q31. Comment l'annulation est-elle idempotente ?**
**Court :** par un test d'état avant l'action.
`cancelBooking` retourne immédiatement si `status == CANCELLED` : la place n'est restituée qu'une fois, même si deux `DELETE` arrivent. S6 le prouve : `204` + `204` mais **une seule** place rendue (44 → 45).

**Q32. À quoi sert la compensation, et quelle est sa limite ?**
**Court :** à annuler l'effet déjà produit ; sa limite est le crash du processus.
Si `save` échoue après `reserveSeat`, `releaseQuietly` remet la place. Mais si la JVM meurt **entre** les deux appels, aucune compensation ne s'exécute : il n'y a pas de journal de saga persistant. C'est une saga *au mieux*, à assumer explicitement.

**Q33. Pourquoi le circuit breaker pose-t-il un problème de jeton ?**
**Court :** parce qu'il change de thread.
Le `SecurityContext` est un `ThreadLocal` : dans le pool de Resilience4j, il est vide. D'où `BearerTokens.current()` appelé **avant** l'appel Feign, et le jeton passé en en-tête à chaque méthode du client.

**Q34. À quoi sert `CircuitBreakerNameResolverConfig` ?**
**Court :** à donner au circuit breaker le nom du client Feign.
Par défaut, OpenFeign nomme l'instance `flight-service#getFlightById(Long,String)` : aucune propriété `resilience4j.circuitbreaker.instances.flight-service.*` ne s'applique et les seuils retombent aux valeurs par défaut. Le bean force le nom court `flight-service`.

### 5.5 RabbitMQ et communication asynchrone (Q35 → Q43)

**Q35. Pourquoi un exchange *topic* et pas *direct* ou *fanout* ?**
**Court :** pour router par motif hiérarchique.
Un `topic` accepte `booking.#`, `flight.#`, `traveler.#` : un seul exchange sert trois domaines, et de nouveaux consommateurs peuvent s'abonner à `booking.*` sans toucher au producteur. `direct` imposerait une clé exacte, `fanout` diffuserait à tous.

**Q36. Que garantit `durable: true` ?**
**Court :** que la file et l'exchange survivent au redémarrage du broker.
Sans cela, RabbitMQ recréerait une file vide au boot et les messages en attente seraient perdus. La durabilité de la **file** ne suffit pas : il faut aussi que le **message** soit persistant (`deliveryMode=2`, défaut Spring AMQP) — c'est ce que S9 prouve en retrouvant `Ready = 3` après un `restart rabbitmq`.

**Q37. Pourquoi la publication est-elle enveloppée dans un `try/catch` ?**
**Court :** parce que l'asynchrone ne doit jamais bloquer le métier.
Le canal AMQP est un **bonus** : si le broker est éteint, la réservation reste valide et commitée. On journalise `WARN [RABBITMQ] publication … impossible` et on renvoie `false` — l'utilisateur voit son `201` (ou une réponse dégradée), pas un 500.

**Q38. `basic_ack` ou `basic_nack(requeue=false)` ?**
**Court :** ack si le message est traité, nack sans requeue s'il est irrécupérable.
`ack` supprime le message du broker. `nack(requeue=false)` le refuse **sans** le remettre en tête : RabbitMQ applique alors la dead-letter policy de la file. `requeue=true` aurait créé une boucle infinie sur un message invalide (il revient, échoue, revient…).

**Q39. Décrivez le chemin exact d'un message empoisonné.**
**Court :** `nomadix.events` → `notification.events` → nack → `nomadix.dlx` → `notification.events.dlq`.
Le message est publié avec la clé `booking.poison` (liée par `booking.#`), arrive dans la file, est refusé par le consommateur (`json.loads` échoue), puis le `x-dead-letter-exchange` de la **file** le réachemine vers le DLX avec la `x-dead-letter-routing-key`, d'où la DLQ.

**Q40. Que contient l'en-tête `x-death` ?**
**Court :** la trace du refus.
Un tableau avec `queue` (file d'origine : `notification.events`), `reason` (`rejected`), `routing-keys` (`booking.poison`), `count` et les dates. C'est la preuve exploitable en production : on sait **d'où** vient le message et **pourquoi** il a été isolé.

**Q41. Pourquoi le consommateur est-il en Python et pas en `@RabbitListener` ?**
**Court :** pour démontrer l'interopérabilité du broker.
AMQP est un protocole : un producteur Spring AMQP et un consommateur `pika` se parlent sans rien partager (ni classe, ni schéma). C'est la démonstration la plus forte du découplage — et il ne faut **pas** prétendre qu'il existe un listener Java dans ce projet.

**Q42. Quelle garantie de livraison ?**
**Court :** *at-least-once*, pas *exactly-once*.
Le consommateur acquitte **après** l'écriture du fichier. S'il meurt entre les deux, le message est re-livré → l'entrée est écrite deux fois. Il n'y a **aucune** déduplication par `messageId`. C'est acceptable pour des notifications, pas pour un débit bancaire.

**Q43. Pourquoi publier l'événement après le `save` et non avant ?**
**Court :** pour ne jamais annoncer une réservation qui n'existe pas.
Publier avant le commit créerait un `booking.created` fantôme si l'écriture échoue. Le code publie après `bookingRepository.save()`, dans le `try`, et en cas d'échec c'est la compensation qui s'exécute — sans jamais émettre d'événement mensonger.

### 5.6 Base de données, AOP et profilage (Q44 → Q50)

**Q44. Qu'est-ce que le *database-per-service* et comment le prouvez-vous ?**
**Court :** une base privée par service, aucune jointure inter-services.
`SHOW TABLES` donne `travelers`/`traveler_profiles`/`traveler_favorite_flights` dans `nomadix_traveler_db`, `flight` dans `nomadix_flight_db`, `bookings` dans `nomadix_booking_db`. Une requête `information_schema` prouve que la base du voyageur ne contient **aucune** table `bookings` ou `flight` (S11).

**Q45. Pourquoi pas de clé étrangère entre `bookings.flight_id` et `flight.id` ?**
**Court :** parce que ce sont deux bases différentes, potentiellement deux serveurs.
Une FK exigerait un accès direct à la base d'un autre service — exactement ce que l'architecture interdit. `flightId` est une **référence logique** : l'intégrité est garantie par les appels Feign au moment de l'écriture, pas par le moteur SQL.

**Q46. Qu'est-ce que l'AOP, en trois mots ?**
**Court :** aspect, pointcut, conseil (advice).
L'**aspect** est la classe (`@Aspect`). Le **pointcut** est la règle de sélection (`execution(* ..*Repository+.save(..))`). Le **conseil** est le code exécuté (`@AfterReturning`) — ici, recalculer et persister le profil.

**Q47. Pourquoi `@AfterReturning` et pas `@Around` ?**
**Court :** parce qu'il faut l'id généré, pas le contrôle de la méthode.
`save()` retourne l'entité **avec** son id auto-généré : `returning = "entity"` le récupère directement. `@Around` obligerait à appeler `proceed()` et à manipuler le résultat : plus puissant, inutilement complexe, et risqué (un `proceed()` oublié bloque l'écriture).

**Q48. Pourquoi le garde-fou `TravelerProfile` dans l'aspect ?**
**Court :** pour empêcher la récursion infinie.
Le pointcut attrape **tous** les repositories du package, y compris `TravelerProfileRepository`. Sans le test sur `getSimpleName().equals("TravelerProfile")`, sauvegarder un profil déclencherait un nouveau profiling, qui sauvegarderait un profil, etc.

**Q49. Pourquoi le profiling est-il « fail-safe » ?**
**Court :** parce qu'il ne doit jamais casser le métier.
`profileAndSave` enveloppe tout dans `try/catch` et journalise un `WARN`. Un profil impossible à calculer (entité sans id, base indisponible) ne fait pas échouer la création du voyageur : c'est une donnée **dérivée**, pas une donnée de référence.

**Q50. Pourquoi versionner les règles (`RULES_VERSION = "1.1"`) ?**
**Court :** pour rendre les scores comparables et recalculables.
Chaque profil stocke `rulesVersion` : on sait avec quel barème il a été produit. Quand la politique change, `POST /api/travelers/profiles/{id}/recompute` réapplique les règles courantes, et les tableaux de bord distinguent les profils anciens des nouveaux.

---

## 6. Règles d'or pour la démonstration

1. **Ne jamais lire l'état du circuit breaker juste après avoir redémarré le service arrêté.**
   Après `docker compose start flight-service`, le circuit reste `OPEN` puis `HALF_OPEN` : il ne repasse `CLOSED` qu'après des appels réels réussis. Attendre `CLOSED` **par sondage** (ou l'annoncer comme normal) avant de conclure.
2. **Toujours passer le jeton dans `curl`.**
   `-H "Authorization: Bearer $TOKEN"` sur tout `/api/**` sauf `GET /api/flights` et `GET /api/conferences`. Sans jeton, `/api/travelers` renvoie 401 et non 200 : ce n'est pas une panne.
3. **Ne pas confondre port hôte et port interne.**
   `localhost:9083` pour observer, `booking-service:8083` pour router. Publier un port hôte dans Eureka casse tous les appels internes (500 partout).
4. **Ne pas promettre 201 sur la création d'un vol.**
   `FlightController.create` retourne l'entité directement : Spring répond **200**. Seuls `POST /api/travelers` et `POST /api/bookings` répondent 201.
5. **Ne pas dire « exactly-once » ni « transaction distribuée ».**
   Le système est *at-least-once* côté RabbitMQ et *saga au mieux* côté réservation (pas de journal durable). Le dire soi-même avant que le jury ne le demande est un point de crédibilité, pas une faiblesse.

---

## 7. Les chiffres à connaître par cœur

| Grandeur | Valeur exacte | Où c'est défini |
|---|---|---|
| Score d'un profil complet | **100** (40+10+5+15+10+5+5+10) | `ProfilingRulesEngine` |
| Score `Karim X` + mailinator + 2 champs | **28**, complétude **33**, segment **BRONZE** | idem |
| Seuils de segment | PLATINUM ≥ 80, GOLD ≥ 65, SILVER ≥ 45, sinon BRONZE | idem |
| Version des règles | **1.1** | `RULES_VERSION` |
| Timeout Feign | connect **2 s** / read **3 s** | `booking/application.properties` |
| TimeLimiter Resilience4j | **4 s** (supérieur au read-timeout) | idem |
| Circuit breaker | fenêtre **10**, minimum **5** appels, **50 %**, ouvert **10 s**, **3** appels de sonde | idem |
| Latence observée en panne | ~**4,5 s** (appels 1-4) puis ~**0,06 s** (circuit ouvert) | logs S7 |
| Prefetch consommateur | **10** | `packh_rabbit_consumer.py` |
| Archive de notifications | **200** entrées max | idem |
| Bases MySQL | **3** (`nomadix_traveler_db`, `nomadix_flight_db`, `nomadix_booking_db`) | `01-databases.sql` |
| Routes Gateway | **9** (5 métier + 4 `packh`) | `gateway/application.yml` |
| Services enregistrés dans Eureka | **6** (5 services + la Gateway ; Eureka ne s'auto-enregistre pas) | logs S2 |
| Conteneurs | **11** | `docker-compose.yml` |
| Expérience de rafraîchissement du jeton | **30 s** de marge | `keycloak.service.ts` |

