# ============================================================================
#  Nomadix - Notification Service (FastAPI + consommateur RabbitMQ)
#  La meme image sert deux processus :
#    - API        : uvicorn app.main:app --host 0.0.0.0 --port 8084 (defaut)
#    - consommateur : python packh_rabbit_consumer.py (surcharge par Compose)
# ============================================================================
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY packh_rabbit_consumer.py .

RUN useradd --system --create-home nomadix \
    && mkdir -p /data \
    && chown -R nomadix:nomadix /app /data
USER nomadix

EXPOSE 8084

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8084"]
