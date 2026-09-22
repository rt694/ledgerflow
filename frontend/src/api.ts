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

type Page<T> = {
  items: T[]
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
