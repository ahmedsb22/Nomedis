# Guide de demonstration Nomadix (v2)

Runbook de soutenance : pour chaque scenario, la **logique**, l'**action** et la
**preuve** a montrer au jury. Toutes les commandes se lancent depuis la racine du
projet, apres `docker compose up -d`.

> Version corrigee du guide precedent. Trois affirmations ont ete remises d'accord
> avec le code reel :
> - Eureka n'est **pas** protege par JWT (aucun `spring-boot-starter-security`
>   dans `backend/discovery/eureka-server/pom.xml`) ;
> - l'eviction d'un service prend jusqu'a **150 s** (bail 90 s + balayage 60 s),
>   pas 40 s ;
> - le disjoncteur s'ouvre au **5e** echec (fenetre 10, minimum 5 appels, 50 %),
>   et les quatre premiers appels prennent ~4,5 s chacun.

---

## Etape 0 : pre-requis (a executer avant toute demo)

Deux jetons Keycloak sont necessaires : `ADMIN` pour les ecritures, `TRAVELER`
pour prouver la matrice de droits.

```bash
# Jeton ADMIN
TOKEN=$(curl -s -X POST http://localhost:8180/realms/nomadix/protocol/openid-connect/token \
  -d "grant_type=password&client_id=nomadix-cli&username=admin&password=admin123&scope=openid profile email" \
  | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)

# Jeton TRAVELER (Sami)
TOKEN_TRAVELER=$(curl -s -X POST http://localhost:8180/realms/nomadix/protocol/openid-connect/token \
  -d "grant_type=password&client_id=nomadix-cli&username=sami&password=sami123&scope=openid profile email" \
  | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)

echo "ADMIN=${#TOKEN} caracteres / TRAVELER=${#TOKEN_TRAVELER} caracteres"
```

Attendu : deux valeurs non nulles (le jeton ADMIN fait ~1100 caracteres).
**Sans ce prefixe, toutes les commandes suivantes renvoient 401.**

---

## Categorie 1 : RabbitMQ metier (communication asynchrone)

### R1 - Evenement `flight.created` (declencheur de demonstration)

**Logique** : `POST /api/packh/flight-events/emit` publie directement dans
l'exchange topic `nomadix.events` avec la routing key `flight.created`. C'est un
declencheur de demonstration : il prouve la chaine producteur -> broker ->
consommateur sans dependre de l'ecriture en base.

```bash
curl -s -X POST http://localhost:9089/api/packh/flight-events/emit \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"flightNumber":"TEST-R1"}'
```

**Preuve logs**

```bash
docker compose logs --since 1m notification-consumer | grep "flight.created"
```

Attendu : `[notif] recu flight.created : flight.created TEST-R1`.

**Preuve archive** (le consommateur Python ecrit dans un volume partage)

```bash
docker compose exec notification-consumer sh -c "tail -n 4 /data/notifications.json"
```

Attendu : un bloc JSON avec `"routingKey": "flight.created"`.

### R2 - `booking.created` (evenement metier reel, apres la SAGA)

**Logique** : Booking valide via Feign, ecrit la reservation, **puis** publie
`booking.created`. La notification ne bloque jamais la reponse HTTP.

```bash
curl -s -X POST "http://localhost:9089/api/bookings?travelerId=1&flightId=2" \
  -H "Authorization: Bearer $TOKEN"
```

Attendu : `201` avec `"totalPrice": 890.00` (prix lu chez Flight, pas envoye par
le client).

```bash
docker compose logs --since 1m notification-consumer | grep "booking.created"
docker compose exec notification-consumer sh -c "grep 'booking.created' /data/notifications.json | tail -n 2"
```

### R3 - `booking.cancelled` et compensation du siege

**Logique** : Booking passe le statut a `CANCELLED`, publie `booking.cancelled`,
puis restitue le siege via Feign. Le `DELETE` est idempotent : un second appel
renvoie `204` sans rendre une deuxieme place.

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE \
  http://localhost:9089/api/bookings/<ID> -H "Authorization: Bearer $TOKEN"

docker compose exec mysql mysql -unomadix -pnomadix123 nomadix_flight_db \
  -e "SELECT available_seats FROM flight WHERE id=2;"
```

Attendu : `204`, puis le stock revient a sa valeur d'avant R2 (ex. 120).

```bash
docker compose logs --since 1m notification-consumer | grep "booking.cancelled"
```

### R4 - `traveler.registered` (declencheur de demonstration)

```bash
curl -s -X POST http://localhost:9089/api/packh/traveler-events/emit \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"travelerId":1}'

docker compose exec notification-consumer sh -c "grep 'traveler.registered' /data/notifications.json | tail -n 4"
```

---

## Categorie 2 : RabbitMQ technique (resilience)

### T1 - Durabilite : la « boite vocale »

**Logique** : la file `notification.events` est declaree `durable=true` dans
`backend/messaging/rabbitmq/definitions.json`. Consommateur arrete, les messages
restent sur disque ; au redemarrage, il les traite tous.

```bash
docker compose stop notification-consumer

# trois emissions d'affilee
for i in 1 2 3; do
  curl -s -o /dev/null -X POST http://localhost:9089/api/packh/flight-events/emit \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"flightNumber\":\"DUR-$i\"}"
done

docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready consumers \
  | grep -w 'notification.events'
```

Attendu : `notification.events  3  0` (3 messages prets, 0 consommateur).

```bash
docker compose start notification-consumer
sleep 10
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready consumers \
  | grep -w 'notification.events'
```

Attendu : `notification.events  0  1`, et les trois messages apparaissent dans les
logs du consommateur.

### T2 - Dead Letter Queue : la « poison pill »

**Logique** : un message volontairement invalide est publie. Le consommateur
Python fait `basic_nack(requeue=False)` ; RabbitMQ applique le `x-dead-letter-exchange`
de la file et deplace le message vers `notification.events.dlq`, sans bloquer la
file principale.

```bash
curl -s -X POST http://localhost:9089/api/packh/rabbitmq/poison \
  -H "Authorization: Bearer $TOKEN"

docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready \
  | grep -w 'notification.events'
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready \
  | grep -w 'notification.events.dlq'
```

Attendu : file principale a `0`, compteur DLQ augmente de 1.

---

## Categorie 3 : OpenFeign (communication synchrone et SAGA)

### F1 - Saga de reservation (validation synchrone)

**Logique** : Booking appelle Flight en synchrone (stock + prix), **puis** ecrit
sa propre transaction locale. Si Flight refuse (`409`, `404`), rien n'est ecrit.

```bash
curl -s -X POST "http://localhost:9089/api/bookings?travelerId=1&flightId=2" \
  -H "Authorization: Bearer $TOKEN"
```

**Preuve synchronisation du prix** : la reponse contient `"totalPrice": 890.00`
alors que le client n'envoie que deux identifiants.

```bash
docker compose exec mysql mysql -unomadix -pnomadix123 nomadix_flight_db \
  -e "SELECT available_seats FROM flight WHERE id=2;"
```

Attendu : stock decremente de 1.

### F2 - Enrichissement des favoris (API composition)

**Logique** : Traveler ne stocke que des identifiants ; les details du vol sont
demandes a Flight via Feign au moment de la lecture, puis fusionnes dans la
reponse.

```bash
curl -s -X POST http://localhost:9089/api/travelers/1/favorite-flights/2 \
  -H "Authorization: Bearer $TOKEN"

curl -s http://localhost:9089/api/travelers/1/favorite-flights \
  -H "Authorization: Bearer $TOKEN"
```

Attendu : `flightNumber`, `departureCity`, `price` presents dans la reponse.

**Preuve d'isolation des donnees**

```bash
docker compose exec mysql mysql -unomadix -pnomadix123 nomadix_traveler_db \
  -e "SHOW COLUMNS FROM traveler_favorite_flights;"
```

Attendu : uniquement `traveler_id` et `flight_id`. Aucune donnee de vol dupliquee.

### F3 - Compensation et idempotence

```bash
for i in 1 2; do
  curl -s -o /dev/null -w "DELETE $i : %{http_code}\n" -X DELETE \
    http://localhost:9089/api/bookings/<ID> -H "Authorization: Bearer $TOKEN"
done

docker compose exec mysql mysql -unomadix -pnomadix123 nomadix_flight_db \
  -e "SELECT available_seats FROM flight WHERE id=2;"
```

Attendu : `204` puis `204`, et **une seule** place rendue. Le second appel ne
re-execute pas la compensation parce que le statut est deja `CANCELLED`.

### F4 - Circuit Breaker (Resilience4j)

**Logique** : le client Feign est protege par `@CircuitBreaker(name="flight-service",
fallbackMethod=...)`. Fenetre de 10 appels, minimum 5 appels, seuil 50 %,
`waitDurationInOpenState` 10 s, 3 appels de sonde en `HALF_OPEN`.

```bash
docker compose stop flight-service

for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w "Appel $i : %{http_code} en %{time_total}s\n" \
    -X POST "http://localhost:9089/api/bookings?travelerId=1&flightId=2" \
    -H "Authorization: Bearer $TOKEN"
done
```

Attendu : appels 1 a 4 en `503` au bout de ~4,5 s (timeout Feign 2 s + 3 s,
plafonne par le `TimeLimiter` a 4 s) ; 5e appel en `503` en ~0,06 s, le circuit
etant ouvert.

**Preuve de l'etat du circuit (a faire AVANT de redemarrer Flight)**

```bash
curl -s http://localhost:9083/actuator/health \
  | grep -o '"flight-service".\{0,400\}' | grep -o '"state":"[A-Z_]*"'
```

Attendu : `"state":"OPEN"` (ou `"HALF_OPEN"` si les 10 s sont deja passees).

```bash
docker compose logs --since 2m booking-service | grep -i "circuit\|fallback"
docker compose start flight-service
```

Attendu : une ligne `[CIRCUIT BREAKER] Flight-Service indisponible -> fallback`,
puis retour a `CLOSED` apres ~15 s.

---

## Categorie 4 : Eureka (service discovery)

### E1 - Enregistrement dynamique et isolation reseau

**Logique** : la Gateway ne connait aucune adresse IP. Elle interroge Eureka, qui
renvoie le **nom DNS du conteneur** et le **port interne**. Eureka n'est pas
expose par la Gateway et **n'est pas protege par JWT** dans cette configuration :
sa securite repose sur l'isolation du reseau Docker. Envoyer un jeton ne change
rien a la reponse.

```bash
curl -s http://localhost:9761/eureka/apps | grep -o '<name>[A-Z-]*</name>' | sort -u
```

Attendu : `API-GATEWAY`, `BOOKING-SERVICE`, `CONFERENCE-SERVICE`,
`FLIGHT-SERVICE`, `NOTIFICATION-SERVICE`, `TRAVELER-SERVICE`. Eureka ne
s'auto-enregistre pas.

```bash
curl -s http://localhost:9761/eureka/apps/FLIGHT-SERVICE \
  | grep -E '<hostName>|<port enabled="true">'
```

Attendu : `<hostName>flight-service</hostName>` et
`<port enabled="true">8082</port>` : nom resolvable, port interne, pas d'IP, pas
de port hote.

### E2 - Panne et desenregistrement automatique

**Logique** : le bail est de 90 s (`lease-expiration-duration-in-seconds`) et le
balayage d'eviction de 60 s (`eviction-interval-timer-in-ms`). Un service arrete
reste donc `UP` jusqu'a **150 s** dans l'annuaire.

```bash
docker compose stop flight-service

for i in $(seq 1 16); do
  n=$(curl -s http://localhost:9761/eureka/apps/FLIGHT-SERVICE | grep -c '<status>UP</status>')
  echo "t=$((i*10))s | instances UP: $n"
  [ "$n" -eq 0 ] && break
  sleep 10
done
```

Attendu : la boucle s'arrete quand `instances UP: 0` (typiquement entre 90 s et
150 s). **Ne pas conclure a 40 s : la valeur serait encore 1.**

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9089/api/flights \
  -H "Authorization: Bearer $TOKEN"
docker compose start flight-service
```

Attendu : `503` (la Gateway a vide son cache `registry-fetch-interval-seconds: 5`),
puis retour a `200` apres redemarrage.

---

## Categorie 5 : API Gateway (securite et routage)

### G1 - Table de routage chargee

```bash
curl -s http://localhost:9089/actuator/gateway/routes \
  -H "Authorization: Bearer $TOKEN" | grep -o '"uri":"lb://[A-Z-]*"' | sort -u
```

Attendu : `lb://BOOKING-SERVICE`, `lb://FLIGHT-SERVICE`, `lb://TRAVELER-SERVICE`,
`lb://CONFERENCE-SERVICE`, `lb://NOTIFICATION-SERVICE`. Le prefixe `lb://` est ce
qui declenche la resolution via Eureka et le load-balancing cote client.

### G2 - Matrice de securite (401 / 403 / 200)

```bash
# 1. aucun jeton -> 401
curl -s -o /dev/null -w "Sans token : %{http_code}\n" http://localhost:9089/api/travelers

# 2. jeton TRAVELER sur une route ADMIN -> 403
curl -s -o /dev/null -w "TRAVELER sur route ADMIN : %{http_code}\n" \
  -X POST http://localhost:9089/api/flights \
  -H "Authorization: Bearer $TOKEN_TRAVELER" -H 'Content-Type: application/json' \
  -d '{"flightNumber":"TEST","departureCity":"Tunis","arrivalCity":"Paris","departureDate":"2027-01-01","availableSeats":10,"price":100}'

# 3. jeton ADMIN -> 200 (le controleur Flight renvoie 200, pas 201)
curl -s -o /dev/null -w "ADMIN sur route ADMIN : %{http_code}\n" \
  -X POST http://localhost:9089/api/flights \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"flightNumber":"TEST-OK","departureCity":"Tunis","arrivalCity":"Paris","departureDate":"2027-01-01","availableSeats":10,"price":100}'
```

Attendu : `401`, `403`, `200`. Le corps des erreurs est du JSON (`"status":401`),
pas une page HTML : c'est le `SecurityConfig` de la Gateway qui repond.

### G3 - CORS (defense en profondeur)

```bash
curl -s -D - -o /dev/null -X OPTIONS http://localhost:9089/api/flights \
  -H 'Origin: http://localhost:8090' \
  -H 'Access-Control-Request-Method: POST' \
  | grep -i 'access-control-allow-origin'
```

Attendu : `access-control-allow-origin: http://localhost:8090`. L'origine est
listee explicitement dans `CorsConfig.java` : pas de `*` avec
`allow-credentials: true`.

---

## Categorie 6 : Smart Profiling et AOP

### P1 - Profil complet (score 100, PLATINUM)

**Logique** : le controleur sauvegarde l'entite ; l'aspect `@AfterReturning`
intercepte `save()`, applique `ProfilingRulesEngine` (40 pts identite, 10 email
valide, 5 email non jetable, 15 passeport, 10 telephone, 5 nationalite, 5 date de
naissance, 10 coherence) et persiste dans `traveler_profiles`.

```bash
curl -s -X POST http://localhost:9089/api/travelers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"firstName":"Alan","lastName":"Turing","email":"alan@science.fr","role":"USER",
       "phone":"+33612345678","passportNumber":"UK987654","nationality":"Britannique",
       "dateOfBirth":"1912-06-23"}'
```

Noter l'`id` retourne (ex. 42), puis :

```bash
curl -s http://localhost:9089/api/travelers/profiles/42 -H "Authorization: Bearer $TOKEN"
```

Attendu : `"score":100`, `"segment":"PLATINUM"`, `"completeness":100`,
`"riskFlags":""`.

```bash
docker compose logs --since 2m traveler-service | grep "PROFILING.*42"
docker compose exec mysql mysql -unomadix -pnomadix123 nomadix_traveler_db \
  -e "SELECT traveler_id, score, segment FROM traveler_profiles WHERE traveler_id=42;"
```

### P2 - Profil a risque (score 28, BRONZE)

```bash
curl -s -X POST http://localhost:9089/api/travelers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"firstName":"Hack","lastName":"Test","email":"test@yopmail.com","role":"USER"}'
```

Attendu sur `/api/travelers/profiles/43` : `"score":28`, `"segment":"BRONZE"`,
`"completeness":33`,
`"riskFlags":"EMAIL_JETABLE, PASSEPORT_MANQUANT, PROFIL_A_RISQUE"`.

### P3 - Preuve de l'AOP sur la mise a jour

**Logique** : aucun endpoint `/recompute` n'est appele. Le `PUT` declenche un
`save()`, donc l'aspect s'execute une seconde fois et le score remonte seul.

```bash
curl -s -X PUT http://localhost:9089/api/travelers/43 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"firstName":"Hack","lastName":"Fixed","email":"hack.fixed@nomadix.tn","role":"USER",
       "phone":"0600000000","passportNumber":"FR1111111","nationality":"Francaise",
       "dateOfBirth":"1990-01-01"}'

curl -s http://localhost:9089/api/travelers/profiles/43 -H "Authorization: Bearer $TOKEN"
docker compose logs --since 5m traveler-service | grep "PROFILING.*43"
```

Attendu : `"score":100`, `"segment":"PLATINUM"`, et **deux** lignes de log pour
l'id 43 (score=28 au POST, score=100 au PUT).

```bash
docker compose exec mysql mysql -unomadix -pnomadix123 nomadix_traveler_db \
  -e "SELECT traveler_id, score, segment FROM traveler_profiles WHERE traveler_id=43;"
```

---

## Remise en etat apres la demo

```bash
# Vols de test crees pendant G2
curl -s -o /dev/null -X DELETE http://localhost:9089/api/flights/<id> \
  -H "Authorization: Bearer $TOKEN"

# Le circuit breaker revient a CLOSED tout seul (waitDurationInOpenState 10 s)
docker compose start flight-service
docker compose ps
```

Les identifiants crees restent en base comme jeu de demonstration : le profiling
lit des deltas, les scenarios sont rejouables sans purge.

---

## Recapitulatif

| Scenario | Mecanisme | Preuve attendue |
|---|---|---|
| R1 | RabbitMQ, routing key `flight.created` | log consommateur + entree JSON |
| R2 | RabbitMQ apres SAGA | `booking.created` + prix Feign 890.00 |
| R3 | Compensation idempotente | `204`, stock +1 une seule fois |
| R4 | RabbitMQ `traveler.registered` | archive `/data/notifications.json` |
| T1 | Durabilite de la file | `3 / 0` puis `0 / 1` |
| T2 | DLQ (`nack requeue=false`) | file principale 0, DLQ +1 |
| F1 | Feign synchrone | `totalPrice` non envoye par le client |
| F2 | API composition | champs du vol presents, non stockes |
| F3 | Idempotence | deux `204`, une seule place rendue |
| F4 | Resilience4j | ~4,5 s x4 puis ~0,06 s, `state=OPEN` |
| E1 | Eureka | 6 services, `hostName` + port interne |
| E2 | Bail et eviction | `UP=0` avant 150 s, Gateway `503` |
| G1 | Routage `lb://` | 5 routes metier |
| G2 | Zero trust | `401` / `403` / `200` |
| G3 | CORS | origine explicite, pas de `*` |
| P1 | AOP creation | score 100, PLATINUM |
| P2 | AOP regles de risque | score 28, 3 flags |
| P3 | AOP mise a jour | score 28 -> 100 sans `/recompute` |
