# Trying the fake bank connection

LedgerFlow talks only to Plaid's free Sandbox environment. It can create a Link token, exchange a one-time public token, save the connected accounts, and import transaction changes. No real bank account is needed.

## Local setup

Copy `.env.example` to the ignored `.env` file and add your Plaid Sandbox client ID and secret. Make a separate encryption key for saved access tokens:

```sh
openssl rand -base64 32
```

Set these values in `.env`:

```text
PLAID_ENABLED=true
PLAID_CLIENT_ID=your-sandbox-client-id
PLAID_SECRET=your-sandbox-secret
PLAID_WEBHOOK_URL=https://your-public-test-url/api/v1/webhooks/plaid
PLAID_TOKEN_ENCRYPTION_KEY=the-generated-base64-value
```

Plaid needs a public HTTPS address to deliver a real webhook, so a local-only URL will not work for that part. Use a temporary HTTPS tunnel you trust and point it at port `18080`. The app still works with manual sync while developing locally.

Restart the backend after changing the environment. The app refuses to start in enabled mode if credentials, the encryption key, or the HTTPS webhook URL are missing.

## Quick account-backed check

With the backend running and the same Plaid variables exported in your terminal:

```sh
python3 scripts/smoke-plaid-sandbox.py
```

The script creates a throwaway LedgerFlow user and organization, asks Plaid for a Sandbox public token using its dynamic fake-transactions user, exchanges it through LedgerFlow, runs a sync, and prints only the account and transaction counts. It never prints either Plaid token.

You can also use the banking endpoints in Swagger. Start with `POST /banking/link-token`, open Plaid Link in a browser client with the returned token, and send Link's public token to `POST /banking/connections` with a new `Idempotency-Key` header.

## What the backend protects

- The permanent Plaid access token is encrypted with AES-256-GCM before storage. The organization and connection IDs are part of the authentication data, so ciphertext cannot be moved to another connection and decrypted there.
- API responses leave out access tokens, encryption bytes, cursors, public tokens, and idempotency hashes.
- A sync downloads every cursor page first, then stores the accounts, transaction changes, and new cursor in one database transaction.
- If Plaid reports a mutation during pagination, the whole page loop restarts from the original cursor. Concurrent syncs cannot advance the same cursor twice.
- Added, modified, and removed records update the current transaction view and append an immutable change record.
- Webhooks accept only ES256 signatures from Plaid's current verification key, a recent issue time, an exact raw-body hash, and the Sandbox environment.

Sandbox credentials are intentionally left out of Git. The automated suite uses synthetic provider responses and a real temporary PostgreSQL database; the account-backed script is a separate check because it needs your Plaid account and a network connection.
