CREATE TABLE ledgerflow.users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL CHECK (length(trim(display_name)) > 0),
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT normalized_email CHECK (email = lower(trim(email)))
);
CREATE TABLE ledgerflow.organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE ledgerflow.memberships (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES ledgerflow.organizations(id),
    user_id UUID NOT NULL REFERENCES ledgerflow.users(id),
    role VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'EMPLOYEE', 'ACCOUNTANT')),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (organization_id, user_id)
);
CREATE INDEX membership_user_idx ON ledgerflow.memberships(user_id, created_at, id);
