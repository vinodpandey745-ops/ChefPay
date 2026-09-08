/**
 * Follow-up enhancement ("Enable Logging" - "logging should be both end as per application
 * architecture client side and server side"). Before this file existed, chefpay-web had ZERO
 * console.log/error/warn calls anywhere and no way to see a frontend error after the fact - a JS
 * exception on a POS terminal in the field left no trace anyone could investigate. This is a thin,
 * best-effort wrapper, deliberately NOT built on the app's own `api` client (lib/api.ts): a logging
 * call must never itself queue into the offline outbox, retry, redirect on 401, or throw - it is
 * pure "fire and forget, never affect the caller" telemetry, using a raw `fetch` straight to
 * `POST /api/client-logs` (ClientLogController - a write-only endpoint that just appends the line
 * to the server's own log file, see that controller's javadoc). Always logs to the browser console
 * too (console.error/warn/info) so local dev workflow is unaffected/improved - the network call is
 * additive, not a replacement.
 */

const CLIENT_LOG_ENDPOINT = '/api/client-logs'

function send(level: 'error' | 'warn' | 'info', message: string, context?: unknown): void {
  const contextStr = context === undefined
    ? undefined
    : context instanceof Error
      ? `${context.name}: ${context.message}\n${context.stack ?? ''}`
      : typeof context === 'string'
        ? context
        : safeStringify(context)

  // Best-effort only - never let a failed log call surface anywhere, never await it, never retry.
  try {
    const token = readAuthToken()
    void fetch(CLIENT_LOG_ENDPOINT, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({
        level,
        message,
        context: contextStr,
        path: window.location.pathname,
      }),
    }).catch(() => {
      // Genuinely offline/unreachable - nothing more useful to do than keep the console line.
    })
  } catch {
    // window/fetch unavailable (shouldn't happen in a browser) - console line above still stands.
  }
}

/** Reads the auth token directly out of the same localStorage key store/auth.ts persists to
 * (STORAGE_KEY = 'chefpay_web_session_v1', a flat {token, ...} object - see that file), rather
 * than importing useAuthStore, avoiding any import-cycle risk between this low-level utility and
 * the store. */
function readAuthToken(): string | null {
  try {
    const raw = localStorage.getItem('chefpay_web_session_v1')
    if (!raw) return null
    const parsed = JSON.parse(raw) as { token?: string | null }
    return parsed.token ?? null
  } catch {
    return null
  }
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

export const logger = {
  error(message: string, context?: unknown): void {
    console.error(message, context)
    send('error', message, context)
  },
  warn(message: string, context?: unknown): void {
    console.warn(message, context)
    send('warn', message, context)
  },
  info(message: string, context?: unknown): void {
    console.info(message, context)
    send('info', message, context)
  },
}
