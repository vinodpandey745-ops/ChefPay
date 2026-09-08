import { useAuthStore } from '@/store/auth'
import { useSyncStore } from '@/store/sync'
import { ApiError, type ApiEnvelope } from '@/types/api'

import { logger } from './logger'
import { buildCacheKey, enqueueOutbox, getCached, setCached } from './offlineDb'

/** Base path is relative - in production this app is served by chefpay-server itself (same origin,
 * see vite.config.ts's `base: '/'`); in dev, Vite's proxy forwards /api to localhost:8080. Same
 * approach the existing /manager companion uses, just centralized here instead of inlined per call. */
const API_BASE = '/api'

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'
  body?: unknown
  query?: Record<string, string | number | boolean | undefined>
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = new URL(API_BASE + path, window.location.origin)
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined) url.searchParams.set(key, String(value))
    }
  }
  return url.pathname + url.search
}

/** Single fetch wrapper every page uses - unwraps the ApiResponse envelope, attaches the JWT
 * bearer token (same header the JavaFX client and /manager companion both send), and normalizes
 * failures into ApiError so callers can branch on `.status`/`.errorCode` instead of parsing
 * response bodies themselves. On a 401, clears the session so the next render redirects to login -
 * mirrors the /manager companion's "session expired" handling.
 *
 * OFFLINE HANDLING (see lib/offlineDb.ts / lib/syncEngine.ts): the `fetch()` call itself throwing
 * (a `TypeError`, e.g. "Failed to fetch") means the server is genuinely unreachable - offline, DNS
 * failure, connection refused - as distinct from the server responding with an HTTP error status
 * (validation error, 409, 401, ...), which continues to throw ApiError exactly as before and is
 * NOT treated as "offline" here. Only that first case falls back to the local cache (GET) or
 * queues the write into the outbox (POST/PUT/PATCH/DELETE) - a reachable server's real error
 * response must never be swallowed or reinterpreted as "queued for later". */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = useAuthStore.getState().token
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`

  const method = options.method ?? 'GET'
  const cacheKey = method === 'GET' ? buildCacheKey(path, options.query) : null

  let response: Response
  try {
    response = await fetch(buildUrl(path, options.query), {
      method,
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    })
  } catch (networkError) {
    if (method === 'GET') {
      const cached = cacheKey ? await getCached<T>(cacheKey) : null
      if (cached !== null) return cached
      // Follow-up enhancement ("Enable Logging"): a GET with no cached fallback at all means the
      // user is about to see a blank/error screen with no local data to show instead - worth a
      // record, unlike the routine "offline write queued" case just below (expected, already
      // surfaced to the user via the sync indicator, not itself a failure worth flagging).
      logger.warn(`GET ${path} failed and no cached data was available`, networkError)
      throw networkError
    }

    // A queued write - honest about "not yet confirmed" rather than fabricating a fake success
    // with made-up server-issued ids/versions, which would risk corrupting downstream state.
    await enqueueOutbox({ method, path, query: options.query, body: options.body })
    useSyncStore.getState().bumpPending()
    throw new ApiError(
      "You're offline - this change has been saved and will sync automatically once the connection returns.",
      0,
      'OFFLINE_QUEUED',
    )
  }

  let envelope: ApiEnvelope<T> | null = null
  try {
    envelope = (await response.json()) as ApiEnvelope<T>
  } catch {
    // No JSON body (e.g. a proxy/network error page) - fall through to the generic error below.
  }

  if (!response.ok || !envelope || envelope.success === false) {
    if (response.status === 401) {
      useAuthStore.getState().signOut()
    }
    const message = envelope?.message ?? `Request failed (${response.status})`
    // Follow-up enhancement ("Enable Logging"): only a genuine server-side failure (5xx, or no
    // parseable response body at all) is logged from here - an expected 4xx (validation, a
    // permission check, "invalid credentials") is already shown to the user in the UI and already
    // logged server-side by GlobalExceptionHandler, so logging it again here would just be noise.
    if (response.status >= 500 || !envelope) {
      logger.error(`${method} ${path} failed (${response.status})`, message)
    }
    throw new ApiError(message, response.status, envelope?.errorCode ?? null)
  }

  if (method === 'GET' && cacheKey) {
    // Opportunistic write-through cache - never await this / never let a cache-write failure
    // affect the real response the caller is waiting on.
    void setCached(cacheKey, envelope.data).catch(() => {})
  }

  return envelope.data as T
}

export const api = {
  get: <T>(path: string, query?: RequestOptions['query']) => apiRequest<T>(path, { method: 'GET', query }),
  post: <T>(path: string, body?: unknown, query?: RequestOptions['query']) =>
    apiRequest<T>(path, { method: 'POST', body, query }),
  patch: <T>(path: string, body?: unknown, query?: RequestOptions['query']) =>
    apiRequest<T>(path, { method: 'PATCH', body, query }),
  put: <T>(path: string, body?: unknown, query?: RequestOptions['query']) =>
    apiRequest<T>(path, { method: 'PUT', body, query }),
  delete: <T>(path: string, body?: unknown) => apiRequest<T>(path, { method: 'DELETE', body }),
}
