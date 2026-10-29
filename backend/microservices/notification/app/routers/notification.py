"""
Router unique des notifications.

Un seul contrat (app.schemas.NotificationCreate) et les deux variantes
d'URL (avec et sans slash final) pour eviter les 307/405 derriere la Gateway.
Les lectures/ecritures sont protegees par la Gateway (jeton Keycloak) :
le service lui-meme reste interne (localhost).
"""
import json
import os
from datetime import datetime, timezone
from typing import List

from fastapi import APIRouter, HTTPException, Response, status

from app.schemas import NotificationCreate, NotificationResponse

router = APIRouter(prefix="/api/notifications", tags=["Notifications"])

# Stockage en memoire (demo) pour les notifications saisies dans l'UI.
_store: List[dict] = []
_counter = {"id": 0}

# Archive ecrite par le consommateur RabbitMQ (packh_rabbit_consumer.py).
# Le volume Docker nomadix-notifications est monte sur /data dans les deux
# conteneurs (API et consommateur) : c'est ce qui rend les evenements
# asynchrones visibles dans le frontend.
ARCHIVE_PATH = os.getenv("NOTIFICATIONS_STORE", "/data/notifications.json")


def _find(item_id: int) -> dict:
    for item in _store:
        if item["id"] == item_id:
            return item
    raise HTTPException(status_code=404, detail=f"Notification {item_id} inexistante")


@router.get("/hello", summary="Sonde de disponibilite", description="Retourne un message fixe.")
def hello():
    return {"message": "Nomadix Notification Service is UP"}


@router.get(
    "",
    response_model=List[NotificationResponse],
    summary="Lister les notifications",
    description="Liste les notifications enregistrees pendant la session (stockage memoire).",
)
@router.get("/", response_model=List[NotificationResponse], include_in_schema=False)
def list_notifications():
    return _store


# IMPORTANT : cette route doit rester declaree AVANT /{item_id}, sinon
# FastAPI interprete "events" comme un identifiant et renvoie 422.
@router.get(
    "/events",
    summary="Evenements RabbitMQ consommes",
    description=(
        "Historique des messages recus par `packh_rabbit_consumer.py` sur la file "
        "`notification.events`, lu dans `notifications.json` (volume partage).\n\n"
        "Chaque entree contient la routing key (`flight.created`, "
        "`traveler.registered`, `booking.created`, `booking.cancelled`...), "
        "l'horodatage de reception et le corps du message.\n\n"
        "Un message refuse (JSON invalide) n'apparait **pas** ici : il est "
        "deplace dans `notification.events.dlq`, visible dans la console "
        "RabbitMQ (http://localhost:15672)."
    ),
)
def list_events(limit: int = 50):
    try:
        with open(ARCHIVE_PATH, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except FileNotFoundError:
        return []
    except Exception:
        return []
    if not isinstance(data, list):
        return []
    return data[:limit]


@router.post(
    "",
    response_model=NotificationResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Envoyer (simuler) une notification",
    description="Enregistre la notification en memoire et lui attribue un identifiant.",
    responses={422: {"description": "Corps invalide (validation Pydantic)"}},
)
@router.post("/", response_model=NotificationResponse, status_code=201, include_in_schema=False)
def send_notification(notification: NotificationCreate):
    _counter["id"] += 1
    item = {
        "id": _counter["id"],
        **notification.model_dump(),
        "status": "sent",
        "sentAt": datetime.now(timezone.utc),
    }
    _store.append(item)
    return item


@router.get(
    "/{item_id}",
    response_model=NotificationResponse,
    summary="Obtenir une notification",
    responses={404: {"description": "Notification inexistante"}},
)
def get_notification(item_id: int):
    return _find(item_id)


@router.put(
    "/{item_id}",
    response_model=NotificationResponse,
    summary="Remplacer une notification",
    responses={404: {"description": "Notification inexistante"}},
)
def update_notification(item_id: int, notification: NotificationCreate):
    item = _find(item_id)
    item.update(notification.model_dump())
    return item


@router.delete(
    "/{item_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Supprimer une notification",
    responses={404: {"description": "Notification inexistante"}},
)
def delete_notification(item_id: int):
    item = _find(item_id)
    _store.remove(item)
    return Response(status_code=204)
