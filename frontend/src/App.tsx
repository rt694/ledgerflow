import { useEffect, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import './App.css'
import {
  ApiError,
  createOrganization,
  getCurrentUser,
  listOrganizations,
  login,
  register,
} from './api'
import type { Organization, User } from './api'
import { OrganizationWorkspace } from './workspace/OrganizationWorkspace'

const SESSION_KEY = 'ledgerflow.accessToken'

function App() {
  const [token, setToken] = useState(() => sessionStorage.getItem(SESSION_KEY))
  const [user, setUser] = useState<User | null>(null)
  const [organizations, setOrganizations] = useState<Organization[]>([])
  const [activeOrganizationId, setActiveOrganizationId] = useState<string | null>(null)
  const [loading, setLoading] = useState(Boolean(token))
  const [sessionError, setSessionError] = useState('')

  useEffect(() => {
    if (!token) {
      return
    }

    let active = true
    Promise.all([getCurrentUser(token), listOrganizations(token)])
      .then(([currentUser, page]) => {
        if (!active) return
        setUser(currentUser)
        setOrganizations(page.items)
        setActiveOrganizationId(page.items[0]?.id ?? null)
      })
      .catch((error: unknown) => {
        if (!active) return
        sessionStorage.removeItem(SESSION_KEY)
        setToken(null)
        setSessionError(errorMessage(error))
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => {
      active = false
    }
  }, [token])

  function handleAuthenticated(accessToken: string) {
    sessionStorage.setItem(SESSION_KEY, accessToken)
    setSessionError('')
    setLoading(true)
    setToken(accessToken)
  }

  function handleSignOut() {
    sessionStorage.removeItem(SESSION_KEY)
    setToken(null)
    setUser(null)
    setOrganizations([])
    setActiveOrganizationId(null)
  }

  if (loading) return <LoadingScreen />

  if (!token || !user) {
    return <AuthScreen initialError={sessionError} onAuthenticated={handleAuthenticated} />
  }

  return (
    <Workspace
      token={token}
      user={user}
      organizations={organizations}
      activeOrganizationId={activeOrganizationId}
      onOrganizationCreated={(organization) => {
        setOrganizations((current) => [...current, organization])
        setActiveOrganizationId(organization.id)
      }}
      onSelectOrganization={setActiveOrganizationId}
      onSignOut={handleSignOut}
    />
  )
}

function AuthScreen({
  initialError,
  onAuthenticated,
}: {
  initialError: string
  onAuthenticated: (token: string) => void
}) {
  const [mode, setMode] = useState<'login' | 'register'>('register')
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(initialError)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError('')
    setSubmitting(true)

    try {
      if (mode === 'register') {
        await register(email, displayName, password)
      }
      const result = await login(email, password)
      onAuthenticated(result.accessToken)
    } catch (caught) {
      setError(errorMessage(caught))
    } finally {
      setSubmitting(false)
    }
  }

  function switchMode(nextMode: 'login' | 'register') {
    setMode(nextMode)
    setError('')
  }

  return (
    <main className="auth-layout">
      <section className="auth-story">
        <Brand />
        <div className="story-copy">
          <p className="eyebrow">Bookkeeping with a clearer flow</p>
          <h1>A clear approach to financial management.</h1>
          <p className="story-intro">
            Create invoices, track payments, and keep your records organized in one clear workspace.
          </p>
        </div>
        <p className="story-note">A hands-on accounting platform built to make each financial step easier to understand.</p>
      </section>

      <section className="auth-panel">
        <div className="auth-card">
          <div className="auth-heading">
            <p className="eyebrow">{mode === 'register' ? 'Set up your workspace' : 'Welcome back'}</p>
            <h2>{mode === 'register' ? 'Create your LedgerFlow account' : 'Sign in to continue'}</h2>
            <p>
              {mode === 'register'
                ? 'Create an account and organization to begin managing your financial records.'
                : 'Access your customers, invoices, and organization records.'}
            </p>
          </div>

          <div className="auth-tabs" role="tablist" aria-label="Account action">
            <button className={mode === 'register' ? 'active' : ''} onClick={() => switchMode('register')} type="button">Create account</button>
            <button className={mode === 'login' ? 'active' : ''} onClick={() => switchMode('login')} type="button">Sign in</button>
          </div>

          <form onSubmit={handleSubmit}>
            {mode === 'register' && (
              <Field label="Your name" id="displayName">
                <input id="displayName" name="displayName" autoComplete="name" required minLength={2} value={displayName} onChange={(event) => setDisplayName(event.target.value)} placeholder="Alex Morgan" />
              </Field>
            )}
            <Field label="Email address" id="email">
              <input id="email" name="email" type="email" autoComplete="email" required value={email} onChange={(event) => setEmail(event.target.value)} placeholder="alex@example.com" />
            </Field>
            <Field label="Password" id="password" hint={mode === 'register' ? 'Use at least 8 characters.' : undefined}>
              <input id="password" name="password" type="password" autoComplete={mode === 'register' ? 'new-password' : 'current-password'} required minLength={8} value={password} onChange={(event) => setPassword(event.target.value)} placeholder="••••••••" />
            </Field>

            {error && <div className="error-message" role="alert">{error}</div>}

            <button className="primary-button" disabled={submitting} type="submit">
              {submitting ? 'Please wait…' : mode === 'register' ? 'Create workspace' : 'Sign in'}
            </button>
          </form>
        </div>
      </section>
    </main>
  )
}

function Workspace({
  token,
  user,
  organizations,
  activeOrganizationId,
  onOrganizationCreated,
  onSelectOrganization,
  onSignOut,
}: {
  token: string
  user: User
  organizations: Organization[]
  activeOrganizationId: string | null
  onOrganizationCreated: (organization: Organization) => void
  onSelectOrganization: (id: string) => void
  onSignOut: () => void
}) {
  const [creating, setCreating] = useState(false)
  const [organizationName, setOrganizationName] = useState('')
  const [error, setError] = useState('')
  const activeOrganization = organizations.find(({ id }) => id === activeOrganizationId)

  async function handleCreateOrganization(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setCreating(true)
    setError('')

    try {
      const organization = await createOrganization(token, organizationName)
      onOrganizationCreated(organization)
      setOrganizationName('')
    } catch (caught) {
      setError(errorMessage(caught))
    } finally {
      setCreating(false)
    }
  }

  return (
    <div className="workspace-shell">
      <header className="topbar">
        <Brand />
        <div className="account-menu">
          <div className="avatar" aria-hidden="true">{initials(user.displayName)}</div>
          <div className="account-copy">
            <strong>{user.displayName}</strong>
            <span>{user.email}</span>
          </div>
          <button className="text-button" onClick={onSignOut} type="button">Sign out</button>
        </div>
      </header>

      <div className="workspace-layout">
        <aside className="sidebar">
          <div className="sidebar-heading">
            <span>Organizations</span>
            <span className="count">{organizations.length}</span>
          </div>
          <nav aria-label="Organizations">
            {organizations.map((organization) => (
              <button className={`organization-link ${organization.id === activeOrganizationId ? 'active' : ''}`} key={organization.id} onClick={() => onSelectOrganization(organization.id)} type="button">
                <span className="organization-mark">{organization.name.charAt(0).toUpperCase()}</span>
                <span><strong>{organization.name}</strong><small>{formatRole(organization.role)}</small></span>
              </button>
            ))}
          </nav>
        </aside>

        <main className="workspace-main">
          {activeOrganization ? (
            <OrganizationWorkspace key={activeOrganization.id} organization={activeOrganization} token={token} />
          ) : (
            <section className="empty-state">
              <div className="empty-icon" aria-hidden="true">✦</div>
              <p className="eyebrow">Start with an organization</p>
              <h1>Create your organization</h1>
              <p>An organization keeps one business and its financial records together in LedgerFlow.</p>
              <form className="organization-form" onSubmit={handleCreateOrganization}>
                <Field label="Organization name" id="organizationName">
                  <input id="organizationName" required minLength={2} maxLength={100} value={organizationName} onChange={(event) => setOrganizationName(event.target.value)} placeholder="Morgan Design Studio" autoFocus />
                </Field>
                {error && <div className="error-message" role="alert">{error}</div>}
                <button className="primary-button" disabled={creating} type="submit">{creating ? 'Creating…' : 'Create organization'}</button>
              </form>
            </section>
          )}
        </main>
      </div>
    </div>
  )
}

function Brand() {
  return <a className="brand" href="/" aria-label="LedgerFlow home"><span className="brand-mark">L</span><span>LedgerFlow</span></a>
}

function Field({ label, id, hint, children }: { label: string; id: string; hint?: string; children: ReactNode }) {
  return <label className="field" htmlFor={id}><span>{label}</span>{children}{hint && <small>{hint}</small>}</label>
}

function LoadingScreen() {
  return <main className="loading-screen"><Brand /><div className="spinner" aria-label="Loading" /></main>
}

function errorMessage(error: unknown) {
  return error instanceof ApiError || error instanceof Error ? error.message : 'Something went wrong. Please try again.'
}

function initials(name: string) {
  return name.split(' ').filter(Boolean).slice(0, 2).map((part) => part[0]).join('').toUpperCase()
}

function formatRole(role: Organization['role']) {
  return role.charAt(0) + role.slice(1).toLowerCase()
}

export default App
