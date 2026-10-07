from fastapi import APIRouter
from app.schemas import NotificationCreate, NotificationResponse
router = APIRouter(prefix="/api/notifications", tags=["Notifications-Voyage"])

@router.post("/", response_model=NotificationResponse)
def send_notification(notification: NotificationCreate):
    # Simulation d'envoi pour démo ESPRIT
    return NotificationResponse(**notification.dict(), id=99, status="sent")

@router.get("/hello")
def hello():
    return {"message": "Nomadix Notification Service is UP ✈️"}