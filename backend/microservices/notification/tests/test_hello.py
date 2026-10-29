"""Tests du Notification-Service (contrat OpenAPI + CRUD).

Run with: pytest

Ces tests sont la verification executable de la specification exposee sur
/v3/api-docs : chaque statut annonce dans Swagger est verifie ici.
"""
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


# --------------------------------------------------------------------- base

def test_hello_returns_greeting():
    res = client.get("/api/notifications/hello")
    assert res.status_code == 200
    assert res.json() == {"message": "Nomadix Notification Service is UP"}


def test_openapi_spec_is_exposed():
    res = client.get("/v3/api-docs")
    assert res.status_code == 200
    spec = res.json()
    assert spec["info"]["title"] == "Nomadix Notification Service API"
    assert "/api/notifications/hello" in spec["paths"]
    # Toutes les operations du CRUD doivent etre documentees (Swagger = verite)
    operations = spec["paths"]["/api/notifications"]
    for method in ("get", "post"):
        assert method in operations
    assert "put" in spec["paths"]["/api/notifications/{item_id}"]
    assert "delete" in spec["paths"]["/api/notifications/{item_id}"]


def test_swagger_ui_is_served():
    res = client.get("/swagger-ui")
    assert res.status_code == 200
    assert "swagger" in res.text.lower()


def test_unknown_route_returns_404():
    assert client.get("/api/unknown").status_code == 404


# --------------------------------------------------------------------- CRUD

def test_create_notification_returns_201_with_contract_fields():
    res = client.post(
        "/api/notifications",
        json={
            "traveler_id": 1,
            "type": "flight_delay",
            "message": "Vol TN-404 retardé de 45 minutes",
        },
    )
    assert res.status_code == 201
    body = res.json()
    assert isinstance(body["id"], int)
    assert body["traveler_id"] == 1
    assert body["type"] == "flight_delay"
    assert body["status"] == "sent"
    assert body["sentAt"]


def test_create_notification_rejects_invalid_body_with_422():
    # `message` est obligatoire dans NotificationCreate
    res = client.post("/api/notifications", json={"traveler_id": "abc"})
    assert res.status_code == 422


def test_list_contains_created_notification():
    created = client.post(
        "/api/notifications",
        json={"traveler_id": 2, "message": "Confirmation envoyée"},
    ).json()

    res = client.get("/api/notifications")
    assert res.status_code == 200
    ids = [item["id"] for item in res.json()]
    assert created["id"] in ids


def test_get_update_delete_lifecycle():
    created = client.post(
        "/api/notifications",
        json={"traveler_id": 3, "message": "Message initial"},
    ).json()
    item_id = created["id"]

    found = client.get(f"/api/notifications/{item_id}")
    assert found.status_code == 200
    assert found.json()["message"] == "Message initial"

    updated = client.put(
        f"/api/notifications/{item_id}",
        json={"traveler_id": 3, "type": "payment_success", "message": "Message corrigé"},
    )
    assert updated.status_code == 200
    assert updated.json()["message"] == "Message corrigé"
    assert updated.json()["type"] == "payment_success"

    assert client.delete(f"/api/notifications/{item_id}").status_code == 204
    assert client.get(f"/api/notifications/{item_id}").status_code == 404
    assert client.delete(f"/api/notifications/{item_id}").status_code == 404
