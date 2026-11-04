# Nomadix — Scenarios de test complets (runbook de soutenance)

Ce fichier est le **mode d'emploi executable** de la plateforme : chaque scenario donne les
commandes, les URLs, le corps JSON exact, le resultat attendu et **ce que la preuve demontre**.
Il complete [`06-scenarios-test.md`](06-scenarios-test.md) (version courte) et
[`02-workflows.md`](02-workflows.md) (diagrammes des flux).

Trois familles de scenarios :

| Famille | Ce qu'elle prouve | Codes |
|---|---|---|
| **RabbitMQ metier** | des evenements de domaine sont diffuses sans couplage | `R1` a `R5` |
| **RabbitMQ technique** | decouplage, durabilite, dead-lettering, mode degrade, topologie | `T1` a `T6` |
| **OpenFeign** | appels synchrones, saga, compensation, resilience | `F1` a `F7` |
| **Eureka + Gateway** | annuaire dynamique, routage, CORS, securite, scalabilite | `I1` a `I4`, `G1` a `G7` |
| **Fonctionnel** | le produit marche de bout en bout (CRUD, profilage, front) | `E1` a `E12` |

> **Regle d'or de lecture** : un scenario se termine toujours par *une* preuve observable
> (compteur dans l'UI, ligne de log, champ JSON, code HTTP). Ne passez au suivant qu'apres
> l'avoir vue.

> **Version executable** : `bash documentation/scripts/run-scenarios.sh` rejoue les
> scenarios S1 a S12 et **verifie** chaque preuve par une assertion (HTTP, SQL dans la base
> du bon service, compteurs Ready/Unacked/Consumers de RabbitMQ, en-tete `x-death`, lignes
> de log Docker bornees dans le temps). Il **affiche aussi** chaque commande lancee, sa
> sortie brute, les requetes SQL et les extraits de logs : chaque assertion est justifiee
> par ce qui est imprime au-dessus d'elle. Le script lit des **deltas** : il est rejouable
> sans purge ni `down -v`. Les sections ci-dessous restent la version pedagogique, avec les
> clics exacts dans la console RabbitMQ et le commentaire de chaque resultat.

## Sommaire

| # | Section | Contenu |
|---|---|---|
| 0 | [Sequence express](#0-sequence-express-sans-rien-redemarrer) | 14 commandes, aucun redemarrage, tous les domaines |
| 1 | [Preparation](#1-preparation-une-seule-fois-4-minutes) | demarrage, ports, comptes, jeton `curl` |
| 2 | [RabbitMQ metier](#2-scenarios-rabbitmq-metier) | `R1`–`R5` |
| 3 | [RabbitMQ technique](#3-scenarios-rabbitmq-techniques) | `T1`–`T6` |
| 4 | [OpenFeign](#4-scenarios-openfeign-synchrone) | `F1`–`F7` |
| 5 | [Eureka](#5-scenarios-eureka-service-discovery) | `I1`–`I4` |
| 6 | [API Gateway](#6-scenarios-api-gateway) | `G1`–`G7` |
| 7 | [Fonctionnel](#7-scenarios-fonctionnels-le-produit-de-bout-en-bout) | `E1`–`E12` (CRUD, profilage/AOP, front) |
| 8 | [Commandes Docker](#8-commandes-docker-de-reference) | reference rapide |
| 9 | [Depannage](#9-depannage-les-pannes-reellement-rencontrees) | pannes reelles et corrections |
| 10 | [Checklist](#10-checklist-de-soutenance-20-minutes) | parcours de 20 minutes |
| 11 | [Rejouer les tests](#11-rejouer-les-tests-remise-a-zero) | niveaux A/B/C de remise a zero |

---

## 0. Sequence express (sans rien redemarrer)

Pour une stack **deja lancee** : aucune purge, aucun `down`, aucun rebuild. On lit des
**deltas** (valeur avant / valeur apres) au lieu de valeurs absolues. ~3 minutes au total.

```bash
cd <racine du projet>
docker compose ps --format "table {{.Name}}\t{{.Status}}"        # 1. tout est Up/healthy ?

# 2. jeton ADMIN (client public nomadix-cli, password grant)
TOKEN=$(curl -s -X POST http://localhost:8180/realms/nomadix/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d grant_type=password -d client_id=nomadix-cli \
  -d username=admin -d password=admin123 -d scope="openid profile email" \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

# 3. ETAT DE DEPART a noter (une seule fois)
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
curl -s http://localhost:9089/api/travelers/profiles/segments -H "Authorization: Bearer $TOKEN"; echo

# 4. EUREKA : les applications enregistrees (doit en lister 6)
curl -s -H "Accept: application/json" http://localhost:9761/eureka/apps \
  | grep -o '"name":"[A-Z-]*"' | sort -u

# 5. GATEWAY (application API-GATEWAY) : la table de routage (ADMIN)
curl -s http://localhost:9089/actuator/gateway/routes -H "Authorization: Bearer $TOKEN" \
  | grep -o '"uri":"lb://[A-Z-]*"' | sort -u

# 6. OPENFEIGN : diagnostic des deux appels synchrones
curl -s "http://localhost:9089/api/bookings/integrations/feign?travelerId=1&flightId=1" \
  -H "Authorization: Bearer $TOKEN"; echo

# 7. SAGA : creer un vol, puis reserver (totalPrice vient de Flight, stock -1)
curl -s -X POST http://localhost:9089/api/flights -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"flightNumber":"EXPRESS-01","departureCity":"Tunis",
  "arrivalCity":"Paris","departureDate":"2027-03-01","availableSeats":50,"price":350.00}' \
  -o /tmp/f.json
FID=$(sed -E 's/.*"id":([0-9]+).*$/\1/' /tmp/f.json); echo "FID=$FID"
curl -s -X POST "http://localhost:9089/api/bookings?travelerId=1&flightId=$FID" \
  -H "Authorization: Bearer $TOKEN" -o /tmp/b.json; cat /tmp/b.json; echo
BID=$(sed -E 's/.*"id":([0-9]+).*$/\1/' /tmp/b.json)
curl -s http://localhost:9089/api/flights/$FID | grep -o '"availableSeats":[0-9]*'

# 8. COMPENSATION : annuler, la place revient (idempotent)
curl -s -o /dev/null -w "DELETE 1 -> %{http_code}\n" -X DELETE http://localhost:9089/api/bookings/$BID \
  -H "Authorization: Bearer $TOKEN"
curl -s -o /dev/null -w "DELETE 2 -> %{http_code}\n" -X DELETE http://localhost:9089/api/bookings/$BID \
  -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:9089/api/flights/$FID | grep -o '"availableSeats":[0-9]*'

# 9. RABBITMQ : un evenement metier, puis la lecture cote consommateur
curl -s -X POST http://localhost:9089/api/packh/flight-events/emit \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}' | head -c 200; echo
docker compose logs --tail=3 notification-consumer

# 10. DEAD LETTER : un message invalide part en DLQ (noter le compteur avant)
curl -s -X POST http://localhost:9089/api/packh/rabbitmq/poison -H "Authorization: Bearer $TOKEN" | head -c 220; echo
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready | grep dlq

# 11. PROFILING (AOP) : un voyageur complet, profil calcule sans appel explicite
curl -s -X POST http://localhost:9089/api/travelers -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"firstName":"Express","lastName":"Soutenance",
  "email":"express@nomadix.tn","role":"USER","phone":"+216 55 000 111",
  "passportNumber":"TN5555555","nationality":"Tunisienne","dateOfBirth":"1994-09-09"}' \
  -o /tmp/t.json
TID=$(sed -E 's/.*"id":([0-9]+).*$/\1/' /tmp/t.json); echo "TID=$TID"
curl -s http://localhost:9089/api/travelers/profiles/$TID -H "Authorization: Bearer $TOKEN"; echo

# 12. SECURITE : la meme route selon le jeton
curl -s -o /dev/null -w "sans jeton -> %{http_code}\n" http://localhost:9089/api/travelers

# 13. LOGS : la preuve cote serveur
curl -s http://localhost:9081/actuator/health | grep -o '"status":"[A-Z]*"' | head -1
docker compose logs --tail=5 traveler-service | grep PROFILING

# 14. FRONT : une seule origine pour le navigateur
curl -s -o /dev/null -w "frontend -> %{http_code}\n" http://localhost:8090
```

**Resultats attendus** (les valeurs absolues dependent de l'historique, d'ou le delta) :

| Etape | Attendu |
|---|---|
| 4 | les 6 applications enregistrees : `BOOKING-SERVICE`, `CONFERENCE-SERVICE`, `FLIGHT-SERVICE`, `API-GATEWAY`, `NOTIFICATION-SERVICE`, `TRAVELER-SERVICE` (l'Eureka-Server ne s'enregistre pas lui-meme ; la Gateway publie `api-gateway`, soit `API-GATEWAY` en majuscules dans l'annuaire) |
| 5 | une ligne `lb://` par service route |
| 6 | `"reachable":true` et `"found":true` pour les deux services |
| 7 | `201` + `"totalPrice":350.0` ; `availableSeats` passe de 50 a **49** |
| 8 | `204` deux fois ; `availableSeats` remonte a **50** (une seule fois) |
| 9 | `"routingKey":"flight.created"` ; log `[notif] recu flight.created` |
| 10 | `"published":true` ; `notification.events.dlq` +1 |
| 11 | `"score":100`, `"segment":"PLATINUM"`, `"completeness":100` |
| 12 | `401` |
| 14 | `200` |

Aucun redemarrage, aucune purge : cette sequence fonctionne sur une stack deja utilisee.
Pour des chiffres **reproductibles au chiffre pres** (utile pour une capture d'ecran ou un
rapport), appliquer le niveau C de la section 11 avant de commencer.

---

## 1. Preparation (une seule fois, ~4 minutes)

### 1.1 Demarrage complet

```bash
cd <racine du projet>
docker compose up -d --build          # premier lancement : compilation des images
docker compose ps                     # attendre que mysql / rabbitmq / keycloak / eureka soient "healthy"
docker compose logs -f gateway        # Ctrl+C quand "Started GatewayApplication" apparait
```

Le premier demarrage prend 2 a 4 minutes (3 JVM Java + build Angular). Les suivants ~40 s.

### 1.2 Adresses (ports publies sur l'hote)

| Composant | URL | Identifiants |
|---|---|---|
| Frontend Angular (nginx) | <http://localhost:8090> | Keycloak (voir 1.3) |
| API Gateway | <http://localhost:9089> | JWT Keycloak |
| Eureka | <http://localhost:9761> | — |
| Swagger Traveler / Flight / Booking | `/swagger-ui.html` sur `:9081` / `:9082` / `:9083` | bouton *Authorize* |
| Swagger Notification | <http://localhost:9084/swagger-ui> | bouton *Authorize* |
| Swagger Conference | <http://localhost:3001/swagger-ui> | bouton *Authorize* |
| Keycloak | <http://localhost:8180> | `admin` / `admin` |
| RabbitMQ management | <http://localhost:15672> | `nomadix` / `nomadix123` (vhost `/`) |
| MySQL | `localhost:3307` | `root`/`root` ou `nomadix`/`nomadix123` |

Ports **internes** (utilises par le routage Docker) : 8761, 8089, 8081-8084, 3000.
Ne les utilisez jamais depuis le navigateur en mode Compose.

### 1.3 Comptes de test

| Utilisateur | Mot de passe | Role | Usage |
|---|---|---|---|
| `admin` | `admin123` | `ADMIN` | ecritures catalogue, `/api/packh/**`, supervision |
| `sami` | `sami123` | `TRAVELER` | reservations, favoris, profilage personnel |

### 1.4 Obtenir un jeton pour les commandes `curl`

1. Ouvrir <http://localhost:9081/swagger-ui.html>, bouton **Authorize**, se connecter
   (`admin` / `admin123`). Swagger revient avec le cadenas ferme.
2. Dans le navigateur : `F12` > onglet **Application/Stockage** > cookies/localStorage, ou
   simplement copier l'en-tete envoye par une requete *Try it out* (onglet **Network**).
3. Exporter dans le terminal :

```bash
TOKEN="eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJ..."
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:9089/api/flights | head -c 300
```

Toutes les commandes `curl` de ce document supposent `TOKEN` exporte. Sans jeton, les routes
protegees renvoient **401** : c'est justement le scenario `G4`.

---

## 2. Scenarios RabbitMQ metier

Topologie en place (importee au demarrage par `backend/messaging/rabbitmq/definitions.json`) :

```
nomadix.events (topic, durable)
   ├── binding flight.#     ──┐
   ├── binding traveler.#   ──┼──► notification.events (durable, DLX = nomadix.dlx)
   └── binding booking.#    ──┘          │ basic_nack(requeue=false)
                                         ▼
nomadix.dlx (topic) ── binding notification.events.dlq ──► notification.events.dlq
```

> **Point important, souvent mal explique** : `POST /api/flights` (creation d'un vol) **ne publie
> pas** d'evenement. L'emission est explicite, via les endpoints `/api/packh/...`. Les evenements
> automatiques sont ceux du **booking** (`booking.created` a la creation, `booking.cancelled` a
> l'annulation/au passage en `CANCELLED`). Cela permet de choisir qui notifie quoi.

### R1 — `flight.created` publie par le Flight-Service

*Prouve : le catalogue sait diffuser un evenement de domaine sans connaitre le consommateur.*

**Etape 1 — etat initial.** <http://localhost:15672> > *Queues* > `notification.events` :
`Ready = 0`, `Unacked = 0`, `Consumers = 1`.

**Etape 2 — emission** (direct sur le service, jeton `admin`) :

```bash
curl -s -X POST http://localhost:9082/api/packh/flight-events/emit \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"flightNumber":"DEMO-R1-001","departureCity":"Tunis","arrivalCity":"Paris","availableSeats":100}'
```

Reponse attendue (preuve cote producteur) :

```json
{
  "flightNumber": "DEMO-R1-001",
  "departureCity": "Tunis",
  "arrivalCity": "Paris",
  "availableSeats": 100,
  "type": "flight.created",
  "occurredAt": "2026-10-30T10:15:02.481Z",
  "publishedTo": "nomadix.events",
  "routingKey": "flight.created"
}
```

Meme chose via la Gateway (`:9089`, route `packh-flight-events`, role ADMIN) :

```bash
curl -s -X POST http://localhost:9089/api/packh/flight-events/emit -H "Authorization: Bearer $TOKEN"
```
(sans corps : un vol d'exemple `TNHX-<timestamp>` est genere)

**Etape 3 — preuve cote consommateur :**

```bash
docker compose logs notification-consumer | tail -3
```

```
nomadix-notification-consumer  | [notif] recu flight.created : flight.created DEMO-R1-001
```

**Etape 4 — preuve d'archivage (volume partage) :**

```bash
docker compose exec notification-consumer sh -c "head -c 400 /data/notifications.json"
```

```json
[
  {
    "routingKey": "flight.created",
    "receivedAt": "2026-10-30T10:15:02",
    "payload": { "flightNumber": "DEMO-R1-001", "type": "flight.created", "...": "..." }
  }
]
```

> Si `flight-service` est injoignable en direct, l'endpoint est aussi expose par la Gateway :
> `/api/packh/flight-events/emit` (le role ADMIN est exige aux deux niveaux).

### R2 — `traveler.registered` publie par le Traveler-Service

```bash
curl -s -X POST http://localhost:9081/api/packh/traveler-events/emit \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"travelerId":"TRV-42","fullName":"Sami Trabelsi","email":"sami@test.tn"}'
docker compose logs notification-consumer | tail -2
```

```
[notif] recu traveler.registered : traveler.registered TRV-42
```

### R3 — `booking.created` publie par une vraie reservation (preuve de la Saga)

C'est **le** scenario metier prioritaire : la creation d'une reservation declenche a la fois
un appel synchrone (Feign vers Flight) et un evenement asynchrone.

**Etape 1 — creer une reservation** (voyageur 1 et vol 1 existent grace au jeu de donnees) :

```bash
curl -s -X POST "http://localhost:9089/api/bookings?travelerId=1&flightId=1" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{ "id": 1, "travelerId": 1, "flightId": 1, "totalPrice": 350.50, "status": "CONFIRMED" }
```

**Etape 2 — trois preuves simultanees :**

```bash
docker compose logs notification-consumer | tail -2
# [notif] recu booking.created : booking.created 1

curl -s http://localhost:9082/api/flights/1        # sans jeton : GET catalogue public
# availableSeats = 119  (120 - 1 : decrement fait par Feign)

curl -s "http://localhost:9089/api/notifications/events?limit=3" -H "Authorization: Bearer $TOKEN"
# derniere entree : routingKey "booking.created", payload.totalPrice 350.5
```

**Etape 3 — reservation sur vol complet (409) :** voir `F6`.

### R4 — `booking.cancelled` publie par l'annulation

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE http://localhost:9089/api/bookings/1 \
  -H "Authorization: Bearer $TOKEN"
# 204
docker compose logs notification-consumer | tail -2
# [notif] recu booking.cancelled : booking.cancelled 1
curl -s http://localhost:9082/api/flights/1
# availableSeats = 120  (place restituee : compensation)
```

### R5 — emission « a blanc » d'un `booking.created` (sans creer de reservation)

Utile pour une demonstration rapide, sans consommer de stock :

```bash
curl -s -X POST http://localhost:9083/api/packh/booking-events/emit \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"bookingId":999,"travelerId":1,"flightId":2,"status":"CONFIRMED"}'
```

Le corps renvoye contient `publishedTo: nomadix.events` et `routingKey: booking.created`.

### Tableau de synthese des routing keys

| Routing key | Producteur | Declencheur | Binding |
|---|---|---|---|
| `flight.created` | Flight-Service | `POST /api/packh/flight-events/emit` | `flight.#` |
| `traveler.registered` | Traveler-Service | `POST /api/packh/traveler-events/emit` | `traveler.#` |
| `booking.created` | Booking-Service | `POST /api/bookings` **ou** `/api/packh/booking-events/emit` | `booking.#` |
| `booking.cancelled` | Booking-Service | `DELETE /api/bookings/{id}` ou `PUT ...?status=CANCELLED` | `booking.#` |
| `booking.poison` | Booking-Service | `POST /api/packh/rabbitmq/poison` | `booking.#` (puis DLQ) |

---

## 3. Scenarios RabbitMQ techniques

### T1 — Decouplage asynchrone (cas nominal, 60 s)

*Prouve : le producteur ne bloque pas sur le consommateur.*

1. UI RabbitMQ > *Queues* > `notification.events` : noter `Ready=0`, `Unacked=0`, `Total` (X).
2. Executer `R1` (ou `R5`) et **regarder l'UI en rafraichissant (F5) pendant 2 s**.
3. Observer :

| Compteur | Avant | Pendant | Apres |
|---|---|---|---|
| `Ready` | 0 | 1 (instantane) | 0 |
| `Unacked` | 0 | 1 | 0 |
| `Total` | X | X+1 | X+1 |
| `Consumers` | 1 | 1 | 1 |

4. Cote producer, la duree de la requete (Swagger affiche `Duration` grace a
   `springdoc.swagger-ui.display-request-duration=true`) reste de quelques millisecondes :
   elle ne contient **pas** le traitement Python.

**Phrase de soutenance** : « Ready monte a 1 puis retombe a 0 : le message a bien transite par
le broker, et le service Java a rendu sa reponse sans attendre l'ecriture dans `notifications.json`.
C'est le decouplage temporel : ni couplage d'adresse, ni couplage de disponibilite. »

Confort visuel (option) : *Overview* > graphique **Message rates** pour la courbe entrante.

### T2 — Durabilite (consommateur arrete)

*Prouve : aucun evenement perdu si le consommateur tombe.*

```bash
docker compose stop notification-consumer
docker compose ps notification-consumer         # Etat : exited
```

UI RabbitMQ > `notification.events` : `Consumers = 0`.

Publier **3** evenements pendant la panne :

```bash
for i in 1 2 3; do
  curl -s -X POST http://localhost:9083/api/packh/booking-events/emit \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"bookingId\":90$i,\"status\":\"CONFIRMED\",\"tag\":\"DURABILITY-TEST-$i\"}" > /dev/null
done
```

UI : `Ready = 3`, `Consumers = 0` (les messages sont sur disque : file *durable* + messages
*persistents*, volume `nomadix-rabbitmq-data`).

```bash
docker compose up -d notification-consumer
sleep 5
docker compose logs notification-consumer | tail -6
```

```
[notif] connecte a RabbitMQ - attente de messages sur 'notification.events'
[notif] recu booking.created : booking.created 901
[notif] recu booking.created : booking.created 902
[notif] recu booking.created : booking.created 903
```

```bash
docker compose exec notification-consumer sh -c "grep -c DURABILITY /data/notifications.json"
# 3
```

UI : `Ready = 0`, `Consumers = 1`.

| Phase | Ready | Consumers |
|---|---|---|
| Avant panne | 0 | 1 |
| Pendant panne | 3 | 0 |
| Apres redemarrage | 0 | 1 |

### T3 — Dead Letter Queue (message empoisonne)

*Prouve : un message invalide est isole, pas rejoue en boucle.*

**Voie A — depuis Swagger/curl (recommande, reproductible) :**

```bash
curl -s -X POST http://localhost:9083/api/packh/rabbitmq/poison -H "Authorization: Bearer $TOKEN"
```

```json
{
  "published": true,
  "exchange": "nomadix.events",
  "routingKey": "booking.poison",
  "deadLetterExchange": "nomadix.dlx",
  "deadLetterQueue": "notification.events.dlq",
  "inspectInUi": "http://localhost:15672/#/queues/%2F/notification.events.dlq",
  "expected": "Le message quitte notification.events et apparait dans notification.events.dlq"
}
```

**Voie B — depuis l'UI (manipulation a montrer au jury) :**
*Exchanges* > `nomadix.events` > section **Publish message** :
routing key `booking.poison`, payload `{"ceci": "json invalide"` (accolade fermante oubliee),
encoding `string` > *Publish message*.

**Observation :**

1. `notification.events` : `Ready = 0` (le message n'y reste pas bloque).
2. `notification.events.dlq` : `Ready` augmente de 1.
3. **Ou cliquer exactement pour lire `x-death`** : *Queues and Streams* > clic sur le **nom**
   `notification.events.dlq` (pas la case a cocher). La page de la file enchaine :
   *Overview* / *Details* / *Consumers* / *Bindings* / **Get messages** / *Move messages* / *Delete* / *Purge*.
   Le panneau **Get messages** est donc **en bas de la page**, juste au-dessus des boutons
   *Delete* et *Purge*. Regler :
   - `Ack mode` : **Nack message requeue true** (lecture **non destructive** : le message
     retourne dans la file). `Ack message requeue false` le **retire** definitivement ;
   - `Messages` : `1` ;
   - puis **Get Message(s)**.

   Le resultat s'affiche **sous le bouton**, dans un bloc `Messages` : un JSON par message
   recupere. `x-death` se lit dans `properties` > `headers` (l'UI peut aussi presenter une
   section depliable *Message headers* au lieu du JSON brut). Le corps contient :

```json
{
  "payload": "POISON-MESSAGE (not JSON) emis a 2026-10-30T10:20:11.482Z",
  "payload_encoding": "string",
  "exchange": "nomadix.events",
  "routing_key": "booking.poison",
  "properties": {
    "headers": {
      "x-death": [
        {
          "count": 1,
          "reason": "rejected",
          "queue": "notification.events",
          "exchange": "nomadix.events",
          "routing-keys": ["booking.poison"],
          "time": 1793000411
        }
      ],
      "x-first-death-exchange": "nomadix.events",
      "x-first-death-queue": "notification.events",
      "x-first-death-reason": "rejected"
    }
  }
}
```

4. Log du consommateur :

```bash
docker compose logs notification-consumer | tail -2
```
```
[notif][DLQ] message refuse sur 'booking.poison' (Expecting property name enclosed in double quotes) -> notification.events.dlq via nomadix.dlx
```

Le consommateur **reste vivant** et continue de traiter les messages valides : relancer `R1`
apres le `T3` pour le prouver.

> **Si la DLQ n'affiche pas `Ready = 1`** : les compteurs s'accumulent d'un test a l'autre.
> Noter la valeur de depart et verifier « +1 », ou cliquer **Purge** (bouton en bas de la page
> de la file) pour repartir de zero. `Consumers = 0` sur la DLQ est **normal** : personne ne
> consomme volontairement une file d'echec.
>
> Un message publie **directement** dans la DLQ (bouton *Publish message* de la file) n'a jamais
> ete rejete : il ne porte donc **aucun** `x-death`. Il faut passer par `booking.poison` (ou par
> la voie B) pour obtenir l'en-tete.

> Variante sans publier de JSON casse : envoyer un corps valide contenant `"simulateFailure": true`.
> Le consommateur le refuse volontairement (`ValueError("simulateFailure=true demande par l'emetteur")`).

### T4 — Mode degrade (fire-and-forget) : RabbitMQ eteint

*Prouve : une panne du broker ne casse pas le metier.*

```bash
docker compose stop rabbitmq
```

```bash
# 1) emission explicite : pas d'exception, un flag false
curl -s -X POST http://localhost:9083/api/packh/booking-events/emit -H "Authorization: Bearer $TOKEN"
# { "bookingId":0, ..., "type":"booking.created", "publishedTo":"nomadix.events", "routingKey":"booking.created" }
# (le corps est renvoye ; cote logs, un WARN explique la publication impossible)

# 2) une vraie reservation fonctionne toujours
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://localhost:9089/api/bookings?travelerId=1&flightId=2" \
  -H "Authorization: Bearer $TOKEN"
# 201
```

```bash
docker compose logs booking-service | tail -5
```
```
[RABBITMQ] publication booking.created impossible (service toujours disponible) : Connection refused
```

```bash
docker compose start rabbitmq
```

**Phrase de soutenance** : « RabbitMQ est un canal de diffusion, pas la source de verite. La
reservation est ecrite en base avant l'emission ; une panne du broker degrade la notification,
jamais la transaction. »

### T5 — Topologie (exchanges, files, bindings)

*Prouve : la topologie est declaree en code/infra, pas cliquee a la main.*

```bash
docker compose exec rabbitmq rabbitmqctl list_exchanges name type durable | grep nomadix
# nomadix.dlx      topic   true
# nomadix.events   topic   true

docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers arguments
# notification.events        0  1  [{"x-dead-letter-exchange","nomadix.dlx"},{"x-dead-letter-routing-key","notification.events.dlq"},{"x-queue-type","classic"}]
# notification.events.dlq    0  0  [{"x-queue-type","classic"}]

docker compose exec rabbitmq rabbitmqctl list_bindings source_name destination_name routing_key | grep -E "notification|nomadix"
# nomadix.events  notification.events      flight.#
# nomadix.events  notification.events      traveler.#
# nomadix.events  notification.events      booking.#
# nomadix.dlx     notification.events.dlq  notification.events.dlq
```

Dans l'UI, l'onglet *Exchanges* affiche **9** entrees : les 7 exchanges internes (`(AMQP default)`,
`amq.direct`, `amq.fanout`, `amq.headers`, `amq.match`, `amq.rabbitmq.trace`, `amq.topic`) plus
les 2 exchanges Nomadix. L'onglet *Queues* affiche **2** files.

### T6 — Prefetch et Unacked

*Prouve : le consommateur limite son travail en cours (pas de noyade).*

Le consommateur Python appelle `channel.basic_qos(prefetch_count=10)` : au plus **10** messages
`Unacked` a la fois. Visible dans l'UI quand `Ready > 10` (scenario `T2` avec 15 emissions) :
`Unacked` plafonne a 10, `Ready` garde le reste. L'acquittement n'a lieu qu'**apres** l'ecriture
dans `notifications.json` (`basic_ack`) : c'est l'accuse de traitement qui rend la durabilite utile.

---

## 4. Scenarios OpenFeign (synchrone)

Configuration commune : `connect-timeout = 2 s`, `read-timeout = 3 s`, TimeLimiter Resilience4j
`4 s`, circuit breaker `sliding-window-size = 10`, `minimum-number-of-calls = 5`,
`failure-rate-threshold = 50 %`, `wait-duration-in-open-state = 10 s`.
Le jeton de l'utilisateur est **relaye explicitement** dans chaque appel (le circuit breaker
change de thread, le `SecurityContext` n'y est plus visible).

### F1 — Saga de reservation : le prix vient de Flight

*Prouve : Booking obtient la donnee d'un autre service en temps reel, sans base partagee.*

```bash
curl -s -X POST "http://localhost:9089/api/bookings?travelerId=1&flightId=2" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{ "id": 2, "travelerId": 1, "flightId": 2, "totalPrice": 890.0, "status": "CONFIRMED" }
```

`totalPrice` = prix du vol 2 (`AF1234`, 890.00) lu par Feign. **Aucun prix n'est envoye par le
client** : il est impossible de le falsifier depuis la requete.

Deuxieme preuve : `GET http://localhost:9082/api/flights/2` donne `availableSeats` 15 → 14.

### F2 — Enrichissement des favoris (Traveler → Flight)

*Prouve : l'identifiant logique est resolu en objet complet.*

```bash
curl -s -X POST "http://localhost:9089/api/travelers/1/favorite-flights/2" -H "Authorization: Bearer $TOKEN"
# "Vol 2 ajoute aux favoris du voyageur 1."

curl -s "http://localhost:9089/api/travelers/1/favorite-flights" -H "Authorization: Bearer $TOKEN"
```

```json
[
  {
    "id": 2,
    "flightNumber": "AF1234",
    "departureCity": "Paris",
    "arrivalCity": "Dubai",
    "departureDate": "2026-11-05",
    "availableSeats": 14,
    "price": 890.0
  }
]
```

Preuve du `database-per-service` : la table de jointure ne stocke que le `flightId`, jamais les
colonnes du vol. En base :

```bash
docker compose exec mysql mysql -unomadix -pnomadix123 nomadix_traveler_db \
  -e "SELECT * FROM traveler_favorite_flights;"
```

### F3 — Compensation : l'annulation libere la place

```bash
curl -s http://localhost:9082/api/flights/2 | grep -o '"availableSeats":[0-9]*'   # 14
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE http://localhost:9089/api/bookings/2 \
  -H "Authorization: Bearer $TOKEN"                                              # 204
curl -s http://localhost:9082/api/flights/2 | grep -o '"availableSeats":[0-9]*'   # 15
```

**Idempotence** : rejouer le `DELETE` renvoie encore `204` mais ne rend **pas** une seconde place
(`availableSeats` reste 15). C'est le `if ("CANCELLED".equals(booking.getStatus())) return;`
de `BookingService.cancelBooking`.

### F4 — Circuit breaker ouvert (Flight-Service down)

*Prouve : le systeme degrade proprement au lieu de bloquer.*

```bash
docker compose stop flight-service
curl -s -o /tmp/err.json -w "%{http_code} en %{time_total}s\n" \
  -X POST "http://localhost:9089/api/bookings?travelerId=1&flightId=1" -H "Authorization: Bearer $TOKEN"
# 503 en ~0.3 s (fallback), et non 60 s de blocage
cat /tmp/err.json
```

```json
{
  "timestamp": "2026-10-30T10:31:44.120+00:00",
  "status": 503,
  "error": "Service Unavailable",
  "message": "Flight-Service indisponible : timeout ou circuit breaker OPEN",
  "path": "/api/bookings"
}
```

**Etat du disjoncteur** (le nom est celui du client Feign) :

```bash
curl -s http://localhost:9083/actuator/health | python3 -m json.tool | grep -A3 circuitBreakers
```

```json
"circuitBreakers": {
  "status": "UP",
  "details": {
    "flight-service": { "state": "OPEN", "failureRate": "100.0%", "bufferedCalls": 5, "failedCalls": 5 }
  }
}
```

Apres `docker compose start flight-service` et **10 s** (`wait-duration-in-open-state`), l'etat
passe `OPEN` → `HALF_OPEN` (3 appels d'essai) → `CLOSED`. Verifier avec la meme commande, puis
refaire un `F1` : il repasse a `201`.

### F5 — Diagnostic des deux appels Feign (endpoint dedie)

OpenFeign n'apparait pas naturellement dans Swagger : ce controleur le rend observable.

```bash
curl -s "http://localhost:9089/api/bookings/integrations/feign?travelerId=1&flightId=1" \
  -H "Authorization: Bearer $TOKEN"
```

Tout va bien :

```json
{
  "travelerService": { "reachable": true, "found": true, "data": { "id": 1, "firstName": "Sami", "...": "..." } },
  "flightService":   { "reachable": true, "found": true, "data": { "id": 1, "flightNumber": "TU700", "...": "..." } }
}
```

Flight arrete :

```json
{ "flightService": { "reachable": false, "found": false, "error": "503 Service Unavailable ..." } }
```

Ressource inexistante (`flightId=999`) :

```json
{ "flightService": { "reachable": true, "found": false, "httpStatus": 404 } }
```

**Ce que signifient exactement les deux booleens** (code de `FeignIntegrationController`) :

| `reachable` | `found` | Signification | Cause |
|---|---|---|---|
| `true` | `true` | le service a repondu **et** la ressource existe | appel Feign nominal |
| `true` | `false` | le service a repondu, la ressource n'existe pas | `FeignException.NotFound` -> `httpStatus: 404` |
| `false` | `false` | le service est **injoignable**, le fallback a pris la main | timeout (2 s / 3 s), circuit **OPEN**, conteneur arrete |

`reachable` repond donc a « le service m'a-t-il repondu sans passer par le fallback ? » et
`found` a « la ressource demandee existe-t-elle ? ». Le controleur renvoie **toujours 200** :
c'est un diagnostic, pas une operation metier. Un `reachable: false` n'est donc pas une erreur
de cette API, c'est l'observation d'une panne en aval.

Cette distinction est essentielle : **panne** (503, on ne sait rien) n'est pas **absence**
(404, information metier).

### F6 — Erreurs metier propagees (404, 409, 400)

```bash
# Vol inexistant : le 404 Feign est retraduit en metier
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://localhost:9089/api/bookings?travelerId=1&flightId=999" -H "Authorization: Bearer $TOKEN"
# 404

# Voyageur inexistant
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://localhost:9089/api/bookings?travelerId=999&flightId=1" -H "Authorization: Bearer $TOKEN"
# 404

# Statut invalide
curl -s -o /dev/null -w "%{http_code}\n" -X PUT "http://localhost:9089/api/bookings/1?status=FOO" -H "Authorization: Bearer $TOKEN"
# 400
```

**Vol complet (409)** : le vol 4 (`TU404`) a 3 places.

```bash
for i in 1 2 3; do
  curl -s -o /dev/null -X POST "http://localhost:9089/api/bookings?travelerId=1&flightId=4" -H "Authorization: Bearer $TOKEN"
done
curl -s -X POST "http://localhost:9089/api/bookings?travelerId=1&flightId=4" -H "Authorization: Bearer $TOKEN"
# 409 { "message": "Vol 4 complet : aucune place disponible" }
```

Le 409 de Flight (`Conflict`) n'est **pas** absorbe par le fallback : il traverse Feign et devient
une erreur metier lisible par le client.

### F7 — Traveler-Service down : validation impossible = refus propre

```bash
docker compose stop traveler-service
curl -s -o /tmp/e2.json -w "%{http_code}\n" -X POST "http://localhost:9089/api/bookings?travelerId=1&flightId=1" -H "Authorization: Bearer $TOKEN"
# 503
cat /tmp/e2.json
# "message": "Traveler-Service indisponible : impossible de valider le voyageur"
docker compose start traveler-service
```

C'est la traduction du fallback `TravelerClientFallbackFactory` (retourne `null`) en refus
explicite : on ne cree **jamais** une reservation sans voyageur valide.

---

## 5. Scenarios Eureka (service discovery)

### I1 — Enregistrement automatique au demarrage

Ouvrir <http://localhost:9761>. Le tableau *Instances currently registered with Eureka* doit
lister :

| Application | AMIs | Availability Zones | Status |
|---|---|---|---|
| `BOOKING-SERVICE` | `booking-service:8083` | (1) | **UP (1)** |
| `CONFERENCE-SERVICE` | `conference-service:3000` | (1) | **UP (1)** |
| `FLIGHT-SERVICE` | `flight-service:8082` | (1) | **UP (1)** |
| `API-GATEWAY` | `gateway:8089` | (1) | **UP (1)** |
| `NOTIFICATION-SERVICE` | `notification-service:8084` | (1) | **UP (1)** |
| `TRAVELER-SERVICE` | `traveler-service:8081` | (1) | **UP (1)** |

Les identifiants (`instanceId`) sont lisibles (`flight-service:8082`), pas des hashs de conteneur :
c'est le role de `EUREKA_INSTANCE_INSTANCE_ID` dans `docker-compose.yml`.

### I2 — Desenregistrement et retour en ligne

```bash
docker compose stop flight-service
# attendre 60 a 90 s (bail Eureka par defaut : 90 s sans heartbeat), puis rafraichir http://localhost:9761
# FLIGHT-SERVICE disparait (ou passe OUT_OF_SERVICE) : plus aucun heartbeat
# verifier aussi cote API : curl -s -H "Accept: application/json" http://localhost:9761/eureka/apps/FLIGHT-SERVICE
docker compose start flight-service
# attente 60 a 90 s (demarrage JVM ~40 s + premier heartbeat) : FLIGHT-SERVICE revient UP (1)
```

Preuve cote routage : pendant que Flight est arrete, la Gateway renvoie `503` sur
`GET /api/flights` (aucune instance disponible) — et non `500` avec une IP inconnue.

### I3 — Preuve « sans URL en dur » via l'API du registre

```bash
curl -s -H "Accept: application/json" http://localhost:9761/eureka/apps \
  | grep -o '"app":"[A-Z-]*"' | sort -u
# "app":"BOOKING-SERVICE" ... "app":"TRAVELER-SERVICE"

curl -s -H "Accept: application/json" http://localhost:9761/eureka/apps/FLIGHT-SERVICE \
  | grep -o '"instanceId":"[^"]*"'
# "instanceId":"flight-service:8082"
```

Aucun fichier de configuration ne contient `http://localhost:9082` comme cible d'appel : les
clients utilisent `@FeignClient(name = "flight-service")` et la Gateway `lb://FLIGHT-SERVICE`.

### I4 — Superviseur Eureka du service Python

`py_eureka_client` abandonne le renouvellement apres un incident reseau transitoire ; l'instance
est alors evincee et la Gateway repond `503` sur `/api/notifications/**`. Un superviseur
(`eureka_supervisor`, intervalle 20 s) la re-enregistre.

```bash
docker compose restart eureka-server
docker compose logs -f notification-service | grep -E "re-enregistr|Registered"
# [warn] Instance absente du registre Eureka : re-enregistrement...
# [ok] Instance re-enregistree dans Eureka
```

Pendant ce temps, `GET http://localhost:9089/api/notifications/hello` avec jeton repond `200`
des le retour de l'enregistrement.

---

## 6. Scenarios API Gateway

### G1 — Routage unique (le pattern API Gateway)

| Appel via la Gateway (`:9089`) | Route | Resultat |
|---|---|---|
| `GET /api/flights` | `flight-service` | `200` sans jeton (public) |
| `GET /api/travelers` (avec jeton) | `traveler-service` | `200` |
| `GET /api/bookings` (avec jeton) | `booking-service` | `200` |
| `GET /api/notifications/events` (avec jeton) | `notification-service` | `200` |
| `GET /api/conferences` | `conference-service` | `200` sans jeton |

```bash
for p in /api/flights /api/travelers /api/bookings /api/notifications/hello /api/conferences; do
  printf "%-32s %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" http://localhost:9089$p)"
done
```

### G2 — Table de routage observable

```bash
curl -s http://localhost:9089/actuator/gateway/routes -H "Authorization: Bearer $TOKEN" \
  | python3 -m json.tool | grep -E '"(route_id|uri)"'
```

```json
"route_id": "traveler-service", "uri": "lb://TRAVELER-SERVICE"
"route_id": "flight-service",   "uri": "lb://FLIGHT-SERVICE"
"route_id": "booking-service",  "uri": "lb://BOOKING-SERVICE"
"route_id": "notification-service", "uri": "lb://NOTIFICATION-SERVICE"
"route_id": "conference-service",   "uri": "lb://CONFERENCE-SERVICE"
"route_id": "packh-traveler-events", "uri": "lb://TRAVELER-SERVICE"
"route_id": "packh-flight-events",   "uri": "lb://FLIGHT-SERVICE"
"route_id": "packh-booking-events",  "uri": "lb://BOOKING-SERVICE"
"route_id": "packh-rabbitmq-scenarios", "uri": "lb://BOOKING-SERVICE"
```

Note : `/actuator/**` est reserve a ADMIN (`hasRole("ADMIN")`) — sans jeton admin, `401`.

> `discovery.locator.enabled=true` ajoute aussi des routes automatiques `/{service-id}/**`.
> Elles ne portent pas les regles fines de `/api/**` ; la protection reelle reste celle de chaque
> microservice (defense en profondeur). En production, on desactive le locator.

### G3 — CORS (preflight OPTIONS)

```bash
curl -s -i -X OPTIONS http://localhost:9089/api/flights \
  -H "Origin: http://localhost:8090" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: authorization,content-type" | head -20
```

Attendu : `HTTP/1.1 200` avec `Access-Control-Allow-Origin: http://localhost:8090`,
`Access-Control-Allow-Methods: GET,POST,PUT,PATCH,DELETE,OPTIONS`,
`Access-Control-Allow-Credentials: true`.

`allowedOriginPatterns: http://localhost:*` couvre 4200 (dev), 8090 (nginx) et tous les Swagger
(8081-8084, 3000, 9081-9084, 3001) : une origine oubliee faisait echouer le preflight en 403,
et le navigateur masquait le vrai code derriere `TypeError: NetworkError`.

### G4 — 401 : aucun jeton

```bash
curl -s -o /tmp/g4.json -w "%{http_code}\n" http://localhost:9089/api/travelers   # 401
cat /tmp/g4.json
```

```json
{ "status": 401, "error": "Unauthorized",
  "message": "Jeton absent, expire ou invalide : connectez-vous via Keycloak (http://localhost:8180/realms/nomadix)",
  "path": "/api/travelers" }
```

Meme test sur un endpoint public (`GET /api/flights`) : `200`. La Gateway n'est donc pas
« tout ou rien » : elle applique la matrice de droits.

### G5 — 403 : jeton valide, role insuffisant

Avec le jeton de `sami` (TRAVELER) :

```bash
curl -s -o /tmp/g5.json -w "%{http_code}\n" -X POST http://localhost:9089/api/flights \
  -H "Authorization: Bearer $TOKEN_TRAVELER" -H "Content-Type: application/json" \
  -d '{"flightNumber":"HACK-1","departureCity":"X","arrivalCity":"Y","departureDate":"2026-12-01","availableSeats":1,"price":1}'
# 403
cat /tmp/g5.json
# { "status":403, "error":"Forbidden", "message":"Role insuffisant : cette operation exige le role ADMIN", ... }
```

La requete n'atteint jamais le microservice : le controle est fait au point d'entree.

### G6 — Defense en profondeur (acces direct)

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9082/api/flights          # 200 (public partout)
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9081/api/travelers        # 401 (service aussi)
```

Le microservice revalide le JWT : contourner la Gateway n'ouvre aucun droit.

### G7 — Repartition de charge (2 instances)

Prerequis : le port `9082:8082` doit etre retire temporairement de `flight-service` dans
`docker-compose.yml` (Docker ne peut pas publier le meme port hote pour deux conteneurs).
Remettre le mapping apres le test.

```bash
docker compose up -d --scale flight-service=2 --no-recreate
# Eureka : FLIGHT-SERVICE  UP (2)
for i in 1 2 3 4; do curl -s -o /dev/null -w "%{http_code} " http://localhost:9089/api/flights; done; echo
docker compose logs flight-service | grep -c "GET /api/flights"
docker compose up -d --scale flight-service=1        # retour a la normale
```

Les deux conteneurs recoivent des requetes en alternance (Spring Cloud LoadBalancer, round robin),
**sans modification de code** : la Gateway utilisait deja `lb://FLIGHT-SERVICE`.

---

## 7. Scenarios fonctionnels (le produit de bout en bout)

### E1 — Authentification Keycloak

1. <http://localhost:8090> > *Se connecter* > `sami` / `sami123`.
2. Le nom d'utilisateur apparait dans l'en-tete ; les pages protegees deviennent accessibles.
3. Se deconnecter, se reconnecter en `admin` / `admin123` : les boutons d'ecriture
   (creer/modifier/supprimer un vol) apparaissent.

### E2 — CRUD du catalogue des vols

```bash
# Lecture publique (aucun jeton)
curl -s http://localhost:9082/api/flights | head -c 300; echo

# Creation (ADMIN) - le statut renvoye est 200, pas 201
curl -s -X POST http://localhost:9089/api/flights -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{
    "flightNumber":"DEMO-E2","departureCity":"Tunis","arrivalCity":"Rome",
    "departureDate":"2027-01-15","availableSeats":60,"price":275.00}'

# Mise a jour (ADMIN) : 200 ; l'entite existante est modifiee (pas de doublon)
curl -s -X PUT http://localhost:9089/api/flights/<id> -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{
    "flightNumber":"DEMO-E2","departureCity":"Tunis","arrivalCity":"Rome",
    "departureDate":"2027-01-15","availableSeats":65,"price":275.00}'

# Suppression (ADMIN) : 204
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE http://localhost:9089/api/flights/<id> -H "Authorization: Bearer $TOKEN"
```

### E3 — CRUD des voyageurs

`POST` renvoie **201**. Le corps est un `TravelerCreateDTO` : identite obligatoire, puis les
quatre champs du profilage (facultatifs mais valorises par le score, voir `E7`).

```bash
# Creation : 201 + id genere
curl -s -X POST http://localhost:9089/api/travelers -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{
    "firstName":"Feign","lastName":"Test","email":"feign@test.tn","role":"USER",
    "phone":"+216 55 123 456","passportNumber":"TN1234567",
    "nationality":"Tunisienne","dateOfBirth":"1995-03-21"}'
# {"id":18,...,"phone":"+216 55 123 456","favoriteFlights":[]}

# Lecture (liste + detail)
curl -s http://localhost:9089/api/travelers -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:9089/api/travelers/18 -H "Authorization: Bearer $TOKEN"

# Mise a jour : 200, ne touche pas aux favoris
curl -s -X PUT http://localhost:9089/api/travelers/18 -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{
    "firstName":"Feign","lastName":"Test","email":"feign@test.tn","role":"USER",
    "phone":"+216 55 123 456","passportNumber":"TN1234567",
    "nationality":"Tunisienne","dateOfBirth":"1995-03-21"}'

# Suppression : 204 en ADMIN (403 avec un jeton TRAVELER)
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE http://localhost:9089/api/travelers/18 \
  -H "Authorization: Bearer $TOKEN"
```

Un `dateOfBirth` envoye en chaine vide (`""`) produit un **400** : envoyer `null` ou omettre
le champ. Le frontend applique deja cette conversion.

### E4 — Cycle de vie complet d'une reservation

| Etape | Appel | Resultat |
|---|---|---|
| Creer | `POST /api/bookings?travelerId=4&flightId=1` | `201` + `status: CONFIRMED` |
| Lire | `GET /api/bookings/{id}` | `200` |
| Changer de statut | `PUT /api/bookings/{id}?status=CANCELLED` | `200`, place rendue |
| Reactiver | `PUT /api/bookings/{id}?status=CONFIRMED` | `200`, place reprise |
| Annuler | `DELETE /api/bookings/{id}` | `204` (idempotent) |
| Re-annuler | `DELETE /api/bookings/{id}` | `204`, stock inchange |

### E5 — Vol complet : 409

Voir `F6`. C'est le scenario qui prouve la **coherence du stock** sous concurrence : le decrement
est fait cote Flight par un service dedie, pas par un `SELECT` puis `UPDATE` applicatif.

### E6 — Favoris (ajout, doublon, retrait)

```bash
curl -s -X POST http://localhost:9089/api/travelers/1/favorite-flights/3 -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost:9089/api/travelers/1/favorite-flights/3 -H "Authorization: Bearer $TOKEN"  # sans effet (Set)
curl -s http://localhost:9089/api/travelers/1/favorite-flights -H "Authorization: Bearer $TOKEN"           # 1 seul vol 3
curl -s -X DELETE http://localhost:9089/api/travelers/1/favorite-flights/3 -H "Authorization: Bearer $TOKEN"
```

### E7 — Smart Profiling (service avance, AOP)

Le profilage est declenche par un **aspect** (`TravelerProfilerAspect`, pointcut sur les
`save(...)` des repositories) : ecrire un voyageur — creation **ou** mise a jour — recalcule
son score sans aucun appel explicite dans le code metier. L'extraction des attributs est
**typee** (`TravelerProfilerService.fromTraveler`) : l'entite porte les quatre champs attendus,
le moteur ne devine plus rien par reflexion.

**Grille de scoring (`ProfilingRulesEngine`, version 1.1)**

| Regle | Points |
|---|---|
| Completude (6 champs : nom, email, telephone, passeport, nationalite, date de naissance) | 40 au prorata |
| Email valide | 10 |
| Email non jetable | 5 |
| Passeport valide (`^[A-Z0-9]{6,9}$`) | 15 |
| Telephone valide (`^[+]?[0-9 ]{8,15}$`) | 10 |
| Qualite du nom (>= 3 caracteres et contient un espace) | 5 |
| Nationalite renseignee | 5 |
| Coherence (email **et** passeport valides) | 10 |
| **Total** | **100** |

Segments : `>= 80` PLATINUM, `>= 65` GOLD, `>= 45` SILVER, sinon BRONZE (+ flag
`PROFIL_A_RISQUE`). `completeness` est un **pourcentage** (0-100) ; les 40 points de
completude apportes au score sont une grandeur distincte.

**Preuve AOP n°1 — profil parfait (100 / PLATINUM)**

```bash
curl -s -X POST http://localhost:9089/api/travelers -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{
    "firstName":"Amira","lastName":"Ben Salah","email":"amira.bensalah@gmail.com",
    "role":"USER","phone":"+216 55 123 456","passportNumber":"TN1234567",
    "nationality":"Tunisienne","dateOfBirth":"1998-04-12"}'
# 201 + {"id":16,...,"favoriteFlights":[]}

curl -s http://localhost:9089/api/travelers/profiles/16 -H "Authorization: Bearer $TOKEN"
```
```json
{"travelerId":"16","fullName":"Amira Ben Salah","email":"amira.bensalah@gmail.com",
 "phone":"+216 55 123 456","passportNumber":"TN1234567","nationality":"Tunisienne",
 "birthDate":"1998-04-12","score":100,"segment":"PLATINUM","completeness":100,
 "validEmail":true,"disposableEmail":false,"validPassport":true,"riskFlags":"",
 "rulesVersion":"1.1","createdAt":"...","updatedAt":"..."}
```

**Preuve AOP n°2 — profil incomplet, puis complete par un simple `PUT`**

```bash
curl -s -X POST http://localhost:9089/api/travelers -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{
    "firstName":"Karim","lastName":"X","email":"karim@mailinator.com","role":"USER"}'
# score 28, segment BRONZE, completeness 33,
# riskFlags "EMAIL_JETABLE, PASSEPORT_MANQUANT, PROFIL_A_RISQUE"

curl -s -X PUT http://localhost:9089/api/travelers/17 -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{
    "firstName":"Karim","lastName":"Ben Ali","email":"karim.benali@gmail.com",
    "role":"USER","phone":"95432100","passportNumber":"TN7654321",
    "nationality":"Tunisienne","dateOfBirth":"1992-06-30"}'
# score 100, segment PLATINUM, riskFlags "" : aucun appel a /recompute n'a ete necessaire
```

**Preuve dans les logs (l'aspect s'est bien execute)**

```bash
docker compose logs traveler-service | grep PROFILING | tail -5
```
```
[PROFILING] Traveler 17 -> score=100, segment=PLATINUM, flags=[]
```

**Endpoints de consultation et recalcul**

```bash
curl -s "http://localhost:9089/api/travelers/profiles/top?limit=3" -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:9089/api/travelers/profiles/segments    -H "Authorization: Bearer $TOKEN"
# {"PLATINUM":2,"GOLD":0,"SILVER":0,"BRONZE":11}
curl -s http://localhost:9089/api/travelers/profiles/statistics  -H "Authorization: Bearer $TOKEN"
# {"totalProfils":13,"scoreMoyen":43.3,"completudeMoyenne":17.1,...}
curl -s http://localhost:9089/api/travelers/profiles/risky       -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost:9089/api/travelers/profiles/17/recompute -H "Authorization: Bearer $TOKEN"
```

Un profil calcule **avant** l'enrichissement du modele garde son ancien score : un `recompute`
(ou n'importe quel `PUT`) le remet a jour avec toutes les donnees de l'entite.

Tout `/api/travelers/profiles/**` est **ADMIN** cote Gateway et cote service.

### E8 — Notification-Service (FastAPI)

```bash
# Ecriture (memoire)
curl -s -X POST http://localhost:9089/api/notifications -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"recipient":"sami@test.tn","message":"Reservation confirmee"}'

# Archive des evenements RabbitMQ (volume partage)
curl -s "http://localhost:9089/api/notifications/events?limit=5" -H "Authorization: Bearer $TOKEN"

# Sonde
curl -s http://localhost:9089/api/notifications/hello -H "Authorization: Bearer $TOKEN"
```

### E9 — Conference-Service (Node/Express)

```bash
curl -s http://localhost:3001/api/conferences

curl -s -X POST http://localhost:3001/api/conferences -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{
    "title":"Architecture microservices","speaker":"Equipe Nomadix",
    "date":"2027-02-10","location":"ESPRIT"}'

curl -s -X DELETE http://localhost:3001/api/conferences/<id> -H "Authorization: Bearer $TOKEN"
```

Via la Gateway : `POST /api/conferences` exige ADMIN, `GET` est public. Swagger :
<http://localhost:3001/swagger-ui>, spec <http://localhost:3001/v3/api-docs>.

### E10 — Parcours frontend complet (a montrer en premier si le temps est court)

1. <http://localhost:8090> : catalogue des vols (aucun jeton necessaire).
2. Connexion `sami` / `sami123`.
3. *Voyageurs* : creation et consultation.
4. *Reservations* : reserver un vol, verifier que le prix affiche est celui du vol,
   puis annuler et verifier que le nombre de places remonte.
5. *Favoris* : ajouter un vol, la carte s'enrichit des details du vol (preuve visuelle de Feign).
6. *Notifications* : la liste des evenements RabbitMQ consommes apparait (`/api/notifications/events`).
7. *Conferences* : CRUD du service Node.
8. Deconnexion : les pages protegees redirigent vers Keycloak.

Le navigateur n'a **qu'une seule origine** (`http://localhost:8090`) : nginx proxifie `/api/**`
vers la Gateway. Aucun port de microservice n'est appele directement par le front.

### E11 — Matrice de securite (une ligne = une preuve)

| Requete | Sans jeton | TRAVELER | ADMIN |
|---|---|---|---|
| `GET /api/flights` | 200 | 200 | 200 |
| `POST /api/flights` | 401 | **403** | 200 |
| `DELETE /api/flights/{id}` | 401 | **403** | 204 |
| `GET /api/travelers` | 401 | 200 | 200 |
| `DELETE /api/travelers/{id}` | 401 | **403** | 204 |
| `GET /api/travelers/profiles/top` | 401 | **403** | 200 |
| `POST /api/bookings` | 401 | 201 | 201 |
| `POST /api/packh/rabbitmq/poison` | 401 | **403** | 200 |
| `GET /actuator/health` (Gateway) | 200 | 200 | 200 |
| `GET /actuator/gateway/routes` | 401 | **403** | 200 |

### E12 — Observabilite (fin de demonstration)

```bash
for p in 9081 9082 9083 9089; do
  printf "%s : " $p; curl -s http://localhost:$p/actuator/health | grep -o '"status":"[A-Z]*"'
done
curl -s http://localhost:9084/health
curl -s http://localhost:3001/
```

Chaque service expose `/actuator/health` (statut, base de donnees, RabbitMQ, disjoncteurs) :
la Gateway peut ainsi etre supervisée comme le reste du parc.

---

## 8. Commandes Docker de reference

| Besoin | Commande |
|---|---|
| Tout demarrer | `docker compose up -d --build` |
| Tout arreter (volumes conserves) | `docker compose down` |
| Tout arreter + effacer MySQL/RabbitMQ | `docker compose down -v` |
| Etat des conteneurs | `docker compose ps` |
| Logs d'un service | `docker compose logs -f booking-service` |
| 3 dernieres lignes du consommateur | `docker compose logs notification-consumer \| tail -3` |
| Logs filtres (routage) | `docker compose logs gateway \| grep "/api/flights"` |
| Arreter / relancer un service | `docker compose stop flight-service` / `docker compose start flight-service` |
| Redemarrer (recree le conteneur) | `docker compose restart notification-service` |
| Executer une commande dans un conteneur | `docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers` |
| Lire l'archive des notifications | `docker compose exec notification-consumer sh -c "cat /data/notifications.json"` |
| Vider et reconstruire un service | `docker compose up -d --build --no-deps flight-service` |
| Deux instances (test de charge) | `docker compose up -d --scale flight-service=2 --no-recreate` |
| Base MySQL d'un service | `docker compose exec mysql mysql -unomadix -pnomadix123 nomadix_flight_db -e "SELECT id,flight_number,available_seats FROM flight;"` |
| Compteurs des files en CLI | `docker compose exec rabbitmq rabbitmqctl list_queues name messages messages_ready messages_unacknowledged consumers` |
| Vider la DLQ avant un test | `docker compose exec rabbitmq rabbitmqctl purge_queue notification.events.dlq` |
| Verifier qu'on est a la racine | `pwd; ls docker-compose.yml` |
| Voir les conteneurs et leur sante | `docker compose ps --format "table {{.Name}}\t{{.Status}}"` |

---

## 9. Depannage (les pannes reellement rencontrees)

| Symptome | Cause | Correction |
|---|---|---|
| `500` sur toutes les routes Gateway | instance Eureka publiee avec `localhost`/port hote | garder `EUREKA_INSTANCE_HOSTNAME=<nom compose>` ; les ports hote ne servent qu'aux liens du dashboard |
| `503` sur une route, service pourtant demarre | 90 s d'expiration du bail non encore ecoulee, ou enregistrement Python perdu | attendre, ou `docker compose restart notification-service` (superviseur) |
| `PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange'` | file `notification.events` creee a la main sans les arguments DLX | supprimer la file dans l'UI (ou `docker compose down -v`), le broker la recree avec `definitions.json` |
| `TypeError: NetworkError` dans Swagger | preflight CORS refuse (origine non autorisee) | verifier `globalcors.allowedOriginPatterns` et `OPTIONS permitAll` dans le service |
| `401` partout depuis Docker | jeton absent/expire, ou audience differente de `nomadix-api` | refaire *Authorize*, verifier `NOMADIX_KEYCLOAK_ISSUER` |
| `500` sur `/api/flights` apres redemarrage | MySQL pas encore pret (healthcheck) | `docker compose logs mysql`, attendre `healthy` |
| Consommateur en boucle sur `[notif] RabbitMQ indisponible` | broker arrete ou mot de passe different | `docker compose start rabbitmq`, verifier `RABBIT_USER/RABBIT_PASSWORD` |
| `404` sur `/api/packh/**` via Gateway | route absente d'une ancienne version | `docker compose up -d --build gateway` (les routes `packh-*` sont dans `application.yml`) |
| Vol `500` a la creation | conflit Hibernate (colonne NOT NULL) | `docker compose logs flight-service`, verifier le corps JSON (tous les champs du `Flight`) |
| `//: Is a directory` | une ligne de commentaire (`// bash`, `Attendu : ...`) a ete collee dans le terminal | ne coller que les lignes de commande ; les resultats attendus commencent par `#` |
| `Attendu: command not found` | meme cause : la ligne d'explication a ete executee | idem, ou la prefixer par `#` |
| `no configuration file provided: not found` | `docker compose` lance hors de la racine du projet | `cd /mnt/c/.../AWD-Training` puis `ls docker-compose.yml` avant toute commande `docker compose` |
| `cd: backend/...: No such file or directory` | le terminal est dans `/mnt/c/WINDOWS/system32` (repertoire par defaut de WSL) | se placer d'abord dans le depot, ensuite seulement utiliser les chemins relatifs |
| `python: command not found` | WSL n'expose pas `python`, seulement `python3` | utiliser `python3`, ou supprimer le formatage JSON (`... | head -c 300`) |
| `400` sur `POST/PUT /api/travelers` | `dateOfBirth` envoye en chaine vide | envoyer `null` ou omettre le champ (le frontend le fait deja) |
| Score de profil bloque a 33 | profil calcule avant l'ajout des champs au modele | `POST /api/travelers/profiles/{id}/recompute` apres `docker compose up -d --build traveler-service` |
| `tls: failed to verify certificate: x509: certificate is not valid for any names` au `--build` | interception TLS entre WSL et Docker Hub (antivirus qui inspecte le HTTPS, proxy d'entreprise, miroir de registre casse) : le certificat presente n'a aucune SAN, le build ne peut pas telecharger `docker/dockerfile:1.7` ni les images de base | `bash documentation/scripts/diagnose-docker-registry.sh` (il nomme la couche fautive et la correction), puis relancer le build. Aucune modification du projet n'est necessaire |
| `WARNING: failed to get git commit` + `UtilAcceptVsock ... accept4 failed 110` juste avant l'erreur TLS | incident reseau WSL/Docker Desktop, pas une erreur du build | `wsl --shutdown` depuis PowerShell, relancer Docker Desktop, puis `docker pull hello-world` avant `docker compose up -d --build` |
| fichiers nommes `=`, `ERROR`, `[gateway`, `[flight-service]`, `reading`, `transferring` a la racine | sortie du terminal (lignes `=>` et `[service]`) collee dans bash : chaque fragment est devenu une redirection ou une commande | `rm -f '=' ERROR '['* reading transferring` puis relancer la commande normalement |

---

### 9.1 Build qui ne demarre pas : erreur TLS sur le registre

Le message est toujours le meme, seul le nom du registre change :

```
failed to resolve source metadata for docker.io/library/node:20-alpine
x509: certificate is not valid for any names, but wanted to match registry-1.docker.io
```

Ce n'est pas un probleme de code : BuildKit essaie de joindre Docker Hub et recoit
un certificat qui ne designe aucun nom d'hote. Trois causes possibles, dans l'ordre de
frequence : antivirus qui dechiffre le HTTPS, proxy configure dans Docker Desktop,
pile reseau WSL desynchronisee. Le diagnostic est automatise :

```bash
bash documentation/scripts/diagnose-docker-registry.sh
```

Il teste successivement la resolution DNS, le certificat reellement presente (SAN), les
variables `*_proxy`, les `registry-mirrors` du daemon et un `docker pull hello-world`, puis
imprime la correction correspondante et sort en code 1 si une interception existe.

Correction la plus courante, dans cet ordre :

```bash
# 1. remettre la pile reseau d'aplomb (PowerShell)
wsl --shutdown
# 2. rouvrir Docker Desktop, attendre "Engine running", puis verifier :
docker pull hello-world
# 3. seulement ensuite relancer le projet
docker compose up -d --build
```

Si `docker pull` fonctionne mais que le build echoue encore, le probleme est propre a
BuildKit (recuperation de `docker/dockerfile:1.7`) :

```bash
DOCKER_BUILDKIT=0 docker compose build flight-service   # contournement
```

Attendre `Pull complete` avant de relancer `docker compose up -d --build` : tant que le
registre reste injoignable, aucun `--build` ne peut aboutir, quel que soit le service.

---

## 10. Checklist de soutenance (20 minutes)

| # | Ce qui est montre | Scenario | Preuve attendue |
|---|---|---|---|
| 1 | Architecture et demarrage | `docker compose ps` | tous les conteneurs `Up`/`healthy` |
| 2 | Annuaire dynamique | `I1` / `I2` | Eureka : 6 applications `UP` |
| 3 | Point d'entree unique | `G1` / `G2` | 9 routes `lb://` |
| 4 | Securite | `G4` / `G5` / `E11` | `401`, `403`, matrice |
| 5 | CRUD vol + voyageur | `E2` / `E3` | `200` / `201` / `204` |
| 6 | Saga + Feign | `F1` | `totalPrice` et stock `-1` |
| 7 | Compensation | `F3` | stock `+1`, idempotence |
| 8 | Resilience | `F4` / `F5` | `503` rapide, CB `OPEN` puis `CLOSED` |
| 9 | Decouplage | `T1` / `R1` | `Ready` 1 → 0, log consommateur |
| 10 | Durabilite | `T2` | `Ready = 3` pendant la panne, 0 apres |
| 11 | Dead Letter Queue | `T3` | `x-death` dans `notification.events.dlq` |
| 12 | Mode degrade | `T4` | `201` avec broker eteint |
| 13 | Scalabilite | `G7` | `UP (2)` et round robin |
| 14 | Produit final | `E10` | parcours front complet |

> Ordre conseille : 1 → 14. Si le temps manque, les trois scenarios non negociables sont `F1`
> (communication synchrone), `T2` (durabilite) et `T3` (dead-lettering) : ils couvrent a eux seuls
> les deux modes de communication et la tolerance aux pannes.

---

## 11. Rejouer les tests (remise a zero)

Trois niveaux, du moins couteux au plus radical. Choisir selon ce que l'on veut demontrer.

### 11.1 Niveau A — rejouer sans rien perdre (~30 s)

Suffit pour `T1`/`T2`/`T3` (compteurs qui s'accumulent) et pour tout test d'ecriture repetable.

```bash
cd <racine du projet>

# 1) vider les files : les compteurs Ready repartent de 0
#    (note la valeur AVANT si tu veux parler en "+1")
docker compose exec rabbitmq rabbitmqctl purge_queue notification.events.dlq
docker compose exec rabbitmq rabbitmqctl purge_queue notification.events

# 2) ligne de base a noter avant chaque scenario
docker compose exec rabbitmq rabbitmqctl list_queues \
  name messages_ready messages_unacknowledged consumers
```

`Purge` est aussi un bouton en bas de la page de chaque file dans l'UI.

### 11.2 Niveau B — prendre en compte un changement de code (~2 min)

Necessaire apres une modification du service (ex. la semantique de `completeness`).

```bash
cd <racine du projet>
docker compose up -d --build traveler-service
docker compose logs -f traveler-service | grep -E "Started TravelerApplication|PROFILING"
```

Puis recalculer les profils existants :

```bash
# Tous les profils, en une boucle (ids lus depuis l'API, rien n'est code en dur)
for id in $(curl -s http://localhost:9089/api/travelers/profiles \
             -H "Authorization: Bearer $TOKEN" \
           | grep -o '"travelerId":"[0-9]*"' | grep -o '[0-9]*'); do
  curl -s -X POST "http://localhost:9089/api/travelers/profiles/$id/recompute" \
    -H "Authorization: Bearer $TOKEN" > /dev/null
  echo "recompute ok : $id"
done
```

> **Limite a connaitre** : `recompute` reapplique les **regles courantes** aux donnees
> **deja stockees**. Il remet `completeness` a l'echelle 0-100, mais il n'invente pas un
> passeport absent. Pour faire monter un ancien profil (creation avant l'ajout des champs),
> il faut **renseigner le voyageur** — c'est le `PUT` qui met a jour l'entite *et* le profil :

```bash
curl -s -X PUT http://localhost:9089/api/travelers/1 -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{
    "firstName":"Sami","lastName":"Trabelsi","email":"sami@test.tn","role":"USER",
    "phone":"+216 55 123 456","passportNumber":"TN1234567",
    "nationality":"Tunisienne","dateOfBirth":"1998-04-12"}'
curl -s http://localhost:9089/api/travelers/profiles/1 -H "Authorization: Bearer $TOKEN"
# score 100, segment PLATINUM, completeness 100
```

### 11.3 Niveau C — repartir de zero (destructif)

Efface MySQL, RabbitMQ **et** l'archive des notifications, puis reapplique les jeux de demo
(`data.sql` + `definitions.json` + realm Keycloak). Les ids repartent de 1.

```bash
docker compose down -v
docker compose up -d --build
docker compose ps        # attendre que mysql / rabbitmq / keycloak / eureka soient healthy
```

### 11.4 Ne jamais coder un id en dur

`16`, `17` dependent de la base : sur un volume neuf, ce sont 4, 5... Capturer a la volee :

```bash
# creation -> fichier temporaire -> extraction de l'id
curl -s -X POST http://localhost:9089/api/travelers -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"firstName":"Test","lastName":"Rejouable",
   "email":"test@nomadix.tn","role":"USER","phone":"95432100",
   "passportNumber":"TN9999999","nationality":"Tunisienne","dateOfBirth":"1990-01-01"}' \
  -o /tmp/t.json

ID=$(sed -E 's/.*"id":([0-9]+).*$/\1/' /tmp/t.json); echo "ID=$ID"
curl -s http://localhost:9089/api/travelers/profiles/$ID -H "Authorization: Bearer $TOKEN"
```

### 11.5 Ce qui doit etre recalcule, et comment

| Element | Pourquoi il n'est plus valable | Geste exact |
|---|---|---|
| `completeness` des anciens profils | l'ancienne valeur etait en points (max 40), la nouvelle en % | niveau B puis `recompute` |
| Score d'un ancien voyageur reste a 33 | ses donnees de profilage sont reellement vides | `PUT` avec les 4 champs (11.2) |
| `Ready = 3` sur la DLQ | messages accumules des tests precedents | `purge_queue` (11.1) ou *Purge* dans l'UI |
| `segments` / `statistics` | moyennes sur tout l'historique, pas sur le test courant | noter avant/apres et parler en delta |
| `Ready = 1` attendu en `T1`/`R1` | tout depend du point de depart | lire « +1 » a partir de la ligne de base (11.1) |
| Ids `16` / `17` | generes par MySQL | capture dynamique (11.4) |
| Stock des vols | decremente a chaque reservation reussie | noter `availableSeats` avant, ou niveau C |
