#!/usr/bin/env bash
# =============================================================================
#  Nomadix - DIAGNOSTIC DE L'ACCES AUX REGISTRES DOCKER
# -----------------------------------------------------------------------------
#  A lancer quand 'docker compose up -d --build' echoue avec :
#
#      failed to resolve source metadata for docker.io/...
#      tls: failed to verify certificate:
#      x509: certificate is not valid for any names, but wanted to match ...
#
#  Cette erreur ne vient JAMAIS du projet : le build demande une image publique
#  (maven, node, python, nginx) et la chaine TLS entre WSL et le registre est
#  interceptee. Le script determine laquelle des quatre causes est la bonne :
#
#    1. WSL / Docker Desktop desynchronise (incident reseau, cf. les lignes
#       'UtilAcceptVsock ... accept4 failed 110' qui precedent l'erreur)
#    2. un proxy sortant configure (variables d'environnement ou Docker Desktop)
#    3. un antivirus / pare-feu qui inspecte le HTTPS (certificat sans SAN)
#    4. un miroir de registre casse declare dans le daemon
#
#  Usage :
#      bash documentation/scripts/diagnose-docker-registry.sh
#      bash documentation/scripts/diagnose-docker-registry.sh registry-1.docker.io
#
#  Code de sortie : 0 si la chaine TLS est saine (le build peut marcher),
#                   1 si une interception est detectee (le build echouera).
# =============================================================================
set -uo pipefail

REGISTRY="${1:-registry-1.docker.io}"
FRONTEND_IMAGE="docker.io/docker/dockerfile:1.7"

CAUSE_WSL=0
CAUSE_PROXY=0
CAUSE_MITM=0
CAUSE_MIRROR=0

section() { printf '\n\033[1m=== %s ===\033[0m\n' "$*"; }
ok()      { printf '  [OK]    %s\n' "$*"; }
ko()      { printf '  [ALERTE] %s\n' "$*"; }
info()    { printf '  ..      %s\n' "$*"; }
fix()     { printf '           -> %s\n' "$*"; }

# -----------------------------------------------------------------------------
section "0  Environnement"
# -----------------------------------------------------------------------------
if [ -r /proc/version ] && grep -qi microsoft /proc/version; then
  ok "shell WSL detecte (c'est le bon endroit pour lancer docker compose)"
else
  info "shell non-WSL : le diagnostic reste valable si docker y est installe"
fi

if command -v docker >/dev/null 2>&1; then
  ok "client docker present ($(docker --version 2>/dev/null | cut -d, -f1))"
  DOCKER=1
else
  ko "client docker introuvable dans ce shell : le build ne peut pas partir d'ici"
  fix "lancer Docker Desktop, puis executer ce script depuis WSL (pas PowerShell)"
  DOCKER=0
fi

# -----------------------------------------------------------------------------
section "1  Nom DNS et joignabilite de $REGISTRY"
# -----------------------------------------------------------------------------
DNS=""
if command -v getent >/dev/null 2>&1; then
  DNS=$(getent ahostsv4 "$REGISTRY" 2>/dev/null | awk 'NR==1{print $1}')
fi
if [ -z "$DNS" ] && [ -r /etc/hosts ]; then
  DNS=$(grep -m1 "[[:space:]]$REGISTRY\$" /etc/hosts 2>/dev/null | awk '{print $1}')
fi
if [ -n "${DNS:-}" ]; then
  ok "resolution : $REGISTRY -> $DNS"
  case "$DNS" in
    127.*|10.*|192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[01].*)
      ko "l'adresse est privee : un proxy local ou un blocage DNS detourne le registre"
      CAUSE_MITM=1
      fix "desactiver le proxy/mirror local (voir sections 3 et 4)"
      ;;
  esac
elif command -v getent >/dev/null 2>&1; then
  ko "resolution impossible de $REGISTRY"
  fix "verifier /etc/resolv.conf dans WSL, puis 'wsl --shutdown' et relancer Docker Desktop"
  CAUSE_WSL=1
else
  info "resolution non verifiable ici (ni getent ni entree /etc/hosts) : le test TLS ci-dessous tranche"
fi

# -----------------------------------------------------------------------------
section "2  Chaine TLS presentee par le registre"
# -----------------------------------------------------------------------------
if command -v curl >/dev/null 2>&1; then
  CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "https://$REGISTRY/v2/" 2>/dev/null)
  ERR=$(curl -sS -o /dev/null --max-time 20 "https://$REGISTRY/v2/" 2>&1 >/dev/null | head -2)
  # 401 = TLS valide, authentification demandee : c'est le resultat NORMAL.
  case "${CODE:-000}" in
    401|200) ok "chaine TLS valide (HTTP $CODE sur /v2/, 401 attendu sans jeton)" ;;
    *)
      ko "appel HTTPS en echec (code '${CODE:-000}')"
      [ -n "${ERR:-}" ] && info "curl : $(printf '%s' "$ERR" | tr '\n' ' ' | cut -c1-200)"
      case "$ERR" in
        *certificate*|*SSL*|*x509*) CAUSE_MITM=1 ;;
        *) CAUSE_WSL=1 ;;
      esac
      ;;
  esac
else
  info "curl absent : test TLS par openssl seulement"
fi

if command -v openssl >/dev/null 2>&1; then
  CERT=$(openssl s_client -connect "$REGISTRY:443" -servername "$REGISTRY" </dev/null 2>/dev/null \
         | openssl x509 -noout -subject -issuer -ext subjectAltName 2>/dev/null)
  if [ -n "$CERT" ]; then
    printf '%s\n' "$CERT" | sed 's/^/           /' | head -8
    if printf '%s' "$CERT" | grep -qE "DNS:\*?\.?$REGISTRY|DNS:\*\.docker\.io|DNS:docker\.io"; then
      ok "le certificat couvre bien $REGISTRY (pas d'interception TLS)"
    else
      ko "le certificat ne couvre PAS $REGISTRY : interception TLS confirmee"
      info "signature '${CERT%%$'\n'*}'"
      CAUSE_MITM=1
      fix "un antivirus/pare-feu (scan HTTPS) ou un proxy d'entreprise dechiffre le trafic"
    fi
  else
    ko "poignee de main TLS impossible : sortie reseau bloquee ou WSL desynchronise"
    CAUSE_WSL=1
  fi
else
  info "openssl absent du shell : installer ca-certificates pour le test detaille"
fi

# -----------------------------------------------------------------------------
section "3  Proxys visibles depuis le shell"
# -----------------------------------------------------------------------------
PROXY_VARS=$(env | grep -iE '^(http|https|ftp|all|no)_proxy=' 2>/dev/null)
if [ -n "$PROXY_VARS" ]; then
  ko "variables de proxy definies dans ce shell :"
  printf '%s\n' "$PROXY_VARS" | sed 's/^/           /'
  CAUSE_PROXY=1
  fix "soit retirer ces variables ('unset http_proxy https_proxy all_proxy')"
  fix "soit ajouter le certificat du proxy dans /usr/local/share/ca-certificates + update-ca-certificates"
else
  ok "aucune variable *_proxy dans le shell"
fi

if command -v netsh.exe >/dev/null 2>&1; then
  WINPROXY=$(netsh.exe winhttp show proxy 2>/dev/null | tr -d '\r')
  if printf '%s' "$WINPROXY" | grep -qi 'proxy server'; then
    info "proxy systeme Windows configure (il n'affecte pas WSL, mais Docker Desktop le lit) :"
    printf '%s\n' "$WINPROXY" | sed 's/^/           /' | head -5
    CAUSE_PROXY=1
    fix "Docker Desktop > Settings > Resources > Proxies : 'No proxy' (ou le bon proxy + son certificat)"
  fi
fi

# -----------------------------------------------------------------------------
section "4  Configuration du daemon (miroirs et proxy)"
# -----------------------------------------------------------------------------
if [ "${DOCKER:-0}" -eq 1 ]; then
  DINFO=$(docker info 2>/dev/null | tr -d '\r')
  MIRRORS=$(printf '%s\n' "$DINFO" | grep -i -A3 'Registry Mirrors' | grep -E 'https?://' | tr -d ' -')
  if [ -n "${MIRRORS:-}" ]; then
    ko "miroir(s) de registre declare(s) :"
    printf '%s\n' "$MIRRORS" | sed 's/^/           /'
    CAUSE_MIRROR=1
    fix "Docker Desktop > Settings > Docker Engine : retirer 'registry-mirrors' puis Apply & Restart"
  else
    ok "aucun miroir de registre declare"
  fi

  DPROXY=$(printf '%s\n' "$DINFO" | grep -i -A4 '^ *HTTP Proxy' | tr -d '\r')
  if [ -n "${DPROXY:-}" ]; then
    info "proxy cote daemon :"
    printf '%s\n' "$DPROXY" | sed 's/^/           /' | head -6
    CAUSE_PROXY=1
  fi

  section "5  Le daemon sait-il telecharger une image ?"
  if docker pull hello-world >/dev/null 2>&1; then
    ok "docker pull fonctionne : la couche daemon est saine"
    info "si le build echoue malgre cela, il s'agit de BuildKit (voir section 6)"
  else
    ko "docker pull hello-world echoue aussi : le blocage touche tout le daemon"
    info "reproduire l'erreur exacte : docker pull $FRONTEND_IMAGE"
  fi
fi

# -----------------------------------------------------------------------------
section "6  Corrections, dans l'ordre (de la moins invasive a la plus)"
# -----------------------------------------------------------------------------
printf '  1. Relancer la pile reseau (resout la majorite des cas) :\n'
printf '         wsl --shutdown            (depuis PowerShell)\n'
printf '         puis rouvrir Docker Desktop, attendre "Engine running"\n'
printf '         docker pull hello-world   (doit afficher "Pull complete")\n\n'
printf '     Si la resolution DNS de registry-1.docker.io echoue : ajouter\n'
printf '     "nameserver 1.1.1.1" dans /etc/resolv.conf, puis relancer.\n\n'
printf '  2. Couper toute inspection HTTPS : antivirus (Kaspersky, ESET, Avast,\n'
printf "     Bitdefender, Zscaler) et proxy d'entreprise, puis relancer le build.\n\n"
printf '  3. Docker Desktop > Settings > Resources > Proxies : "No proxy"\n'
printf '     (ou le proxy reel + son certificat importe).\n\n'
printf '  4. Docker Desktop > Settings > Docker Engine : aucun "registry-mirrors".\n\n'
printf '  5. Build sans BuildKit (contourne la recuperation de docker/dockerfile:1.7) :\n'
printf '         DOCKER_BUILDKIT=0 docker compose build flight-service\n\n'

section "VERDICT"
if [ "$CAUSE_MITM" -eq 1 ]; then
  printf '  Interception TLS detectee : corrige le point 2 (ou 3) ci-dessus.\n'
  exit 1
elif [ "$CAUSE_WSL" -eq 1 ]; then
  printf "  Pile reseau WSL/Docker a remettre d'aplomb : corrige le point 1.\n"
  exit 1
elif [ "$CAUSE_MIRROR" -eq 1 ]; then
  printf '  Miroir de registre en cause : corrige le point 4.\n'
  exit 1
elif [ "$CAUSE_PROXY" -eq 1 ]; then
  printf '  Proxy en cause : corrige le point 3 (et le certificat associe).\n'
  exit 1
else
  printf '  Aucune interception : la chaine TLS est saine, le build peut partir.\n'
  printf '  Relancer : docker compose up -d --build\n'
  exit 0
fi
