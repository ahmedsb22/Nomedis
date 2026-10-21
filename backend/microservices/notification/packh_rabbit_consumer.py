#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Pack H - Consommateur RabbitMQ du notification-service (python + pika).

Queue      : notification.events (durable : les messages attendent si
             ce consommateur est eteint, comme une boite vocale)
Exchange   : nomadix.events (topic)
Bindings   : flight.# et traveler.# (declares ici aussi, idempotent)
Stockage   : notifications.json (200 derniers evenements)

Arret : Ctrl+C
"""
import json
import os
import time

import pika

RABBIT_HOST = os.environ.get("RABBIT_HOST", "localhost")
RABBIT_PORT = int(os.environ.get("RABBIT_PORT", "5672"))
QUEUE = "notification.events"
EXCHANGE = "nomadix.events"
BINDINGS = ["flight.#", "traveler.#"]
STORE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "notifications.json")
MAX_ENTRIES = 200


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


def callback(ch, method, properties, body):
    try:
        payload = json.loads(body.decode("utf-8"))
    except Exception:
        payload = {"raw": body.decode("utf-8", errors="replace")}
    entry = {
        "routingKey": method.routing_key,
        "receivedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "payload": payload,
    }
    save_notification(entry)
    kind = payload.get("type", "?") if isinstance(payload, dict) else "?"
    detail = ""
    if isinstance(payload, dict):
        for k in ("flightNumber", "travelerId", "path"):
            if k in payload:
                detail = str(payload[k])
                break
    print("[notif] recu %s : %s %s" % (method.routing_key, kind, detail))
    ch.basic_ack(delivery_tag=method.delivery_tag)


def main():
    while True:
        try:
            params = pika.ConnectionParameters(
                host=RABBIT_HOST,
                port=RABBIT_PORT,
                heartbeat=60,
                blocked_connection_timeout=30,
            )
            connection = pika.BlockingConnection(params)
            channel = connection.channel()
            channel.exchange_declare(exchange=EXCHANGE, exchange_type="topic", durable=True)
            channel.queue_declare(queue=QUEUE, durable=True)
            for binding in BINDINGS:
                channel.queue_bind(queue=QUEUE, exchange=EXCHANGE, routing_key=binding)
            channel.basic_qos(prefetch_count=10)
            channel.basic_consume(queue=QUEUE, on_message_callback=callback)
            print("[notif] connecte a RabbitMQ - attente de messages sur '%s'" % QUEUE)
            channel.start_consuming()
        except KeyboardInterrupt:
            print("[notif] arret demande, au revoir")
            break
        except Exception as exc:
            print("[notif] RabbitMQ indisponible (%s) - nouvelle tentative dans 5 s..." % exc)
            time.sleep(5)


if __name__ == "__main__":
    main()