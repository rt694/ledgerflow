export type User = {
  id: string
  email: string
  displayName: string
}

export type Organization = {
  id: string
  name: string
  role: 'OWNER' | 'ACCOUNTANT' | 'EMPLOYEE'
}

type TokenResponse = {
  accessToken: string
}

export type Page<T> = {
  items: T[]
  page: number
  size: number
  totalElements: number
}

export type Customer = {
  id: string
  name: string
  email: string
  version: number
}

export type InvoiceLine = {
  description: string
  quantity: number
  unitPrice: string
  taxRate: string
  subtotal: string
  tax: string
  total: string
}

export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'PAID' | 'VOID'

export type Invoice = {
  id: string
  customerId: string
  customerName: string
  customerEmail: string
  number: string
  currency: 'USD'
  status: InvoiceStatus
  dueDate: string
  subtotal: string
  tax: string
  total: string
  version: number
  issuedAt: string | null
  voidedAt: string | null
  lines: InvoiceLine[]
}

export type NewInvoice = {
  customerId: string
  number: string
  currency: 'USD'
  dueDate: string
  lines: Array<{
    description: string
    quantity: number
    unitPrice: string
    taxRate: string
  }>
}

type Problem = {
  title?: string
  detail?: string
  message?: string
}

export class ApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function request<T>(path: string, options: RequestInit = {}, token?: string): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')

  if (options.body) {
    headers.set('Content-Type', 'application/json')
  }

  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  let response: Response
  try {
    response = await fetch(`/api/v1${path}`, { ...options, headers })
  } catch {
    throw new ApiError('The API is not reachable. Make sure the backend is running on port 18080.', 0)
  }

  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as Problem
    throw new ApiError(
      problem.detail ?? problem.message ?? problem.title ?? 'Something went wrong. Please try again.',
      response.status,
    )
  }

  return response.json() as Promise<T>
}

export function register(email: string, displayName: string, password: string) {
  return request<User>('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, displayName, password }),
  })
}

export function login(email: string, password: string) {
  return request<TokenResponse>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  })
}

export function getCurrentUser(token: string) {
  return request<User>('/auth/me', {}, token)
}

export function listOrganizations(token: string) {
  return request<Page<Organization>>('/organizations?size=100', {}, token)
}

export function createOrganization(token: string, name: string) {
  return request<Organization>('/organizations', {
    method: 'POST',
    body: JSON.stringify({ name }),
  }, token)
}

export function listCustomers(token: string, organizationId: string) {
  return request<Page<Customer>>(`/organizations/${organizationId}/customers?size=100`, {}, token)
}

export function createCustomer(token: string, organizationId: string, name: string, email: string) {
  return request<Customer>(`/organizations/${organizationId}/customers`, {
    method: 'POST',
    body: JSON.stringify({ name, email }),
  }, token)
}

export function listInvoices(token: string, organizationId: string) {
  return request<Page<Invoice>>(`/organizations/${organizationId}/invoices?size=100`, {}, token)
}

export function createInvoice(token: string, organizationId: string, invoice: NewInvoice) {
  return request<Invoice>(`/organizations/${organizationId}/invoices`, {
    method: 'POST',
    body: JSON.stringify(invoice),
  }, token)
}

export function issueInvoice(token: string, organizationId: string, invoiceId: string, version: number) {
  return request<Invoice>(`/organizations/${organizationId}/invoices/${invoiceId}/issue`, {
    method: 'POST',
    body: JSON.stringify({ version }),
  }, token)
}
