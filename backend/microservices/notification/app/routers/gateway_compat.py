# ============================================================================
# gateway_compat.py - Router de compatibilite pour l'API Gateway
# Expose /api/notifications AVEC et SANS slash final (fix du 405).
# ============================================================================
from fastapi import APIRouter
from pydantic import BaseModel
from typing import List, Optional
from datetime import datetime

router = APIRouter(prefix="/api/notifications", tags=["Notifications"])


class NotificationRequest(BaseModel):
    recipient: Optional[str] = "unknown@nomadix.io"
    subject: Optional[str] = "Notification Nomadix"
    message: Optional[str] = ""
    type: Optional[str] = "EMAIL"


_store: List[dict] = []
_counter = {"id": 0}


def _list() -> List[dict]:
    return _store


def _send(req: NotificationRequest) -> dict:
    _counter["id"] += 1
    item = {
        "id": _counter["id"],
        "recipient": req.recipient,
        "subject": req.subject,
        "message": req.message,
        "type": req.type,
        "status": "SENT",
        "sentAt": datetime.utcnow().isoformat() + "Z",
    }
    _store.append(item)
    return item


# --- Les DEUX variantes pour chaque methode (fix slash final) ---------------
@router.get("")
@router.get("/")
def list_notifications():
    """Liste toutes les notifications envoyees (demo)."""
    return _list()


@router.post("")
@router.post("/")
def send_notification(req: NotificationRequest):
    """Envoie (simule) une notification et la stocke."""
    return _send(req)


@router.get("/health")
def health():
    return {"status": "UP", "service": "notification-service"}