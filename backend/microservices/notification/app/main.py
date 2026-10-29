"""Notification microservice - entry point.

Run:  uvicorn app.main:app --reload --port 8084
  or: python -m app.main
"""
import asyncio
import os
import socket
import sys

import httpx
from fastapi import FastAPI
from fastapi.openapi.docs import get_swagger_ui_html, get_swagger_ui_oauth2_redirect_html
from fastapi.openapi.utils import get_openapi
from fastapi.responses import HTMLResponse
from app.routers import notification
import py_eureka_client.eureka_client as eureka_client

# Console Windows (cp1252) : evite UnicodeEncodeError sur les messages accentues.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

PORT = int(os.getenv("PORT", "8084"))
APP_NAME = "NOTIFICATION-SERVICE"
EUREKA_SERVER = os.getenv("EUREKA_DEFAULT_ZONE", "http://localhost:8761/eureka/")
# Nom publie dans Eureka : en conteneur, le nom du service Docker. Les autres
# conteneurs (la Gateway) ne peuvent joindre ni 'localhost' ni une IP 172.20.x.x :
# le routage lb://NOTIFICATION-SERVICE doit donc utiliser un nom resolvable.
INSTANCE_HOST = os.getenv("EUREKA_INSTANCE_HOSTNAME", "localhost")
try:
    INSTANCE_IP = os.getenv("EUREKA_INSTANCE_IP") or socket.gethostbyname(socket.gethostname())
except Exception:
    INSTANCE_IP = "127.0.0.1"
# Identifiant lisible dans le dashboard Eureka (py_eureka_client utilisait
# <ip>:<app>:<port>, ce qui produisait une adresse IP au lieu d'un nom de service).
INSTANCE_ID = os.getenv("EUREKA_INSTANCE_ID", f"{INSTANCE_HOST}:{PORT}")
# Adresse PUBLIEE (port hote) : le dashboard Eureka est ouvert depuis le navigateur
# du poste hote ; elle ne sert pas au routage interne.
PUBLIC_URL = os.getenv("EUREKA_PUBLIC_URL", f"http://localhost:{PORT}").rstrip("/")

# Keycloak : le bouton "Authorize" du Swagger utilise le client public
# nomadix-swagger (Authorization Code + PKCE). Le jeton obtenu sert a appeler
# l'API via la Gateway ("http://localhost:9089"), qui exige un JWT.
KEYCLOAK_URL = os.getenv("NOMADIX_KEYCLOAK_URL", "http://localhost:8180").rstrip("/")
KEYCLOAK_REALM = os.getenv("NOMADIX_KEYCLOAK_REALM", "nomadix")
OIDC_BASE = f"{KEYCLOAK_URL}/realms/{KEYCLOAK_REALM}/protocol/openid-connect"
SWAGGER_CLIENT_ID = os.getenv("NOMADIX_SWAGGER_CLIENT_ID", "nomadix-swagger")

# Bouton "Authorize" : Authorization Code + PKCE sur le client public
# nomadix-swagger.
SWAGGER_INIT_OAUTH = {
    "clientId": SWAGGER_CLIENT_ID,
    "appName": "Nomadix Notification Service",
    "usePkceWithAuthorizationCodeGrant": True,
    "scopes": "openid profile email",
}
SWAGGER_PARAMETERS = {
    "persistAuthorization": True,
    "displayRequestDuration": True,
}
# Page de retour OAuth2. Elle DOIT etre servie par ce service (meme origine que
# le Swagger) et declaree a l'identique dans Keycloak :
# http://localhost:9084/swagger-ui/oauth2-redirect

# py_eureka_client abandonne le renouvellement apres un timeout transitoire (il
# purge son cache d'URL serveur et n'en recupere pas d'autre) : l'instance est
# alors evincee du registre au bout de duration_in_secs et la Gateway repond 503.
# Ce superviseur la remet en ligne automatiquement.
REGISTRY_CHECK_INTERVAL_SECONDS = int(os.getenv("EUREKA_CHECK_INTERVAL_SECONDS", "20"))

app = FastAPI(
    title="Nomadix Notification Service API",
    version="2.0.0",
    description=(
        "Notifications voyageurs (Python / FastAPI, stockage memoire).\n\n"
        "Le service est toujours appele via l'API Gateway "
        "(http://localhost:9089/api/notifications en Docker Compose, "
        "http://localhost:8089 hors Docker) qui exige un jeton Keycloak.\n\n"
        "## Consommateur RabbitMQ et DLQ\n"
        "Le script `packh_rabbit_consumer.py` ecoute la file durable "
        "`notification.events` (bindings `flight.#`, `traveler.#`, `booking.#`).\n\n"
        "- message valide : enregistre dans `notifications.json` puis `basic_ack`\n"
        "  (relisibles via `GET /api/notifications/events`, volume partage) ;\n"
        "- message invalide ou `simulateFailure: true` : `basic_nack(requeue=false)`,\n"
        "  RabbitMQ le deplace via l'exchange `nomadix.dlx` vers la file\n"
        "  `notification.events.dlq` (en-tete `x-death`).\n\n"
        "Console de gestion : http://localhost:15672 (nomadix / nomadix123)."
        "Les scenarios complets sont documentes dans le Swagger du Booking-Service."
    ),
    contact={"name": "Equipe Nomadix"},
    openapi_tags=[
        {
            "name": "Notifications",
            "description": "Creation, consultation et suppression des notifications simulees.",
        }
    ],
    # Le premier serveur est celui selectionne par defaut dans l'UI : en Docker
    # Compose la Gateway est publiee sur 9089 (8089 n'est pas expose cote hote).
    servers=[
        {"url": "http://localhost:9089", "description": "Via l API Gateway (Docker Compose - recommande)"},
        {"url": "http://localhost:9084", "description": "Acces direct au conteneur notification"},
        {"url": "http://localhost:8089", "description": "Via l API Gateway (services hors Docker)"},
        {"url": f"http://localhost:{PORT}", "description": "Acces direct au service (dev local)"},
    ],
    # La page Swagger est servie par les routes explicites ci-dessous : avec
    # docs_url par defaut, FastAPI generait un retour OAuth2 vers
    # /docs/oauth2-redirect (inexistant) au lieu de /swagger-ui/oauth2-redirect.
    docs_url=None,
    openapi_url="/v3/api-docs",
    redoc_url="/redoc",
)


def custom_openapi():
    """Ajoute le schema de securite Keycloak (bouton Authorize du Swagger).

    Sans cette declaration, l'UI n'affiche aucun bouton Authorize : le service
    Python ne valide pas le jeton lui-meme, mais le Swagger doit pouvoir en
    envoyer un pour appeler la Gateway (`/api/notifications`, JWT obligatoire).
    """
    if app.openapi_schema:
        return app.openapi_schema
    schema = get_openapi(
        title=app.title,
        version=app.version,
        description=app.description,
        routes=app.routes,
        tags=app.openapi_tags,
        contact=app.contact,
    )
    schema.setdefault("components", {}).setdefault("securitySchemes", {})["keycloak"] = {
        "type": "oauth2",
        "description": (
            "Keycloak (realm nomadix). Comptes de test : admin/admin123 (ADMIN), "
            "sami/sami123 (TRAVELER)."
        ),
        "flows": {
            "authorizationCode": {
                "authorizationUrl": f"{OIDC_BASE}/auth",
                "tokenUrl": f"{OIDC_BASE}/token",
                "scopes": {
                    "openid": "Identite de l utilisateur",
                    "profile": "Nom et prenom",
                    "email": "Adresse email",
                },
            }
        },
    }
    # Le jeton est envoye a toutes les operations : utile quand le serveur
    # selectionne dans l'UI est la Gateway (9089), qui refuse sans JWT.
    schema["security"] = [{"keycloak": []}]
    # `servers` est porte par l'app FastAPI : get_openapi ne le reprend pas.
    schema["servers"] = app.servers
    app.openapi_schema = schema
    return schema


app.openapi = custom_openapi

app.include_router(notification.router)


@app.get("/swagger-ui", include_in_schema=False)
async def swagger_ui_page() -> HTMLResponse:
    """Swagger UI avec le bouton Authorize branche sur Keycloak."""
    return get_swagger_ui_html(
        openapi_url=app.openapi_url,
        title=f"{app.title} - Swagger UI",
        oauth2_redirect_url="/swagger-ui/oauth2-redirect",
        init_oauth=SWAGGER_INIT_OAUTH,
        swagger_ui_parameters=SWAGGER_PARAMETERS,
    )


@app.get("/swagger-ui/oauth2-redirect", include_in_schema=False)
async def swagger_ui_redirect() -> HTMLResponse:
    """Page de retour OAuth2 : renvoie le code au popup Swagger d'origine."""
    return get_swagger_ui_oauth2_redirect_html()


async def register_instance() -> None:
    """Enregistre l'instance dans Eureka (demarrage + superviseur).

    `instance_host` porte le nom resolvable dans le reseau Docker, `instance_id`
    un identifiant lisible, et les trois URL pointent vers le port publie :
    c'est ce que le dashboard Eureka affiche comme lien.
    """
    await eureka_client.init_async(
        eureka_server=EUREKA_SERVER,
        app_name=APP_NAME,
        instance_id=INSTANCE_ID,
        instance_port=PORT,
        instance_host=INSTANCE_HOST,
        instance_ip=INSTANCE_IP,
        home_page_url=f"{PUBLIC_URL}/",
        status_page_url=f"{PUBLIC_URL}/swagger-ui",
        health_check_url=f"{PUBLIC_URL}/health",
        renewal_interval_in_secs=30,
        duration_in_secs=90,
    )


async def is_registered() -> bool:
    """Vrai si cette instance est presente et UP dans le registre Eureka."""
    url = f"{EUREKA_SERVER.rstrip('/')}/apps/{APP_NAME}"
    async with httpx.AsyncClient(timeout=5) as client:
        response = await client.get(url, headers={"Accept": "application/json"})
    if response.status_code != 200:
        return False
    instances = (response.json().get("application") or {}).get("instance") or []
    if isinstance(instances, dict):
        instances = [instances]
    return any(
        instance.get("instanceId") == INSTANCE_ID and instance.get("status") == "UP"
        for instance in instances
    )


async def eureka_supervisor() -> None:
    """Re-enregistre l'instance si elle disparait du registre."""
    while True:
        await asyncio.sleep(REGISTRY_CHECK_INTERVAL_SECONDS)
        try:
            if not await is_registered():
                print("[warn] Instance absente du registre Eureka : re-enregistrement...")
                await register_instance()
                print("[ok] Instance re-enregistree dans Eureka")
        except Exception as e:
            # Le superviseur ne doit jamais s'arreter sur une erreur reseau.
            print(f"[warn] Supervision Eureka : {e}")


@app.on_event("startup")
async def startup_event():
    """Appelé APRÈS le démarrage du serveur (version ASYNC)"""
    print(f"[start] Notification Service on port {PORT}...")
    try:
        # ✅ UTILISER init_async AVEC 'await' POUR FASTAPI
        await register_instance()
        print(f"[ok] Registered on Eureka (port {PORT})")
    except Exception as e:
        print(f"[error] Eureka registration failed: {e}")
    asyncio.create_task(eureka_supervisor())


@app.on_event("shutdown")
async def shutdown_event():
    """Désenregistrement propre (version ASYNC)"""
    print("[stop] Stopping...")
    try:
        await eureka_client.stop_async()
        print("[ok] Unregistered from Eureka")
    except Exception as e:
        print(f"[warn] Error during Eureka unregistration: {e}")


@app.get("/", summary="Identite du service")
def read_root():
    return {"service": "NOTIFICATION-SERVICE", "status": "UP", "port": PORT}


@app.get("/health", summary="Sonde de sante")
def health_check():
    return {"status": "UP"}


@app.get("/info", summary="Informations de version")
def get_service_info():
    return {
        "name": "Notification Service",
        "description": "Nomadix Notification Microservice",
        "version": "1.0.0",
        "port": 8084,
        "status": "UP"
    }
