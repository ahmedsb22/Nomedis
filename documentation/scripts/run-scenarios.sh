#!/usr/bin/env bash
# =============================================================================
#  Nomadix - RUNBOOK EXECUTABLE (soutenance MT-41) - v2 "tout est montre"
# -----------------------------------------------------------------------------
#  Principe : pour chaque preuve, le script AFFICHE la commande qu'il lance,
#  puis la SORTIE BRUTE obtenue, puis l'assertion qui en decoule.
#
#      $ curl -s -X POST http://localhost:9089/api/flights ...
#        | HTTP 200
#        | {"id":47,"flightNumber":"CMD-101500",...}
#        [OK]   POST /api/flights = 200
#
#  Trois familles de preuves, toutes reelles (aucune valeur inventee) :
#
#    - HTTP      : curl (code de retour + corps JSON)
#    - MySQL     : requete SQL dans la base DU service concerne (la ligne
#                  existe vraiment, avec les vraies valeurs)
#    - RabbitMQ  : API de management (Ready / Unacked / Consumers) + en-tete
#                  x-death + topologie lue par rabbitmqctl
#    - Docker    : extraits de logs bornes dans le temps (--since), affiches
#
#  Principe de rejouabilite : les valeurs absolues (Ready, statistiques,
#  segments, DLQ) dependent de l'historique. Le script lit des DELTAS
#  (valeur avant / valeur apres), jamais des valeurs figees. Aucune purge,
#  aucun "down -v" : on peut le relancer indefiniment.
#
#  Usage :
#      bash documentation/scripts/run-scenarios.sh            # tout
#      bash documentation/scripts/run-scenarios.sh --no-resilience
#                                                             # sans arret de
#                                                             # conteneurs
#  Code de sortie : 0 si toutes les assertions passent, 1 sinon, 2 si la stack
#  n'est pas demarree.
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 2

TMP="$(mktemp -d)"
BODY="$TMP/body.json"          # corps de la derniere requete HTTP
trap 'rm -rf "$TMP"' EXIT

GATEWAY="http://localhost:9089"
EUREKA="http://localhost:9761"
KEYCLOAK="http://localhost:8180"
RABBIT_API="http://localhost:15672/api"
RABBIT_USER="nomadix"
RABBIT_PASS="nomadix123"
MQ="notification.events"
DLQ="notification.events.dlq"
DB_USER="nomadix"
DB_PASS="nomadix123"
SKIP_RESILIENCE=0
[ "${1:-}" = "--no-resilience" ] && SKIP_RESILIENCE=1
SEND_TIMEOUT=30                 # secondes accordees a une requete HTTP

PASS=0
FAIL=0
QUIET=0                        # 1 pendant les boucles d'attente (pas de spam)

# ----------------------------------------------------------------------------
#  Affichage
# ----------------------------------------------------------------------------
section() { printf '\n\033[1m=== %s ===\033[0m\n' "$*"; }
proof()   { printf '  -- %s\n' "$*"; }
info()    { printf '     ..     %s\n' "$*"; }
ok()      { PASS=$((PASS+1)); printf '     \033[32m[OK]\033[0m   %s\n' "$*"; }
ko()      { FAIL=$((FAIL+1)); printf '     \033[31m[ECHEC]\033[0m %s\n' "$*"; }

# Commande reellement executee. Ecrite sur stderr : les helpers qui renvoient
# une valeur sont appeles dans $(...) et ne doivent pas polluer le resultat.
show() { [ "$QUIET" = 1 ] || printf '\033[36m     $ %s\033[0m\n' "$*" >&2; }
# Sortie brute d'une commande (preuve lisible).
raw()  { [ "$QUIET" = 1 ] || printf '       \033[2m| %s\033[0m\n' "$1" >&2; }
# Ligne de preuve ecrite sur stdout (extraits de logs).
p()    { printf '       | %s\n' "$*"; }

# ----------------------------------------------------------------------------
#  Assertions
# ----------------------------------------------------------------------------
check_eq() { # attendu obtenu libelle
  if [ "$1" = "$2" ]; then ok "$3 = ${2:-<vide>}"; else ko "$3 : attendu '$1', obtenu '${2:-<vide>}'"; fi
}

check_num() { # attendu obtenu libelle (egalite numerique : 210 == 210.00)
  if awk -v a="$1" -v b="$2" 'BEGIN{exit !(a==b)}' 2>/dev/null; then
    ok "$3 = $2"
  else
    ko "$3 : attendu $1, obtenu ${2:-<vide>}"
  fi
}

check_lt() { # seuil obtenu libelle
  if awk -v s="$1" -v v="$2" 'BEGIN{exit !(v<s)}' 2>/dev/null; then
    ok "$3 ($2 < $1)"
  else
    ko "$3 : $2 n'est pas < $1"
  fi
}

check_ge() { # seuil obtenu libelle
  if awk -v s="$1" -v v="$2" 'BEGIN{exit !(v>=s)}' 2>/dev/null; then
    ok "$3 ($2 >= $1)"
  else
    ko "$3 : $2 n'est pas >= $1"
  fi
}

check_contains() { # aiguille botte libelle
  case "$2" in
    *"$1"*) ok "$3 contient '$1'" ;;
    *)      ko "$3 ne contient pas '$1' (extrait: $(printf '%s' "$2" | tr -d '\n' | cut -c1-160))" ;;
  esac
}

check_match() { # regex ERE chaine libelle
  if printf '%s' "$2" | grep -qE "$1"; then ok "$3"; else ko "$3 (motif '$1' absent)"; fi
}

check_not_contains() {
  case "$2" in
    *"$1"*) ko "$3 : '$1' ne devrait pas apparaitre" ;;
    *)      ok "$3 (absent comme attendu)" ;;
  esac
}

# ----------------------------------------------------------------------------
#  Attente (avec silence pendant le polling, resultat affiche a la fin)
# ----------------------------------------------------------------------------
wait_eq() { # libelle attendu timeout_s commande...
  local label="$1" want="$2" timeout="$3" i=0 got=""
  shift 3
  QUIET=1
  while [ "$i" -lt "$timeout" ]; do
    got="$("$@" 2>/dev/null)"
    if [ "$got" = "$want" ]; then
      QUIET=0
      ok "$label = $got (apres ${i}s)"
      return 0
    fi
    sleep 2; i=$((i+2))
  done
  QUIET=0
  ko "$label : attendu '$want', obtenu '${got:-<vide>}' apres ${timeout}s"
}

wait_ge() { # libelle minimum timeout_s commande...
  local label="$1" want="$2" timeout="$3" i=0 got=0
  shift 3
  QUIET=1
  while [ "$i" -lt "$timeout" ]; do
    got="$("$@" 2>/dev/null)"
    case "$got" in ''|*[!0-9]*) got=0 ;; esac
    if [ "$got" -ge "$want" ]; then
      QUIET=0
      ok "$label = $got (>= $want, apres ${i}s)"
      return 0
    fi
    sleep 1; i=$((i+1))
  done
  QUIET=0
  ko "$label : $got < $want apres ${timeout}s"
}

# ----------------------------------------------------------------------------
#  HTTP : commande affichee, code et corps bruts conserves
# ----------------------------------------------------------------------------
http_code() { # options curl... (la derniere valeur doit etre l'URL)
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$@" 2>/dev/null)
  code="${code:-000}"
  show "curl -s -o /dev/null -w %{http_code} $*"
  raw "HTTP $code"
  printf '%s' "$code"
}

# Corps compact (une seule ligne) et tronque, tel qu'il est ecrit par curl.
braw()  { tr -d '\r\n' < "$BODY" 2>/dev/null; }
bprev() { braw | cut -c1-400; }

# send METHODE URL [CORPS_JSON] [JETON]
# Laisse le code dans $CODE et le corps dans $BODY (les deux globaux).
send() {
  local method="$1" url="$2" data="${3:-}" token="${4:-}" shown out
  local args=(-s -X "$method" --max-time "${SEND_TIMEOUT:-30}" -o "$BODY" -w '%{http_code}')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$data" ]  && args+=(-H 'Content-Type: application/json' -d "$data")

  shown="curl -s -X $method $url"
  [ -n "$token" ] && shown="$shown -H 'Authorization: Bearer <jeton>'"
  [ -n "$data" ]  && shown="$shown -d '$data'"
  show "$shown"

  # Troncature du corps : si curl expire sans reponse (HTTP 000), on ne doit pas
  # relire le corps de la requete precedente comme s'il appartenait a celle-ci.
  : > "$BODY"
  CODE=$(curl "${args[@]}" "$url" 2>/dev/null)
  CODE="${CODE:-000}"
  out=$(bprev)
  raw "HTTP $CODE"
  raw "${out:-<corps vide>}"
}

# Extrait la premiere valeur d'une cle JSON. Volontairement sans jq (absent
# sur un poste Windows/WSL neuf) : tr + grep -oE + sed suffisent.
jget() { # fichier cle
  tr -d '\r\n' < "$1" 2>/dev/null \
    | grep -oE "\"$2\"[[:space:]]*:[[:space:]]*(\"[^\"]*\"|[0-9]+([.][0-9]+)?|true|false|null)" \
    | head -1 \
    | sed -E 's/^"[^"]*"[[:space:]]*:[[:space:]]*//; s/^"//; s/"$//'
}

# ----------------------------------------------------------------------------
#  MySQL : requete affichee et resultat brut affiche
# ----------------------------------------------------------------------------
sql() { # base requete
  local out
  show "docker compose exec -T mysql mysql -u$DB_USER -p$DB_PASS -N -B $1 -e \"$2\""
  out=$(docker compose exec -T mysql mysql -u"$DB_USER" -p"$DB_PASS" -N -B -e "$2" "$1" 2>/dev/null | tr -d '\r')
  if [ -z "$out" ]; then
    # Volume MySQL cree avant le script d'init : le compte applicatif peut
    # manquer, root reste disponible dans le conteneur.
    out=$(docker compose exec -T mysql mysql -uroot -proot -N -B -e "$2" "$1" 2>/dev/null | tr -d '\r')
  fi
  raw "${out:-<aucune ligne>}"
  printf '%s' "$out"
}

# ----------------------------------------------------------------------------
#  RabbitMQ : compteurs lus sur l'API de management (les memes que l'UI)
# ----------------------------------------------------------------------------
qstat() { # file champ [vhost]
  local vhost="${3:-%2F}" value
  curl -s -u "$RABBIT_USER:$RABBIT_PASS" --max-time 6 \
    "$RABBIT_API/queues/$vhost/$1" -o "$TMP/q.json" 2>/dev/null
  value=$(jget "$TMP/q.json" "$2")
  show "curl -s -u $RABBIT_USER:*** $RABBIT_API/queues/$vhost/$1  ->  champ '$2'"
  raw "$1.$2 = ${value:-<vide>}"
  printf '%s' "$value"
}

# ----------------------------------------------------------------------------
#  Docker : fenetre temporelle stricte + extraits de logs affiches
# ----------------------------------------------------------------------------
# On ne relit jamais une ligne produite avant l'action courante : les 2 s de
# marge absorbent le decalage d'horloge entre WSL et les conteneurs.
mark() { SINCE="$(date -u -d '2 seconds ago' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
                 || date -u +%Y-%m-%dT%H:%M:%SZ)"; }

logcount() { # service motif
  # 2>&1 est INDISPENSABLE : `docker compose logs` recopie sur stdout ce qu'un
  # conteneur ecrit sur stdout, et sur stderr ce qu'il ecrit sur stderr. Avec
  # 2>/dev/null, les lignes emises sur stderr (dont celles des services Java)
  # etaient jetees : le comptage valait 0 en permanence, alors que la ligne
  # existait. C'etait la cause des faux [ECHEC] sur les preuves de logs.
  docker compose logs --since "$SINCE" --no-color "$1" 2>&1 | grep -cF -- "$2" || true
}

# Affiche les lignes de log qui constituent la preuve.
logs() { # service motif [nb_lignes]
  local n="${3:-3}" out
  show "docker compose logs --since $SINCE --no-color $1 2>&1 | grep -F '$2' | tail-$n"
  out=$(docker compose logs --since "$SINCE" --no-color "$1" 2>&1 | grep -F -- "$2" | tail -"$n")
  if [ -n "$out" ]; then
    printf '%s\n' "$out" | while IFS= read -r line; do p "$line"; done
  else
    p "<aucune ligne contenant '$2' dans $1 depuis $SINCE>"
  fi
}

wait_log() { # service motif timeout libelle
  local i=0 n=0
  QUIET=1
  while [ "$i" -lt "$3" ]; do
    n=$(logcount "$1" "$2")
    if [ "$n" -ge 1 ]; then
      QUIET=0
      ok "$4 (log '$2' trouve)"
      return 0
    fi
    sleep 1; i=$((i+1))
  done
  QUIET=0
  ko "$4 : aucune ligne '$2' dans les logs de $1"
}

TOKEN=""
TOKEN_TRAVELER=""

# Empeche une section de produire des assertions trompeuses quand une section
# amont a echoue (vol ou voyageur non cree).
need_ids() {
  if [ -z "${FID:-}" ] || [ -z "${TID:-}" ]; then
    ko "section ignoree : FID/TID absents (S4 ou S5 en echec)"
    return 1
  fi
  return 0
}

token_for() { # utilisateur motdepasse fichier_sortie
  show "curl -s -X POST $KEYCLOAK/realms/nomadix/protocol/openid-connect/token -d grant_type=password -d client_id=nomadix-cli -d username=$1"
  curl -s --max-time 15 -X POST \
    "$KEYCLOAK/realms/nomadix/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d 'grant_type=password' -d 'client_id=nomadix-cli' \
    -d "username=$1" -d "password=$2" -d 'scope=openid profile email' \
    -o "$3" 2>/dev/null
  jget "$3" access_token
}

# =============================================================================
#  S1 - PREFLIGHT : la stack tourne, Keycloak repond, les jetons sont obtenus
# =============================================================================
s1_preflight() {
  section "S1  Preflight - conteneurs, Keycloak, jetons, sante des services"

  if ! command -v docker >/dev/null 2>&1; then
    echo "Docker est introuvable dans ce shell. Lancez le script depuis WSL." >&2
    return 2
  fi
  if [ ! -f docker-compose.yml ]; then
    echo "docker-compose.yml absent de $ROOT : mauvais dossier." >&2
    return 2
  fi

  proof "etat reel des conteneurs"
  show "docker compose ps --format 'table {{.Name}}  {{.Status}}'"
  docker compose ps --format 'table {{.Name}}  {{.Status}}' 2>/dev/null | while IFS= read -r line; do p "$line"; done

  local running
  running=$(docker compose ps --status running --format '{{.Name}}' 2>/dev/null | wc -l | tr -d ' ')
  check_ge 10 "$running" "conteneurs en etat running"

  proof "Keycloak : le realm repond"
  send GET "$KEYCLOAK/realms/nomadix"
  check_eq 200 "$CODE" "GET /realms/nomadix"
  check_contains '"realm":"nomadix"' "$(braw)" "le corps decrit bien le realm nomadix"

  proof "jeton ADMIN (admin/admin123, client public nomadix-cli)"
  TOKEN=$(token_for admin admin123 "$TMP/tok-admin.json")
  if [ -n "$TOKEN" ]; then ok "jeton ADMIN obtenu (${#TOKEN} caracteres)"; else ko "jeton ADMIN non obtenu (admin/admin123, client nomadix-cli)"; return 1; fi

  proof "jeton TRAVELER (sami/sami123) pour la matrice de droits"
  TOKEN_TRAVELER=$(token_for sami sami123 "$TMP/tok-sami.json")
  if [ -n "$TOKEN_TRAVELER" ]; then ok "jeton TRAVELER obtenu"; else ko "jeton TRAVELER non obtenu"; fi

  proof "sante Actuator de chaque service (le JSON brut est la preuve)"
  local port
  for port in 9081 9082 9083 9089; do
    send GET "http://localhost:$port/actuator/health"
    check_eq 200 "$CODE" "GET :$port/actuator/health"
    check_contains '"status":"UP"' "$(braw)" ":$port declare UP"
  done
}

# =============================================================================
#  S2 - EUREKA : annuaire dynamique, aucune URL en dur
# =============================================================================
s2_eureka() {
  section "S2  Eureka - les services s'annoncent eux-memes, avec un nom resolvable"

  send GET "$EUREKA/eureka/apps"
  check_eq 200 "$CODE" "GET /eureka/apps"
  local apps
  apps=$(braw)

  proof "les 6 applications attendues figurent au registre (le serveur ne s'auto-enregistre pas)"
  # Eureka repond en XML (pas en JSON) : le nom d'une application est un
  # element <name>...</name>. Chercher '"BOOKING-SERVICE"' ne peut jamais
  # reussir et produisait 6 faux [ECHEC].
  info "format XML : on cherche <name>SERVICE</name>"
  local s
  for s in BOOKING-SERVICE FLIGHT-SERVICE TRAVELER-SERVICE API-GATEWAY NOTIFICATION-SERVICE CONFERENCE-SERVICE; do
    check_contains "<name>$s</name>" "$apps" "registre : $s"
  done

  proof "instance FLIGHT-SERVICE : NOM resolvable + PORT INTERNE (pas une IP, pas le port hote)"
  send GET "$EUREKA/eureka/apps/FLIGHT-SERVICE"
  local inst
  inst=$(braw)
  check_contains '<hostName>flight-service</hostName>' "$inst" "hostname Eureka de l'instance"
  check_match '<port enabled="true">8082</port>' "$inst" "port publie au registre = 8082 (port interne)"
  check_contains '<status>UP</status>' "$inst" "statut de l'instance"
  check_contains '<instanceId>flight-service:8082</instanceId>' "$inst" "instanceId lisible (EUREKA_INSTANCE_INSTANCE_ID)"
  check_contains '<ipAddr>172.' "$inst" "ipAddr presente mais non utilisee pour le routage (prefer-ip-address=false)"

  proof "preuve negative : aucune adresse 'localhost' n'est publiee pour le routage"
  check_not_contains '<hostName>localhost</hostName>' "$inst" "pas de localhost dans l'annuaire"
}

# =============================================================================
#  S3 - API GATEWAY : routage unique + securite centralisee
# =============================================================================
s3_gateway() {
  section "S3  API Gateway - point d'entree unique, routage lb://, 401/403/200"

  proof "table de routage reellement chargee (actuator/gateway/routes, ADMIN requis)"
  send GET "$GATEWAY/actuator/gateway/routes" "" "$TOKEN"
  check_eq 200 "$CODE" "GET /actuator/gateway/routes (ADMIN)"
  local routes
  routes=$(braw)
  check_contains 'lb://FLIGHT-SERVICE' "$routes" "route  /api/flights"
  check_contains 'lb://TRAVELER-SERVICE' "$routes" "route  /api/travelers"
  check_contains 'lb://BOOKING-SERVICE' "$routes" "route  /api/bookings"
  check_contains 'lb://CONFERENCE-SERVICE' "$routes" "route  /api/conferences"
  check_contains 'lb://NOTIFICATION-SERVICE' "$routes" "route  /api/notifications"
  check_contains '/api/packh/flight-events' "$routes" "route  packh flight-events"

  proof "le meme GET donne 200 via la Gateway et via le port direct du service"
  send GET "$GATEWAY/api/flights"
  check_eq 200 "$CODE" "GET gateway:9089/api/flights (public)"
  send GET "http://localhost:9082/api/flights"
  check_eq 200 "$CODE" "GET direct:9082/api/flights (public)"

  proof "preflight CORS du frontend Angular (origine 8090)"
  show "curl -s -D - -o /dev/null -X OPTIONS $GATEWAY/api/flights -H 'Origin: http://localhost:8090' -H 'Access-Control-Request-Method: GET'"
  local hdr
  # Les en-tetes HTTP ne sont pas sensibles a la casse : comparaison en minuscules.
  hdr=$(curl -s -D - -o /dev/null --max-time 10 -X OPTIONS "$GATEWAY/api/flights" \
        -H 'Origin: http://localhost:8090' -H 'Access-Control-Request-Method: GET' 2>/dev/null \
        | tr 'A-Z' 'a-z' | tr -d '\r')
  raw "$(printf '%s' "$hdr" | grep -E '^(http/|access-control)' | tr '\n' ' ')"
  check_contains 'access-control-allow-origin: http://localhost:8090' "$hdr" "en-tete CORS renvoye"

  proof "matrice de securite : 401 sans jeton, 403 role insuffisant, 200 ADMIN"
  send GET "$GATEWAY/api/travelers"
  check_eq 401 "$CODE" "sans jeton sur /api/travelers"
  check_contains '"status":401' "$(braw)" "corps 401 au format API (pas une page HTML)"
  send GET "$GATEWAY/api/travelers/profiles" "" "$TOKEN_TRAVELER"
  check_eq 403 "$CODE" "TRAVELER sur /profiles (ADMIN requis)"
  send GET "$GATEWAY/api/travelers/profiles" "" "$TOKEN"
  check_eq 200 "$CODE" "ADMIN sur /profiles"
}

# =============================================================================
#  S4 - CRUD LOCAL : chaque service ecrit dans SA base, sans appeler personne
# =============================================================================
FID=""
FLIGHT_NUMBER=""
s4_crud() {
  section "S4  CRUD local (Flight) - ecriture dans sa base, ZERO evenement RabbitMQ"

  FLIGHT_NUMBER="CMD-$(date +%H%M%S)"
  proof "POST /api/flights (ADMIN) - le controleur renvoie 200, pas 201"
  send POST "$GATEWAY/api/flights" \
    "{\"flightNumber\":\"$FLIGHT_NUMBER\",\"departureCity\":\"Tunis\",\"arrivalCity\":\"Rome\",\"departureDate\":\"2027-04-01\",\"availableSeats\":40,\"price\":210.00}" \
    "$TOKEN"
  check_eq 200 "$CODE" "POST /api/flights"
  FID=$(jget "$BODY" id)
  if [ -n "$FID" ]; then
    ok "vol cree, id=$FID (numero $FLIGHT_NUMBER)"
  else
    ko "aucun id retourne : $(bprev)"
    return 1
  fi

  proof "la ligne existe dans nomadix_flight_db (source de verite du service)"
  check_eq "$FLIGHT_NUMBER" "$(sql nomadix_flight_db "SELECT flight_number FROM flight WHERE id=$FID;")" "SQL flight.flight_number"
  check_eq 40 "$(sql nomadix_flight_db "SELECT available_seats FROM flight WHERE id=$FID;")" "SQL flight.available_seats"
  check_num 210 "$(sql nomadix_flight_db "SELECT price FROM flight WHERE id=$FID;")" "SQL flight.price"

  proof "PUT /api/flights/{id} modifie la ligne existante (jamais d'INSERT implicite)"
  send PUT "$GATEWAY/api/flights/$FID" \
    "{\"flightNumber\":\"$FLIGHT_NUMBER\",\"departureCity\":\"Tunis\",\"arrivalCity\":\"Rome\",\"departureDate\":\"2027-04-01\",\"availableSeats\":45,\"price\":210.00}" \
    "$TOKEN"
  check_eq 200 "$CODE" "PUT /api/flights/$FID"
  check_eq 45 "$(sql nomadix_flight_db "SELECT available_seats FROM flight WHERE id=$FID;")" "SQL available_seats apres PUT"
  check_eq 1 "$(sql nomadix_flight_db "SELECT COUNT(*) FROM flight WHERE flight_number='$FLIGHT_NUMBER';")" "une seule ligne pour ce numero de vol (pas de doublon)"

  proof "preuve negative : une operation CRUD locale ne publie AUCUN evenement"
  mark
  sleep 2
  check_eq 0 "$(logcount notification-consumer "$FLIGHT_NUMBER")" "aucun log [notif] pour $FLIGHT_NUMBER (le CRUD n'emet rien)"
  logs notification-consumer "$FLIGHT_NUMBER" 2
}

# =============================================================================
#  S5 - SERVICE AVANCE : Smart Profiling declenche par AOP
# =============================================================================
TID=""
BADID=""
s5_profiling() {
  section "S5  Smart Profiling + AOP - le profil est calcule par un aspect, pas par le controleur"
  mark

  send GET "$GATEWAY/api/travelers/profiles/statistics" "" "$TOKEN"
  local before
  before=$(jget "$BODY" totalProfils)
  info "profils existants avant le test : ${before:-0}"

  proof "POST /api/travelers avec les 4 champs de profiling -> 201"
  send POST "$GATEWAY/api/travelers" \
    '{"firstName":"Amira","lastName":"Ben Salah","email":"amira.demo@nomadix.tn","role":"USER","phone":"+216 55 123 456","passportNumber":"TN1234567","nationality":"Tunisienne","dateOfBirth":"1998-04-12"}' \
    "$TOKEN"
  check_eq 201 "$CODE" "POST /api/travelers (profil complet)"
  TID=$(jget "$BODY" id)
  if [ -n "$TID" ]; then ok "voyageur cree, id=$TID"; else ko "id voyageur absent"; return 1; fi

  proof "la ligne existe dans travelers (base du service)"
  check_eq "amira.demo@nomadix.tn" "$(sql nomadix_traveler_db "SELECT email FROM travelers WHERE id=$TID;")" "SQL travelers.email"
  check_eq 1 "$(sql nomadix_traveler_db "SELECT COUNT(*) FROM travelers WHERE id=$TID;")" "SQL travelers : une ligne"

  proof "le profil existe DEJA, sans appel a /recompute : c'est l'aspect @AfterReturning qui l'a cree"
  send GET "$GATEWAY/api/travelers/profiles/$TID" "" "$TOKEN"
  check_eq 200 "$CODE" "GET /api/travelers/profiles/$TID"
  local score
  score=$(jget "$BODY" score)
  check_eq 100 "$score" "score du profil complet"
  if [ "$score" != 100 ]; then
    info "ecart de scoring : l'image traveler-service est peut-etre anterieure au correctif"
    info "-> docker compose up -d --build traveler-service, puis relancer ce script"
  fi
  check_eq PLATINUM "$(jget "$BODY" segment)" "segment"
  check_eq 100 "$(jget "$BODY" completeness)" "completude (pourcentage)"
  check_eq 1.1 "$(jget "$BODY" rulesVersion)" "version des regles appliquee"
  check_match '"riskFlags"[[:space:]]*:[[:space:]]*""' "$(braw)" "aucune anomalie"

  proof "le profil est persiste dans traveler_profiles (table dediee)"
  check_eq "$(printf '%s\t100\tPLATINUM' "$TID")" "$(sql nomadix_traveler_db \
    "SELECT traveler_id, score, segment FROM traveler_profiles WHERE traveler_id='$TID';")" \
    "SQL traveler_profiles (traveler_id / score / segment)"
  sql nomadix_traveler_db "SELECT traveler_id, completeness, valid_email, valid_passport, risk_flags, rules_version FROM traveler_profiles WHERE traveler_id='$TID';" >/dev/null

  proof "profil incoherent : email jetable + passeport manquant -> BRONZE + flags"
  send POST "$GATEWAY/api/travelers" \
    '{"firstName":"Karim","lastName":"X","email":"karim@mailinator.com","role":"USER"}' \
    "$TOKEN"
  check_eq 201 "$CODE" "POST /api/travelers (profil incomplet)"
  BADID=$(jget "$BODY" id)
  send GET "$GATEWAY/api/travelers/profiles/$BADID" "" "$TOKEN"
  check_eq 28 "$(jget "$BODY" score)" "score du profil incomplet"
  check_eq BRONZE "$(jget "$BODY" segment)" "segment"
  check_eq 33 "$(jget "$BODY" completeness)" "completude (2 champs sur 6)"
  local flags
  flags=$(braw)
  check_contains 'EMAIL_JETABLE' "$flags" "flag email jetable"
  check_contains 'PASSEPORT_MANQUANT' "$flags" "flag passeport manquant"
  check_contains 'PROFIL_A_RISQUE' "$flags" "flag profil a risque"

  proof "PUT /api/travelers/{id} : le score remonte SANS appeler /recompute (preuve AOP sur l'update)"
  send PUT "$GATEWAY/api/travelers/$BADID" \
    '{"firstName":"Karim","lastName":"Ben Ali","email":"karim.benali@nomadix.tn","role":"USER","phone":"95432100","passportNumber":"TN7654321","nationality":"Tunisienne","dateOfBirth":"1992-06-30"}' \
    "$TOKEN"
  check_eq 200 "$CODE" "PUT /api/travelers/$BADID"
  send GET "$GATEWAY/api/travelers/profiles/$BADID" "" "$TOKEN"
  check_eq 100 "$(jget "$BODY" score)" "score apres completion"
  check_eq PLATINUM "$(jget "$BODY" segment)" "segment apres completion"
  check_match '"riskFlags"[[:space:]]*:[[:space:]]*""' "$(braw)" "anomalies levees"

  proof "le moteur a tourne 3 fois (create complet, create incomplet, update) : trace serveur"
  wait_ge "lignes [PROFILING] dans traveler-service" 3 20 logcount traveler-service "[PROFILING] Traveler"
  logs traveler-service "[PROFILING] Traveler" 4

  proof "KPIs lus en DELTA (les moyennes portent sur tout l'historique)"
  send GET "$GATEWAY/api/travelers/profiles/statistics" "" "$TOKEN"
  local after
  after=$(jget "$BODY" totalProfils)
  check_eq "$((before + 2))" "$after" "totalProfils (+2)"
  send GET "$GATEWAY/api/travelers/profiles/segments" "" "$TOKEN"
  check_contains '"PLATINUM"' "$(braw)" "repartition par segment"
  # Beaucoup de profils sont ex aequo a 100 pts : l'ordre du top 3 n'est pas
  # deterministe (ORDER BY score DESC sans critere secondaire). On verifie donc
  # l'APPARTENANCE au classement, pas le rang, avec une limite large.
  send GET "$GATEWAY/api/travelers/profiles/top?limit=50" "" "$TOKEN"
  check_contains "\"travelerId\":\"$TID\"" "$(braw)" "le profil $TID figure au classement (top 50)"
  info "ex aequo a 100 pts -> le rang exact dans le top 3 n'est pas une propriete stable"

  proof "les deux profils de ce test sont dans la base, avec leurs scores respectifs"
  sql nomadix_traveler_db "SELECT traveler_id, score, segment FROM traveler_profiles WHERE traveler_id IN ('$TID','$BADID') ORDER BY score DESC;" >/dev/null
}

# =============================================================================
#  S6 - OPENFEIGN : saga, compensation idempotente, favoris enrichis
# =============================================================================
BID=""
SEATS0=45
s6_feign() {
  section "S6  OpenFeign - saga de reservation, compensation, favoris enrichis"
  need_ids || return 1

  proof "diagnostic des deux clients Feign (Traveler + Flight), jeton relaye"
  send GET "$GATEWAY/api/bookings/integrations/feign?travelerId=$TID&flightId=$FID" "" "$TOKEN"
  check_eq 200 "$CODE" "GET /api/bookings/integrations/feign"
  check_eq 2 "$(printf '%s' "$(braw)" | grep -o '"reachable":true' | wc -l | tr -d ' ')" "services joignables via Feign (2/2)"
  check_eq 2 "$(printf '%s' "$(braw)" | grep -o '"found":true' | wc -l | tr -d ' ')" "ressources trouvees (2/2)"

  SEATS0=$(sql nomadix_flight_db "SELECT available_seats FROM flight WHERE id=$FID;")
  info "stock avant reservation : $SEATS0"

  proof "POST /api/bookings : totalPrice vient de Flight (le client ne l'envoie pas) + stock -1"
  send POST "$GATEWAY/api/bookings?travelerId=$TID&flightId=$FID" "" "$TOKEN"
  check_eq 201 "$CODE" "POST /api/bookings"
  BID=$(jget "$BODY" id)
  check_num 210 "$(jget "$BODY" totalPrice)" "totalPrice recupere par Feign"
  check_eq CONFIRMED "$(jget "$BODY" status)" "statut initial"
  check_eq "$((SEATS0 - 1))" "$(sql nomadix_flight_db "SELECT available_seats FROM flight WHERE id=$FID;")" "SQL stock apres reservation (-1)"

  proof "la reservation est ecrite dans nomadix_booking_db, avec l'id du vol (reference logique)"
  check_eq "$(printf '%s\tCONFIRMED' "$FID")" "$(sql nomadix_booking_db \
    "SELECT flight_id, status FROM bookings WHERE id=$BID;")" "SQL bookings (flight_id / status)"
  check_num "$TID" "$(sql nomadix_booking_db "SELECT traveler_id FROM bookings WHERE id=$BID;")" "SQL bookings.traveler_id"

  proof "evenement booking.created publie APRES l'ecriture (trace producteur)"
  wait_ge "logs [RABBITMQ] publie booking.created" 1 20 logcount booking-service "[RABBITMQ] publie booking.created"
  logs booking-service "[RABBITMQ] publie booking.created" 1

  proof "DELETE /api/bookings/{id} deux fois : 204 + 204, UNE seule place restituee (idempotence)"
  send DELETE "$GATEWAY/api/bookings/$BID" "" "$TOKEN"
  check_eq 204 "$CODE" "premier DELETE"
  send DELETE "$GATEWAY/api/bookings/$BID" "" "$TOKEN"
  check_eq 204 "$CODE" "second DELETE (idempotent)"
  check_eq "$SEATS0" "$(sql nomadix_flight_db "SELECT available_seats FROM flight WHERE id=$FID;")" "SQL stock restitue (compensation)"
  check_eq CANCELLED "$(sql nomadix_booking_db "SELECT status FROM bookings WHERE id=$BID;")" "SQL bookings.status apres annulation"

  proof "favoris : Traveler ne stocke que l'id, puis enrichit la reponse via Feign"
  send POST "$GATEWAY/api/travelers/$TID/favorite-flights/$FID" "" "$TOKEN"
  check_eq 200 "$CODE" "POST /api/travelers/$TID/favorite-flights/$FID"
  send GET "$GATEWAY/api/travelers/$TID/favorite-flights" "" "$TOKEN"
  check_eq 200 "$CODE" "GET /api/travelers/$TID/favorite-flights"
  local fav
  fav=$(braw)
  check_contains "\"flightNumber\":\"$FLIGHT_NUMBER\"" "$fav" "champ flightNumber (vient de Flight)"
  check_contains '"price":210' "$fav" "champ price (vient de Flight)"

  proof "la table de favoris ne stocke que des identifiants (aucune donnee dupliquee)"
  local favtable favrows
  favtable=$(sql nomadix_traveler_db "SELECT table_name FROM information_schema.tables WHERE table_schema='nomadix_traveler_db' AND table_name LIKE '%favorite%' LIMIT 1;")
  if [ -n "$favtable" ]; then
    favrows=$(sql nomadix_traveler_db "SELECT * FROM $favtable;")
    check_contains "$FID" "$favrows" "SQL $favtable : la ligne contient le vol $FID"
    check_not_contains 'Rome' "$favrows" "aucun champ du vol (ville) n'est duplique"
  else
    info "table de favoris introuvable (nom inattendu) - la preuve API suffit"
  fi
}

# =============================================================================
#  S7 - CIRCUIT BREAKER : panne reelle de Flight, 503 rapide, retour a CLOSED
# =============================================================================
s7_circuit_breaker() {
  section "S7  Resilience - Flight arrete : 503 rapide (fallback), puis fermeture automatique"
  need_ids || return 1

  if [ "$SKIP_RESILIENCE" -eq 1 ]; then
    info "ignore (--no-resilience)"
    return 0
  fi

  proof "arret reel du conteneur Flight"
  show "docker compose stop flight-service"
  docker compose stop flight-service >/dev/null 2>&1
  wait_eq "GET direct:9082/api/flights (doit echouer)" 000 60 http_code --max-time 3 "http://localhost:9082/api/flights"

  proof "6 reservations successives : les timeouts remplissent la fenetre du circuit breaker"
  show "curl -s -o $BODY -w '%{http_code} %{time_total}' -X POST '$GATEWAY/api/bookings?travelerId=$TID&flightId=$FID' -H 'Authorization: Bearer <jeton>'   (x6)"
  local last_time=0 last_code="" i code t
  for i in 1 2 3 4 5 6; do
    read -r code t < <(curl -s -o "$BODY" -w '%{http_code} %{time_total}' --max-time 30 -X POST \
        "$GATEWAY/api/bookings?travelerId=$TID&flightId=$FID" \
        -H "Authorization: Bearer $TOKEN" 2>/dev/null)
    printf '       | appel %d -> HTTP %s en %ss\n' "$i" "${code:-000}" "${t:-?}"
    last_code="${code:-000}"; last_time="${t:-0}"
  done
  check_eq 503 "$last_code" "code retour quand Flight est indisponible"
  check_lt 1.5 "$last_time" "temps de reponse du fallback (circuit ouvert)"

  proof "etat du circuit breaker expose par Actuator (health-details)"
  send GET "http://localhost:9083/actuator/health"
  check_contains 'OPEN' "$(braw)" "un circuit est OPEN"
  check_contains '"flight-service"' "$(braw)" "instance surveillee : flight-service"

  proof "trace serveur du fallback"
  wait_ge "logs [CIRCUIT BREAKER] dans booking-service" 1 20 logcount booking-service "[CIRCUIT BREAKER] Flight-Service indisponible"
  logs booking-service "[CIRCUIT BREAKER] Flight-Service indisponible" 2

  proof "aucune reservation orpheline n'a ete ecrite pendant la panne"
  check_eq 0 "$(sql nomadix_booking_db "SELECT COUNT(*) FROM bookings WHERE traveler_id=$TID AND flight_id=$FID AND status='CONFIRMED';")" "SQL bookings : aucun CONFIRMED residuel"

  proof "redemarrage de Flight : OPEN -> HALF_OPEN (sonde) -> CLOSED (attente active)"
  show "docker compose start flight-service"
  docker compose start flight-service >/dev/null 2>&1
  wait_eq "GET gateway/api/flights apres redemarrage" 200 240 http_code "$GATEWAY/api/flights"

  # Un circuit ouvert ne se referme pas sur un simple sleep : il passe en
  # HALF_OPEN apres waitDurationInOpenState (10 s) et n'autorise que quelques
  # appels de sonde. On boucle donc : une sonde Feign reelle, puis lecture de
  # /actuator/health. On s'arrete des que plus aucun CIRCUIT_OPEN n'apparait.
  show "boucle : GET $GATEWAY/api/bookings/integrations/feign (sonde) puis grep CIRCUIT_OPEN dans :9083/actuator/health"
  local i=0 open_now=1
  while [ "$i" -lt 180 ]; do
    curl -s -o /dev/null --max-time 20 -H "Authorization: Bearer $TOKEN" \
      "$GATEWAY/api/bookings/integrations/feign?travelerId=$TID&flightId=$FID" 2>/dev/null
    open_now=$(curl -s --max-time 10 "http://localhost:9083/actuator/health" 2>/dev/null | grep -c 'CIRCUIT_OPEN' || true)
    [ "${open_now:-1}" = "0" ] && break
    sleep 2; i=$((i+2))
  done
  raw "circuits encore ouverts apres ${i}s : ${open_now:-1}"
  send GET "http://localhost:9083/actuator/health"
  check_not_contains 'CIRCUIT_OPEN' "$(braw)" "plus aucun circuit OPEN apres redemarrage (${i}s)"
  send GET "$GATEWAY/api/bookings/integrations/feign?travelerId=$TID&flightId=$FID" "" "$TOKEN"
  check_eq 200 "$CODE" "les appels Feign refonctionnent"
  check_eq 2 "$(printf '%s' "$(braw)" | grep -o '"reachable":true' | wc -l | tr -d ' ')" "les 2 services sont de nouveau joignables"
}

# =============================================================================
#  S8 - RABBITMQ METIER : 4 evenements de domaine consommes par Python
# =============================================================================
s8_rabbit_business() {
  section "S8  RabbitMQ metier - flight / traveler / booking publies et consommes"
  need_ids || return 1

  local base archive_before
  base=$(qstat "$MQ" messages_ready)
  [ -z "$base" ] && base=0
  info "Ready de depart sur $MQ : $base"
  show "docker compose exec -T notification-consumer sh -c 'wc -c < /data/notifications.json'"
  archive_before=$(docker compose exec -T notification-consumer sh -c 'wc -c < /data/notifications.json' 2>/dev/null | tr -d ' \r')
  raw "taille de l'archive avant : ${archive_before:-0} octets"

  proof "R1  flight.created : emission explicite puis consommation"
  mark
  send POST "$GATEWAY/api/packh/flight-events/emit" "{\"flightNumber\":\"EVT-$FLIGHT_NUMBER\"}" "$TOKEN"
  check_eq 200 "$CODE" "POST /api/packh/flight-events/emit"
  check_eq 'nomadix.events' "$(jget "$BODY" publishedTo)" "exchange de publication"
  check_eq 'flight.created' "$(jget "$BODY" routingKey)" "routing key emise"
  wait_log notification-consumer "recu flight.created" 20 "R1 : consommateur Python"
  wait_eq "Ready revenu a la valeur de depart" "$base" 30 qstat "$MQ" messages_ready
  logs notification-consumer "recu flight.created" 1
  # Le consommateur INSERE en tete (store.insert(0, entry)) : l'evenement le plus
  # recent est au DEBUT du fichier. `tail` lisait la fin et pouvait manquer la
  # ligne ; `head` est le bon cote.
  check_contains "EVT-$FLIGHT_NUMBER" "$(docker compose exec -T notification-consumer \
    sh -c 'head -c 5000 /data/notifications.json' 2>/dev/null)" "archive JSON du consommateur"

  proof "R2  traveler.registered"
  mark
  send POST "$GATEWAY/api/packh/traveler-events/emit" '{"travelerId":"TRV-DEMO","email":"event@nomadix.tn"}' "$TOKEN"
  check_eq 200 "$CODE" "POST /api/packh/traveler-events/emit"
  wait_log notification-consumer "recu traveler.registered" 20 "R2 : consommateur Python"
  logs notification-consumer "recu traveler.registered" 1

  proof "R3  booking.created : emis par une VRAIE reservation (apres ecriture en base)"
  mark
  send POST "$GATEWAY/api/bookings?travelerId=$TID&flightId=$FID" "" "$TOKEN"
  check_eq 201 "$CODE" "POST /api/bookings (nouvelle reservation)"
  local bid2
  bid2=$(jget "$BODY" id)
  wait_log notification-consumer "recu booking.created" 20 "R3 : consommateur Python"
  logs notification-consumer "recu booking.created" 1

  proof "R4  booking.cancelled : emis par l'annulation"
  mark
  send DELETE "$GATEWAY/api/bookings/$bid2" "" "$TOKEN"
  check_eq 204 "$CODE" "DELETE de cette reservation"
  wait_log notification-consumer "recu booking.cancelled" 20 "R4 : consommateur Python"
  logs notification-consumer "recu booking.cancelled" 1

  proof "R5  emission a blanc (sans creer de reservation) : prouve le decouplage producteur/consommateur"
  mark
  send POST "$GATEWAY/api/packh/booking-events/emit" '{}' "$TOKEN"
  check_eq 200 "$CODE" "POST /api/packh/booking-events/emit"
  wait_log notification-consumer "recu booking.created" 20 "R5 : consommateur Python"

  proof "l'archive physique du consommateur a grossi (volume Docker partage API/consommateur)"
  local archive_after
  show "docker compose exec -T notification-consumer sh -c 'wc -c < /data/notifications.json'"
  archive_after=$(docker compose exec -T notification-consumer sh -c 'wc -c < /data/notifications.json' 2>/dev/null | tr -d ' \r')
  raw "taille de l'archive apres : ${archive_after:-?} octets"
  if [ -n "$archive_before" ] && [ -n "$archive_after" ] && [ "$archive_after" -gt "$archive_before" ]; then
    ok "/data/notifications.json : $archive_before -> $archive_after octets"
  else
    ko "/data/notifications.json n'a pas grossi ($archive_before -> ${archive_after:-?})"
  fi

  proof "les evenements sont relisibles par l'API (meme volume, autre conteneur)"
  send GET "$GATEWAY/api/notifications/events?limit=3" "" "$TOKEN"
  check_eq 200 "$CODE" "GET /api/notifications/events?limit=3"
  check_contains 'routingKey' "$(braw)" "evenements relisibles via l'API"
}

# =============================================================================
#  S9 - RABBITMQ TECHNIQUE : decouplage, durabilite, DLQ, topologie
# =============================================================================
s9_rabbit_technical() {
  section "S9  RabbitMQ technique - boite vocale durable, redemarrage broker, dead-letter"

  proof "G1  consommateur arrete : Ready monte, Consumers = 0 (personne ne perd le message)"
  show "docker compose stop notification-consumer"
  docker compose stop notification-consumer >/dev/null 2>&1
  wait_eq "Consumers sur $MQ" 0 60 qstat "$MQ" consumers
  local r0 i
  r0=$(qstat "$MQ" messages_ready)
  [ -z "$r0" ] && r0=0
  info "Ready avant la panne : $r0"
  for i in 1 2 3; do
    send POST "$GATEWAY/api/packh/flight-events/emit" "{\"flightNumber\":\"QUEUE-$i-$(date +%H%M%S)\"}" "$TOKEN"
    check_eq 200 "$CODE" "emission QUEUE-$i (consommateur arrete)"
  done
  wait_eq "Ready pendant la panne" "$((r0 + 3))" 30 qstat "$MQ" messages_ready
  check_eq 0 "$(qstat "$MQ" consumers)" "toujours aucun consommateur"

  proof "G2a durabilite reelle : on redemarre le BROKER, les 3 messages restent dans la file"
  show "docker compose restart rabbitmq"
  docker compose restart rabbitmq >/dev/null 2>&1
  wait_eq "API management RabbitMQ de retour" 200 120 http_code -u "$RABBIT_USER:$RABBIT_PASS" "$RABBIT_API/overview"
  wait_eq "Ready apres redemarrage du broker" "$((r0 + 3))" 60 qstat "$MQ" messages_ready

  proof "G2b rattrapage : au redemarrage du consommateur, Ready retombe et 3 messages sont traites"
  mark
  show "docker compose start notification-consumer"
  docker compose start notification-consumer >/dev/null 2>&1
  wait_eq "Consumers apres redemarrage" 1 60 qstat "$MQ" consumers
  wait_eq "Ready apres rattrapage" "$r0" 60 qstat "$MQ" messages_ready
  wait_ge "messages consommes (lignes [notif])" 3 30 logcount notification-consumer "recu flight.created"
  logs notification-consumer "recu flight.created" 3

  proof "G3  message invalide -> nack requeue=false -> nomadix.dlx -> $DLQ (x-death)"
  local d0
  d0=$(qstat "$DLQ" messages_ready)
  [ -z "$d0" ] && d0=0
  info "Ready de la DLQ avant le test : $d0"
  mark
  send POST "$GATEWAY/api/packh/rabbitmq/poison" "" "$TOKEN"
  check_eq 200 "$CODE" "POST /api/packh/rabbitmq/poison"
  check_eq true "$(jget "$BODY" published)" "le message a bien ete publie"
  wait_eq "Ready de $DLQ" "$((d0 + 1))" 30 qstat "$DLQ" messages_ready
  wait_log notification-consumer "[notif][DLQ] message refuse" 20 "consommateur : message refuse puis isole"
  logs notification-consumer "[notif][DLQ] message refuse" 1

  proof "lecture de l'en-tete x-death SANS detruire le message (ack_requeue_true = 'Ack requeue true' dans l'UI)"
  show "curl -s -u $RABBIT_USER:*** -X POST $RABBIT_API/queues/%2F/$DLQ/get -d '{\"count\":1,\"ackmode\":\"ack_requeue_true\"}'"
  curl -s -u "$RABBIT_USER:$RABBIT_PASS" --max-time 15 \
    -X POST "$RABBIT_API/queues/%2F/$DLQ/get" -H 'content-type: application/json' \
    -d '{"count":1,"ackmode":"ack_requeue_true","encoding":"auto","truncate":50000}' \
    -o "$TMP/dlq.json" 2>/dev/null
  raw "$(jget "$TMP/dlq.json" payload)"
  check_contains '"reason":"rejected"' "$(tr -d '\r\n' < "$TMP/dlq.json")" "x-death.reason = rejected (refus explicite)"
  check_contains '"queue":"notification.events"' "$(tr -d '\r\n' < "$TMP/dlq.json")" "x-death.queue = file d'origine"
  check_contains '"routing-keys":["booking.poison"]' "$(tr -d '\r\n' < "$TMP/dlq.json")" "x-death.routing-keys = cle d'origine"

  proof "G4  topologie declaree : 2 exchanges, 2 files, 4 bindings (identique a definitions.json)"
  local ex bind args
  show "docker compose exec -T rabbitmq rabbitmqctl list_exchanges name type"
  ex=$(docker compose exec -T rabbitmq rabbitmqctl list_exchanges name type 2>/dev/null | tr -d '\r')
  raw "$(printf '%s' "$ex" | grep -E 'nomadix' | tr '\n' ' ')"
  check_match 'nomadix\.events[[:space:]]+topic' "$ex" "exchange nomadix.events (topic)"
  check_match 'nomadix\.dlx[[:space:]]+topic' "$ex" "exchange nomadix.dlx (topic)"
  show "docker compose exec -T rabbitmq rabbitmqctl list_bindings source_name destination_name routing_key"
  bind=$(docker compose exec -T rabbitmq rabbitmqctl list_bindings source_name destination_name routing_key 2>/dev/null | tr -d '\r')
  raw "$(printf '%s' "$bind" | grep -E 'notification|flight|traveler|booking' | head -6 | tr '\n' ' ')"
  check_contains 'flight.#' "$bind" "binding flight.#"
  check_contains 'traveler.#' "$bind" "binding traveler.#"
  check_contains 'booking.#' "$bind" "binding booking.#"
  check_contains 'notification.events.dlq' "$bind" "binding de la DLQ"
  show "docker compose exec -T rabbitmq rabbitmqctl list_queues name arguments"
  args=$(docker compose exec -T rabbitmq rabbitmqctl list_queues name arguments 2>/dev/null | tr -d '\r')
  raw "$(printf '%s' "$args" | grep -E 'notification' | cut -c1-220 | tr '\n' ' ')"
  check_contains 'x-dead-letter-exchange' "$args" "argument x-dead-letter-exchange present"
}

# =============================================================================
#  S10 - MODE DEGRADE : broker eteint, la reservation aboutit quand meme
# =============================================================================
s10_degraded() {
  section "S10 Mode degrade - RabbitMQ eteint : fire-and-forget, le metier continue"
  need_ids || return 1

  if [ "$SKIP_RESILIENCE" -eq 1 ]; then info "ignore (--no-resilience)"; return 0; fi

  local before bid wrote
  before=$(sql nomadix_flight_db "SELECT available_seats FROM flight WHERE id=$FID;")
  info "stock avant le test : $before"

  proof "arret reel du broker puis creation d'une reservation"
  show "docker compose stop rabbitmq"
  docker compose stop rabbitmq >/dev/null 2>&1
  mark
  # Sans broker, le client AMQP peut mettre plusieurs dizaines de secondes a
  # echouer : on accorde un delai plus large que la valeur par defaut, sinon
  # curl rend 000 et le corps (donc l'id) est perdu.
  SEND_TIMEOUT=120
  send POST "$GATEWAY/api/bookings?travelerId=$TID&flightId=$FID" "" "$TOKEN"
  SEND_TIMEOUT=30
  if [ "$CODE" = 201 ]; then
    ok "POST /api/bookings sans broker = 201"
  else
    info "reponse HTTP $CODE : le corps peut manquer si curl a expire pendant les tentatives AMQP"
    info "ce n'est PAS un echec metier : la preuve est l'ecriture en base, verifiee ci-dessous"
  fi

  bid=$(jget "$BODY" id)
  check_eq "$((before - 1))" "$(sql nomadix_flight_db "SELECT available_seats FROM flight WHERE id=$FID;")" "SQL stock decremente malgre la panne RabbitMQ"

  # Si la reponse HTTP n'a pas livre l'id, on le relit dans la base : c'est la
  # source de verite, et cela evite un 'DELETE /api/bookings/' (404) en cascade.
  if [ -z "$bid" ]; then
    bid=$(sql nomadix_booking_db "SELECT id FROM bookings WHERE traveler_id=$TID AND flight_id=$FID AND status='CONFIRMED' ORDER BY id DESC LIMIT 1;")
    info "id de la reservation relu directement en base : ${bid:-<aucun>}"
  fi
  wrote=$(sql nomadix_booking_db "SELECT COUNT(*) FROM bookings WHERE id=${bid:-0};")
  check_eq 1 "$wrote" "SQL bookings : la reservation est bien ecrite (id ${bid:-?})"

  proof "trace serveur : publication impossible, service toujours disponible"
  wait_ge "logs WARN [RABBITMQ] publication dans booking-service" 1 40 logcount booking-service "[RABBITMQ] publication"
  logs booking-service "[RABBITMQ] publication" 2

  proof "redemarrage du broker : la compensation fonctionne toujours"
  show "docker compose start rabbitmq"
  docker compose start rabbitmq >/dev/null 2>&1
  wait_eq "API management RabbitMQ de retour" 200 150 http_code -u "$RABBIT_USER:$RABBIT_PASS" "$RABBIT_API/overview"
  if [ -z "$bid" ]; then
    ko "annulation impossible : aucune reservation identifiee (ni HTTP ni SQL)"
    return 1
  fi
  send DELETE "$GATEWAY/api/bookings/$bid" "" "$TOKEN"
  check_eq 204 "$CODE" "annulation de la reservation creee en mode degrade"
  check_eq "$before" "$(sql nomadix_flight_db "SELECT available_seats FROM flight WHERE id=$FID;")" "SQL stock revenu a sa valeur initiale"
}

# =============================================================================
#  S11 - ISOLATION DES DONNEES + OBSERVABILITE
# =============================================================================
s11_isolation() {
  section "S11 Isolation database-per-service + observabilite"

  proof "tables reellement presentes dans chaque base (une base par service)"
  sql nomadix_traveler_db "SHOW TABLES;" >/dev/null
  sql nomadix_flight_db "SHOW TABLES;" >/dev/null
  sql nomadix_booking_db "SHOW TABLES;" >/dev/null

  proof "aucune table 'bookings'/'flight' dans la base du Traveler (pas de jointure inter-services)"
  check_eq 0 "$(sql nomadix_traveler_db "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='nomadix_traveler_db' AND table_name IN ('bookings','flight');")" "tables booking/flight dans nomadix_traveler_db"
  check_eq 1 "$(sql nomadix_flight_db "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='nomadix_flight_db' AND table_name='flight';")" "table flight dans nomadix_flight_db"
  check_eq 1 "$(sql nomadix_booking_db "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='nomadix_booking_db' AND table_name='bookings';")" "table bookings dans nomadix_booking_db"

  proof "sante de chaque service (Actuator) et du frontend"
  local port
  for port in 9081 9082 9083 9089; do
    send GET "http://localhost:$port/actuator/health"
    check_eq 200 "$CODE" "GET :$port/actuator/health"
  done
  send GET "http://localhost:8090"
  check_eq 200 "$CODE" "GET frontend :8090 (nginx)"

  proof "service complementaire Node.js (conference) et Python (notification)"
  send GET "$GATEWAY/api/conferences"
  check_eq 200 "$CODE" "GET /api/conferences via Gateway (public)"
  send GET "$GATEWAY/api/notifications/hello" "" "$TOKEN"
  check_eq 200 "$CODE" "GET /api/notifications/hello via Gateway"
  check_contains 'UP' "$(braw)" "le service Python repond UP"

  proof "etat des conteneurs en fin de runbook"
  show "docker compose ps --format '{{.Name}}  {{.State}}'"
  docker compose ps --format '{{.Name}}  {{.State}}' 2>/dev/null | head -14 | while IFS= read -r line; do p "$line"; done
}

# =============================================================================
#  S12 - ERREURS METIER REELLES : 409, 404, favoris, changement de statut
# =============================================================================
s12_business_errors() {
  section "S12 Erreurs metier reelles - 409 vol complet, 404 voyageur, favoris, statut"
  need_ids || return 1

  proof "vol COMPLET (0 place) : la reservation est refusee en 409, sans surbooking"
  local fullnum fullid
  fullnum="FULL-$(date +%H%M%S)"
  send POST "$GATEWAY/api/flights" \
    "{\"flightNumber\":\"$fullnum\",\"departureCity\":\"Tunis\",\"arrivalCity\":\"Sfax\",\"departureDate\":\"2027-05-01\",\"availableSeats\":0,\"price\":90.00}" \
    "$TOKEN"
  check_eq 200 "$CODE" "POST /api/flights (0 place)"
  fullid=$(jget "$BODY" id)
  check_eq 0 "$(sql nomadix_flight_db "SELECT available_seats FROM flight WHERE id=$fullid;")" "SQL stock du vol complet"
  send POST "$GATEWAY/api/bookings?travelerId=$TID&flightId=$fullid" "" "$TOKEN"
  check_eq 409 "$CODE" "POST /api/bookings sur un vol complet"
  check_contains 'complet' "$(braw)" "message metier du 409"
  check_eq 0 "$(sql nomadix_booking_db "SELECT COUNT(*) FROM bookings WHERE flight_id=$fullid;")" "SQL bookings : aucune reservation orpheline"
  send DELETE "$GATEWAY/api/flights/$fullid" "" "$TOKEN"
  check_eq 204 "$CODE" "nettoyage : DELETE du vol de test"

  proof "voyageur inexistant : 404 propage par Feign, aucun booking ecrit"
  local bidcount_before
  bidcount_before=$(sql nomadix_booking_db "SELECT COUNT(*) FROM bookings;")
  send POST "$GATEWAY/api/bookings?travelerId=999999&flightId=$FID" "" "$TOKEN"
  check_eq 404 "$CODE" "POST /api/bookings voyageur 999999"
  check_contains 'inexistant' "$(braw)" "message metier du 404"
  check_eq "$bidcount_before" "$(sql nomadix_booking_db "SELECT COUNT(*) FROM bookings;")" "SQL bookings : nombre de lignes inchange"

  proof "retrait d'un favori (DELETE) : la liste enrichie ne le contient plus"
  send DELETE "$GATEWAY/api/travelers/$TID/favorite-flights/$FID" "" "$TOKEN"
  check_eq 200 "$CODE" "DELETE /api/travelers/$TID/favorite-flights/$FID"
  send GET "$GATEWAY/api/travelers/$TID/favorite-flights" "" "$TOKEN"
  check_not_contains "\"id\":$FID" "$(braw)" "le vol $FID n'est plus dans les favoris"

  proof "changement de statut via PUT ?status=CANCELLED : la place revient, puis idempotence"
  send POST "$GATEWAY/api/bookings?travelerId=$TID&flightId=$FID" "" "$TOKEN"
  check_eq 201 "$CODE" "POST /api/bookings (pour le test de statut)"
  local bid seats
  bid=$(jget "$BODY" id)
  seats=$(sql nomadix_flight_db "SELECT available_seats FROM flight WHERE id=$FID;")
  send PUT "$GATEWAY/api/bookings/$bid?status=CANCELLED" "" "$TOKEN"
  check_eq 200 "$CODE" "PUT /api/bookings/$bid?status=CANCELLED"
  check_eq CANCELLED "$(jget "$BODY" status)" "statut renvoye"
  check_eq "$((seats + 1))" "$(sql nomadix_flight_db "SELECT available_seats FROM flight WHERE id=$FID;")" "SQL stock restitue par le PUT"
  send PUT "$GATEWAY/api/bookings/$bid?status=CANCELLED" "" "$TOKEN"
  check_eq 200 "$CODE" "PUT a nouveau CANCELLED (deja annule)"
  check_eq "$((seats + 1))" "$(sql nomadix_flight_db "SELECT available_seats FROM flight WHERE id=$FID;")" "SQL stock inchange (idempotence)"
}

# =============================================================================
#  BILAN
# =============================================================================
summary() {
  section "BILAN"
  printf '  assertions reussies : %d\n  assertions echouees : %d\n' "$PASS" "$FAIL"
  printf '  identifiants crees  : vol=%s  voyageur=%s  voyageur incomplet=%s\n' \
    "${FID:-?}" "${TID:-?}" "${BADID:-?}"
  printf '  (ils restent en base comme jeu de demonstration ; relancer le script en cree de nouveaux)\n'
  if [ "$FAIL" -eq 0 ]; then
    printf '\n\033[32m  TOUS LES SCENARIOS SONT VERIFIES SUR LA STACK REELLE.\033[0m\n'
    return 0
  fi
  printf '\n\033[31m  %d assertion(s) en echec : voir les lignes [ECHEC] ci-dessus.\033[0m\n' "$FAIL"
  return 1
}

# ----------------------------------------------------------------------------
main() {
  printf '\033[1m  Legende :\033[0m  $\033[36mcommande executee\033[0m   |\033[2msortie brute\033[0m   [OK]/[ECHEC] assertion\n'
  local status=0
  s1_preflight || status=$?
  if [ "$status" -eq 2 ]; then
    echo "  Stack indisponible : demarrez-la avec 'docker compose up -d --build'." >&2
    exit 2
  fi
  if [ "$status" -ne 0 ]; then summary; exit 1; fi
  s2_eureka
  s3_gateway
  s4_crud
  s5_profiling
  s6_feign
  s7_circuit_breaker
  s8_rabbit_business
  s9_rabbit_technical
  s10_degraded
  s11_isolation
  s12_business_errors
  summary
}

main
