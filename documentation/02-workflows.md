# 02 - Workflows

Huit workflows, chacun avec son diagramme et les fichiers de code qui le portent.
Les statuts HTTP indiques sont ceux du code actuel, pas ceux du sujet.

## W1 - CRUD isole : creer un voyageur

Un service qui gere sa propre base sans appeler personne. Aucun `@FeignClient`, aucun
`RabbitTemplate` dans le chemin : c'est le scenario « communication isolee ».

```mermaid
sequenceDiagram
  autonumber
  participant C as Client ou Swagger
  participant G as Gateway 8089
  participant T as Traveler 8081
  participant DB as MySQL traveler_db
  C->>G: POST /api/travelers avec JWT
  G->>G: verifie JWT puis droits
  G->>T: POST /api/travelers
  T->>T: TravelerController.create
  T->>DB: INSERT travelers
  DB-->>T: ligne creee, id genere
  T-->>G: 201 Traveler
  G-->>C: 201 Traveler
  Note over T,DB: effet de bord local, l aspect AOP calcule le profil
```

Code : `traveler/controller/TravelerController.java`, `traveler/repository/TravelerRepository.java`.
Les favoris passent par un second controleur (`FavoriteFlightController`) et, eux, appellent
Flight (W5).

## W2 - Saga de reservation nominale

`POST /api/bookings?travelerId=X&flightId=Y`. Le Booking-Service orchestre deux appels
synchrones avant d'ecrire chez lui. Le prix affiche dans la reservation est la preuve que
Feign a bien lu le vol.

```mermaid
sequenceDiagram
  autonumber
  participant C as Client
  participant G as Gateway 8089
  participant B as Booking 8083
  participant T as Traveler 8081
  participant F as Flight 8082
  participant DB as MySQL booking_db
  C->>G: POST /api/bookings avec JWT
  G->>B: POST /api/bookings, Authorization conserve
  B->>B: BearerTokens.current capture le jeton
  B->>T: GET /api/travelers/X plus Bearer
  T-->>B: 200 TravelerDTO
  B->>F: GET /api/flights/Y plus Bearer
  F-->>B: 200 FlightDTO price et availableSeats
  B->>B: verifie availableSeats superieur a 0
  B->>F: POST /api/flights/Y/seats/reserve plus Bearer
  F->>F: UPDATE conditionnel available_seats superieur a 0
  F-->>B: 200 FlightDTO avec une place en moins
  B->>DB: INSERT bookings status CONFIRMED totalPrice
  B-->>G: 201 Booking
  G-->>C: 201 Booking
```

Points a retenir :

- le jeton est capture **avant** l'appel Feign : le circuit breaker execute l'appel dans un
  `CompletableFuture`, ou le `SecurityContext` n'existe plus (`security/BearerTokens.java`) ;
- le stock est decremente par une requete atomique
  (`FlightRepository.decrementSeat`), pas par un lire-puis-ecrire ;
- la reservation nait directement `CONFIRMED` (`BookingService.createBooking`).

## W3 - Compensation si l'ecriture locale echoue

Pas de transaction distribuee : si la sauvegarde de la reservation echoue apres le
decrement, Booking rend la place (compensation best-effort).

```mermaid
sequenceDiagram
  autonumber
  participant B as Booking 8083
  participant F as Flight 8082
  participant DB as MySQL booking_db
  B->>F: POST seats/reserve
  F-->>B: 200, place decrementee
  B->>DB: INSERT bookings
  DB-->>B: erreur
  B->>F: POST seats/release, compensation
  alt compensation reussie
    F-->>B: 200
    B->>B: exception propagee au client
  else Flight indisponible
    F-->>B: fallback null
    B->>B: log ERROR, restitution manuelle a prevoir
  end
```

## W4 - Panne de Flight : circuit breaker et fallback

```mermaid
stateDiagram-v2
  [*] --> CLOSED
  CLOSED --> OPEN: au moins 5 appels juges et 50 pour cent d echecs
  OPEN --> HALF_OPEN: apres 10 secondes
  HALF_OPEN --> CLOSED: 3 appels test reussis
  HALF_OPEN --> OPEN: un appel test echoue
  CLOSED --> CLOSED: appel reussi
```

Configuration reelle (`booking-service/src/main/resources/application.properties`) :

| Parametre | Valeur | Effet |
|---|---|---|
| `connect-timeout` | 2 000 ms | une instance morte est detectee vite |
| `read-timeout` | 3 000 ms | au lieu des 60 s par defaut de Feign |
| `timelimiter ... timeout-duration` | 4 s | doit rester superieur au read-timeout |
| `sliding-window-size` | 10 | fenetre d'observation |
| `minimum-number-of-calls` | 5 | pas de jugement sur 1 ou 2 appels |
| `failure-rate-threshold` | 50 % | seuil d'ouverture |
| `wait-duration-in-open-state` | 10 s | duree avant test |
| `permitted-number-of-calls-in-half-open-state` | 3 | appels de verification |
| `register-health-indicator` | true | etat visible dans `/actuator/health` |

```mermaid
sequenceDiagram
  autonumber
  participant C as Client
  participant B as Booking 8083
  participant F as Flight 8082
  C->>B: POST /api/bookings
  B->>F: GET /api/flights/1
  F--x B: connexion refusee en quelques millisecondes
  B->>B: fallback FlightClientFallbackFactory
  B-->>C: 503 Service Unavailable, immediat
  Note over B: apres 5 appels la fenetre passe OPEN
```

Le nom du circuit breaker n'est pas `client_methode` : `CircuitBreakerNameResolverConfig`
force le nom court du client Feign. D'ou deux prefixes de configuration differents :

| Service | `@FeignClient(name=...)` | Prefixe de configuration |
|---|---|---|
| Booking | `flight-service` | `...config.flight-service.*` |
| Traveler | `FLIGHT-SERVICE` | `...config.[FLIGHT-SERVICE].*` (crochets = casse preservee) |

Comportement des fallbacks :

| Client | Erreur propagee | Sinon |
|---|---|---|
| Booking vers Flight, `getFlightById` | 404 tel quel | 503 |
| Booking vers Flight, `reserveSeat` | 404 et 409 tels quels | 503 |
| Booking vers Flight, `releaseSeat` | - | `null` + log ERROR |
| Booking vers Traveler | 404 tel quel | `null`, converti en 503 |
| Traveler vers Flight | - | `null` (favori) ou liste vide |

## W5 - Favoris : enrichissement synchrone Traveler vers Flight

Seuls les identifiants sont stockes (`@ElementCollection Set<Long>`). Le detail est lu a
chaque affichage : pas de copie de donnees entre services.

```mermaid
sequenceDiagram
  autonumber
  participant C as Client
  participant T as Traveler 8081
  participant F as Flight 8082
  C->>T: POST /api/travelers/1/favorite-flights/1
  T->>F: GET /api/flights/1 plus Bearer
  alt vol existant
    F-->>T: 200 FlightDTO
    T->>T: ajoute l id dans le Set puis save
    T-->>C: 200 message confirme
  else vol inexistant
    F-->>T: 404
    T->>T: fallback renvoie null
    T-->>C: 404 Vol introuvable
  else Flight arrete
    T->>T: fallback renvoie null
    T-->>C: 404 Vol introuvable
  end
```

```mermaid
sequenceDiagram
  autonumber
  participant C as Client
  participant T as Traveler 8081
  participant F as Flight 8082
  C->>T: GET /api/travelers/1/favorite-flights
  loop pour chaque identifiant favori
    T->>F: GET /api/flights/id
    F-->>T: FlightDTO ou null si fallback
  end
  T->>T: filtre les null
  T-->>C: 200 tableau de FlightDTO
```

`GET` renvoie des vols complets (`departureCity`, `arrivalCity`, `price`), jamais
`[null]` : le filtre a ete ajoute pour respecter le schema publie.

## W6 - Profilage automatique par AOP

Aucune ligne du CRUD n'a ete modifiee : un aspect intercepte les sauvegardes du repository.

```mermaid
sequenceDiagram
  autonumber
  participant C as Client
  participant R as TravelerRepository
  participant A as TravelerProfilerAspect
  participant P as TravelerProfilerService
  participant E as ProfilingRulesEngine
  participant DB as traveler_profiles
  C->>R: save traveler
  R-->>A: retour avec id genere
  A->>P: profileAndSave entity
  P->>P: extraction par reflexion des champs connus
  P->>E: evaluate attributs
  E-->>P: score, segment, completude, flags
  P->>DB: upsert profil
  Note over P: toute erreur est journalisee, le metier continue
```

Une suppression de voyageur declenche la suppression du profil. L'aspect exclut
`TravelerProfile` pour eviter la recursion.

## W7 - RabbitMQ : publication et consommation

Topologie reelle, declaree par le service Python et par les configurations Java :

```mermaid
flowchart LR
  F["Flight-Service 8082<br/>POST /api/packh/flight-events/emit"]
  T["Traveler-Service 8081<br/>POST /api/packh/traveler-events/emit"]
  X{{"Exchange topic<br/>nomadix.events"}}
  Q[["Queue durable<br/>notification.events"]]
  P["packh_rabbit_consumer.py<br/>pika"]
  J[("notifications.json<br/>200 derniers evenements")]
  F -->|"flight.created"| X
  T -->|"traveler.registered"| X
  X -->|"binding flight.#"| Q
  X -->|"binding traveler.#"| Q
  Q -->|"basic_ack apres traitement"| P
  P --> J
```

```mermaid
sequenceDiagram
  autonumber
  participant A as Admin
  participant F as Flight 8082
  participant X as nomadix.events
  participant Q as notification.events
  participant P as Consommateur pika
  A->>F: POST /api/packh/flight-events/emit avec JWT ADMIN
  F->>F: construit le corps JSON puis publie
  F->>X: convertAndSend flight.created
  X->>Q: routage par binding flight.#
  Note over Q: si le consommateur est arrete, les messages restent en Ready
  Q->>P: livraison
  P->>P: ecrit dans notifications.json puis basic_ack
```

- `GET /api/notifications` (FastAPI, memoire) **n'expose pas** ces evenements : ils vont
  dans `notifications.json`, lu par le consommateur. Deux stockages, deux usages.
- Aucun evenement n'est emis automatiquement par un CRUD : publier passe par les endpoints
  `/api/packh/...`, qui sont des declencheurs de demonstration.
- Booking ne publie rien : il n'a pas la dependance AMQP (voir la limite en fin de
  [`../update2.md`](../update2.md)).

## W8 - Cycle de vie d'une reservation

```mermaid
stateDiagram-v2
  [*] --> CONFIRMED: creation, place decrementee
  CONFIRMED --> CANCELLED: PUT status CANCELLED ou DELETE
  CANCELLED --> CONFIRMED: PUT status CONFIRMED, une place reprise
  CONFIRMED --> PENDING: PUT status PENDING
  PENDING --> CONFIRMED: PUT status CONFIRMED
  PENDING --> CANCELLED: PUT status CANCELLED, place restituee
  CANCELLED --> CANCELLED: DELETE ou PUT CANCELLED, idempotent
```

Regles verifiees dans `BookingService` :

| Action | Effet sur le stock | Idempotence |
|---|---|---|
| creation | -1, refus 409 si complet | non |
| `PUT ...?status=CANCELLED` | +1 | oui si deja annulee |
| `DELETE /api/bookings/{id}` | +1 | oui, ne rend jamais deux places |
| `PUT ...?status=CONFIRMED` depuis `CANCELLED` | -1, 409 si complet | non |
| `PUT` avec un statut inconnu | aucun | 400 |

Le statut par defaut `PENDING` n'est applique que s'il est absent
(`Booking.onCreate`), ce qui preserve `CONFIRMED` pose par le service.

## Ordre de demarrage

```mermaid
flowchart LR
  E["1 Eureka 8761"] --> M["2 MySQL 3306"]
  M --> S["3 Traveler 8081, Flight 8082, Booking 8083"]
  S --> R["4 RabbitMQ 5672"]
  R --> N["5 Notification 8084 et Conference 3000"]
  N --> G["6 Gateway 8089"]
  G --> A["7 Angular 4200"]
  K["Keycloak 8180"] -.->|"avant le premier appel protege"| G
```
