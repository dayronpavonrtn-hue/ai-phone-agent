import json
import logging
import os
from decimal import Decimal, InvalidOperation
from typing import Any, Dict

import httpx
from fastapi import FastAPI, HTTPException, Request

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("nexora-payment-listener")

app = FastAPI(title="Nexora Payment Listener", version="1.0.0")

PAYPAL_BASE_URL = os.getenv("PAYPAL_BASE_URL", "https://api-m.paypal.com")
PAYPAL_CLIENT_ID = os.getenv("PAYPAL_CLIENT_ID", "")
PAYPAL_CLIENT_SECRET = os.getenv("PAYPAL_CLIENT_SECRET", "")
PAYPAL_WEBHOOK_ID = os.getenv("PAYPAL_WEBHOOK_ID", "")
EXPECTED_AMOUNT = Decimal(os.getenv("EXPECTED_AMOUNT", "15.00"))
EXPECTED_CURRENCY = os.getenv("EXPECTED_CURRENCY", "USD").upper()

processed_event_ids: set[str] = set()
recent_confirmed: list[Dict[str, Any]] = []


@app.get("/")
def root() -> Dict[str, str]:
    return {"service": "Nexora Payment Listener", "status": "online"}


@app.get("/health")
def health() -> Dict[str, Any]:
    return {
        "ok": True,
        "paypal_configured": bool(PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET and PAYPAL_WEBHOOK_ID),
        "expected_amount": str(EXPECTED_AMOUNT),
        "expected_currency": EXPECTED_CURRENCY,
    }


@app.get("/payments/recent")
def payments_recent() -> Dict[str, Any]:
    return {"confirmed": recent_confirmed[-20:]}


async def paypal_access_token() -> str:
    if not PAYPAL_CLIENT_ID or not PAYPAL_CLIENT_SECRET:
        raise HTTPException(status_code=503, detail="PayPal API credentials not configured")
    async with httpx.AsyncClient(timeout=20) as client:
        response = await client.post(
            f"{PAYPAL_BASE_URL}/v1/oauth2/token",
            data={"grant_type": "client_credentials"},
            auth=(PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET),
            headers={"Accept": "application/json"},
        )
    if response.status_code >= 400:
        logger.error("PAYPAL_TOKEN_ERROR status=%s body=%s", response.status_code, response.text[:500])
        raise HTTPException(status_code=502, detail="Unable to authenticate with PayPal")
    return response.json()["access_token"]


async def verify_paypal_webhook(request: Request, event: Dict[str, Any]) -> bool:
    if not PAYPAL_WEBHOOK_ID:
        raise HTTPException(status_code=503, detail="PAYPAL_WEBHOOK_ID not configured")

    headers = request.headers
    required = {
        "auth_algo": headers.get("paypal-auth-algo"),
        "cert_url": headers.get("paypal-cert-url"),
        "transmission_id": headers.get("paypal-transmission-id"),
        "transmission_sig": headers.get("paypal-transmission-sig"),
        "transmission_time": headers.get("paypal-transmission-time"),
    }
    if not all(required.values()):
        return False

    token = await paypal_access_token()
    payload = {
        **required,
        "webhook_id": PAYPAL_WEBHOOK_ID,
        "webhook_event": event,
    }
    async with httpx.AsyncClient(timeout=20) as client:
        response = await client.post(
            f"{PAYPAL_BASE_URL}/v1/notifications/verify-webhook-signature",
            json=payload,
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        )
    if response.status_code >= 400:
        logger.error("PAYPAL_VERIFY_ERROR status=%s body=%s", response.status_code, response.text[:500])
        return False
    return response.json().get("verification_status") == "SUCCESS"


def extract_capture(event: Dict[str, Any]) -> Dict[str, Any]:
    resource = event.get("resource") or {}
    amount_obj = resource.get("amount") or {}
    payer = resource.get("payer") or {}
    payer_email = payer.get("email_address")
    if not payer_email:
        supplementary = resource.get("supplementary_data") or {}
        related = supplementary.get("related_ids") or {}
        payer_email = related.get("payer_id")
    return {
        "capture_id": resource.get("id"),
        "status": resource.get("status"),
        "amount": amount_obj.get("value"),
        "currency": (amount_obj.get("currency_code") or "").upper(),
        "payer_email": payer_email,
        "custom_id": resource.get("custom_id"),
        "invoice_id": resource.get("invoice_id"),
    }


@app.post("/paypal/webhook")
async def paypal_webhook(request: Request) -> Dict[str, Any]:
    try:
        raw = await request.body()
        event = json.loads(raw.decode("utf-8"))
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON")

    event_id = str(event.get("id") or "")
    event_type = str(event.get("event_type") or "")
    logger.info("PAYPAL_EVENT_RECEIVED id=%s type=%s", event_id, event_type)

    verified = await verify_paypal_webhook(request, event)
    if not verified:
        logger.warning("PAYPAL_EVENT_REJECTED id=%s reason=signature_verification_failed", event_id)
        raise HTTPException(status_code=400, detail="Invalid PayPal signature")

    if event_id and event_id in processed_event_ids:
        return {"ok": True, "duplicate": True}

    if event_type == "PAYMENT.CAPTURE.COMPLETED":
        capture = extract_capture(event)
        try:
            amount = Decimal(str(capture.get("amount")))
        except (InvalidOperation, TypeError):
            logger.warning("PAYMENT_REJECTED event=%s reason=invalid_amount", event_id)
            raise HTTPException(status_code=400, detail="Invalid payment amount")

        if capture.get("status") != "COMPLETED":
            logger.warning("PAYMENT_REJECTED event=%s reason=status_%s", event_id, capture.get("status"))
            raise HTTPException(status_code=400, detail="Payment not completed")
        if capture.get("currency") != EXPECTED_CURRENCY:
            logger.warning("PAYMENT_REJECTED event=%s reason=currency_%s", event_id, capture.get("currency"))
            raise HTTPException(status_code=400, detail="Unexpected currency")
        if amount != EXPECTED_AMOUNT:
            logger.warning("PAYMENT_REJECTED event=%s reason=amount_%s", event_id, amount)
            raise HTTPException(status_code=400, detail="Unexpected amount")

        confirmed = {
            "event_id": event_id,
            "capture_id": capture.get("capture_id"),
            "amount": str(amount),
            "currency": capture.get("currency"),
            "payer_email": capture.get("payer_email"),
            "custom_id": capture.get("custom_id"),
            "invoice_id": capture.get("invoice_id"),
        }
        recent_confirmed.append(confirmed)
        logger.info("PAYMENT_CONFIRMED %s", json.dumps(confirmed, separators=(",", ":")))

    elif event_type in {
        "PAYMENT.CAPTURE.PENDING",
        "PAYMENT.CAPTURE.DENIED",
        "PAYMENT.CAPTURE.REFUNDED",
        "PAYMENT.CAPTURE.REVERSED",
    }:
        logger.warning("PAYMENT_NONFINAL_EVENT id=%s type=%s", event_id, event_type)

    if event_id:
        processed_event_ids.add(event_id)

    return {"ok": True, "event_type": event_type}
