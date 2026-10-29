# 06 - Scenarios de test (OpenFeign, RabbitMQ, Eureka, Gateway)

Guide operationnel : comment provoquer chaque scenario, quoi observer, et quel resultat
prouve que le mecanisme fonctionne. Toutes les commandes sont en **bash** (Git Bash, WSL
ou Linux) et supposent la stack Docker demarree.

## 1. Demarrage et adresses

```bash
cd <racine du projet>
docker compose up -d --build      # 10 conteneurs
docker compose ps                 # tout doit etre "Up" (mysql/rabbitmq/eureka/keycloak "healthy")
```

| Composant | URL | Identifiants |
|---|---|---|
| Frontend Angular | <http://localhost:8090> | admin / admin123, sami / sami123 |
| Gateway | <http://localhost:9089> | — |
| Eureka | <http://localhost:9761> | — |
| Swagger traveler | <http://localhost:9081/swagger-ui.html> | bouton **Authorize** |
| Swagger flight | <http://localhost:9082/swagger-ui.html> | bouton **Authorize** |
| Swagger booking | <http://localhost:9083/swagger-ui.html> | bouton **Authorize** |
| Swagger notification | <http://localhost:9084/swagger-ui> | bouton **Authorize** |
| Swagger conference | <http://localhost:3001/swagger-ui> | bouton **Authorize** |
| Keycloak | <http://localhost:8180> | admin / admin |
| RabbitMQ | <http://localhost:15672> | nomadix / nomadix123 |
| MySQL | `localhost:3307` | root / root |

## 2. Obtenir un jeton (client `nomadix-cli`)

Le client public `nomadix-cli` autorise le *Resource Owner Password Grant* : pratique pour
`curl`. Le jeton porte l'audience `nomadix-api`, exigee par la Gateway et les services.

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/nomadix/protocol/openid-connect/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'client_id=nomadix-cli' \
  -d 'grant_type=password' -d 'username=admin' -d 'password=admin123' \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

echo "${TOKEN:0:24}..."     # doit afficher les 24 premiers caracteres
```

Pour un simple voyageur : `username=sami&password=sami123` (role `TRAVELER`, pas `ADMIN`).

## 3. Verifications de base (2 minutes)

```bash
# 1. Eureka : 6 applications, toutes UP
curl -s -H 'Accept: application/json' http://localhost:9761/eureka/apps \
  | grep -o '"name":"[A-Z-]*"' | sort -u

# 2. Gateway : catalogue public (200 sans jeton)
curl -s -o /dev/null -w 'flights publics : %{http_code}\n' http://localhost:9089/api/flights

# 3. Gateway : ressource protegee sans jeton (401 attendu)
curl -s -o /dev/null -w 'travelers sans jeton : %{http_code}\n' http://localhost:9089/api/travelers

# 4. Gateway : avec jeton (200 attendu)
curl -s -o /dev/null -w 'travelers avec jeton : %{http_code}\n' \
  -H "Authorization: Bearer $TOKEN" http://localhost:9089/api/travelers

# 5. Ecriture ADMIN (201) puis refus avec le compte sami (403)
curl -s -o /dev/null -w 'POST flight admin : %{http_code}\n' \
  -X POST http://localhost:9089/api/flights \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"flightNumber":"TN900","departureCity":"Tunis","arrivalCity":"Rome","departureDate":"2027-03-15","availableSeats":80,"price":290.00}'
```

Dans le dashboard Eureka (<http://localhost:9761>), chaque instance doit afficher :

```
API-GATEWAY          UP (1) - gateway:8089
TRAVELER-SERVICE     UP (1) - traveler-service:8081
FLIGHT-SERVICE       UP (1) - flight-service:8082
BOOKING-SERVICE      UP (1) - booking-service:8083
NOTIFICATION-SERVICE UP (1) - notification-service:8084
CONFERENCE-SERVICE   UP (1) - conference-service:3000
```

Le lien de chaque instance pointe vers le **port publie** (`localhost:9081`, `9082`...),
donc il est cliquable depuis le navigateur du poste hote. L'identifiant, lui, reste fonde
sur le **nom Docker + port interne** : c'est ce que la Gateway et Feign resolvent.

## 4. Scenarios OpenFeign

OpenFeign est un appel **sortant** : il ne s'invoque pas depuis le navigateur, il se
declenche par une route metier. Trois observatoires : l'UI Angular, les endpoints de
diagnostic, les logs Docker.

### F1 - Favoris d'un voyageur (Feign Traveler -> Flight)

*UI* : **Voyageurs** -> carte d'un voyageur -> **Voir les favoris**.

*Observation* : la liste s'affiche avec numero de vol, villes et date. Le Traveler-Service
ne stocke que des identifiants : ces champs viennent d'un appel Feign au Flight-Service.

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:9089/api/travelers/1/favorite-flights
```

*Echec attendu si le Flight-Service est arrete* : reponse de fallback (`[]` ou message
explicite), pas une erreur 500. Voir F5.

### F2 - Diagnostic des deux clients Feign

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:9089/api/bookings/integrations/feign?travelerId=1&flightId=1'
```

```json
{
  "travelerService": { "reachable": true, "found": true, "data": { "id": 1, "...": "..." } },
  "flightService":   { "reachable": true, "found": true, "data": { "id": 1, "...": "..." } }
}
```

`reachable: false` = timeout, exception ou circuit ouvert : ce n'est pas une erreur de
l'API Booking. `found: false` = le service repond mais la ressource n'existe pas (404).

### F3 - Saga de reservation nominale

*UI* : **Reservations** -> choisir un voyageur et un vol -> **Reserver**.

*Deroulement verifie* : voyageur lu par Feign -> vol lu par Feign (prix) -> place decrementee
par Feign (`POST /api/flights/{id}/seats/reserve`) -> booking `CONFIRMED` en base ->
`booking.created` publie -> notification archivee.

```bash
# travelerId et flightId sont des parametres de requete, pas un corps JSON
curl -s -X POST "http://localhost:9089/api/bookings?travelerId=1&flightId=1" \
  -H "Authorization: Bearer $TOKEN"
```

Puis, dans le meme ordre :

```bash
# le stock a baisse de 1
curl -s http://localhost:9089/api/flights/1 | grep availableSeats

# l'evenement a ete consomme (archive du consommateur Python)
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:9089/api/notifications/events | head -40
```

### F4 - Stock epuise (409, pas de surbooking)

```bash
# consommer tout le stock d'un vol de test (cree pour l'occasion)
for i in $(seq 1 3); do
  curl -s -o /dev/null -X POST \
    "http://localhost:9089/api/bookings?travelerId=1&flightId=<ID_VOL_3_PLACES>" \
    -H "Authorization: Bearer $TOKEN"
done
# la 4e doit renvoyer 409 et un message metier
curl -s -w '\nHTTP %{http_code}\n' -X POST \
  "http://localhost:9089/api/bookings?travelerId=1&flightId=<ID_VOL_3_PLACES>" \
  -H "Authorization: Bearer $TOKEN"
```

### F5 - Panne d'un service : fallback et circuit breaker

```bash
docker stop nomadix-flight

# 1. la lecture publique echoue en ~2 s : 500 tant que l'instance est encore
#    enregistree (connexion refusee), puis 503 apres son eviction (~90 s)
curl -s -o /dev/null -w 'flights : %{http_code}\n' http://localhost:9089/api/flights

# 2. Feign ne bloque pas la reservation : fallback / erreur explicite
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://localhost:9089/api/bookings/integrations/feign?travelerId=1&flightId=1'

# 3. etat du circuit breaker dans le service appelant
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:9089/actuator/health | grep -i circuit

docker start nomadix-flight      # retour a la normale en ~30 s
```

Attendu : `reachable: false`, delai < 4 s (timeouts 2 s connexion / 3 s lecture,
TimeLimiter 4 s), aucun thread bloque. Apres quelques echecs, le circuit passe `OPEN`
puis `HALF_OPEN` apres 10 s.

### F6 - Voyageur inexistant (404 propage, pas de booking orphelin)

```bash
curl -s -w '\nHTTP %{http_code}\n' -X POST \
  "http://localhost:9089/api/bookings?travelerId=99999&flightId=1" \
  -H "Authorization: Bearer $TOKEN"
```

Attendu : `404` (ou `400`) avec un message lisible. Verifier qu'aucune ligne n'a ete creee :

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:9089/api/bookings | grep 99999
```

### F7 - Trace dans les logs

```bash
docker logs nomadix-booking 2>&1 | grep -iE 'feign|traveler|flight' | tail -20
docker logs nomadix-traveler 2>&1 | grep -i 'favorite' | tail -10
docker logs nomadix-gateway 2>&1 | grep -iE 'route|lb://' | tail -10
```

## 5. Scenarios RabbitMQ

Topologie : exchange topic `nomadix.events` (bindings `flight.#`, `traveler.#`, `booking.#`)
-> file durable `notification.events` -> consommateur Python ; en cas de refus :
`nomadix.dlx` -> `notification.events.dlq`.

Ouvrir <http://localhost:15672> (onglet **Queues and Streams**) pendant les tests : les
compteurs *Ready / Unacked* sont la preuve visuelle.

### R1 - `traveler.registered` (emission manuelle, ADMIN)

```bash
curl -s -X POST http://localhost:9089/api/packh/traveler-events/emit \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"travelerId":"TRV-42","fullName":"Sami Trabelsi","email":"sami@nomadix.tn"}'
```

Attendu : `routingKey: "traveler.registered"`, `publishedTo: "nomadix.events"`, puis la
file revient a 0 (consommee) et l'evenement apparait dans
`GET /api/notifications/events` et dans l'UI **Notifications > Evenements consommes**.

### R2 - `flight.created`

```bash
curl -s -X POST http://localhost:9089/api/packh/flight-events/emit \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"flightNumber":"TN999","departureCity":"Tunis","arrivalCity":"Paris"}'
```

### R3 - `booking.created` automatique

Se declenche par F3 (aucun appel `/api/packh` necessaire). Verification : nouvelle entree
`booking.created` en tete de `GET /api/notifications/events`.

Emission manuelle possible :

```bash
curl -s -X POST http://localhost:9089/api/packh/booking-events/emit \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'
```

### R4 - `booking.cancelled` et restitution du stock

*UI* : **Reservations** -> **Annuler**.

```bash
curl -s -X DELETE -H "Authorization: Bearer $TOKEN" http://localhost:9089/api/bookings/<ID>
```

Attendu : statut `CANCELLED`, stock +1 (une seule fois meme si l'annulation est rejouee),
evenement `booking.cancelled` archive.

### R5 - Message empoisonne -> DLX -> DLQ

```bash
curl -s -X POST http://localhost:9089/api/packh/rabbitmq/poison -H "Authorization: Bearer $TOKEN"
```

Reponse : `published: true`, `deadLetterQueue: "notification.events.dlq"`.

Dans la console RabbitMQ :

1. `notification.events` : *Ready* ne doit pas rester a 1 (le consommateur refuse vite) ;
2. `notification.events.dlq` : *Ready* = 1 ;
3. file DLQ -> **Get messages** : corps `POISON-MESSAGE (not JSON)...` et en-tete
   `x-death` avec `reason = rejected`, `queue = notification.events` ;
4. onglet **Exchanges** : `nomadix.dlx` est le point de passage.

### R6 - `simulateFailure` (refus applicatif, pas syntaxique)

Publier depuis l'UI RabbitMQ (**Exchanges > nomadix.events > Publish message**),
routing key `booking.created`, payload :

```json
{"type":"booking.created","bookingId":999,"simulateFailure":true}
```

Attendu : le consommateur appelle `basic_nack(requeue=false)` -> le message part en DLQ
sans boucle infinie. C'est la difference avec un message simplement mal forme.

### R7 - RabbitMQ indisponible (mode degrade)

```bash
docker stop nomadix-rabbitmq

# La reservation doit reussir quand meme : la publication est fire-and-forget
curl -s -w '\nHTTP %{http_code}\n' -X POST \
  "http://localhost:9089/api/bookings?travelerId=1&flightId=1" \
  -H "Authorization: Bearer $TOKEN"

# Le scenario poison renvoie published:false au lieu d'une erreur 500
curl -s -X POST http://localhost:9089/api/packh/rabbitmq/poison -H "Authorization: Bearer $TOKEN"

docker start nomadix-rabbitmq   # le consommateur se reconnecte seul (boucle de retry 5 s)
```

Attendu : `201` pour la reservation et `published: false` pour le poison. Un broker
indisponible ne doit jamais bloquer le chemin synchrone.

### R8 - Redemarrage du consommateur (durabilite)

```bash
docker restart nomadix-notification-consumer
sleep 5
docker logs nomadix-notification-consumer 2>&1 | tail -5
# -> "connecte a RabbitMQ - attente de messages sur 'notification.events'"
```

Les messages presents dans la file durable sont traites au redemarrage ; ceux deja
`ack` ne sont pas rejoues (pas de doublon dans `notifications.json`).

## 6. Verification via l'interface Angular

Parcours complet, dans cet ordre, avec le compte **admin / admin123** :

1. **Vols** : catalogue public, recherche, bouton *Ajouter un vol (ADMIN)* -> POST `/api/flights`.
2. **Voyageurs** : creation (prenom/nom/email/role), *Modifier*, *Supprimer* (ADMIN),
   *Voir les favoris* -> preuve d'OpenFeign.
3. **Reservations** : reserver -> stock decremente, statut `CONFIRMED`, *Annuler* -> stock restitue.
4. **Conferences** : creation/suppression ADMIN ; lecture possible sans compte.
5. **Notifications** : envoyer une notification, publier un evenement (`flight`/`traveler`/`booking`),
   verifier la liste *Evenements consommes* (archive RabbitMQ).
6. Se deconnecter et reconnecter avec **sami / sami123** : les boutons ADMIN disparaissent,
   les ecritures renvoient `403` (et non `401`).

## 7. Depannage rapide

| Symptome | Cause probable | Action |
|---|---|---|
| `503` sur toutes les routes | aucune instance dans Eureka | verifier le dashboard, `docker restart nomadix-<service>` |
| `401` avec un jeton valide | audience absente | utiliser le client `nomadix-cli` / `nomadix-swagger` (mapper d'audience) |
| `403` sur une ecriture | compte sans role `ADMIN` | se connecter avec `admin` |
| `TypeError: NetworkError` dans Swagger | preflight CORS refuse | verifier l'origine du navigateur (`localhost` vs `127.0.0.1`) |
| Consommateur arrete en boucle `PRECONDITION_FAILED` | file existante sans arguments DLX | supprimer `notification.events` dans l'UI RabbitMQ puis relancer |
| Eureka affiche une IP `172.20.x.x` | `EUREKA_PREFER_IP` ou hostname mal configure | verifier les variables `EUREKA_INSTANCE_*` du service |

## 8. Liens avec les autres documents

- Diagrammes : [05-diagrammes.html](05-diagrammes.html)
- Architecture et ports : [01-architecture.md](01-architecture.md)
- Workflows detailles : [02-workflows.md](02-workflows.md)
- Reference API : [04-api-reference.md](04-api-reference.md)
