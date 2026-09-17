CREATE TABLE ledgerflow.ledger_accounts (
  organization_id UUID NOT NULL REFERENCES ledgerflow.organizations(id),
  code VARCHAR(32) NOT NULL CHECK (code IN ('RECEIVABLES','REVENUE','TAX_PAYABLE','CASH')),
  currency VARCHAR(3) NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
  PRIMARY KEY (organization_id, code, currency)
);
CREATE FUNCTION ledgerflow.seed_accounts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO ledgerflow.ledger_accounts(organization_id, code)
    SELECT NEW.id, code FROM unnest(ARRAY['RECEIVABLES','REVENUE','TAX_PAYABLE','CASH']) code;
  RETURN NEW;
END $$;
CREATE TRIGGER organization_accounts AFTER INSERT ON ledgerflow.organizations
  FOR EACH ROW EXECUTE FUNCTION ledgerflow.seed_accounts();
INSERT INTO ledgerflow.ledger_accounts(organization_id, code)
  SELECT id, code FROM ledgerflow.organizations CROSS JOIN
  unnest(ARRAY['RECEIVABLES','REVENUE','TAX_PAYABLE','CASH']) code;

CREATE TABLE ledgerflow.journal_transactions (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL REFERENCES ledgerflow.organizations(id),
  invoice_id UUID NOT NULL,
  operation VARCHAR(8) NOT NULL CHECK (operation IN ('ISSUE','VOID')),
  currency VARCHAR(3) NOT NULL CHECK (currency = 'USD'),
  posted_at TIMESTAMPTZ NOT NULL,
  reverses_id UUID UNIQUE,
  UNIQUE (organization_id, id, currency),
  UNIQUE (organization_id, invoice_id, operation),
  FOREIGN KEY (organization_id, invoice_id) REFERENCES ledgerflow.invoices(organization_id,id),
  FOREIGN KEY (organization_id, reverses_id, currency)
    REFERENCES ledgerflow.journal_transactions(organization_id,id,currency),
  CHECK ((operation = 'ISSUE' AND reverses_id IS NULL) OR
         (operation = 'VOID' AND reverses_id IS NOT NULL AND reverses_id <> id))
);
CREATE INDEX journal_org_posted ON ledgerflow.journal_transactions(organization_id,posted_at,id);
CREATE TABLE ledgerflow.journal_entries (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  journal_id UUID NOT NULL,
  account_code VARCHAR(32) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  debit NUMERIC(19,2) NOT NULL,
  credit NUMERIC(19,2) NOT NULL,
  CHECK ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0)),
  UNIQUE (journal_id, account_code),
  FOREIGN KEY (organization_id,journal_id,currency)
    REFERENCES ledgerflow.journal_transactions(organization_id,id,currency),
  FOREIGN KEY (organization_id,account_code,currency)
    REFERENCES ledgerflow.ledger_accounts(organization_id,code,currency)
);
CREATE INDEX entry_account ON ledgerflow.journal_entries(organization_id,account_code,currency);

CREATE FUNCTION ledgerflow.reject_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Ledger records are immutable' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER immutable_accounts BEFORE UPDATE OR DELETE ON ledgerflow.ledger_accounts
  FOR EACH ROW EXECUTE FUNCTION ledgerflow.reject_ledger_mutation();
CREATE TRIGGER immutable_journals BEFORE UPDATE OR DELETE ON ledgerflow.journal_transactions
  FOR EACH ROW EXECUTE FUNCTION ledgerflow.reject_ledger_mutation();
CREATE TRIGGER immutable_entries BEFORE UPDATE OR DELETE ON ledgerflow.journal_entries
  FOR EACH ROW EXECUTE FUNCTION ledgerflow.reject_ledger_mutation();

-- Entries may only be assembled in the transaction that inserted their header.
-- Locking the header also prevents concurrent attempts to append entries.
CREATE FUNCTION ledgerflow.guard_entry_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE header_xid BIGINT;
BEGIN
  SELECT xmin::text::bigint INTO header_xid FROM ledgerflow.journal_transactions
    WHERE id = NEW.journal_id FOR UPDATE;
  IF header_xid IS DISTINCT FROM (txid_current() % 4294967296) THEN
    RAISE EXCEPTION 'Cannot append to a posted journal' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER entry_insert_guard BEFORE INSERT ON ledgerflow.journal_entries
  FOR EACH ROW EXECUTE FUNCTION ledgerflow.guard_entry_insert();

CREATE FUNCTION ledgerflow.check_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE line_count INTEGER; debits NUMERIC; credits NUMERIC; original_invoice UUID;
BEGIN
  SELECT count(*), sum(debit), sum(credit) INTO line_count,debits,credits
    FROM ledgerflow.journal_entries WHERE journal_id = NEW.id;
  IF line_count < 2 OR debits IS DISTINCT FROM credits THEN
    RAISE EXCEPTION 'Journal must have balanced entries' USING ERRCODE = '23514';
  END IF;
  IF NEW.reverses_id IS NOT NULL THEN
    SELECT invoice_id INTO original_invoice FROM ledgerflow.journal_transactions
      WHERE id = NEW.reverses_id AND operation = 'ISSUE';
    IF original_invoice IS DISTINCT FROM NEW.invoice_id OR EXISTS (
      (SELECT account_code,debit,credit FROM ledgerflow.journal_entries WHERE journal_id = NEW.id
       EXCEPT SELECT account_code,credit,debit FROM ledgerflow.journal_entries WHERE journal_id = NEW.reverses_id)
      UNION ALL
      (SELECT account_code,credit,debit FROM ledgerflow.journal_entries WHERE journal_id = NEW.reverses_id
       EXCEPT SELECT account_code,debit,credit FROM ledgerflow.journal_entries WHERE journal_id = NEW.id)
    ) THEN
      RAISE EXCEPTION 'Reversal must exactly reverse its original' USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_balanced AFTER INSERT ON ledgerflow.journal_transactions
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledgerflow.check_journal();

-- Preserve existing synthetic invoice history; no entries for zero-dollar invoices.
INSERT INTO ledgerflow.journal_transactions(id,organization_id,invoice_id,operation,currency,posted_at)
 SELECT gen_random_uuid(),organization_id,id,'ISSUE',currency,issued_at
 FROM ledgerflow.invoices WHERE issued_at IS NOT NULL AND total > 0;
INSERT INTO ledgerflow.journal_entries(id,organization_id,journal_id,account_code,currency,debit,credit)
 SELECT gen_random_uuid(),j.organization_id,j.id,v.code,j.currency,v.debit,v.credit
 FROM ledgerflow.journal_transactions j JOIN ledgerflow.invoices i ON i.id = j.invoice_id
 CROSS JOIN LATERAL (VALUES ('RECEIVABLES',i.total,0::numeric),
 ('REVENUE',0::numeric,i.subtotal),('TAX_PAYABLE',0::numeric,i.tax)) v(code,debit,credit)
 WHERE v.debit + v.credit > 0;
INSERT INTO ledgerflow.journal_transactions(id,organization_id,invoice_id,operation,currency,posted_at,reverses_id)
 SELECT gen_random_uuid(),j.organization_id,i.id,'VOID',j.currency,i.voided_at,j.id
 FROM ledgerflow.journal_transactions j JOIN ledgerflow.invoices i ON i.id = j.invoice_id
 WHERE i.status = 'VOID';
INSERT INTO ledgerflow.journal_entries(id,organization_id,journal_id,account_code,currency,debit,credit)
 SELECT gen_random_uuid(),j.organization_id,j.id,e.account_code,j.currency,e.credit,e.debit
 FROM ledgerflow.journal_transactions j JOIN ledgerflow.journal_entries e ON e.journal_id = j.reverses_id
 WHERE j.operation = 'VOID';
