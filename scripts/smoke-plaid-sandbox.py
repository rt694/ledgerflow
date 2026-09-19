#!/usr/bin/env python3
"""Connect one fake Plaid bank and ask LedgerFlow to import its transactions."""

import json
import os
import secrets
import urllib.error
import urllib.request

APP = os.getenv("LEDGERFLOW_URL", "http://localhost:18080")
CLIENT_ID = os.environ.get("PLAID_CLIENT_ID", "")
SECRET = os.environ.get("PLAID_SECRET", "")


def request(url, body=None, token=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    values = {"Content-Type": "application/json", **(headers or {})}
    if token:
        values["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=values, method="GET" if body is None else "POST")
    try:
        with urllib.request.urlopen(req) as response:
            content = response.read()
            return None if not content else json.loads(content)
    except urllib.error.HTTPError as error:
        detail = error.read().decode()
        raise RuntimeError(f"Request failed with HTTP {error.code}: {detail}") from error


if not CLIENT_ID or not SECRET:
    raise SystemExit("Set PLAID_CLIENT_ID and PLAID_SECRET to Sandbox credentials first.")

suffix = secrets.token_hex(6)
email = f"plaid-smoke-{suffix}@example.test"
password = f"synthetic-{suffix}-password"
request(f"{APP}/api/v1/auth/register", {"email": email, "displayName": "Plaid smoke", "password": password})
login = request(f"{APP}/api/v1/auth/login", {"email": email, "password": password})
token = login["accessToken"]
organization = request(f"{APP}/api/v1/organizations", {"name": "Plaid sandbox shop"}, token)["id"]

public_token = request(
    "https://sandbox.plaid.com/sandbox/public_token/create",
    {
        "client_id": CLIENT_ID,
        "secret": SECRET,
        "institution_id": "ins_109508",
        "initial_products": ["transactions"],
        "options": {
            "override_username": "user_transactions_dynamic",
            "override_password": "pass_good",
        },
    },
)["public_token"]

connection = request(
    f"{APP}/api/v1/organizations/{organization}/banking/connections",
    {"publicToken": public_token},
    token,
    {"Idempotency-Key": f"plaid-smoke-{suffix}"},
)
request(
    f"{APP}/api/v1/organizations/{organization}/banking/connections/{connection['id']}/sync",
    {},
    token,
)
accounts = request(
    f"{APP}/api/v1/organizations/{organization}/banking/connections/{connection['id']}/accounts",
    token=token,
)
transactions = request(
    f"{APP}/api/v1/organizations/{organization}/banking/transactions",
    token=token,
)
print(f"Plaid Sandbox check passed: {len(accounts)} accounts and {len(transactions)} current transactions imported.")
