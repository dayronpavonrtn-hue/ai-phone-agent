import asyncio
import email
import imaplib
import json
import logging
import os
import re
import smtplib
from decimal import Decimal, InvalidOperation
from email.header import decode_header
from email.message import EmailMessage
from email.utils import parseaddr
from io import BytesIO
from typing import Any, Dict, Optional

import httpx
from fastapi import FastAPI, HTTPException, Request
from PIL import Image, ImageDraw, ImageFont

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("nexora-payment-listener")

app = FastAPI(title="Nexora Payment Listener", version="2.0.0")

PAYPAL_BASE_URL = os.getenv("PAYPAL_BASE_URL", "https://api-m.paypal.com")
PAYPAL_CLIENT_ID = os.getenv("PAYPAL_CLIENT_ID", "")
PAYPAL_CLIENT_SECRET = os.getenv("PAYPAL_CLIENT_SECRET", "")
PAYPAL_WEBHOOK_ID = os.getenv("PAYPAL_WEBHOOK_ID", "")
EXPECTED_AMOUNT = Decimal(os.getenv("EXPECTED_AMOUNT", "15.00"))
EXPECTED_CURRENCY = os.getenv("EXPECTED_CURRENCY", "USD").upper()
GMAIL_USER = os.getenv("GMAIL_USER", "")
GMAIL_APP_PASSWORD = os.getenv("GMAIL_APP_PASSWORD", "")

processed_event_ids: set[str] = set()
recent_confirmed: list[Dict[str, Any]] = []
fulfillment_status: dict[str, Dict[str, Any]] = {}


def gmail_configured() -> bool:
    return bool(GMAIL_USER and GMAIL_APP_PASSWORD)


@app.on_event("startup")
async def startup_log() -> None:
    logger.info(
        "CONFIG paypal_client_id=%s paypal_client_secret=%s paypal_webhook_id=%s paypal_base=%s expected=%s_%s gmail=%s",
        bool(PAYPAL_CLIENT_ID), bool(PAYPAL_CLIENT_SECRET), bool(PAYPAL_WEBHOOK_ID),
        PAYPAL_BASE_URL, EXPECTED_AMOUNT, EXPECTED_CURRENCY, gmail_configured(),
    )


@app.get("/")
def root() -> Dict[str, str]:
    return {"service": "Nexora Payment Listener", "status": "online", "version": "2.0.0"}


@app.get("/health")
def health() -> Dict[str, Any]:
    return {
        "ok": True,
        "paypal_configured": bool(PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET and PAYPAL_WEBHOOK_ID),
        "gmail_configured": gmail_configured(),
        "paypal_environment": "sandbox" if "sandbox" in PAYPAL_BASE_URL else "live",
        "expected_amount": str(EXPECTED_AMOUNT),
        "expected_currency": EXPECTED_CURRENCY,
    }


@app.get("/payments/recent")
def payments_recent() -> Dict[str, Any]:
    return {"confirmed": recent_confirmed[-20:]}


@app.get("/fulfillment/recent")
def fulfillment_recent() -> Dict[str, Any]:
    return {"orders": list(fulfillment_status.values())[-20:]}


@app.post("/paypal/simulator")
async def paypal_simulator(request: Request) -> Dict[str, Any]:
    try:
        event = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON")
    event_id = str(event.get("id") or "")
    event_type = str(event.get("event_type") or "")
    logger.info("PAYPAL_SIMULATOR_EVENT_RECEIVED id=%s type=%s", event_id, event_type)
    return {"ok": True, "simulator": True, "verified_payment": False, "event_type": event_type}


async def paypal_access_token() -> str:
    if not PAYPAL_CLIENT_ID or not PAYPAL_CLIENT_SECRET:
        raise HTTPException(status_code=503, detail="PayPal API credentials not configured")
    async with httpx.AsyncClient(timeout=20) as client:
        response = await client.post(
            f"{PAYPAL_BASE_URL}/v1/oauth2/token",
            data={"grant_type": "client_credentials"},
            auth=(PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET),
            headers={"Accept": "application/json", "Accept-Language": "en_US"},
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
    payload = {**required, "webhook_id": PAYPAL_WEBHOOK_ID, "webhook_event": event}
    async with httpx.AsyncClient(timeout=20) as client:
        response = await client.post(
            f"{PAYPAL_BASE_URL}/v1/notifications/verify-webhook-signature",
            json=payload,
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        )
    if response.status_code >= 400:
        logger.error("PAYPAL_VERIFY_ERROR status=%s body=%s", response.status_code, response.text[:500])
        return False
    status = response.json().get("verification_status")
    logger.info("PAYPAL_VERIFY_RESULT status=%s", status)
    return status == "SUCCESS"


def extract_capture(event: Dict[str, Any]) -> Dict[str, Any]:
    resource = event.get("resource") or {}
    amount_obj = resource.get("amount") or {}
    payer = resource.get("payer") or {}
    return {
        "capture_id": resource.get("id"),
        "status": resource.get("status"),
        "amount": amount_obj.get("value"),
        "currency": (amount_obj.get("currency_code") or "").upper(),
        "payer_email": payer.get("email_address"),
        "custom_id": resource.get("custom_id"),
        "invoice_id": resource.get("invoice_id"),
    }


def _decode(value: Optional[str]) -> str:
    if not value:
        return ""
    parts = []
    for chunk, enc in decode_header(value):
        if isinstance(chunk, bytes):
            parts.append(chunk.decode(enc or "utf-8", errors="replace"))
        else:
            parts.append(chunk)
    return "".join(parts)


def _plain_body(msg: email.message.Message) -> str:
    if msg.is_multipart():
        for part in msg.walk():
            if part.get_content_type() == "text/plain" and "attachment" not in str(part.get("Content-Disposition", "")):
                payload = part.get_payload(decode=True)
                if payload:
                    return payload.decode(part.get_content_charset() or "utf-8", errors="replace")
    payload = msg.get_payload(decode=True)
    return payload.decode(msg.get_content_charset() or "utf-8", errors="replace") if payload else ""


def _find_prior_outreach(payer_email: str) -> Dict[str, str]:
    if not gmail_configured():
        return {}
    mail = imaplib.IMAP4_SSL("imap.gmail.com")
    try:
        mail.login(GMAIL_USER, GMAIL_APP_PASSWORD)
        # Gmail exposes Sent as this localized special-use path for most accounts.
        for box in ('"[Gmail]/Sent Mail"', '"[Gmail]/All Mail"'):
            try:
                if mail.select(box, readonly=True)[0] != "OK":
                    continue
                typ, data = mail.search(None, 'TO', f'"{payer_email}"')
                if typ != "OK" or not data or not data[0]:
                    continue
                ids = data[0].split()[-10:]
                for mid in reversed(ids):
                    typ, msg_data = mail.fetch(mid, "(RFC822)")
                    if typ != "OK" or not msg_data:
                        continue
                    raw = next((x[1] for x in msg_data if isinstance(x, tuple)), None)
                    if not raw:
                        continue
                    msg = email.message_from_bytes(raw)
                    subject = _decode(msg.get("Subject"))
                    body = _plain_body(msg)
                    return {"subject": subject, "body": body}
            except Exception:
                continue
    finally:
        try: mail.logout()
        except Exception: pass
    return {}


def _business_from_outreach(subject: str, body: str, payer_email: str) -> tuple[str, str]:
    subject = subject.strip()
    patterns = [r"(?:for|idea for|promo for)\s+(.+)$", r"Quick promo idea for\s+(.+)$"]
    business = "Your Business"
    for p in patterns:
        m = re.search(p, subject, flags=re.I)
        if m:
            business = m.group(1).strip(" -|:")[:60]
            break
    if business == "Your Business":
        local = payer_email.split("@")[0].replace(".", " ").replace("_", " ").strip()
        business = local.title()[:60] or business

    lines = [re.sub(r"\s+", " ", x).strip() for x in body.splitlines() if x.strip()]
    promo = "Professional social media promotion designed to attract more local customers."
    for line in lines:
        if 25 <= len(line) <= 150 and not line.lower().startswith(("hi ", "hello", "best", "nexora", "http")):
            promo = line
            break
    return business, promo


def _font(size: int, bold: bool = False):
    candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
    ]
    for path in candidates:
        try:
            return ImageFont.truetype(path, size=size)
        except Exception:
            pass
    return ImageFont.load_default()


def _wrap(draw: ImageDraw.ImageDraw, text: str, font, max_width: int) -> list[str]:
    words = text.split()
    lines, current = [], ""
    for word in words:
        trial = (current + " " + word).strip()
        if draw.textbbox((0, 0), trial, font=font)[2] <= max_width:
            current = trial
        else:
            if current: lines.append(current)
            current = word
    if current: lines.append(current)
    return lines[:5]


def generate_ad_png(business: str, promo: str) -> bytes:
    img = Image.new("RGB", (1080, 1080), "white")
    d = ImageDraw.Draw(img)
    # clean premium layout, no sample watermark after verified payment
    d.rectangle((0, 0, 1080, 220), fill=(20, 20, 24))
    d.rectangle((70, 265, 1010, 900), outline=(35, 35, 40), width=4)
    title_f = _font(64, True)
    body_f = _font(38, False)
    small_f = _font(30, True)
    d.text((70, 70), business[:36], font=title_f, fill="white")
    d.text((70, 175), "CUSTOM SOCIAL MEDIA PROMOTION", font=small_f, fill=(230, 230, 230))
    y = 350
    for line in _wrap(d, promo, body_f, 820):
        d.text((130, y), line, font=body_f, fill=(25, 25, 28))
        y += 58
    d.rounded_rectangle((130, 730, 950, 835), radius=24, fill=(20, 20, 24))
    d.text((300, 760), "CONTACT US TODAY", font=small_f, fill="white")
    d.text((70, 960), "Created by Nexora Growth Media", font=_font(26), fill=(80, 80, 90))
    out = BytesIO(); img.save(out, format="PNG", optimize=True); return out.getvalue()


def _send_email_with_ad(to_email: str, business: str, promo: str, png: bytes) -> None:
    msg = EmailMessage()
    msg["From"] = f"Nexora Growth Media <{GMAIL_USER}>"
    msg["To"] = to_email
    msg["Subject"] = f"Your completed Nexora promotion for {business}"
    msg.set_content(
        f"Hi,\n\nThank you for your payment. Your custom social media promotion for {business} is attached and ready to post.\n\nSuggested caption:\n{promo}\n\nThank you,\nNexora Growth Media"
    )
    msg.add_attachment(png, maintype="image", subtype="png", filename="nexora-promotion.png")
    with smtplib.SMTP_SSL("smtp.gmail.com", 465, timeout=30) as smtp:
        smtp.login(GMAIL_USER, GMAIL_APP_PASSWORD)
        smtp.send_message(msg)


def _send_need_details(to_email: str) -> None:
    msg = EmailMessage()
    msg["From"] = f"Nexora Growth Media <{GMAIL_USER}>"
    msg["To"] = to_email
    msg["Subject"] = "Payment received — one detail needed for your promotion"
    msg.set_content(
        "Hi,\n\nYour $15 payment was received and verified. Reply with your business name plus the service or offer you want promoted. If you have a website or social page, include the link. We will prepare your final promotion automatically.\n\nNexora Growth Media"
    )
    with smtplib.SMTP_SSL("smtp.gmail.com", 465, timeout=30) as smtp:
        smtp.login(GMAIL_USER, GMAIL_APP_PASSWORD)
        smtp.send_message(msg)


async def fulfill_paid_order(confirmed: Dict[str, Any]) -> None:
    event_id = confirmed.get("event_id") or confirmed.get("capture_id") or "unknown"
    payer_email = (confirmed.get("payer_email") or "").strip()
    state = {"event_id": event_id, "payer_email": payer_email, "status": "started"}
    fulfillment_status[event_id] = state
    if not payer_email:
        state["status"] = "needs_payer_email"
        logger.warning("FULFILLMENT_BLOCKED event=%s reason=no_payer_email", event_id)
        return
    if not gmail_configured():
        state["status"] = "needs_gmail_configuration"
        logger.warning("FULFILLMENT_BLOCKED event=%s reason=gmail_not_configured", event_id)
        return
    try:
        outreach = await asyncio.to_thread(_find_prior_outreach, payer_email)
        if outreach:
            business, promo = _business_from_outreach(outreach.get("subject", ""), outreach.get("body", ""), payer_email)
            png = await asyncio.to_thread(generate_ad_png, business, promo)
            await asyncio.to_thread(_send_email_with_ad, payer_email, business, promo, png)
            state.update({"status": "delivered", "business": business})
            logger.info("FULFILLMENT_DELIVERED event=%s email=%s business=%s", event_id, payer_email, business)
        else:
            await asyncio.to_thread(_send_need_details, payer_email)
            state["status"] = "awaiting_customer_details"
            logger.info("FULFILLMENT_DETAILS_REQUESTED event=%s email=%s", event_id, payer_email)
    except Exception as exc:
        state.update({"status": "error", "error": type(exc).__name__})
        logger.exception("FULFILLMENT_ERROR event=%s", event_id)


@app.post("/paypal/webhook")
async def paypal_webhook(request: Request) -> Dict[str, Any]:
    try:
        raw = await request.body(); event = json.loads(raw.decode("utf-8"))
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
            raise HTTPException(status_code=400, detail="Invalid payment amount")
        if capture.get("status") != "COMPLETED":
            raise HTTPException(status_code=400, detail="Payment not completed")
        if capture.get("currency") != EXPECTED_CURRENCY:
            raise HTTPException(status_code=400, detail="Unexpected currency")
        if amount != EXPECTED_AMOUNT:
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
        asyncio.create_task(fulfill_paid_order(confirmed))

    elif event_type in {"PAYMENT.CAPTURE.PENDING", "PAYMENT.CAPTURE.DENIED", "PAYMENT.CAPTURE.REFUNDED", "PAYMENT.CAPTURE.REVERSED"}:
        logger.warning("PAYMENT_NONFINAL_EVENT id=%s type=%s", event_id, event_type)

    if event_id:
        processed_event_ids.add(event_id)
    return {"ok": True, "verified": True, "event_type": event_type}
