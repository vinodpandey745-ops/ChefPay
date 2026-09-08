import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'

import { ErrorBoundary } from './components/common/ErrorBoundary'
import App from './App.tsx'
import './index.css'
import { logger } from './lib/logger'
import { startAutoSync } from './lib/syncEngine'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 10_000,
      refetchOnWindowFocus: true,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* Follow-up enhancement ("Enable Logging"): catches any uncaught React render error anywhere
        in the app - see ErrorBoundary's own javadoc-style comment for why this is mounted once,
        here, rather than per-page. */}
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        {/* Bistrodesk Phase 12: no `basename` needed any more - this app is mounted at the server
            root ("/") rather than "/app" now, matching vite.config.ts's `base: '/'`. */}
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </QueryClientProvider>
    </ErrorBoundary>
  </StrictMode>,
)

// Follow-up enhancement ("Enable Logging"): a React render error is caught by ErrorBoundary above,
// but an error thrown from a plain event handler, a setTimeout callback, or a rejected Promise
// never reaches a React error boundary at all - these two listeners are the only way to catch
// those too. Same best-effort "never throw, never affect the app" discipline as lib/logger.ts.
window.addEventListener('error', (event) => {
  logger.error(`Uncaught error: ${event.message}`, event.error)
})
window.addEventListener('unhandledrejection', (event) => {
  logger.error('Unhandled promise rejection', event.reason)
})

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      // Installable-shell only - a failed SW registration should never block the app from working.
    })
  })
}

// Starts the dedicated background sync worker (30-minute auto-sync of the offline outbox) and the
// online/offline + auth-token listeners that feed it - see lib/syncEngine.ts's top comment.
startAutoSync()
