import { useEffect, useMemo, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import {
  ApiError,
  createCustomer,
  createInvoice,
  issueInvoice,
  listCustomers,
  listInvoices,
} from '../api'
import type { Customer, Invoice, NewInvoice, Organization } from '../api'
import './OrganizationWorkspace.css'

type Section = 'overview' | 'invoices' | 'customers'
type DraftLine = {
  description: string
  quantity: string
  unitPrice: string
  taxPercent: string
}

const EMPTY_LINE: DraftLine = {
  description: '',
  quantity: '1',
  unitPrice: '0.00',
  taxPercent: '0',
}

export function OrganizationWorkspace({ token, organization }: { token: string; organization: Organization }) {
  const [section, setSection] = useState<Section>('overview')
  const [customers, setCustomers] = useState<Customer[]>([])
  const [invoices, setInvoices] = useState<Invoice[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    Promise.all([listCustomers(token, organization.id), listInvoices(token, organization.id)])
      .then(([customerPage, invoicePage]) => {
        if (!active) return
        setCustomers(customerPage.items)
        setInvoices(invoicePage.items)
      })
      .catch((caught: unknown) => {
        if (active) setError(errorMessage(caught))
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => {
      active = false
    }
  }, [organization.id, token])

  if (loading) {
    return <div className="workspace-loading"><div className="spinner" aria-label="Loading organization" /></div>
  }

  return (
    <section className="organization-home">
      <div className="welcome-row">
        <div>
          <p className="eyebrow">Your money HQ ✦</p>
          <h1>{organization.name}</h1>
          <p>Customers, invoices, and money moves—all hanging out in one tidy place.</p>
        </div>
        <span className="role-badge">{formatRole(organization.role)}</span>
      </div>

      <nav className="workspace-tabs" aria-label="Workspace sections">
        <Tab active={section === 'overview'} onClick={() => setSection('overview')}>Overview</Tab>
        <Tab active={section === 'invoices'} onClick={() => setSection('invoices')}>Invoices <span>{invoices.length}</span></Tab>
        <Tab active={section === 'customers'} onClick={() => setSection('customers')}>Customers <span>{customers.length}</span></Tab>
      </nav>

      {error && <div className="error-message workspace-error" role="alert">{error}</div>}

      {section === 'overview' && (
        <Overview customers={customers} invoices={invoices} onOpenInvoices={() => setSection('invoices')} onOpenCustomers={() => setSection('customers')} />
      )}
      {section === 'customers' && (
        <CustomersSection
          customers={customers}
          onCreated={(customer) => setCustomers((current) => [customer, ...current])}
          organizationId={organization.id}
          token={token}
        />
      )}
      {section === 'invoices' && (
        <InvoicesSection
          customers={customers}
          invoices={invoices}
          organization={organization}
          onCreated={(invoice) => setInvoices((current) => [invoice, ...current])}
          onIssued={(invoice) => setInvoices((current) => current.map((item) => item.id === invoice.id ? invoice : item))}
          onNeedCustomer={() => setSection('customers')}
          token={token}
        />
      )}
    </section>
  )
}

function Overview({
  customers,
  invoices,
  onOpenCustomers,
  onOpenInvoices,
}: {
  customers: Customer[]
  invoices: Invoice[]
  onOpenCustomers: () => void
  onOpenInvoices: () => void
}) {
  const openTotal = invoices
    .filter((invoice) => invoice.status === 'ISSUED')
    .reduce((sum, invoice) => sum + Number(invoice.total), 0)

  return (
    <div className="overview-stack">
      <div className="metric-grid">
        <article className="metric-card primary-metric">
          <span>Money on the way</span>
          <strong>{formatMoney(openTotal)}</strong>
          <small>Issued invoices waiting to land</small>
        </article>
        <button className="metric-card metric-button" onClick={onOpenInvoices} type="button">
          <span>Invoices</span>
          <strong>{invoices.length}</strong>
          <small>Make some money mail →</small>
        </button>
        <button className="metric-card metric-button" onClick={onOpenCustomers} type="button">
          <span>Customers</span>
          <strong>{customers.length}</strong>
          <small>Meet the people you bill →</small>
        </button>
      </div>

      <article className="next-step-card">
        <div className="note-mark" aria-hidden="true">02</div>
        <div>
          <p className="eyebrow">Try this next</p>
          <h2>Turn good work into an invoice</h2>
          <p>Pick a customer, add what you did, and let LedgerFlow handle the number crunching.</p>
        </div>
        <button className="secondary-button" onClick={onOpenInvoices} type="button">Go to invoices</button>
      </article>
    </div>
  )
}

function CustomersSection({
  token,
  organizationId,
  customers,
  onCreated,
}: {
  token: string
  organizationId: string
  customers: Customer[]
  onCreated: (customer: Customer) => void
}) {
  const [showForm, setShowForm] = useState(customers.length === 0)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    try {
      const customer = await createCustomer(token, organizationId, name, email)
      onCreated(customer)
      setName('')
      setEmail('')
      setShowForm(false)
    } catch (caught) {
      setError(errorMessage(caught))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="section-stack">
      <SectionHeading title="Your customer crew" description="Keep the people you bill close by. Their details lock into place when an invoice is issued.">
        <button className="secondary-button" onClick={() => setShowForm((current) => !current)} type="button">{showForm ? 'Close form' : 'Add customer'}</button>
      </SectionHeading>

      {showForm && (
        <form className="panel-form customer-form" onSubmit={handleSubmit}>
          <div>
            <p className="eyebrow">New face</p>
            <h3>Add someone to the crew</h3>
          </div>
          <FormField label="Customer name" id="customerName">
            <input id="customerName" required maxLength={200} value={name} onChange={(event) => setName(event.target.value)} placeholder="Northstar Coffee" autoFocus />
          </FormField>
          <FormField label="Billing email" id="customerEmail">
            <input id="customerEmail" type="email" required maxLength={254} value={email} onChange={(event) => setEmail(event.target.value)} placeholder="billing@northstar.example" />
          </FormField>
          {error && <div className="error-message" role="alert">{error}</div>}
          <button className="primary-button compact-button" disabled={submitting} type="submit">{submitting ? 'Saving…' : 'Save customer'}</button>
        </form>
      )}

      {customers.length === 0 ? (
        <EmptyCollection title="It’s quiet in here" detail="Add your first customer and give this list some company." />
      ) : (
        <div className="record-list">
          {customers.map((customer) => (
            <article className="record-row" key={customer.id}>
              <span className="record-avatar">{initials(customer.name)}</span>
              <div><strong>{customer.name}</strong><small>{customer.email}</small></div>
              <span className="record-meta">Customer</span>
            </article>
          ))}
        </div>
      )}
    </div>
  )
}

function InvoicesSection({
  token,
  organization,
  customers,
  invoices,
  onCreated,
  onIssued,
  onNeedCustomer,
}: {
  token: string
  organization: Organization
  customers: Customer[]
  invoices: Invoice[]
  onCreated: (invoice: Invoice) => void
  onIssued: (invoice: Invoice) => void
  onNeedCustomer: () => void
}) {
  const [showForm, setShowForm] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(invoices[0]?.id ?? null)
  const selected = invoices.find((invoice) => invoice.id === selectedId) ?? null

  return (
    <div className="section-stack">
      <SectionHeading title="Money mail" description="Build a draft, check the real backend totals, and lock it in when everything looks right.">
        <button className="secondary-button" onClick={() => setShowForm((current) => !current)} type="button">{showForm ? 'Close form' : 'New invoice'}</button>
      </SectionHeading>

      {showForm && customers.length === 0 && (
        <div className="action-empty">
          <div><strong>This invoice needs a human</strong><p>Add a customer first, then come right back.</p></div>
          <button className="secondary-button" onClick={onNeedCustomer} type="button">Go to customers</button>
        </div>
      )}

      {showForm && customers.length > 0 && (
        <InvoiceForm
          customers={customers}
          nextNumber={invoices.length + 1}
          onCancel={() => setShowForm(false)}
          onCreated={(invoice) => {
            onCreated(invoice)
            setSelectedId(invoice.id)
            setShowForm(false)
          }}
          organizationId={organization.id}
          token={token}
        />
      )}

      {invoices.length === 0 ? (
        <EmptyCollection title="No money mail yet" detail="Create a draft invoice and start tracking what the business is owed." />
      ) : (
        <div className="invoice-layout">
          <div className="invoice-list" role="list" aria-label="Invoices">
            {invoices.map((invoice) => (
              <button className={`invoice-row ${invoice.id === selectedId ? 'active' : ''}`} key={invoice.id} onClick={() => setSelectedId(invoice.id)} type="button">
                <span><strong>{invoice.number}</strong><small>{invoice.customerName}</small></span>
                <span className={`status-badge ${invoice.status.toLowerCase()}`}>{formatStatus(invoice.status)}</span>
                <span className="invoice-amount">{formatMoney(invoice.total)}</span>
              </button>
            ))}
          </div>
          {selected && (
            <InvoiceDetail
              canIssue={organization.role !== 'EMPLOYEE'}
              invoice={selected}
              onIssued={onIssued}
              organizationId={organization.id}
              token={token}
            />
          )}
        </div>
      )}
    </div>
  )
}

function InvoiceForm({
  token,
  organizationId,
  customers,
  nextNumber,
  onCreated,
  onCancel,
}: {
  token: string
  organizationId: string
  customers: Customer[]
  nextNumber: number
  onCreated: (invoice: Invoice) => void
  onCancel: () => void
}) {
  const [customerId, setCustomerId] = useState(customers[0]?.id ?? '')
  const [number, setNumber] = useState(`INV-${String(nextNumber).padStart(4, '0')}`)
  const [dueDate, setDueDate] = useState(defaultDueDate())
  const [lines, setLines] = useState<DraftLine[]>([{ ...EMPTY_LINE }])
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const totals = useMemo(() => previewTotals(lines), [lines])

  function updateLine(index: number, field: keyof DraftLine, value: string) {
    setLines((current) => current.map((line, position) => position === index ? { ...line, [field]: value } : line))
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    const payload: NewInvoice = {
      customerId,
      number,
      currency: 'USD',
      dueDate,
      lines: lines.map((line) => ({
        description: line.description,
        quantity: Number(line.quantity),
        unitPrice: Number(line.unitPrice).toFixed(2),
        taxRate: (Number(line.taxPercent) / 100).toFixed(4),
      })),
    }

    try {
      onCreated(await createInvoice(token, organizationId, payload))
    } catch (caught) {
      setError(errorMessage(caught))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className="panel-form invoice-form" onSubmit={handleSubmit}>
      <div className="form-title-row">
        <div><p className="eyebrow">Fresh draft ✦</p><h3>Make some money mail</h3></div>
        <button className="text-button" onClick={onCancel} type="button">Cancel</button>
      </div>
      <div className="invoice-fields">
        <FormField label="Customer" id="invoiceCustomer">
          <select id="invoiceCustomer" required value={customerId} onChange={(event) => setCustomerId(event.target.value)}>
            {customers.map((customer) => <option value={customer.id} key={customer.id}>{customer.name}</option>)}
          </select>
        </FormField>
        <FormField label="Invoice number" id="invoiceNumber">
          <input id="invoiceNumber" required pattern="[A-Za-z0-9][A-Za-z0-9._/-]{0,63}" value={number} onChange={(event) => setNumber(event.target.value)} />
        </FormField>
        <FormField label="Due date" id="invoiceDueDate">
          <input id="invoiceDueDate" type="date" required min={today()} value={dueDate} onChange={(event) => setDueDate(event.target.value)} />
        </FormField>
      </div>

      <div className="line-heading"><strong>Line items</strong><span>Tax is rounded per line by the backend.</span></div>
      <div className="line-items">
        {lines.map((line, index) => (
          <div className="line-item" key={index}>
            <FormField label="Description" id={`description-${index}`}>
              <input id={`description-${index}`} required maxLength={500} value={line.description} onChange={(event) => updateLine(index, 'description', event.target.value)} placeholder="Monthly design work" />
            </FormField>
            <FormField label="Qty" id={`quantity-${index}`}>
              <input id={`quantity-${index}`} type="number" required min="1" max="10000" step="1" value={line.quantity} onChange={(event) => updateLine(index, 'quantity', event.target.value)} />
            </FormField>
            <FormField label="Unit price" id={`unitPrice-${index}`}>
              <input id={`unitPrice-${index}`} type="number" required min="0" max="999999999.99" step="0.01" value={line.unitPrice} onChange={(event) => updateLine(index, 'unitPrice', event.target.value)} />
            </FormField>
            <FormField label="Tax %" id={`tax-${index}`}>
              <input id={`tax-${index}`} type="number" required min="0" max="100" step="0.01" value={line.taxPercent} onChange={(event) => updateLine(index, 'taxPercent', event.target.value)} />
            </FormField>
            <button className="remove-line" disabled={lines.length === 1} onClick={() => setLines((current) => current.filter((_, position) => position !== index))} type="button" aria-label={`Remove line ${index + 1}`}>×</button>
          </div>
        ))}
      </div>
      <button className="add-line-button" onClick={() => setLines((current) => [...current, { ...EMPTY_LINE }])} type="button">+ Add another line</button>

      <div className="invoice-form-footer">
        <div className="preview-totals">
          <span>Subtotal <strong>{formatMoney(totals.subtotal)}</strong></span>
          <span>Estimated tax <strong>{formatMoney(totals.tax)}</strong></span>
          <span className="preview-total">Estimated total <strong>{formatMoney(totals.total)}</strong></span>
        </div>
        <div className="submit-area">
          {error && <div className="error-message" role="alert">{error}</div>}
          <button className="primary-button compact-button" disabled={submitting} type="submit">{submitting ? 'Creating…' : 'Create draft invoice'}</button>
        </div>
      </div>
    </form>
  )
}

function InvoiceDetail({
  token,
  organizationId,
  invoice,
  canIssue,
  onIssued,
}: {
  token: string
  organizationId: string
  invoice: Invoice
  canIssue: boolean
  onIssued: (invoice: Invoice) => void
}) {
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  async function handleIssue() {
    setSubmitting(true)
    setError('')
    try {
      onIssued(await issueInvoice(token, organizationId, invoice.id, invoice.version))
    } catch (caught) {
      setError(errorMessage(caught))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <article className="invoice-detail">
      <div className="detail-heading">
        <div><p className="eyebrow">Invoice detail</p><h2>{invoice.number}</h2></div>
        <span className={`status-badge ${invoice.status.toLowerCase()}`}>{formatStatus(invoice.status)}</span>
      </div>
      <div className="detail-customer"><span>Bill to</span><strong>{invoice.customerName}</strong><small>{invoice.customerEmail}</small></div>
      <div className="detail-lines">
        {invoice.lines.map((line, index) => (
          <div className="detail-line" key={`${line.description}-${index}`}>
            <span><strong>{line.description}</strong><small>{line.quantity} × {formatMoney(line.unitPrice)} · {formatPercent(line.taxRate)} tax</small></span>
            <strong>{formatMoney(line.total)}</strong>
          </div>
        ))}
      </div>
      <div className="detail-totals">
        <span>Subtotal <strong>{formatMoney(invoice.subtotal)}</strong></span>
        <span>Tax <strong>{formatMoney(invoice.tax)}</strong></span>
        <span className="grand-total">Total <strong>{formatMoney(invoice.total)}</strong></span>
      </div>
      <div className="detail-due"><span>Due date</span><strong>{formatDate(invoice.dueDate)}</strong></div>
      {error && <div className="error-message" role="alert">{error}</div>}
      {invoice.status === 'DRAFT' && canIssue && <button className="primary-button" disabled={submitting} onClick={handleIssue} type="button">{submitting ? 'Issuing…' : 'Issue invoice'}</button>}
      {invoice.status === 'DRAFT' && !canIssue && <p className="permission-note">An owner or accountant can issue this draft.</p>}
    </article>
  )
}

function SectionHeading({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return <div className="section-heading"><div><h2>{title}</h2><p>{description}</p></div>{children}</div>
}

function Tab({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return <button className={active ? 'active' : ''} onClick={onClick} type="button">{children}</button>
}

function FormField({ label, id, children }: { label: string; id: string; children: ReactNode }) {
  return <label className="field" htmlFor={id}><span>{label}</span>{children}</label>
}

function EmptyCollection({ title, detail }: { title: string; detail: string }) {
  return <div className="collection-empty"><span aria-hidden="true">＋</span><strong>{title}</strong><p>{detail}</p></div>
}

function previewTotals(lines: DraftLine[]) {
  return lines.reduce((totals, line) => {
    const subtotal = (Number(line.quantity) || 0) * (Number(line.unitPrice) || 0)
    const tax = Math.round(subtotal * ((Number(line.taxPercent) || 0) / 100) * 100) / 100
    return { subtotal: totals.subtotal + subtotal, tax: totals.tax + tax, total: totals.total + subtotal + tax }
  }, { subtotal: 0, tax: 0, total: 0 })
}

function errorMessage(error: unknown) {
  return error instanceof ApiError || error instanceof Error ? error.message : 'Something went wrong. Please try again.'
}

function today() {
  return new Date().toISOString().slice(0, 10)
}

function defaultDueDate() {
  const date = new Date()
  date.setDate(date.getDate() + 14)
  return date.toISOString().slice(0, 10)
}

function formatMoney(value: string | number) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(value))
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' }).format(new Date(`${value}T00:00:00Z`))
}

function formatPercent(value: string) {
  return `${(Number(value) * 100).toFixed(2).replace(/\.00$/, '')}%`
}

function formatRole(role: Organization['role']) {
  return role.charAt(0) + role.slice(1).toLowerCase()
}

function formatStatus(status: Invoice['status']) {
  return status.charAt(0) + status.slice(1).toLowerCase()
}

function initials(name: string) {
  return name.split(' ').filter(Boolean).slice(0, 2).map((part) => part[0]).join('').toUpperCase()
}
