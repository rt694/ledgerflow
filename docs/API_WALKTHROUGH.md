# Trying the API

Start the app with the [local setup instructions](LOCAL_DEVELOPMENT.md), then open Swagger UI at `http://localhost:18080/swagger-ui/index.html`. Use only fake names and `example.test` email addresses.

## Register and log in

Try `POST /api/v1/auth/register`:

```json
{"email":"student@example.test","displayName":"Student","password":"synthetic-password-123"}
```

Then `POST /api/v1/auth/login` with the same email/password. Copy `accessToken` into Swagger's **Authorize** field. Access tokens last 15 minutes, with 30 seconds of validation clock skew. Log in again when one expires; refresh tokens and server-side logout aren't built yet. There are no authentication cookies, browser sessions, or HTTP Basic login.

`GET /api/v1/auth/me` returns your ID, email, and display name. Passwords are stored using salted BCrypt with cost 12. Passwords need at least 12 characters and at most 72 UTF-8 bytes; multibyte characters can reach the byte limit before the character limit.

## Create an organization and add members

Try `POST /api/v1/organizations` with `{"name":"Synthetic shop"}`. Save the returned ID; your membership is OWNER.

Members first register their own accounts. An owner can add an existing account with `POST /api/v1/organizations/{organizationId}/members`:

```json
{"email":"employee@example.test","role":"EMPLOYEE"}
```

Owners can list members, change a member's role with PUT, or remove a membership with DELETE. These aren't email invitations and they don't send messages. An organization must keep at least one owner. Role changes and removals affect the next organization authorization check even when the user keeps the same JWT.

| Action | OWNER | EMPLOYEE | ACCOUNTANT |
| --- | --- | --- | --- |
| Read organization, customers, and invoices | Yes | Yes | Yes |
| Manage memberships | Yes | No | No |
| Create/edit customers | Yes | Yes | No |
| Create/edit draft invoices | Yes | Yes | Yes |
| Issue/void invoices | Yes | No | Yes |

Roles belong to an organization, not a global user. A user can have different roles in different organizations. A nonmember gets 404 for organization resources; a member with the wrong role gets 403. There is no way to choose a role during registration or grant permissions through JWT role claims.

## Create a customer

Try `POST /api/v1/organizations/{organizationId}/customers`:

```json
{"name":"Synthetic customer","email":"customer@example.test"}
```

Save the customer ID. GET lists/reads customers; PUT replaces a customer's name/email and requires the current `version`. Customer deletion isn't supported because future invoices need their references to remain valid.

## Create an invoice

Try `POST /api/v1/organizations/{organizationId}/invoices`:

```json
{
  "customerId":"replace-with-customer-id",
  "number":"INV-001",
  "currency":"USD",
  "dueDate":"replace-with-today-or-a-future-date",
  "lines":[
    {"description":"Synthetic service","quantity":3,"unitPrice":"19.99","taxRate":"0.0825"}
  ]
}
```

The total is 64.92: subtotal 59.97 plus tax 4.95. Rates are fractions (`0.0825` means 8.25%). Requests can send decimals as strings to avoid a client floating-point calculation. The server calculates amounts and ignores any client-supplied totals.

This first version supports USD only. Each invoice has 1–100 lines, integer quantities of 1–10000, nonnegative unit prices with at most two decimal places and nine integer digits, and tax rates from 0 to 1 with at most four decimal places. Zero-price items are allowed. Tax rounds HALF_UP on each line, then sums. Two 0.05 lines at 10% tax produce 0.02 tax; rounding the aggregate would produce 0.01, so the policy matters.

Numbers are unique within an organization. A customer from another organization is rejected by both the application and a composite database foreign key. Due dates must be today or later when creating/editing/issuing. Dates use UTC for application checks.

## Edit, issue, and void

An invoice starts in DRAFT. PUT replaces all draft content and lines; include the current `version` with the same fields used at creation.

Owners/accountants can call:

- `POST .../invoices/{id}/issue` with `{"version":0}` to move a new draft to ISSUED.
- `POST .../invoices/{id}/void` with the returned current version to move DRAFT or ISSUED to VOID.

Every successful write increments the version. Stale versions return 409; read the current invoice before trying again. Writes hold a scoped row lock while checking the version, so two requests using the same version cannot both succeed.

ISSUED content cannot be edited or issued again. Customer name/email is refreshed at issuance and then stays fixed even if the customer changes. VOID is terminal. There are no invoice deletes or PAID transitions yet. Issuing/voiding does not create ledger entries; ledger and payment workflows come next.

Lists use `page` (zero-based) and `size` (1–100, default 20). Responses contain `items`, `page`, `size`, and `totalElements`, ordered by creation time and ID.

## Checking failures

Try a protected request without a token (401), a different user's organization (404), an employee issuing an invoice (403), an invalid amount (400), and an edit with an old version (409). The [smoke script](../scripts/smoke-workflow.py) automates a small synthetic workflow without printing tokens.
