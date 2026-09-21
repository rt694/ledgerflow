ALTER TABLE ledgerflow.ledger_accounts DROP CONSTRAINT ledger_accounts_code_check;
ALTER TABLE ledgerflow.ledger_accounts ADD CONSTRAINT ledger_accounts_code_check
  CHECK (code IN (
    'RECEIVABLES','REVENUE','TAX_PAYABLE','CASH','STRIPE_CLEARING',
    'PAYOUTS_IN_TRANSIT','PROCESSING_FEES'));
INSERT INTO ledgerflow.ledger_accounts(organization_id,code)
  SELECT id,code FROM ledgerflow.organizations
  CROSS JOIN unnest(ARRAY['PAYOUTS_IN_TRANSIT','PROCESSING_FEES']) code;
CREATE OR REPLACE FUNCTION ledgerflow.seed_accounts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO ledgerflow.ledger_accounts(organization_id,code)
    SELECT NEW.id,code FROM unnest(ARRAY[
      'RECEIVABLES','REVENUE','TAX_PAYABLE','CASH','STRIPE_CLEARING',
      'PAYOUTS_IN_TRANSIT','PROCESSING_FEES']) code;
  RETURN NEW;
END $$;

CREATE TABLE ledgerflow.stripe_payouts (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL REFERENCES ledgerflow.organizations(id),
  provider_id VARCHAR(255) NOT NULL UNIQUE,
  currency VARCHAR(3) NOT NULL CHECK (currency='USD'),
  gross NUMERIC(19,2) NOT NULL CHECK (gross>0),
  fee NUMERIC(19,2) NOT NULL CHECK (fee>=0),
  amount NUMERIC(19,2) NOT NULL CHECK (amount>0),
  arrival_date DATE NOT NULL,
  imported_at TIMESTAMPTZ NOT NULL,
  UNIQUE (organization_id,id),
  CHECK (gross-fee=amount)
);

CREATE TABLE ledgerflow.stripe_payout_allocations (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  payout_id UUID NOT NULL,
  payment_id UUID NOT NULL,
  provider_balance_transaction_id VARCHAR(255) NOT NULL UNIQUE,
  gross NUMERIC(19,2) NOT NULL CHECK (gross>0),
  fee NUMERIC(19,2) NOT NULL CHECK (fee>=0),
  net NUMERIC(19,2) NOT NULL CHECK (net>0),
  UNIQUE (payment_id),
  FOREIGN KEY (organization_id,payout_id)
    REFERENCES ledgerflow.stripe_payouts(organization_id,id),
  FOREIGN KEY (organization_id,payment_id)
    REFERENCES ledgerflow.payments(organization_id,id),
  CHECK (gross-fee=net)
);

CREATE TRIGGER immutable_stripe_payouts
  BEFORE UPDATE OR DELETE ON ledgerflow.stripe_payouts
  FOR EACH ROW EXECUTE FUNCTION ledgerflow.reject_ledger_mutation();
CREATE TRIGGER immutable_stripe_payout_allocations
  BEFORE UPDATE OR DELETE ON ledgerflow.stripe_payout_allocations
  FOR EACH ROW EXECUTE FUNCTION ledgerflow.reject_ledger_mutation();

ALTER TABLE ledgerflow.journal_transactions ADD COLUMN payout_id UUID;
ALTER TABLE ledgerflow.journal_transactions ADD CONSTRAINT journal_payout_fk
  FOREIGN KEY (organization_id,payout_id)
  REFERENCES ledgerflow.stripe_payouts(organization_id,id);
ALTER TABLE ledgerflow.journal_transactions DROP CONSTRAINT journal_operation_check;
ALTER TABLE ledgerflow.journal_transactions ADD CONSTRAINT journal_operation_check
  CHECK (operation IN (
    'ISSUE','VOID','PAYMENT','REFUND','DISPUTE_OUT','DISPUTE_IN',
    'BANK_PAYMENT','STRIPE_PAYOUT'));
ALTER TABLE ledgerflow.journal_transactions DROP CONSTRAINT journal_source_check;
ALTER TABLE ledgerflow.journal_transactions ADD CONSTRAINT journal_source_check CHECK (
  (operation IN ('ISSUE','VOID')
    AND payment_id IS NULL AND bank_transaction_id IS NULL
    AND payout_id IS NULL AND source_key IS NULL) OR
  (operation IN ('PAYMENT','REFUND','DISPUTE_OUT','DISPUTE_IN')
    AND payment_id IS NOT NULL AND bank_transaction_id IS NULL
    AND payout_id IS NULL AND source_key IS NOT NULL) OR
  (operation='BANK_PAYMENT'
    AND payment_id IS NULL AND bank_transaction_id IS NOT NULL
    AND payout_id IS NULL AND source_key IS NOT NULL) OR
  (operation='STRIPE_PAYOUT'
    AND payment_id IS NOT NULL AND bank_transaction_id IS NULL
    AND payout_id IS NOT NULL AND source_key IS NOT NULL));
CREATE INDEX stripe_payout_journals ON ledgerflow.journal_transactions(payout_id)
  WHERE payout_id IS NOT NULL;
