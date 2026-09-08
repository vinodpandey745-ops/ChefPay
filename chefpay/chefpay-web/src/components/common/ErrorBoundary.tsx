import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'

import { logger } from '@/lib/logger'

/** Follow-up enhancement ("Enable Logging"): before this, an uncaught render error anywhere in
 * chefpay-web produced a blank white screen with nothing logged anywhere - not the console (this
 * app had zero console.* calls), not the server, nothing. This is the one place a React render
 * error is actually caught, logged (console + best-effort POST to `/api/client-logs`, see
 * lib/logger.ts), and given a recoverable fallback UI instead of a blank screen. Deliberately
 * mounted ONCE at the app root (main.tsx) - React error boundaries only catch errors in the
 * subtree below them, and wrapping every page individually would be a much larger, riskier change
 * for the same benefit this one wrapper already gives the whole app. */
interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    logger.error(`Unhandled render error: ${error.message}`, `${error.stack ?? ''}\n${info.componentStack ?? ''}`)
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-app px-6 text-center">
          <p className="text-lg font-semibold text-app">Something went wrong.</p>
          <p className="max-w-sm text-sm text-muted">
            This has been recorded. Reloading the page usually fixes it - if it keeps happening, let your
            administrator know.
          </p>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="rounded-lg bg-brand-500 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-600"
          >
            Reload
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
