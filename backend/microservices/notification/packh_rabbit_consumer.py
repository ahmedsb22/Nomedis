#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Pack H - Consommateur RabbitMQ du notification-service (python + pika).

Topologie (declaree ici ET dans backend/messaging/rabbitmq/definitions.json) :

    nomadix.events (topic)            --> routing keys flight.#, traveler.#, booking.#
        +-- notification.events       --> x-dead-letter-exchange = nomadix.dlx
    nomadix.dlx (topic)
        +-- notification.events.dlq   --> messages refuses (poison messages)

Un message est acquitte (basic_ack) seulement apres enregistrement dans
notifications.json. S'il est illisible (JSON invalide) ou si le corps contient
"simulateFailure": true, il est refuse avec basic_nack(requeue=False) :
RabbitMQ le deplace alors vers nomadix.dlx puis notification.events.dlq,
sans le remettre en tete de file (pas de boucle infinie).

Arret : Ctrl+C
"""
import json
import os
import time

import pika

RABBIT_HOST = os.environ.get("RABBIT_HOST", "localhost")
RABBIT_PORT = int(os.environ.get("RABBIT_PORT", "5672"))
# Le broker refuse le compte par defaut guest/guest (desactive cote serveur) :
# on utilise le compte dedie cree pour Nomadix, le meme que les services Java.
RABBIT_USER = os.environ.get("RABBIT_USER", "nomadix")
RABBIT_PASSWORD = os.environ.get("RABBIT_PASSWORD", "nomadix123")
QUEUE = "notification.events"
EXCHANGE = "nomadix.events"
DLX = "nomadix.dlx"
DLQ = "notification.events.dlq"
DLQ_ROUTING_KEY = "notification.events.dlq"
BINDINGS = ["flight.#", "traveler.#", "booking.#"]
STORE_PATH = os.environ.get(
    "NOTIFICATIONS_STORE",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "notifications.json"),
)
MAX_ENTRIES = 200

# Doit rester identique a definitions.json, sinon RabbitMQ refuse la
# redeclaration (PRECONDITION_FAILED) et le consommateur ne demarre pas.
QUEUE_ARGS = {
    "x-queue-type": "classic",
    "x-dead-letter-exchange": DLX,
    "x-dead-letter-routing-key": DLQ_ROUTING_KEY,
}


def load_store():
    try:
        with open(STORE_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
            if isinstance(data, list):
                return data
    except Exception:
        pass
    return []


def save_notification(entry):
    store = load_store()
    store.insert(0, entry)
    del store[MAX_ENTRIES:]
    with open(STORE_PATH, "w", encoding="utf-8") as f:
        json.dump(store, f, indent=2, ensure_ascii=False)


def declare_topology(channel):
    """Idempotent : les services Java declarent deja l'exchange principal."""
    channel.exchange_declare(exchange=EXCHANGE, exchange_type="topic", durable=True)
    channel.exchange_declare(exchange=DLX, exchange_type="topic", durable=True)
    channel.queue_declare(queue=QUEUE, durable=True, arguments=QUEUE_ARGS)
    channel.queue_declare(queue=DLQ, durable=True, arguments={"x-queue-type": "classic"})
    for binding in BINDINGS:
        channel.queue_bind(queue=QUEUE, exchange=EXCHANGE, routing_key=binding)
    channel.queue_bind(queue=DLQ, exchange=DLX, routing_key=DLQ_ROUTING_KEY)


def callback(ch, method, properties, body):
    raw = body.decode("utf-8", errors="replace")

    # --- Validation : un message invalide part en DLQ, il ne bloque pas la file
    try:
        payload = json.loads(raw)
        if not isinstance(payload, dict):
            raise ValueError("le corps JSON doit etre un objet")
        if payload.get("simulateFailure") is True:
            raise ValueError("simulateFailure=true demande par l'emetteur")
    except Exception as exc:
        print(
            "[notif][DLQ] message refuse sur '%s' (%s) -> %s via %s"
            % (method.routing_key, exc, DLQ, DLX)
        )
        # requeue=False : RabbitMQ applique la dead-letter policy de la file.
        ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
        return

    try:
        entry = {
            "routingKey": method.routing_key,
            "receivedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "payload": payload,
        }
        save_notification(entry)
    except Exception as exc:
        print("[notif][DLQ] echec d'enregistrement (%s) -> %s" % (exc, DLQ))
        ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
        return

    kind = payload.get("type", "?")
    detail = ""
    for key in ("flightNumber", "bookingId", "travelerId", "path"):
        if key in payload:
            detail = str(payload[key])
            break
    print("[notif] recu %s : %s %s" % (method.routing_key, kind, detail))
    ch.basic_ack(delivery_tag=method.delivery_tag)


def main():
    while True:
        try:
            params = pika.ConnectionParameters(
                host=RABBIT_HOST,
                port=RABBIT_PORT,
                credentials=pika.PlainCredentials(RABBIT_USER, RABBIT_PASSWORD),
                heartbeat=60,
                blocked_connection_timeout=30,
            )
            connection = pika.BlockingConnection(params)
            channel = connection.channel()
            declare_topology(channel)
            channel.basic_qos(prefetch_count=10)
            channel.basic_consume(queue=QUEUE, on_message_callback=callback)
            print("[notif] connecte a RabbitMQ - attente de messages sur '%s'" % QUEUE)
            print("[notif] DLX '%s' -> DLQ '%s'" % (DLX, DLQ))
            channel.start_consuming()
        except KeyboardInterrupt:
            print("[notif] arret demande, au revoir")
            break
        except pika.exceptions.ChannelClosedByBroker as exc:
            print(
                "[notif] la file '%s' existe sans les arguments DLX (%s).\n"
                "        Supprimez-la une fois (UI RabbitMQ > Queues > Delete) ou\n"
                "        recreez le broker avec backend/messaging/rabbitmq/definitions.json,\n"
                "        puis relancez ce script." % (QUEUE, exc)
            )
            time.sleep(10)
        except Exception as exc:
            print("[notif] RabbitMQ indisponible (%s) - nouvelle tentative dans 5 s..." % exc)
            time.sleep(5)


if __name__ == "__main__":
    main()
