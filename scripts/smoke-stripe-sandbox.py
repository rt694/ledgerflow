#!/usr/bin/env python3
"""Synthetic Stripe payment/refund check; requires test keys and CLI forwarding."""
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import date, timedelta
from decimal import Decimal

BASE = os.environ.get("LEDGERFLOW_URL", "http://127.0.0.1:18080").rstrip("/")


def request(method, url, body=None, headers=None, expected=(200,)):
    req = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=40)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status, data = response.status, response.read()
    if status not in expected:
        raise RuntimeError(f"Unexpected HTTP status {status}; response details withheld")
    return json.loads(data, parse_float=Decimal) if data else None


def api(method, path, body=None, token=None, key=None, expected=(200,)):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    return request(method, BASE + path, json.dumps(body).encode() if body is not None else None,
                   headers, expected)


def wait_status(path, token, expected):
    for attempt in range(30):
        result = api("GET", path, token=token)
        if result["status"] == expected:
            return
        time.sleep(1)
    raise RuntimeError("Webhook processing timed out; check CLI forwarding and signing secret")


def main():
    settings = {}
    env_file = Path(__file__).resolve().parents[1] / ".env"
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                settings[key.strip()] = value.strip().strip("\"").strip("'")
    secret = os.environ.get("STRIPE_SECRET_KEY", settings.get("STRIPE_SECRET_KEY", ""))
    if not secret.startswith(("sk_test_", "rk_test_")):
        raise RuntimeError("Configure a Stripe test key in the ignored .env first")
    email = f"stripe-demo-{uuid.uuid4()}@example.test"
    password = "synthetic-demo-password-123"
    api("POST", "/api/v1/auth/register", {"email": email, "displayName": "Sandbox demo",
                                           "password": password}, expected=(201,))
    token = api("POST", "/api/v1/auth/login", {"email": email, "password": password})["accessToken"]
    org = api("POST", "/api/v1/organizations", {"name": "Synthetic Stripe shop"}, token,
              expected=(201,))["id"]
    base = f"/api/v1/organizations/{org}"
    customer = api("POST", base + "/customers", {"name": "Synthetic customer",
                   "email": "customer@example.test"}, token, expected=(201,))["id"]
    invoice = api("POST", base + "/invoices", {"customerId": customer, "number": "STRIPE-DEMO-1",
                  "currency": "USD", "dueDate": str(date.today() + timedelta(days=30)),
                  "lines": [{"description": "Delivered synthetic service", "quantity": 3,
                             "unitPrice": "19.99", "taxRate": "0.0825"}]}, token, expected=(201,))
    invoice_path = base + "/invoices/" + invoice["id"]
    api("POST", invoice_path + "/issue", {"version": 0}, token)
    key = "demo-" + str(uuid.uuid4())
    payment = api("POST", base + "/payments", {"invoiceId": invoice["id"]}, token, key)
    replay = api("POST", base + "/payments", {"invoiceId": invoice["id"]}, token, key)
    if payment["paymentId"] != replay["paymentId"]:
        raise RuntimeError("Payment idempotency mismatch")
    intent = payment["providerId"]
    confirmed = request("POST", "https://api.stripe.com/v1/payment_intents/" + intent + "/confirm",
                        urllib.parse.urlencode({"payment_method": "pm_card_visa"}).encode(),
                        {"Authorization": "Bearer " + secret,
                         "Content-Type": "application/x-www-form-urlencoded",
                         "Idempotency-Key": "ledgerflow-demo-confirm-" + intent})
    if confirmed.get("livemode") is not False or confirmed.get("status") != "succeeded":
        raise RuntimeError("Expected a successful test payment")
    wait_status(invoice_path, token, "PAID")
    refund_path = base + "/payments/" + payment["paymentId"] + "/refunds"
    refund_key = "refund-" + str(uuid.uuid4())
    refund = api("POST", refund_path, token=token, key=refund_key, expected=(202,))
    replay = api("POST", refund_path, token=token, key=refund_key, expected=(202,))
    if refund["id"] != replay["id"]:
        raise RuntimeError("Refund idempotency mismatch")
    wait_status(invoice_path, token, "ISSUED")
    accounts = api("GET", base + "/ledger/accounts", token=token)
    clearing = next(account for account in accounts if account["code"] == "STRIPE_CLEARING")
    if Decimal(str(clearing["debitMinusCredit"])) != 0:
        raise RuntimeError("Expected zero clearing balance after full refund")
    print("Verified a real Stripe test payment, payment/refund retries, webhooks, and ledger effects.")
    print("Synthetic records remain locally and in Stripe Sandbox. Secrets were not printed.")


if __name__ == "__main__":
    main()
