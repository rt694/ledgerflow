CREATE TABLE ledgerflow.customers (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES ledgerflow.organizations(id),
    name VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    email VARCHAR(254) NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (organization_id, id)
);
CREATE INDEX customer_org_idx ON ledgerflow.customers(organization_id, created_at, id);
CREATE TABLE ledgerflow.invoices (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES ledgerflow.organizations(id),
    customer_id UUID NOT NULL,
    customer_name VARCHAR(200) NOT NULL,
    customer_email VARCHAR(254) NOT NULL,
    invoice_number VARCHAR(64) NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency = 'USD'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'ISSUED', 'VOID')),
    due_date DATE NOT NULL,
    subtotal NUMERIC(19,2) NOT NULL CHECK (subtotal >= 0),
    tax NUMERIC(19,2) NOT NULL CHECK (tax >= 0),
    total NUMERIC(19,2) NOT NULL CHECK (total = subtotal + tax),
    version BIGINT NOT NULL CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    issued_at TIMESTAMPTZ,
    voided_at TIMESTAMPTZ,
    UNIQUE (organization_id, id),
    UNIQUE (organization_id, invoice_number),
    FOREIGN KEY (organization_id, customer_id) REFERENCES ledgerflow.customers(organization_id, id),
    CONSTRAINT invoice_state_dates CHECK (
        (status = 'DRAFT' AND issued_at IS NULL AND voided_at IS NULL) OR
        (status = 'ISSUED' AND issued_at IS NOT NULL AND voided_at IS NULL) OR
        (status = 'VOID' AND voided_at IS NOT NULL)
    )
);
CREATE INDEX invoice_org_idx ON ledgerflow.invoices(organization_id, created_at, id);
CREATE TABLE ledgerflow.invoice_lines (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    invoice_id UUID NOT NULL,
    line_position INTEGER NOT NULL CHECK (line_position >= 0),
    description VARCHAR(500) NOT NULL CHECK (length(trim(description)) > 0),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 10000),
    unit_price NUMERIC(11,2) NOT NULL CHECK (unit_price >= 0),
    tax_rate NUMERIC(5,4) NOT NULL CHECK (tax_rate BETWEEN 0 AND 1),
    subtotal NUMERIC(19,2) NOT NULL CHECK (subtotal = unit_price * quantity),
    tax NUMERIC(19,2) NOT NULL CHECK (tax = round(subtotal * tax_rate, 2)),
    total NUMERIC(19,2) NOT NULL CHECK (total = subtotal + tax),
    FOREIGN KEY (organization_id, invoice_id) REFERENCES ledgerflow.invoices(organization_id, id),
    -- JPA may insert replacement lines before deleting the old ones; check order at commit.
    UNIQUE (invoice_id, line_position) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX invoice_line_parent_idx ON ledgerflow.invoice_lines(invoice_id);
