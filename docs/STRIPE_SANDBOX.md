# Trying sandbox payments

I added the Stripe integration locally and tested it with signed fixtures, PostgreSQL, and the actual SDK against a local HTTP fixture server. An account-backed Stripe Sandbox run is still pending. No real money, card numbers, or bank credentials are used.

## Local setup

Create or select a sandbox in the [Stripe dashboard](https://dashboard.stripe.com/) and copy its test secret key into the repository's ignored `.env`. The key must start with `sk_test_` or `rk_test_`; live keys are rejected even when payments are disabled. A restricted key needs payment-intent, refund, dispute, charge, payout, and balance-transaction read permissions. Do not put credentials in this document or chat.

Install the [Stripe CLI](https://docs.stripe.com/stripe-cli). On this development machine, version 1.51.0 is already installed in `~/.local/bin` and verified against the official release checksum. If your terminal cannot find it, run `export PATH="$HOME/.local/bin:$PATH"`. Then run these in a separate terminal from the repository root:

```sh
stripe login
stripe listen --events payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled,refund.created,refund.updated,refund.failed,charge.dispute.created,charge.dispute.updated,charge.dispute.closed,charge.dispute.funds_withdrawn,charge.dispute.funds_reinstated --forward-to http://127.0.0.1:18080/api/v1/webhooks/stripe
```

Use the same sandbox for the CLI and API key. The listener prints a `whsec_` signing secret; save it locally as `STRIPE_WEBHOOK_SECRET`. This is the CLI listener's secret, which can differ from a dashboard webhook endpoint's secret. Set `STRIPE_ENABLED=true`. Keep the listener running and restart the backend using [local setup](LOCAL_DEVELOPMENT.md), loading `.env` again. With payments disabled, intent creation/webhooks return a safe 503; other features keep working.

From the repository root, with the backend and listener running:

```sh
python3 scripts/smoke-stripe-sandbox.py
```

This creates synthetic users, an organization, a customer, and a 64.92 invoice. It requests/replays a payment intent, confirms it with Stripe's `pm_card_visa` test method, waits for the signed webhook to mark the invoice PAID, requests/replays a full refund, and checks that the invoice returns to ISSUED and clearing returns to zero. It reads the test key from `.env` or the environment and never prints keys, JWTs, client secrets, or provider response bodies. Demo records remain in both systems. This script has not yet been run against an account.

## Requests to try

In Swagger, authorize as an owner/accountant and issue a positive USD invoice. Then call `POST /api/v1/organizations/{organizationId}/payments` with:

```json
{"invoiceId":"replace-with-issued-invoice-id"}
```

Set the required `Idempotency-Key` header to a unique value such as `invoice-demo-1`. Keys contain 1–128 letters, numbers, dots, underscores, or hyphens. Keep the same key for retries. The 200 response includes the local payment ID, Stripe intent ID, client secret, and local state; responses use `Cache-Control: no-store`. Client secrets aren't stored in PostgreSQL or included in payment reads. A future frontend will pass them to Stripe.js; don't copy them into logs or URLs.

Amounts come from the invoice, never from the request. This USD/card-only version supports 0.50–999999.99, matching [Stripe's intent bounds](https://docs.stripe.com/api/payment_intents/create). It creates one intent per invoice. Reusing its key replays that attempt; another key for the same invoice, or the same key for another invoice, returns 409. Network errors preserve the attempt so a retry uses the same provider key. Unresolved creation/refund requests older than 23 hours require recovery rather than risking a new operation after Stripe's idempotency retention window. Automated recovery is still to build.

From the repository root, after saving the returned `pi_...` ID, confirm only with a test method:

```sh
stripe payment_intents confirm replace-with-pi-id --payment-method pm_card_visa
```

For a failure, use `pm_card_chargeDeclined` on a different invoice/intent. A declined confirmation reports an error; its webhook sets the local attempt FAILED with no payment posting. The same intent can later succeed. Generic `stripe trigger` fixtures lack this app's metadata, so they exercise forwarding/signatures but won't settle one of our invoices.

Use `GET .../payments/{id}` for the local payment state. All organization members can read. Owner/accountant writes are scoped; nonmembers get 404 and employees get 403.

Use `POST .../payments/{id}/cancel` to cancel an unpaid intent. Its stable provider key makes cancellation retries safe. Finish creation first if the attempt has no provider ID. Active or successful payment history blocks invoice voiding; a canceled unpaid intent permits it. Refunded paid invoices need a future credit-note workflow rather than voiding financial history.

Use `POST .../payments/{id}/refunds` with a new required `Idempotency-Key` to request one full refund. The 202 response is an accepted request, not proof of ledger completion. Retry with the same key. Only a successful refund webhook moves money; pending/failed refunds do not. There is no second full-refund request or partial-refund request API. Verified partial refunds created in Stripe are supported by the webhook handler and reopen the appropriate receivable.

For a paid test payout, call `POST .../stripe-payouts/import` as an owner or accountant:

```json
{"providerPayoutId":"po_replace_me"}
```

The payout ID itself is the retry key. LedgerFlow retrieves the payout and its balance transactions from Stripe, rather than trusting amounts supplied by the caller. It currently accepts charge-only payouts when every charge maps to a completed local payment in the requested organization. Mixed-organization payouts, unknown charges, refunds or adjustments inside the payout, live-mode data, duplicate payment allocations, and totals that do not balance are rejected atomically. This narrow rule avoids assigning a shared platform payout to the wrong tenant. List saved payouts at `GET .../stripe-payouts` and inspect allocations at `GET .../stripe-payouts/{id}`.

## Money and event handling

A successful payment debits STRIPE_CLEARING and credits RECEIVABLES. A successful refund does the opposite; it doesn't cancel revenue. A dispute-created event records notification history; `funds_withdrawn` debits receivables/credits clearing, and `funds_reinstated` restores them.

A verified payout credits STRIPE_CLEARING for each gross charge, debits PROCESSING_FEES for Stripe's fee, and debits PAYOUTS_IN_TRANSIT for the net. It does not debit CASH yet because the imported Stripe report is separate from the bank statement. Matching the payout to its bank deposit is the next reconciliation step. Payout reversals, payout lines for refunds or adjustments, revenue credit notes, dispute fees, and dispute evidence submissions aren't implemented.

Webhook authentication checks the raw request body with the Stripe SDK, a five-minute signature tolerance, and a future-time guard. Live-mode events are rejected. The handler retrieves current Stripe objects instead of trusting stale snapshots, verifies amounts/currency and organization/invoice/payment metadata, and stores only event identifiers/type/payment/time. Raw events and card details aren't stored.

Event IDs deduplicate deliveries; provider intent/refund/dispute references deduplicate financial effects even if different event IDs describe the same effect. A refund can arrive before a payment notification: the canonical successful intent is posted first. If dispute reinstatement arrives first, withdrawal and restoration are both recorded once, so a later withdrawal delivery doesn't remove money again. Delayed failure events never regress a successful payment.

Provider retrieval happens before the short database transaction. Invoice settlement, journal entries, and the processed-event record commit together. A failure rolls everything back and returns a non-2xx response for retry. This is synchronous handling for now; durable asynchronous inbox/outbox processing, backoff workers, and manual recovery come later. No performance or availability claims have been measured.

## Questions I can explain

- Why does creating an intent differ from confirming a payment?
- Why do external API calls stay outside database transactions?
- Why do both event IDs and business-object references need deduplication?
- Why is a Stripe clearing balance different from cash in a bank?
- Why is a paid provider payout recorded in transit before the bank deposit is reviewed?
- Why must every payout line resolve to the same LedgerFlow organization before anything posts?
- Why does a payment refund reopen receivables without reducing revenue?
- What happens if Stripe succeeds but the client never receives the response?
