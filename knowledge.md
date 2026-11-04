# knowledge.md — Nomadix : tout le code, tous les scénarios, comment lire les preuves

> Ce document explique **ce que fait chaque fichier**, **comment chaque mécanisme est implémenté**,
> **quelles dépendances** sont utilisées, **ce que chaque scénario prouve réellement**, et surtout
> **comment interpréter les sorties** du script `documentation/scripts/run-scenarios.sh`.
> Il est écrit pour être lu par quelqu'un qui découvre le projet (correcteur, jury) : chaque
> affirmation renvoie à un fichier et à un extrait de code réel.

---

## Sommaire

1. [Le projet en 2 minutes](#1-le-projet-en-2-minutes)
2. [Comment lire la sortie du script](#2-comment-lire-la-sortie-du-script)
3. [Architecture et réseau](#3-architecture-et-réseau)
4. [Tous les fichiers et leur rôle](#4-tous-les-fichiers-et-leur-rôle)
5. [Dépendances (versions réelles)](#5-dépendances-versions-réelles)
6. [Les mécanismes clés, expliqués avec le code](#6-les-mécanismes-clés-expliqués-avec-le-code)
7. [Les 12 scénarios : étapes, preuves, interprétation](#7-les-12-scénarios--étapes-preuves-interprétation)
8. [Le barème de scoring, calculé à la main](#8-le-barème-de-scoring-calculé-à-la-main)
9. [Interpréter le bilan final](#9-interpréter-le-bilan-final)
10. [Erreurs déjà rencontrées et leur cause exacte](#10-erreurs-déjà-rencontrées-et-leur-cause-exacte)
11. [Limites de ce dispositif](#11-limites-de-ce-dispositif)

---

## 1. Le projet en 2 minutes

Nomadix est une plateforme de voyage composée de services **indépendants** :

| Ce que fait l'utilisateur | Service qui répond | Base utilisée |
|---|---|---|
| Consulter / gérer le catalogue de vols | `flight-service` | `nomadix_flight_db` |
| Gérer les voyageurs et leurs favoris | `traveler-service` | `nomadix_traveler_db` |
| Réserver un vol | `booking-service` | `nomadix_booking_db` |
| Voir les conférences | `conference-service` (Node.js) | mémoire |
| Recevoir les notifications / événements | `notification-service` (Python) | volume `/data` + RabbitMQ |

Trois idées structurent tout le code :

1. **Un seul point d'entrée** (`gateway`, port 9089) : le navigateur ne connaît que la Gateway.
2. **Aucun service ne connaît l'adresse d'un autre** : les adresses sont découvertes dans
   `eureka-server`, les appels inter-services passent par **OpenFeign**.
3. **Chaque service possède ses données** : pas de jointure entre bases, on échange par **HTTP**
   (synchrone) ou par **RabbitMQ** (asynchrone).

Le travail « avancé » demandé par le sujet est visible à trois endroits précis :

- **Smart Profiling** (moteur de règles + AOP) dans `traveler-service` ;
- **SAGA + compensation + circuit breaker** dans `booking-service` ;
- **Messagerie durable + dead-letter** entre `booking-service` et le consommateur Python.

---

## 2. Comment lire la sortie du script

Le script affiche **trois types de lignes**, toujours dans le même ordre : la commande, la sortie
brute, puis le verdict. Rien n'est inventé : ce qui est affiché est ce qui a été exécuté.

```
     $ curl -s -X POST http://localhost:9089/api/bookings?travelerId=25&flightId=29 -H 'Authorization: Bearer <jeton>'
       | HTTP 201
       | {"id":23,"travelerId":25,"flightId":29,"status":"CONFIRMED","totalPrice":210.00,...}
     [OK]   POST /api/bookings = 201
```

| Symbole | Signification |
|---|---|
| `$` (cyan) | **la commande réellement exécutée**, telle quelle |
| `\|` (gris) | **la sortie brute** de cette commande (JSON, ligne SQL, extrait de log) |
| `[OK]` (vert) | assertion vérifiée sur cette sortie |
| `[ECHEC]` (rouge) | assertion fausse → le message dit *attendu* et *obtenu* |
| `..` | information de contexte (valeur avant/après, id créé) |
| `--` | titre de la preuve en cours |

**Pourquoi les commandes sont visibles alors que les valeurs sont capturées :** les helpers
`show()` et `raw()` écrivent sur **stderr**, tandis que la valeur utile (`CODE`, id, compteur) est
écrite sur **stdout** et capturée par `$(...)`. C'est ce qui permet d'afficher *et* d'affirmer :

```bash
show() { [ "$QUIET" = 1 ] || printf '\033[36m     $ %s\033[0m\n' "$*" >&2; }
raw()  { [ "$QUIET" = 1 ] || printf '       \033[2m| %s\033[0m\n' "$1" >&2; }
```

Deux règles de conception à retenir :

- **Aucune valeur absolue n'est codée en dur** quand elle dépend de l'historique (compteurs RabbitMQ,
  statistiques, DLQ) : le script lit une valeur *avant*, agit, puis compare *après* (delta).
- **Aucune purge** (`down -v`) : le script est rejouable indéfiniment, il crée de nouveaux
  identifiants à chaque exécution.

Codes de sortie : `0` = tout passe, `1` = au moins un `[ECHEC]`, `2` = stack non démarrée.

---

## 3. Architecture et réseau

```
                        navigateur (Angular)
                              :8090
                                |
                                v
        +--------------------------------------------------+
        |  frontend (nginx)  --proxy /api-->  gateway :9089 |
        +---------------------------+----------------------+
                                    |  lb://SERVICE (Eureka)
        +--------------+------------+-----------+-------------+
        |              |                        |             |
   traveler:8081  flight:8082            booking:8083   conference:3000
        |              |                        |             |
  traveler_db     flight_db               booking_db     (mémoire)
        |              |                        |
        +--------------+--------+---------------+
                                |
                    notification:8084 (FastAPI, lecture /data)
                                |
                        rabbitmq :5672 / :15672
                                |
                   notification-consumer (pika, écrit /data)
```

Ports **publiés sur l'hôte** (à gauche) et port **interne** (à droite) :

| Service | Hôte | Interne |
|---|---|---|
| frontend | 8090 | 80 |
| gateway | 9089 | 8089 |
| traveler-service | 9081 | 8081 |
| flight-service | 9082 | 8082 |
| booking-service | 9083 | 8083 |
| notification-service | 9084 | 8084 |
| conference-service | 3001 | 3000 |
| eureka-server | 9761 | 8761 |
| keycloak | 8180 | 8180 |
| rabbitmq (AMQP / management) | 5672 / 15672 | 5672 / 15672 |
| mysql | 3307 | 3306 |

**La distinction hôte/interne est la clé de tout le dépannage réseau** (voir §10.3).

---

## 4. Tous les fichiers et leur rôle

### 4.1 Infrastructure

| Fichier | Rôle | Point à retenir |
|---|---|---|
| `docker-compose.yml` | décrit les 12 conteneurs, réseaux, volumes, healthchecks | les ports **internes** servent au routage, les ports **publiés** seulement au navigateur |
| `docker/mysql/init/01-databases.sql` | crée les 3 bases + l'utilisateur `nomadix` | joué **une seule fois** : à la création du volume |
| `backend/messaging/rabbitmq/definitions.json` | topologie importée au démarrage du broker | doit rester identique aux `queue_declare` du code Python |
| `backend/keycloak/nomadix-realm.json` | realm `nomadix` importé au démarrage | contient les utilisateurs `admin` et `sami` et le client `nomadix-cli` |
| `backend/*/Dockerfile` | image de chaque service (multi-stage) | Java : build Maven puis JRE ; Angular : build npm puis nginx |

### 4.2 `eureka-server` — l'annuaire

| Fichier | Rôle |
|---|---|
| `backend/discovery/eureka-server/src/main/resources/application.yml` | active le serveur, désactive son auto-enregistrement |

Chaque service s'annonce avec **deux informations distinctes** (voir §6.1) :

```yaml
EUREKA_INSTANCE_HOSTNAME: flight-service        # nom resolvable DANS Docker -> routage
EUREKA_INSTANCE_INSTANCE_ID: flight-service:8082
EUREKA_INSTANCE_STATUS_PAGE_URL: http://localhost:9082/actuator/info   # lien pour le dashboard
```

### 4.3 `gateway` — point d'entrée unique + sécurité

| Fichier | Rôle | Extrait clé |
|---|---|---|
| `backend/gateway/src/main/resources/application.yml` | routes `lb://` + CORS | voir ci-dessous |
| `backend/gateway/src/main/java/.../security/SecurityConfig.java` | OAuth2 Resource Server (valide les jetons Keycloak) | `401`/`403` en JSON |
| `backend/gateway/src/main/java/.../security/KeycloakRoles.java` | lit `realm_access.roles` | mappe en `ROLE_ADMIN` |
| `backend/gateway/src/main/java/.../config/CorsConfig.java` | source CORS partagée avec la chaîne de sécurité | sans elle, les 401/403 n'ont pas d'en-têtes CORS |

Les routes : une par domaine, plus une par famille d'événements `packh` :

```yaml
- id: booking-service
  uri: lb://BOOKING-SERVICE          # pas d'IP, pas de port : resolution Eureka
  predicates:
    - Path=/api/bookings,/api/bookings/**
- id: packh-booking-events
  uri: lb://BOOKING-SERVICE
  predicates:
    - Path=/api/packh/booking-events,/api/packh/booking-events/**
```

La matrice de droits (extrait de `SecurityConfig`) :

```java
.pathMatchers(HttpMethod.GET, "/api/flights", "/api/flights/**").permitAll()   // catalogue public
.pathMatchers(HttpMethod.POST, "/api/flights", "/api/flights/**").hasRole("ADMIN")
.pathMatchers("/api/travelers/profiles/**").hasRole("ADMIN")
.pathMatchers("/api/packh/**").hasRole("ADMIN")
.pathMatchers("/actuator/**").hasRole("ADMIN")
.anyExchange().authenticated()
```

### 4.4 `flight-service` — propriétaire du catalogue

| Fichier | Rôle |
|---|---|
| `controller/FlightController.java` | CRUD + les **deux** opérations de stock |
| `entity/Flight.java`, `repository/FlightRepository.java` | table `flight` |
| `messaging/PackHFlightEventController.java` | `POST /api/packh/flight-events/emit` (démo RabbitMQ) |
| `resources/application.properties` | datasource, actuator, Eureka |
| `resources/data.sql` | 5 vols de démonstration en `INSERT IGNORE` |

Le contrôleur renvoie **200** sur `POST` (pas 201) — c'est le comportement réel du code, et le script
l'affirme tel quel. Les deux endpoints utilisés par la saga :

```java
@PostMapping("/{id}/seats/reserve")   // decrement atomique, 409 si stock = 0
@PostMapping("/{id}/seats/release")   // restitution (compensation)
```

### 4.5 `traveler-service` — service avancé (profiling + AOP)

| Fichier | Rôle |
|---|---|
| `controller/TravelerController.java` | `GET/POST/PUT/DELETE /api/travelers` (POST → 201) |
| `controller/FavoriteFlightController.java` | `/api/travelers/{id}/favorite-flights[/{flightId}]` |
| `service/TravelerService.java` | logique métier, `@Transactional` |
| `entity/Traveler.java` | table `travelers` (+ les 4 champs de profiling) |
| `profiling/ProfilingRulesEngine.java` | **moteur de règles pur** : score, segment, complétude, flags |
| `profiling/TravelerProfilerService.java` | extrait les attributs, évalue, **persiste** dans `traveler_profiles` |
| `profiling/TravelerProfilerAspect.java` | **aspect AOP** : déclenche le profiling sans toucher au métier |
| `profiling/TravelerProfileController.java` | `/api/travelers/profiles/**` (top, segments, statistics, recompute) |
| `client/FlightClient.java` + `FlightClientFallbackFactory.java` | Feign + fallback (favoris enrichis) |

### 4.6 `booking-service` — la SAGA

| Fichier | Rôle |
|---|---|
| `controller/BookingController.java` | `POST /api/bookings`, `PUT /{id}?status=`, `DELETE /{id}` |
| `controller/BookingQueryController.java` | `GET /api/bookings/{id}` |
| `service/BookingService.java` | orchestration : valider, réserver, écrire, compenser |
| `client/FlightClient.java`, `client/TravelerClient.java` | appels Feign vers les autres services |
| `client/FlightClientFallbackFactory.java` | **fallback** : `null` + log `[CIRCUIT BREAKER]` |
| `messaging/BookingEventPublisher.java` | publication **fire-and-forget** |
| `messaging/BookingRabbitConfig.java` | déclare exchange/file/bindings |
| `messaging/RabbitScenarioController.java` | `POST /api/packh/rabbitmq/poison` (message invalide) |
| `controller/FeignIntegrationController.java` | diagnostic des deux clients, jeton relayé |

### 4.7 `notification-service` (Python) — API + consommateur

| Fichier | Rôle |
|---|---|
| `app/main.py` | FastAPI : `/api/notifications/hello`, `/api/notifications/events`, enregistrement Eureka |
| `app/routers/notification.py` | routes de lecture des événements (même volume que le consommateur) |
| `packh_rabbit_consumer.py` | consommateur `pika` : archive dans `/data/notifications.json`, DLQ si invalide |
| `requirements.txt` | `fastapi`, `uvicorn`, `py-eureka-client`, `httpx`, `pika` |

### 4.8 `conference-service` (Node.js)

| Fichier | Rôle |
|---|---|
| `backend/microservices/conference-service/src/*` | API Express + enregistrement Eureka manuel |
| `Dockerfile` | `node:20-alpine`, `INSTANCE_HOSTNAME`/`INSTANCE_PORT` internes, `PUBLIC_PORT` pour le dashboard |

### 4.9 Frontend

| Fichier | Rôle |
|---|---|
| `frontEnd/NomadixWeb/src/app/services/*.ts` | appels HTTP vers `/api/**` (donc vers la Gateway) |
| `frontEnd/NomadixWeb/src/app/models/nomadix.models.ts` | contrats TypeScript des DTO |
| `frontEnd/NomadixWeb/nginx.conf` | sert l'app et **proxifie `/api`** vers `gateway:8089` |
| `frontEnd/NomadixWeb/Dockerfile` | build Angular 21 (`npm ci` + `ng build --configuration production`) |

### 4.10 Outils de vérification

| Fichier | Rôle |
|---|---|
| `documentation/scripts/run-scenarios.sh` | **runbook exécutable** : 12 scénarios, ~174 assertions, preuves HTTP/SQL/RabbitMQ/logs |
| `documentation/scripts/diagnose-docker-registry.sh` | diagnostic TLS quand `docker compose build` échoue |
| `documentation/scenario.md` | le même déroulé, en version lisible/imprimable |

---

## 5. Dépendances (versions réelles)

### 5.1 Java — `gateway`

`spring-cloud-starter-gateway` (Spring Cloud 2024/2025 aligné sur Boot 3.x),
`spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-loadbalancer`,
`spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-security`,
`spring-boot-starter-actuator`, `spring-boot-starter-test`, `spring-security-test`.

### 5.2 Java — `traveler-service`

`spring-boot-starter-web`, `data-jpa`, `security`, `oauth2-resource-server`, **`aop`**,
`amqp`, `actuator`, `mysql-connector-j`, `spring-cloud-starter-netflix-eureka-client`,
**`spring-cloud-starter-openfeign`**, **`spring-cloud-starter-circuitbreaker-resilience4j`**,
`springdoc-openapi-starter-webmvc-ui`.

> `spring-boot-starter-aop` est la seule dépendance nécessaire au Smart Profiling : sans elle,
> `@Aspect` n'est jamais tissé et **aucun profil n'est calculé**.

### 5.3 Java — `booking-service`

Même socle que le traveler **sans** `aop`, avec en plus `openfeign` et `resilience4j`
(circuit breaker sur les deux clients).

### 5.4 Java — `flight-service` / `eureka-server`

`flight-service` : web, data-jpa, security, oauth2-resource-server, amqp, validation, actuator,
mysql, eureka-client, springdoc.
`eureka-server` : uniquement `spring-cloud-starter-netflix-eureka-server` + actuator.

### 5.5 Python

`fastapi>=0.115`, `uvicorn[standard]>=0.30`, `py-eureka-client>=0.12`, `httpx>=0.27`, `pika==1.3.2`.

### 5.6 Frontend

Angular `^21.2.0`, `@angular/cli ^21.2.6`, `rxjs ~7.8`, `typescript ~5.9`.

### 5.7 Images Docker

`mysql:8.4`, `rabbitmq:3.13-management`, `quay.io/keycloak/keycloak:26.0.8`,
`node:20-alpine` (conference), `node:22-alpine` + `nginx:1.27-alpine` (frontend),
`python:3.12-slim` (notification), `eclipse-temurin` (services Java, via leurs Dockerfiles).

---

## 6. Les mécanismes clés, expliqués avec le code

### 6.1 Eureka : pourquoi `hostname` **et** port publié ne servent pas à la même chose

Une instance publie deux familles d'informations :

- `hostname` + `port` → utilisés par la Gateway (`lb://`) et Feign, **depuis l'intérieur du réseau
  Docker**. Il faut donc `flight-service` + `8082` (nom Compose, port interne).
- `statusPageUrl` / `healthCheckUrl` → affichés dans le dashboard Eureka, ouvert **depuis Windows**.
  Là, il faut `localhost:9082` (port publié).

```yaml
EUREKA_INSTANCE_HOSTNAME: flight-service             # routage interne
EUREKA_INSTANCE_INSTANCE_ID: flight-service:8082
EUREKA_INSTANCE_STATUS_PAGE_URL: http://localhost:9082/actuator/info
EUREKA_INSTANCE_PREFER_IP_ADDRESS: "false"           # jamais 172.20.x.x
```

**Erreur classique** : mettre `EUREKA_INSTANCE_HOSTNAME=localhost` et
`EUREKA_INSTANCE_NON_SECURE_PORT=9082`. Le registre devient inutilisable : la Gateway appelle
`localhost:9082` **depuis son propre conteneur**, où ce port n'existe pas → toutes les routes en 500.

C'est exactement ce que le scénario **S2** vérifie : `<hostName>flight-service</hostName>` et
`<port enabled="true">8082</port>` dans le XML d'Eureka, et l'**absence** de `localhost`.

### 6.2 AOP : le profil est créé par un aspect, pas par le contrôleur

`TravelerProfilerAspect` intercepte **tous** les `save()` des repositories du paquet :

```java
@AfterReturning(
        pointcut = "execution(* com.nomadix.traveler..*Repository+.save(..))",
        returning = "entity")
public void onTravelerSaved(JoinPoint jp, Object entity) {
    if (entity == null) return;
    if (entity.getClass().getSimpleName().equals("TravelerProfile")) return; // anti-recursivite
    profilerService.profileAndSave(entity);
}
```

Trois conséquences, toutes vérifiées par S5 :

1. `POST /api/travelers` **suffit** : le profil existe immédiatement, sans appeler `/recompute`.
2. `PUT /api/travelers/{id}` recalcule aussi le score (c'est le même `save()`).
3. L'exclusion `TravelerProfile` évite une récursion infinie (le profiling sauvegarde un profil, ce
   qui redéclencherait l'aspect).

Le service de profiling est **fail-safe** : toute exception est capturée, le métier n'échoue jamais.

```java
} catch (Exception e) {
    log.warn("[PROFILING] Echec du profiling (metier non impacte) : {}", e.getMessage());
}
```

et il trace chaque calcul (c'est la preuve serveur lue par le script) :

```java
log.info("[PROFILING] Traveler {} -> score={}, segment={}, flags=[{}]",
        attrs.getTravelerId(), result.getScore(), result.getSegment(), profile.getRiskFlags());
```

### 6.3 SAGA : réserver, écrire, compenser

`BookingService.createBooking` suit toujours le même ordre :

```java
validateTraveler(travelerId, authorization);          // 404 si le voyageur n'existe pas
FlightDTO flight = checkFlightAvailability(...);      // 409 si 0 place, 503 si service down
reserveSeat(flightId, authorization);                 // decrement du stock cote Flight
try {
    Booking saved = bookingRepository.save(booking);
    eventPublisher.bookingCreated(saved);             // APRES le commit
    return saved;
} catch (RuntimeException e) {
    releaseQuietly(flightId, authorization);          // COMPENSATION
    throw e;
}
```

Deux points importants :

- **Le prix vient du Flight**, jamais du client : `booking.setTotalPrice(flight.getPrice())`.
  C'est pourquoi S6 vérifie `totalPrice = 210.00` alors que le client n'envoie rien.
- **La compensation est idempotente** grâce à la garde sur le statut :

```java
public void cancelBooking(Long bookingId) {
    Booking booking = findBooking(bookingId);
    if ("CANCELLED".equals(booking.getStatus())) return;   // 2e DELETE : ne restitue pas 2 fois
    ...
    releaseQuietly(booking.getFlightId(), authorization);
}
```

S6 fait donc **deux `DELETE`** : `204` + `204`, mais **une seule** place rendue.

### 6.4 OpenFeign + circuit breaker : 503 *rapide*, pas 30 s d'attente

Chaque client Feign a une `fallbackFactory` :

```java
log.warn("[CIRCUIT BREAKER] Flight-Service indisponible -> fallback. Cause : {}", cause.toString());
return null;   // le service appelant decide : ici -> 503
```

et l'appelant convertit `null` en erreur métier explicite :

```java
if (flight == null) {
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Flight-Service indisponible : timeout, panne ou circuit breaker OPEN");
}
```

Quand le circuit est **OPEN**, Resilience4j refuse l'appel **sans réseau** : la réponse tombe en
quelques dizaines de millisecondes (≈ 0,06 s mesuré) au lieu d'attendre le timeout. C'est
précisément ce que S7 chronomètre, puis vérifie via Actuator :

```json
"flight-service": { "status": "CIRCUIT_OPEN", "details": { "state": "OPEN", ... } }
```

Puis, après redémarrage : `OPEN → HALF_OPEN` (après `waitDurationInOpenState ≈ 10 s`) → quelques
appels de sonde autorisés → `CLOSED`. **Un circuit ne se referme jamais tout seul sur un `sleep` :
il faut de vraies requêtes.** Le script boucle donc sonde + lecture Actuator.

### 6.5 RabbitMQ : publier sans jamais bloquer le métier

```java
private void publish(String routingKey, Booking booking) {
    ...
    try {
        publishRaw(routingKey, objectMapper.writeValueAsString(node));
    } catch (Exception e) {
        log.warn("[RABBITMQ] evenement {} non publie : {}", routingKey, e.getMessage());
    }
}

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

Topologie (déclarée côté Java **et** importée par `definitions.json`) :

```
nomadix.events (topic)
   ├── flight.#   ─┐
   ├── traveler.# ─┼──> notification.events   (x-dead-letter-exchange = nomadix.dlx)
   └── booking.#  ─┘
nomadix.dlx (topic) ──> notification.events.dlq
```

Côté Python, un message invalide ne bloque **jamais** la file :

```python
except Exception as exc:
    print("[notif][DLQ] message refuse sur '%s' (%s) -> %s via %s" % (...))
    ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)   # -> DLX -> DLQ
    return
...
print("[notif] recu %s : %s %s" % (method.routing_key, kind, detail))
ch.basic_ack(delivery_tag=method.delivery_tag)   # ack SEULEMENT apres ecriture
```

`requeue=False` est essentiel : avec `requeue=True`, le message invalide reviendrait en tête de file
et serait re-livré à l'infini (boucle infinie de `nack`).

### 6.6 Database-per-service

Trois bases, trois propriétaires, **aucune clé étrangère inter-bases** :

| Base | Tables | Propriétaire |
|---|---|---|
| `nomadix_flight_db` | `flight` | flight-service |
| `nomadix_traveler_db` | `travelers`, `traveler_profiles`, `traveler_favorite_flights` | traveler-service |
| `nomadix_booking_db` | `bookings` | booking-service |

`traveler_favorite_flights` ne contient **que des identifiants** ; les données du vol sont
récupérées à la volée par Feign, donc **jamais dupliquées**. C'est ce que vérifie S6 (la table
contient `25 | 29`, et **pas** `Rome`).

### 6.7 Le gateway n'est pas le seul rempart

Chaque service est aussi un OAuth2 Resource Server : il revalide le jeton. La Gateway ne fait que
filtrer en amont. C'est pour cela que le script teste **le port direct** (9082) autant que la
Gateway.

### 6.8 Frontend / nginx

`nginx.conf` sert l'application et proxifie `/api` vers `gateway:8089` : le navigateur ne connaît
qu'une origine (`http://localhost:8090`), ce qui évite la majorité des problèmes CORS. La Gateway
garde malgré tout un `globalcors` avec `allowedOriginPatterns: http://localhost:*` pour les appels
directs (Swagger, tests).

---

## 7. Les 12 scénarios : étapes, preuves, interprétation

> Convention : « preuve » = ce qui est affiché ; « assertion » = ce qui est comparé.

### S1 — Preflight

**But** : distinguer « le code est faux » de « la stack n'est pas prête ».

1. `docker compose ps` → au moins 10 conteneurs `running` ;
2. `GET /realms/nomadix` sur Keycloak → `200` et `"realm":"nomadix"` ;
3. jeton **ADMIN** (`admin/admin123`, client `nomadix-cli`) et jeton **TRAVELER** (`sami/sami123`) ;
4. `/actuator/health` des 4 services Java → `200` + `"status":"UP"`.

**Interprétation** : si S1 échoue, tout le reste est faux pour une raison d'environnement. Le script
sort alors en **code 2** avec le message « Stack indisponible », au lieu d'enchaîner 40 échecs.

**Si le jeton est vide** : Keycloak n'a pas fini d'importer le realm (40 s environ au premier
démarrage), ou le client `nomadix-cli` n'est pas public.

### S2 — Eureka

**But** : prouver qu'aucune adresse n'est codée en dur et que le registre est *utilisable*.

Preuves : XML de `/eureka/apps` (6 `<name>…</name>`), puis XML de l'instance `FLIGHT-SERVICE` :
`<hostName>flight-service</hostName>`, `<port enabled="true">8082</port>`, `<status>UP</status>`,
`<instanceId>flight-service:8082</instanceId>`, `<ipAddr>172.…`.

**Interprétation** : ce qui compte n'est pas « ça répond 200 » mais **le nom et le port publiés**.
`apps__hashcode UP_6_` dans la première ligne indique d'ailleurs que 6 applications sont UP.

**Si un service manque** : il a démarré avant Eureka (il réessaie toutes les 5 s) → attendre, puis
`docker compose restart <service>`.

### S3 — Gateway

**But** : un seul point d'entrée, routage par nom, sécurité centralisée.

1. `/actuator/gateway/routes` (ADMIN requis) → présence de `lb://FLIGHT-SERVICE`,
   `lb://TRAVELER-SERVICE`, `lb://BOOKING-SERVICE`, `lb://CONFERENCE-SERVICE`,
   `lb://NOTIFICATION-SERVICE` et de la route `/api/packh/flight-events` ;
2. `GET /api/flights` via Gateway **et** via `:9082` → `200` les deux fois (même contenu) ;
3. `OPTIONS` avec `Origin: http://localhost:8090` → en-tête `access-control-allow-origin` ;
4. matrice : sans jeton `401`, jeton TRAVELER sur `/profiles` `403`, jeton ADMIN `200`.

**Interprétation** : `lb://` (et non `http://localhost:9082`) est la preuve du routage par
découverte. Les corps `401/403` sont en **JSON** (`"status":401`), pas en page HTML : c'est le
`writeError(...)` de `SecurityConfig`.

### S4 — CRUD local (Flight)

**But** : un service écrit dans **sa** base, sans effet de bord asynchrone.

1. `POST /api/flights` → **200** (le contrôleur ne renvoie pas 201) ;
2. `SELECT flight_number / available_seats / price` dans `nomadix_flight_db` → valeurs exactes ;
3. `PUT` → `available_seats` passe à 45, et `COUNT(*)` reste **1** (pas d'INSERT implicite) ;
4. preuve négative : aucun log du consommateur pour ce numéro de vol.

**Interprétation** : le point 4 est une preuve *négative*, la plus difficile à obtenir : un CRUD
local ne publie **rien** sur RabbitMQ. C'est ce qui distingue une opération locale d'une opération
de saga.

### S5 — Smart Profiling + AOP

**But** : le profil est calculé **automatiquement**, par un aspect, sur create **et** update.

1. `statistics` avant le test → `totalProfils` de départ ;
2. `POST /api/travelers` complet (nom, email, téléphone, passeport, nationalité, naissance) → `201` ;
3. `SELECT email` dans `travelers` → la ligne existe ;
4. `GET /api/travelers/profiles/{id}` **sans** `/recompute` → `score=100`, `segment=PLATINUM`,
   `completeness=100`, `rulesVersion=1.1`, `riskFlags=""` ;
5. `SELECT traveler_id, score, segment FROM traveler_profiles` → `id | 100 | PLATINUM` ;
6. profil incohérent (`karim@mailinator.com`, sans passeport) → `score=28`, `BRONZE`,
   `completeness=33`, flags `EMAIL_JETABLE, PASSEPORT_MANQUANT, PROFIL_A_RISQUE` ;
7. `PUT` complétant ce profil → `score=100`, anomalies levées (**même mécanisme, sans appel direct**) ;
8. logs : au moins 3 lignes `[PROFILING] Traveler … -> score=…` ;
9. `statistics` après → `totalProfils` = avant + 2 (delta, pas valeur absolue).

**Interprétation** : le point 4 est la démonstration AOP : personne n'a appelé le calcul. Le point 7
prouve que l'aspect couvre aussi la mise à jour. Le delta du point 9 rend le test rejouable.

**Si `score ≠ 100`** : l'image `traveler-service` est antérieure au code → `--build`.

### S6 — OpenFeign, saga, compensation, favoris

1. `GET /api/bookings/integrations/feign?travelerId=…&flightId=…` → `reachable:true` ×2,
   `found:true` ×2 ;
2. stock lu **avant** ;
3. `POST /api/bookings` → `201`, `totalPrice=210.00` (**fourni par Flight**), `CONFIRMED`, stock −1 ;
4. `SELECT flight_id, status FROM bookings` → `FID | CONFIRMED` ; `traveler_id` → `TID` ;
5. log producteur `[RABBITMQ] publie booking.created` ;
6. `DELETE` ×2 → `204` + `204`, stock revenu à la valeur initiale (**une seule** place rendue),
   `status=CANCELLED` ;
7. favoris : `POST` puis `GET` → la réponse contient `flightNumber` et `price` (donc enrichie par
   Flight), alors que la table ne stocke que des ids.

**Interprétation** : la séquence 3→6 est une SAGA : deux écritures dans deux bases différentes, avec
compensation. Le double `DELETE` en `204` prouve l'**idempotence**.

### S7 — Résilience (Flight arrêté)

1. `docker compose stop flight-service` → `GET :9082/api/flights` échoue (`000`) ;
2. 6 réservations successives → les 4 premières en ~4,5 s (timeouts Feign), les suivantes en
   ~0,06 s : le circuit est **OPEN** ;
3. `/actuator/health` de booking → un `CIRCUIT_OPEN` sur `flight-service` ;
4. log `[CIRCUIT BREAKER] Flight-Service indisponible` ;
5. `SELECT COUNT(*) … status='CONFIRMED'` → `0` : **aucune réservation orpheline** ;
6. redémarrage → `GET /api/flights` `200`, puis boucle sonde/Actuator jusqu'à disparition de
   `CIRCUIT_OPEN` ; `integrations/feign` → `200` et `reachable:true` ×2.

**Interprétation** : ce scénario ne teste pas seulement « ça répond 503 », il mesure le **temps de
réponse** (le fallback doit être immédiat) et vérifie qu'**aucune donnée incohérente** n'a été
écrite pendant la panne.

**`--no-resilience`** ignore S7 et S10 (utile quand on ne veut pas arrêter de conteneurs).

### S8 — RabbitMQ métier (4 événements + 1 à blanc)

| Étape | Émission | Preuve attendue |
|---|---|---|
| R1 | `POST /api/packh/flight-events/emit` | log `[notif] recu flight.created`, `Ready` revenu au départ |
| R2 | `POST /api/packh/traveler-events/emit` | log `[notif] recu traveler.registered` |
| R3 | **vraie** réservation | log `[notif] recu booking.created`, id de la réservation dans le log |
| R4 | `DELETE` de cette réservation | log `[notif] recu booking.cancelled` |
| R5 | `POST /api/packh/booking-events/emit` `{}` | log `recu booking.created` **sans** réservation en base |

**Interprétation** : R5 est la preuve du **découplage** : le consommateur traite un message qui ne
correspond à aucune ligne en base — producteur et consommateur sont indépendants. La taille de
`/data/notifications.json` doit augmenter (volume partagé entre l'API et le consommateur), et
`GET /api/notifications/events?limit=3` doit relire les mêmes événements depuis **un autre
conteneur**.

### S9 — RabbitMQ technique (durabilité, DLQ, topologie)

1. **G1** : consommateur arrêté → `consumers = 0`, 3 messages émis → `messages_ready` = +3 ;
2. **G2a** : `docker compose restart rabbitmq` → `Ready` **toujours** à +3 (durabilité réelle) ;
3. **G2b** : consommateur redémarré → `consumers = 1`, `Ready` revient à la valeur de départ, 3
   lignes `[notif] recu flight.created` ;
4. **G3** : `POST /api/packh/rabbitmq/poison` → `DLQ = +1`, log
   `[notif][DLQ] message refuse sur 'booking.poison'`, puis lecture `x-death` avec
   `ack_requeue_true` (**on ne détruit pas le message**) : `"reason":"rejected"`,
   `"queue":"notification.events"`, `"routing-keys":["booking.poison"]` ;
5. **G4** : `rabbitmqctl` → 2 exchanges `topic`, bindings `flight.#`, `traveler.#`, `booking.#`,
   argument `x-dead-letter-exchange`.

**Interprétation** : G1 = « boîte vocale » ; G2a = la **durabilité** (le message survit au broker) ;
G2b = le **rattrapage** ; G3 = le **refus explicite** (le poison ne boucle pas) ; G4 = la topologie
réellement déclarée, pas celle du fichier.

### S10 — Mode dégradé (broker éteint)

1. stock lu avant ;
2. `docker compose stop rabbitmq` ;
3. `POST /api/bookings` → **la réservation doit aboutir** malgré l'absence de broker ;
4. stock −1 en base, ligne `bookings` présente ;
5. log `[RABBITMQ] publication … impossible (service toujours disponible)` ;
6. broker redémarré → `DELETE` → `204`, stock revenu à la valeur initiale.

**Interprétation, cas important** : sans broker, le client AMQP peut mettre plusieurs dizaines de
secondes à échouer. La réponse HTTP peut donc être `201` **ou** ne jamais arriver (curl expire). Ce
n'est **pas** un échec métier : la transaction est déjà commitée. C'est exactement la philosophie
« fire-and-forget » du `BookingEventPublisher`. Le script :
- accorde 120 s à cette requête (`SEND_TIMEOUT=120`) ;
- si le corps manque, **relit l'id dans la base** (source de vérité) ;
- affirme l'écriture SQL, le stock et le log — pas le code HTTP.

### S11 — Isolation + observabilité

1. `SHOW TABLES` dans chaque base → `traveler_*` **uniquement** côté traveler, `flight` côté flight,
   `bookings` côté booking ;
2. `information_schema` : `0` table `bookings`/`flight` dans `nomadix_traveler_db` ;
3. `/actuator/health` des 4 services + frontend `:8090` → `200` ;
4. `/api/conferences` (Node) et `/api/notifications/hello` (Python) via Gateway → `200` ;
5. `docker compose ps` final → 12 conteneurs `running`.

**Interprétation** : le point 2 démontre l'absence de couplage par la base (pas de jointure
inter-services possible).

### S12 — Erreurs métier réelles

1. vol à `0` place → `POST /api/bookings` = **409** + message `"Vol X complet"`, **aucune** ligne
   dans `bookings` (pas de surbooking) ; le vol de test est supprimé (`204`) ;
2. voyageur `999999` → **404** `"Voyageur 999999 inexistant"` (propagé par Feign →
   `FeignException.NotFound` → `NOT_FOUND`), `COUNT(*)` inchangé ;
3. `DELETE` d'un favori → `200`, puis `GET` → `[]` ;
4. `POST` puis `PUT ?status=CANCELLED` → `200`, `CANCELLED`, stock +1 ; rejouer le `PUT` → `200`,
   stock **inchangé** (idempotence).

**Interprétation** : les codes sont **métier**, pas techniques. Le `409` vient de
`checkFlightAvailability` (`availableSeats <= 0`), le `404` de la conversion d'exception Feign.

---

## 8. Le barème de scoring, calculé à la main

`ProfilingRulesEngine` (version `1.1`), total maximum **100** :

| Règle | Points | Condition |
|---|---|---|
| Complétude | 40 | `filled × 40 / 6`, arrondi (6 champs : nom, email, téléphone, passeport, nationalité, naissance) |
| Email valide | 10 | regex `^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$` |
| Email non jetable | 5 | domaine absent de la liste (mailinator, yopmail, …) |
| Passeport valide | 15 | `^[A-Z0-9]{6,9}$` |
| Téléphone valide | 10 | `^[+]?[0-9 ]{8,15}$` |
| Qualité du nom | 5 (ou 2) | ≥ 3 caractères **et** contient un espace |
| Nationalité | 5 | renseignée |
| Cohérence | 10 | email **et** passeport valides |

Segments : `≥ 80` PLATINUM, `≥ 65` GOLD, `≥ 45` SILVER, sinon BRONZE. Score `< 45` ⇒ flag
`PROFIL_A_RISQUE`.

### Exemple 1 — profil complet (S5) : **100 / PLATINUM**

`Amira Ben Salah`, `amira.demo@nomadix.tn`, `+216 55 123 456`, `TN1234567`, `Tunisienne`,
`1998-04-12` :

- 6 champs sur 6 → `40` pts, `completeness = 100` ;
- email valide `+10`, non jetable `+5` ;
- passeport 9 caractères alphanumériques `+15` ;
- téléphone : `+` puis 15 caractères autorisés `+10` ;
- nom avec espace `+5`, nationalité `+5`, cohérence `+10`.

**Total = 100.** `completeness` (pourcentage de champs) et les points de complétude sont deux
grandeurs **distinctes** : `100` dans les deux cas ici, mais `33` / `13` dans l'exemple suivant.

### Exemple 2 — profil incohérent (S5) : **28 / BRONZE**

`Karim X`, `karim@mailinator.com`, téléphone/passeport/nationalité/naissance absents :

- 2 champs sur 6 → `completeness = 33`, points `13` ;
- email valide `+10`, mais **jetable** → pas de `+5`, flag `EMAIL_JETABLE` ;
- passeport absent → `0`, flag `PASSEPORT_MANQUANT` ;
- téléphone absent → `0` ;
- nom `"Karim X"` → espace + ≥ 3 caractères `+5` ;
- nationalité `0`, cohérence `0` (passeport invalide).

**Total = 13 + 10 + 5 = 28** → BRONZE (< 45) → flag `PROFIL_A_RISQUE`.

### Exemple 3 — voyageur de démonstration `sami` : **33 / BRONZE**

`Sami Trabelsi`, `sami@test.tn`, téléphone / passeport / nationalité / naissance `null` :

- 2 champs sur 6 → `13` points de complétude ;
- email valide `+10`, non jetable `+5` ;
- nom avec espace `+5` ; téléphone, nationalité, cohérence : `0`.

**Total = 33** → BRONZE.

Ce profil de `data.sql` affiche `completeness = 13` : cette valeur a été écrite par une version
antérieure des règles (qui stockait les points et non le pourcentage). L'écart est **historique**,
pas un défaut du moteur : `POST /api/travelers/profiles/{id}/recompute` réapplique les règles
courantes et remet `completeness = 33`. Le script n'asserte donc pas ce profil : il ne teste que des
profils qu'il vient de créer.

---

## 9. Interpréter le bilan final

```
=== BILAN ===
  assertions reussies : 174
  assertions echouees : 0
  identifiants crees  : vol=31  voyageur=27  voyageur incomplet=28
```

- **Tout `[OK]`** : les 12 scénarios sont vérifiés sur la stack réelle, y compris les preuves SQL et
  la durabilité RabbitMQ.
- **Un `[ECHEC]`** : la ligne juste au-dessus donne `$ commande`, `| sortie` et
  `attendu / obtenu`. Il n'y a pas à chercher ailleurs.
- Les identifiants (`vol=…`, `voyageur=…`) restent en base comme jeu de démonstration ; relancer le
  script en crée de nouveaux (aucune collision : les numéros de vol contiennent l'heure).
- Code de sortie : utilisable en CI (`bash script || echo "echec"`).

Assertions **volontairement absentes** (parce que non déterministes) :

- le **rang** exact dans `/profiles/top` (beaucoup d'ex aequo à 100 pts) → on vérifie l'appartenance ;
- les **valeurs absolues** de `messages_ready`, `totalProfils`, `scoreMoyen` → on vérifie des deltas ;
- le **code HTTP** en mode broker éteint (S10) → on vérifie l'état en base.

---

## 10. Erreurs déjà rencontrées et leur cause exacte

### 10.1 `docker compose build` : `x509: certificate is not valid for any names`

Le TLS vers `registry-1.docker.io` est intercepté et le certificat présenté n'a aucun SAN. Ce n'est
**pas** un problème du projet. Diagnostic : `bash documentation/scripts/diagnose-docker-registry.sh`
(il identifie la couche fautive : WSL, proxy Docker Desktop, antivirus, mirror).

### 10.2 Logs jamais trouvés : `docker compose logs … 2>/dev/null`

`docker compose logs` recopie sur **stdout** ce qu'un conteneur écrit sur stdout, et sur **stderr** ce
qu'il écrit sur stderr. En redirigeant `2>/dev/null`, toutes les lignes émises sur stderr étaient
jetées : le comptage valait `0` en permanence alors que la ligne existait (d'où des `[ECHEC]` sur
`[PROFILING]`, `[RABBITMQ]`, `[CIRCUIT BREAKER]`). **Correction : `2>&1` avant le `grep`.**

### 10.3 Eureka : chercher du JSON alors que la réponse est du XML

`/eureka/apps` renvoie du **XML** par défaut. Chercher `"BOOKING-SERVICE"` (guillemets JSON) ne peut
jamais réussir. **Correction : `<name>BOOKING-SERVICE</name>`, `<hostName>…</hostName>`,
`<port enabled="true">8082</port>`.**

### 10.4 Circuit toujours `OPEN` après redémarrage

Un `sleep 12` ne suffit pas : après `waitDurationInOpenState`, le circuit passe en `HALF_OPEN` et
n'autorise que quelques appels de **sonde**. Sans requête réelle, il reste `HALF_OPEN`/`OPEN`.
**Correction : boucle sonde Feign + lecture Actuator jusqu'à disparition de `CIRCUIT_OPEN`.**

### 10.5 `POST /api/bookings` → `HTTP 000` en mode dégradé

Ce n'est pas une panne du service : c'est le client AMQP qui expire (publication impossible). Le
corps de réponse est alors perdu, et l'ancien script réutilisait le corps de la requête précédente
(le JSON du poison), ce qui donnait `DELETE /api/bookings/` → `404`. **Corrections : troncature du
fichier de corps avant chaque appel, délai porté à 120 s, et relecture de l'id en base.**

### 10.6 `top` : le profil de test absent du top 3

`ORDER BY score DESC LIMIT 3` sans critère secondaire : avec plusieurs profils à 100 points, le
classement est arbitraire. **Correction : `limit=50` + appartenance.**

---

## 11. Limites de ce dispositif

1. **Le script ne peut pas être exécuté sans la stack** : il sort en code `2` avec un message
   explicite s'il ne trouve pas Docker ou `docker-compose.yml`.
2. **Windows / WSL** : lancer depuis le dossier contenant `docker-compose.yml`. Les chemins Docker
   Desktop sont sensibles au montage ; le script utilise uniquement `docker compose exec`.
3. **Le profiling est testé via l'AOP**, pas unitairement : un test JUnit du moteur de règles
   (`ProfilingRulesEngine`) serait le complément naturel.
4. **La messagerie est testée sans garantie « exactly once »** : le consommateur peut traiter un
   message deux fois si le broker le re-livre (at-least-once). Documenté, non testé.
5. **`sami` conserve un profil historique** (`score 33`, `completeness 13`) : donnée antérieure à la
   séparation des deux grandeurs, rafraîchissable par
   `POST /api/travelers/profiles/{id}/recompute`.
6. **S7/S10 arrêtent des conteneurs** : à ne pas lancer pendant une démonstration en direct, ou
   utiliser `--no-resilience`.
