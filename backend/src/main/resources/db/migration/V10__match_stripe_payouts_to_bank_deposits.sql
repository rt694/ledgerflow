ALTER TABLE ledgerflow.journal_transactions ALTER COLUMN invoice_id DROP NOT NULL;
ALTER TABLE ledgerflow.journal_transactions DROP CONSTRAINT journal_operation_check;
ALTER TABLE ledgerflow.journal_transactions ADD CONSTRAINT journal_operation_check
  CHECK (operation IN (
    'ISSUE','VOID','PAYMENT','REFUND','DISPUTE_OUT','DISPUTE_IN',
    'BANK_PAYMENT','STRIPE_PAYOUT','PAYOUT_DEPOSIT'));
ALTER TABLE ledgerflow.journal_transactions DROP CONSTRAINT journal_source_check;
ALTER TABLE ledgerflow.journal_transactions ADD CONSTRAINT journal_source_check CHECK (
  (operation IN ('ISSUE','VOID')
    AND invoice_id IS NOT NULL AND payment_id IS NULL
    AND bank_transaction_id IS NULL AND payout_id IS NULL AND source_key IS NULL) OR
  (operation IN ('PAYMENT','REFUND','DISPUTE_OUT','DISPUTE_IN')
    AND invoice_id IS NOT NULL AND payment_id IS NOT NULL
    AND bank_transaction_id IS NULL AND payout_id IS NULL AND source_key IS NOT NULL) OR
  (operation='BANK_PAYMENT'
    AND invoice_id IS NOT NULL AND payment_id IS NULL
    AND bank_transaction_id IS NOT NULL AND payout_id IS NULL AND source_key IS NOT NULL) OR
  (operation='STRIPE_PAYOUT'
    AND invoice_id IS NOT NULL AND payment_id IS NOT NULL
    AND bank_transaction_id IS NULL AND payout_id IS NOT NULL AND source_key IS NOT NULL) OR
  (operation='PAYOUT_DEPOSIT'
    AND invoice_id IS NULL AND payment_id IS NULL
    AND bank_transaction_id IS NOT NULL AND payout_id IS NOT NULL AND source_key IS NOT NULL));
CREATE UNIQUE INDEX one_deposit_per_payout
  ON ledgerflow.journal_transactions(payout_id)
  WHERE operation='PAYOUT_DEPOSIT';

ALTER TABLE ledgerflow.reconciliation_cases ADD COLUMN matched_payout_id UUID;
ALTER TABLE ledgerflow.reconciliation_cases ADD CONSTRAINT reconciliation_case_payout_fk
  FOREIGN KEY (organization_id,matched_payout_id)
  REFERENCES ledgerflow.stripe_payouts(organization_id,id);
ALTER TABLE ledgerflow.reconciliation_cases
  DROP CONSTRAINT reconciliation_cases_check;
ALTER TABLE ledgerflow.reconciliation_cases
  DROP CONSTRAINT reconciliation_case_match_amount_check;
ALTER TABLE ledgerflow.reconciliation_cases
  ADD CONSTRAINT reconciliation_case_match_check CHECK (
    (status='MATCHED'
      AND matched_amount IS NOT NULL AND matched_amount>0
      AND ((matched_invoice_id IS NOT NULL AND matched_payout_id IS NULL) OR
           (matched_invoice_id IS NULL AND matched_payout_id IS NOT NULL))) OR
    (status<>'MATCHED'
      AND matched_amount IS NULL
      AND matched_invoice_id IS NULL
      AND matched_payout_id IS NULL));
CREATE UNIQUE INDEX one_reconciliation_per_payout
  ON ledgerflow.reconciliation_cases(organization_id,matched_payout_id)
  WHERE status='MATCHED';

CREATE TABLE ledgerflow.payout_reconciliation_candidates (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  case_id UUID NOT NULL,
  payout_id UUID NOT NULL,
  score NUMERIC(5,4) NOT NULL CHECK (score BETWEEN 0 AND 1),
  rule VARCHAR(64) NOT NULL CHECK (rule IN (
    'PAYOUT_AMOUNT_DATE_WINDOW','PAYOUT_REFERENCE_AND_AMOUNT')),
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (case_id,payout_id),
  FOREIGN KEY (organization_id,case_id)
    REFERENCES ledgerflow.reconciliation_cases(organization_id,id) ON DELETE CASCADE,
  FOREIGN KEY (organization_id,payout_id)
    REFERENCES ledgerflow.stripe_payouts(organization_id,id)
);

ALTER TABLE ledgerflow.reconciliation_decisions ADD COLUMN payout_id UUID;
ALTER TABLE ledgerflow.reconciliation_decisions ADD CONSTRAINT reconciliation_decision_payout_fk
  FOREIGN KEY (organization_id,payout_id)
  REFERENCES ledgerflow.stripe_payouts(organization_id,id);
ALTER TABLE ledgerflow.reconciliation_decisions
  DROP CONSTRAINT reconciliation_decisions_check;
ALTER TABLE ledgerflow.reconciliation_decisions
  DROP CONSTRAINT reconciliation_decision_amount_check;
ALTER TABLE ledgerflow.reconciliation_decisions
  ADD CONSTRAINT reconciliation_decision_match_check CHECK (
    (decision='MATCHED'
      AND amount IS NOT NULL AND amount>0
      AND ((invoice_id IS NOT NULL AND payout_id IS NULL) OR
           (invoice_id IS NULL AND payout_id IS NOT NULL))) OR
    (decision='IGNORED'
      AND amount IS NULL AND invoice_id IS NULL AND payout_id IS NULL));
