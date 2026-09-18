ALTER TABLE ledgerflow.invoices DROP CONSTRAINT invoices_status_check;
ALTER TABLE ledgerflow.invoices ADD CONSTRAINT invoices_status_check
 CHECK (status IN ('DRAFT','ISSUED','PAID','VOID'));
ALTER TABLE ledgerflow.invoices DROP CONSTRAINT invoice_state_dates;
ALTER TABLE ledgerflow.invoices ADD CONSTRAINT invoice_state_dates CHECK (
 (status='DRAFT' AND issued_at IS NULL AND voided_at IS NULL) OR
 (status IN ('ISSUED','PAID') AND issued_at IS NOT NULL AND voided_at IS NULL) OR
 (status='VOID' AND voided_at IS NOT NULL));

CREATE TABLE ledgerflow.payments (
 id UUID PRIMARY KEY,
 organization_id UUID NOT NULL REFERENCES ledgerflow.organizations(id),
 invoice_id UUID NOT NULL,
 currency VARCHAR(3) NOT NULL CHECK (currency='USD'),
 amount NUMERIC(19,2) NOT NULL CHECK (amount BETWEEN 0.50 AND 999999.99),
 idempotency_hash VARCHAR(64) NOT NULL,
 provider_id VARCHAR(255) UNIQUE,
 state VARCHAR(24) NOT NULL CHECK (state IN ('CREATING','PENDING','FAILED','SUCCEEDED','CANCELED')),
 created_at TIMESTAMPTZ NOT NULL,
 UNIQUE (organization_id,id), UNIQUE (organization_id,invoice_id),
 UNIQUE (organization_id,invoice_id,id),
 UNIQUE (organization_id,idempotency_hash),
 FOREIGN KEY (organization_id,invoice_id) REFERENCES ledgerflow.invoices(organization_id,id)
);
CREATE TABLE ledgerflow.refund_requests (
 id UUID PRIMARY KEY,
 organization_id UUID NOT NULL,
 payment_id UUID NOT NULL UNIQUE,
 amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
 currency VARCHAR(3) NOT NULL CHECK (currency='USD'),
 idempotency_hash VARCHAR(64) NOT NULL,
 provider_id VARCHAR(255) UNIQUE,
 created_at TIMESTAMPTZ NOT NULL,
 UNIQUE (organization_id,idempotency_hash),
 FOREIGN KEY (organization_id,payment_id) REFERENCES ledgerflow.payments(organization_id,id)
);
CREATE TABLE ledgerflow.stripe_events (
 id VARCHAR(255) PRIMARY KEY,
 type VARCHAR(100) NOT NULL,
 payment_id UUID NOT NULL REFERENCES ledgerflow.payments(id),
 received_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE ledgerflow.ledger_accounts DROP CONSTRAINT ledger_accounts_code_check;
ALTER TABLE ledgerflow.ledger_accounts ADD CONSTRAINT ledger_accounts_code_check
 CHECK (code IN ('RECEIVABLES','REVENUE','TAX_PAYABLE','CASH','STRIPE_CLEARING'));
INSERT INTO ledgerflow.ledger_accounts(organization_id,code)
 SELECT id,'STRIPE_CLEARING' FROM ledgerflow.organizations;
CREATE OR REPLACE FUNCTION ledgerflow.seed_accounts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO ledgerflow.ledger_accounts(organization_id,code)
 SELECT NEW.id,code FROM unnest(ARRAY['RECEIVABLES','REVENUE','TAX_PAYABLE','CASH','STRIPE_CLEARING']) code;
 RETURN NEW;
END $$;
ALTER TABLE ledgerflow.journal_transactions DROP CONSTRAINT journal_transactions_operation_check;
ALTER TABLE ledgerflow.journal_transactions DROP CONSTRAINT journal_transactions_check;
ALTER TABLE ledgerflow.journal_transactions DROP CONSTRAINT journal_transactions_organization_id_invoice_id_operation_key;
ALTER TABLE ledgerflow.journal_transactions ALTER COLUMN operation TYPE VARCHAR(24);
ALTER TABLE ledgerflow.journal_transactions ADD COLUMN payment_id UUID;
ALTER TABLE ledgerflow.journal_transactions ADD COLUMN source_key VARCHAR(255);
ALTER TABLE ledgerflow.journal_transactions ADD CONSTRAINT journal_payment_fk
 FOREIGN KEY (organization_id,invoice_id,payment_id) REFERENCES ledgerflow.payments(organization_id,invoice_id,id);
ALTER TABLE ledgerflow.journal_transactions ADD CONSTRAINT journal_operation_check
 CHECK (operation IN ('ISSUE','VOID','PAYMENT','REFUND','DISPUTE_OUT','DISPUTE_IN'));
ALTER TABLE ledgerflow.journal_transactions ADD CONSTRAINT journal_source_check CHECK (
 (operation IN ('ISSUE','VOID') AND payment_id IS NULL AND source_key IS NULL) OR
 (operation IN ('PAYMENT','REFUND','DISPUTE_OUT','DISPUTE_IN') AND payment_id IS NOT NULL AND source_key IS NOT NULL));
ALTER TABLE ledgerflow.journal_transactions ADD CONSTRAINT journal_reversal_check CHECK (
 (operation='VOID' AND reverses_id IS NOT NULL AND reverses_id<>id) OR
 (operation<>'VOID' AND reverses_id IS NULL));
CREATE UNIQUE INDEX invoice_journal_source ON ledgerflow.journal_transactions(organization_id,invoice_id,operation)
 WHERE operation IN ('ISSUE','VOID');
CREATE UNIQUE INDEX provider_journal_source ON ledgerflow.journal_transactions(organization_id,source_key)
 WHERE source_key IS NOT NULL;
CREATE INDEX payment_journals ON ledgerflow.journal_transactions(payment_id);

-- Keep a full transaction ID instead of comparing wrapping tuple xmin values.
ALTER TABLE ledgerflow.journal_transactions ADD COLUMN creating_transaction BIGINT NOT NULL DEFAULT txid_current();
ALTER TABLE ledgerflow.journal_transactions ADD CONSTRAINT journal_creating_transaction
 CHECK (creating_transaction = txid_current());
CREATE OR REPLACE FUNCTION ledgerflow.guard_entry_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE header_xid BIGINT;
BEGIN
 SELECT creating_transaction INTO header_xid FROM ledgerflow.journal_transactions
 WHERE id=NEW.journal_id FOR UPDATE;
 IF header_xid IS DISTINCT FROM txid_current() THEN
  RAISE EXCEPTION 'Cannot append to a posted journal' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
