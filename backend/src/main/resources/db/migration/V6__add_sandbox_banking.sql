CREATE TABLE ledgerflow.bank_connections (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES ledgerflow.organizations(id),
    idempotency_hash CHAR(64) NOT NULL,
    public_token_hash CHAR(64) NOT NULL,
    provider_item_id VARCHAR(128) UNIQUE,
    access_token_cipher BYTEA,
    access_token_iv BYTEA,
    sync_cursor VARCHAR(512),
    state VARCHAR(24) NOT NULL DEFAULT 'EXCHANGING'
        CHECK (state IN ('EXCHANGING', 'ACTIVE', 'LOGIN_REQUIRED', 'ERROR')),
    error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    last_synced_at TIMESTAMPTZ,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, idempotency_hash),
    UNIQUE (organization_id, provider_item_id),
    CHECK ((state = 'EXCHANGING' AND provider_item_id IS NULL AND access_token_cipher IS NULL AND access_token_iv IS NULL)
        OR (state <> 'EXCHANGING' AND provider_item_id IS NOT NULL AND access_token_cipher IS NOT NULL AND access_token_iv IS NOT NULL))
);

CREATE TABLE ledgerflow.bank_accounts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    provider_account_id VARCHAR(128) NOT NULL,
    name VARCHAR(200) NOT NULL,
    official_name VARCHAR(200),
    mask VARCHAR(12),
    account_type VARCHAR(40) NOT NULL,
    account_subtype VARCHAR(80),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    current_balance NUMERIC(19,4),
    available_balance NUMERIC(19,4),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, provider_account_id),
    FOREIGN KEY (organization_id, connection_id)
        REFERENCES ledgerflow.bank_connections(organization_id, id)
);

CREATE TABLE ledgerflow.bank_transactions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    bank_account_id UUID NOT NULL,
    provider_transaction_id VARCHAR(128) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    transaction_date DATE NOT NULL,
    authorized_date DATE,
    name VARCHAR(500) NOT NULL,
    merchant_name VARCHAR(300),
    pending BOOLEAN NOT NULL,
    pending_provider_transaction_id VARCHAR(128),
    removed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, provider_transaction_id),
    FOREIGN KEY (organization_id, connection_id)
        REFERENCES ledgerflow.bank_connections(organization_id, id),
    FOREIGN KEY (organization_id, bank_account_id)
        REFERENCES ledgerflow.bank_accounts(organization_id, id)
);

CREATE TABLE ledgerflow.bank_transaction_changes (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    transaction_id UUID,
    provider_transaction_id VARCHAR(128) NOT NULL,
    change_type VARCHAR(10) NOT NULL CHECK (change_type IN ('ADDED', 'MODIFIED', 'REMOVED')),
    amount NUMERIC(19,4),
    currency CHAR(3),
    transaction_date DATE,
    name VARCHAR(500),
    pending BOOLEAN,
    observed_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (organization_id, connection_id)
        REFERENCES ledgerflow.bank_connections(organization_id, id),
    FOREIGN KEY (organization_id, transaction_id)
        REFERENCES ledgerflow.bank_transactions(organization_id, id)
);

CREATE INDEX bank_connections_org_idx ON ledgerflow.bank_connections(organization_id);
CREATE INDEX bank_accounts_connection_idx ON ledgerflow.bank_accounts(organization_id, connection_id);
CREATE INDEX bank_transactions_date_idx ON ledgerflow.bank_transactions(organization_id, transaction_date DESC, id);
CREATE INDEX bank_changes_connection_idx ON ledgerflow.bank_transaction_changes(connection_id, observed_at);

CREATE FUNCTION ledgerflow.reject_bank_change_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Bank transaction history is append-only';
END;
$$;

CREATE TRIGGER bank_changes_no_update
BEFORE UPDATE OR DELETE ON ledgerflow.bank_transaction_changes
FOR EACH ROW EXECUTE FUNCTION ledgerflow.reject_bank_change_mutation();
