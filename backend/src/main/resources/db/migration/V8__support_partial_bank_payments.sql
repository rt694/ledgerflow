DROP INDEX ledgerflow.one_reconciliation_per_invoice;

ALTER TABLE ledgerflow.reconciliation_cases ADD COLUMN matched_amount NUMERIC(19,2);
UPDATE ledgerflow.reconciliation_cases c
SET matched_amount=e.credit
FROM ledgerflow.journal_transactions j
JOIN ledgerflow.journal_entries e
  ON e.organization_id=j.organization_id
 AND e.journal_id=j.id
 AND e.account_code='RECEIVABLES'
WHERE c.organization_id=j.organization_id
  AND c.bank_transaction_id=j.bank_transaction_id
  AND c.status='MATCHED';
ALTER TABLE ledgerflow.reconciliation_cases
  ADD CONSTRAINT reconciliation_case_match_amount_check CHECK (
    (status='MATCHED' AND matched_amount IS NOT NULL AND matched_amount>0) OR
    (status<>'MATCHED' AND matched_amount IS NULL));

ALTER TABLE ledgerflow.reconciliation_candidates
  DROP CONSTRAINT reconciliation_candidates_rule_check;
ALTER TABLE ledgerflow.reconciliation_candidates
  ADD CONSTRAINT reconciliation_candidates_rule_check
  CHECK (rule IN (
    'EXACT_AMOUNT_DATE_WINDOW',
    'REFERENCE_AND_EXACT_AMOUNT',
    'REFERENCE_PARTIAL_AMOUNT'));

ALTER TABLE ledgerflow.reconciliation_decisions ADD COLUMN amount NUMERIC(19,2);
UPDATE ledgerflow.reconciliation_decisions d
SET amount=c.matched_amount
FROM ledgerflow.reconciliation_cases c
WHERE d.organization_id=c.organization_id
  AND d.case_id=c.id
  AND d.decision='MATCHED';
ALTER TABLE ledgerflow.reconciliation_decisions
  ADD CONSTRAINT reconciliation_decision_amount_check CHECK (
    (decision='MATCHED' AND amount IS NOT NULL AND amount>0) OR
    (decision='IGNORED' AND amount IS NULL));
