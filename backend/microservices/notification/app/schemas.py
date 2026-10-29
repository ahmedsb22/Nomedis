"""Contrats Pydantic du service de notifications (source unique de verite OpenAPI)."""
from datetime import datetime
from enum import Enum

from pydantic import BaseModel, Field


class NotificationType(str, Enum):
    """Types d'evenements acceptes par l'API."""

    FLIGHT_DELAY = "flight_delay"
    BOOKING_CONFIRMED = "booking_confirmed"
    PAYMENT_SUCCESS = "payment_success"


class NotificationCreate(BaseModel):
    """Corps attendu par POST /api/notifications."""

    traveler_id: int = Field(..., description="Identifiant du voyageur", examples=[1])
    type: NotificationType = Field(
        default=NotificationType.BOOKING_CONFIRMED,
        description="Type de notification",
        examples=["booking_confirmed"],
    )
    message: str = Field(
        ...,
        description="Texte envoye au voyageur",
        examples=["Votre vol TN-404 vers Paris a ete confirme"],
    )


class NotificationResponse(NotificationCreate):
    """Notification enregistree, telle que renvoyee par l'API."""

    id: int = Field(..., description="Identifiant attribue par le service", examples=[1])
    status: str = Field(default="sent", description="Statut d'envoi simule", examples=["sent"])
    sentAt: datetime = Field(
        ...,
        description="Horodatage UTC de l'enregistrement (ISO 8601)",
        examples=["2026-10-24T10:15:30+00:00"],
    )
