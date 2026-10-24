"""Notification microservice - entry point.

Run:  uvicorn app.main:app --reload --port 8084
  or: python -m app.main
"""
import os
import sys

from fastapi import FastAPI
from app.routers import notification
import py_eureka_client.eureka_client as eureka_client

# Console Windows (cp1252) : evite UnicodeEncodeError sur les messages accentues.
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

PORT = int(os.getenv("PORT", "8084"))

app = FastAPI(
    title="Notification Microservice API",
    version="1.0.0",
    description="Notification microservice (Python / FastAPI)",
    contact={"name": "Badia Abouhdid"},
    docs_url="/swagger-ui",
    openapi_url="/v3/api-docs",
    redoc_url="/redoc",
)

app.include_router(notification.router)


@app.on_event("startup")
async def startup_event():
    """Appelé APRÈS le démarrage du serveur (version ASYNC)"""
    print(f"[start] Notification Service on port {PORT}...")
    try:
        # ✅ UTILISER init_async AVEC 'await' POUR FASTAPI
        await eureka_client.init_async(
            eureka_server="http://localhost:8761/eureka/",
            app_name="NOTIFICATION-SERVICE",
            instance_port=PORT,
            instance_host="localhost",
            instance_ip="127.0.0.1",
            renewal_interval_in_secs=30,
            duration_in_secs=90
        )
        print(f"[ok] Registered on Eureka (port {PORT})")
    except Exception as e:
        print(f"[error] Eureka registration failed: {e}")


@app.on_event("shutdown")
async def shutdown_event():
    """Désenregistrement propre (version ASYNC)"""
    print("[stop] Stopping...")
    try:
        await eureka_client.stop_async()
        print("[ok] Unregistered from Eureka")
    except Exception as e:
        print(f"[warn] Error during Eureka unregistration: {e}")


@app.get("/")
def read_root():
    return {"service": "NOTIFICATION-SERVICE", "status": "UP", "port": PORT}


@app.get("/health")
def health_check():
    return {"status": "UP"}


@app.get("/info")
def get_service_info():
    return {
        "name": "Notification Service",
        "description": "Nomadix Notification Microservice",
        "version": "1.0.0",
        "port": 8084,
        "status": "UP"
    }
