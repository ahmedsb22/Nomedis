from pydantic import BaseModel, Field
from enum import Enum

class NotificationType(str, Enum):
    FLIGHT_DELAY = "flight_delay"
    BOOKING_CONFIRMED = "booking_confirmed"
    PAYMENT_SUCCESS = "payment_success"

class NotificationCreate(BaseModel):
    traveler_id: int = Field(..., examples=[1])
    type: NotificationType = NotificationType.BOOKING_CONFIRMED
    message: str = Field(..., examples=["Votre vol TN-404 vers Paris a été confirmé"])

class NotificationResponse(NotificationCreate):
    id: int
    status: str = "sent"