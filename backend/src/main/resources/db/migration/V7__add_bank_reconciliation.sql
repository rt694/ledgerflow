ALTER TABLE ledgerflow.journal_transactions ADD COLUMN bank_transaction_id UUID;
ALTER TABLE ledgerflow.journal_transactions ADD CONSTRAINT journal_bank_transaction_fk
  FOREIGN KEY (organization_id,bank_transaction_id)
  REFERENCES ledgerflow.bank_transactions(organization_id,id);
ALTER TABLE ledgerflow.journal_transactions DROP CONSTRAINT journal_operation_check;
ALTER TABLE ledgerflow.journal_transactions ADD CONSTRAINT journal_operation_check
  CHECK (operation IN ('ISSUE','VOID','PAYMENT','REFUND','DISPUTE_OUT','DISPUTE_IN','BANK_PAYMENT'));
ALTER TABLE ledgerflow.journal_transactions DROP CONSTRAINT journal_source_check;
ALTER TABLE ledgerflow.journal_transactions ADD CONSTRAINT journal_source_check CHECK (
  (operation IN ('ISSUE','VOID') AND payment_id IS NULL AND bank_transaction_id IS NULL AND source_key IS NULL) OR
  (operation IN ('PAYMENT','REFUND','DISPUTE_OUT','DISPUTE_IN') AND payment_id IS NOT NULL AND bank_transaction_id IS NULL AND source_key IS NOT NULL) OR
  (operation='BANK_PAYMENT' AND payment_id IS NULL AND bank_transaction_id IS NOT NULL AND source_key IS NOT NULL));
CREATE UNIQUE INDEX bank_payment_journal ON ledgerflow.journal_transactions(bank_transaction_id)
  WHERE bank_transaction_id IS NOT NULL;

CREATE TABLE ledgerflow.reconciliation_cases (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL REFERENCES ledgerflow.organizations(id),
  bank_transaction_id UUID NOT NULL,
  status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN','MATCHED','IGNORED')),
  matched_invoice_id UUID,
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  UNIQUE (organization_id,id),
  UNIQUE (organization_id,bank_transaction_id),
  FOREIGN KEY (organization_id,bank_transaction_id)
    REFERENCES ledgerflow.bank_transactions(organization_id,id),
  FOREIGN KEY (organization_id,matched_invoice_id)
    REFERENCES ledgerflow.invoices(organization_id,id),
  CHECK ((status='MATCHED' AND matched_invoice_id IS NOT NULL) OR
         (status<>'MATCHED' AND matched_invoice_id IS NULL))
);
CREATE UNIQUE INDEX one_reconciliation_per_invoice
  ON ledgerflow.reconciliation_cases(organization_id,matched_invoice_id)
  WHERE status='MATCHED';
CREATE INDEX reconciliation_case_queue
  ON ledgerflow.reconciliation_cases(organization_id,status,created_at,id);

CREATE TABLE ledgerflow.reconciliation_candidates (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  case_id UUID NOT NULL,
  invoice_id UUID NOT NULL,
  score NUMERIC(5,4) NOT NULL CHECK (score BETWEEN 0 AND 1),
  rule VARCHAR(64) NOT NULL CHECK (rule IN ('EXACT_AMOUNT_DATE_WINDOW','REFERENCE_AND_EXACT_AMOUNT')),
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE (case_id,invoice_id),
  FOREIGN KEY (organization_id,case_id)
    REFERENCES ledgerflow.reconciliation_cases(organization_id,id) ON DELETE CASCADE,
  FOREIGN KEY (organization_id,invoice_id)
    REFERENCES ledgerflow.invoices(organization_id,id)
);

CREATE TABLE ledgerflow.reconciliation_decisions (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  case_id UUID NOT NULL,
  decision VARCHAR(16) NOT NULL CHECK (decision IN ('MATCHED','IGNORED')),
  invoice_id UUID,
  decided_by UUID NOT NULL REFERENCES ledgerflow.users(id),
  decided_at TIMESTAMPTZ NOT NULL,
  UNIQUE (case_id),
  FOREIGN KEY (organization_id,case_id)
    REFERENCES ledgerflow.reconciliation_cases(organization_id,id),
  FOREIGN KEY (organization_id,invoice_id)
    REFERENCES ledgerflow.invoices(organization_id,id),
  CHECK ((decision='MATCHED' AND invoice_id IS NOT NULL) OR
         (decision='IGNORED' AND invoice_id IS NULL))
);

CREATE FUNCTION ledgerflow.reject_reconciliation_decision_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Reconciliation decisions are append-only' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER reconciliation_decisions_no_mutation
  BEFORE UPDATE OR DELETE ON ledgerflow.reconciliation_decisions
  FOR EACH ROW EXECUTE FUNCTION ledgerflow.reject_reconciliation_decision_mutation();
