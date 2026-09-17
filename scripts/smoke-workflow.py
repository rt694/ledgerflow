#!/usr/bin/env python3
"""Try a synthetic API workflow against the running local app; never print tokens."""
import json
import os
import urllib.error
import urllib.request
import uuid
from datetime import date, timedelta
from decimal import Decimal

BASE = os.environ.get("LEDGERFLOW_URL", "http://127.0.0.1:18080").rstrip("/")


def call(method, path, expected, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    payload = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=payload, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.status
        data = response.read()
    if status != expected:
        raise RuntimeError(f"{method} {path}: expected {expected}, got {status}")
    return json.loads(data, parse_float=Decimal) if data else None


def account(label):
    email = f"{label}-{uuid.uuid4()}@example.test"
    password = "synthetic-demo-password-123"
    call("POST", "/api/v1/auth/register", 201,
         {"email": email, "displayName": label, "password": password})
    return call("POST", "/api/v1/auth/login", 200,
                {"email": email, "password": password})["accessToken"]


def main():
    owner, outsider = account("DemoOwner"), account("DemoOutsider")
    org = call("POST", "/api/v1/organizations", 201, {"name": "Synthetic demo shop"}, owner)["id"]
    base = f"/api/v1/organizations/{org}"
    customer = call("POST", base + "/customers", 201,
                    {"name": "Synthetic customer", "email": "customer@example.test"}, owner)["id"]
    body = {"customerId": customer, "number": "DEMO-001", "currency": "USD",
            "dueDate": str(date.today() + timedelta(days=30)),
            "lines": [{"description": "Synthetic service", "quantity": 3,
                       "unitPrice": "19.99", "taxRate": "0.0825"}]}
    invoice = call("POST", base + "/invoices", 201, body, owner)
    if Decimal(str(invoice["total"])) != Decimal("64.92"):
        raise RuntimeError("Invoice total mismatch")
    path = base + "/invoices/" + invoice["id"]
    body["version"] = invoice["version"]
    draft = call("PUT", path, 200, body, owner)
    issued = call("POST", path + "/issue", 200, {"version": draft["version"]}, owner)
    body["version"] = issued["version"]
    call("PUT", path, 409, body, owner)
    call("GET", path, 404, token=outsider)
    voided = call("POST", path + "/void", 200, {"version": issued["version"]}, owner)
    if voided["status"] != "VOID":
        raise RuntimeError("Invoice transition mismatch")
    print("Verified registration, login, organization/customer creation, draft editing, issuing, and voiding.")
    print("Verified issued content cannot be edited and another user cannot read the invoice.")
    print("Synthetic demo records remain in the local database. Tokens were not printed.")


if __name__ == "__main__":
    main()
