"""
Router unique des notifications.

Un seul contrat (app.schemas.NotificationCreate) et les deux variantes
d'URL (avec et sans slash final) pour eviter les 307/405 derriere la Gateway.
"""
from datetime import datetime, timezone
from typing import List

from fastapi import APIRouter, HTTPException, Response

from app.schemas import NotificationCreate, NotificationResponse

router = APIRouter(prefix="/api/notifications", tags=["Notifications"])

# Stockage en memoire (demo). Le consommateur RabbitMQ ecrit, lui,
# dans notifications.json pour l'historique des evenements.
_store: List[dict] = []
_counter = {"id": 0}


def _find(item_id: int) -> dict:
    for item in _store:
        if item["id"] == item_id:
            return item
    raise HTTPException(status_code=404, detail=f"Notification {item_id} inexistante")


@router.get("/hello")
def hello():
    return {"message": "Nomadix Notification Service is UP"}


@router.get("", response_model=List[NotificationResponse])
@router.get("/", response_model=List[NotificationResponse], include_in_schema=False)
def list_notifications():
    """Liste les notifications envoyees pendant la session."""
    return _store


@router.post("", response_model=NotificationResponse, status_code=201)
@router.post("/", response_model=NotificationResponse, status_code=201, include_in_schema=False)
def send_notification(notification: NotificationCreate):
    """Enregistre (simule) l'envoi d'une notification."""
    _counter["id"] += 1
    item = {
        "id": _counter["id"],
        **notification.model_dump(),
        "status": "sent",
        "sentAt": datetime.now(timezone.utc).isoformat(),
    }
    _store.append(item)
    return item


@router.get("/{item_id}", response_model=NotificationResponse)
def get_notification(item_id: int):
    return _find(item_id)


@router.put("/{item_id}", response_model=NotificationResponse)
def update_notification(item_id: int, notification: NotificationCreate):
    item = _find(item_id)
    item.update(notification.model_dump())
    return item


@router.delete("/{item_id}", status_code=204)
def delete_notification(item_id: int):
    item = _find(item_id)
    _store.remove(item)
    return Response(status_code=204)
