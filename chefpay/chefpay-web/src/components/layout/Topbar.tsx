import { HelpCircle, LogOut, Menu as MenuIcon, Moon, Sun } from 'lucide-react'
import { useState } from 'react'

import { SyncStatusBadge } from '@/components/sync/SyncStatusBadge'
import { Modal } from '@/components/ui/Modal'
import { api } from '@/lib/api'
import { useAuthStore } from '@/store/auth'
import { useThemeStore } from '@/store/theme'
import type { SupportSettingsDto } from '@/types/api'

interface TopbarProps {
  onMenuClick: () => void
  title: string
}

export function Topbar({ onMenuClick, title }: TopbarProps) {
  const { isDark, toggleTheme } = useThemeStore()
  const { displayName, role, signOut } = useAuthStore()

  return (
    // min-h (not a fixed h-16) plus safe-area-top padding: on a notched phone in portrait the bar
    // grows just enough to clear the notch/status bar instead of clipping its own contents; on
    // everything else env(safe-area-inset-top) resolves to 0 so this is exactly h-16 as before.
    <header className="flex min-h-16 shrink-0 items-center justify-between gap-2 border-b border-app bg-surface px-4 pt-[env(safe-area-inset-top)] sm:px-6">
      <div className="flex min-w-0 flex-1 items-center gap-2 sm:gap-3">
        <button
          type="button"
          onClick={onMenuClick}
          className="shrink-0 rounded-lg p-2.5 text-muted hover:bg-app sm:p-2 lg:hidden"
          aria-label="Open menu"
        >
          <MenuIcon className="h-5 w-5" />
        </button>
        <h1 className="truncate text-base font-bold text-app sm:text-lg">{title}</h1>
      </div>

      <div className="flex shrink-0 items-center gap-1 sm:gap-3">
        {/* Hidden below `sm` - its label can run long ("Offline · 12 pending"), which would crowd
            the narrowest phones right where the hamburger/title/theme/sign-out controls already
            compete for space. Full-width phones in landscape and up all keep it visible. */}
        <SyncStatusBadge className="hidden sm:flex" />

        <button
          type="button"
          onClick={toggleTheme}
          className="rounded-lg p-2.5 text-muted hover:bg-app sm:p-2"
          aria-label="Toggle dark mode"
          title="Toggle dark mode"
        >
          {isDark ? <Sun className="h-4.5 w-4.5" /> : <Moon className="h-4.5 w-4.5" />}
        </button>

        <HelpButton />

        <div className="hidden text-right sm:block">
          <div className="text-sm font-semibold text-app">{displayName ?? 'Unknown'}</div>
          <div className="text-xs text-muted">{role ?? ''}</div>
        </div>

        <button
          type="button"
          onClick={signOut}
          className="rounded-lg p-2.5 text-muted hover:bg-danger-soft hover:text-danger sm:p-2"
          aria-label="Sign out"
          title="Sign out"
        >
          <LogOut className="h-4.5 w-4.5" />
        </button>
      </div>
    </header>
  )
}

/** Requirement ("Add a small 'Help' icon in the top-right corner..."): shows the support phone/
 * email and Terms & Conditions/Privacy Policy content configured from the BistroDesk Admin
 * Panel's Support & Policy Configuration section (see PlatformOwnerController#getSupport/
 * #updateSupport). Fetches `GET /api/support` the first time it's opened rather than on every
 * Topbar mount - this is help content a user may never open in a given session. */
function HelpButton() {
  const [open, setOpen] = useState(false)
  const [support, setSupport] = useState<SupportSettingsDto | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<'terms' | 'privacy' | null>(null)

  function handleOpen() {
    setOpen(true)
    setExpanded(null)
    if (support || loading) return
    setLoading(true)
    setError(null)
    api
      .get<SupportSettingsDto>('/support')
      .then((dto) => setSupport(dto))
      .catch(() => setError('Could not load Help information. Please try again.'))
      .finally(() => setLoading(false))
  }

  return (
    <>
      <button
        type="button"
        onClick={handleOpen}
        className="rounded-lg p-2.5 text-muted hover:bg-app sm:p-2"
        aria-label="Help"
        title="Help"
      >
        <HelpCircle className="h-4.5 w-4.5" />
      </button>

      <Modal open={open} onClose={() => setOpen(false)} title="Help">
        {loading && <p className="text-sm text-muted">Loading...</p>}
        {error && <p className="text-sm text-danger">{error}</p>}
        {!loading && !error && support && (
          <div className="flex flex-col gap-4">
            {!support.supportPhone && !support.supportEmail && !support.termsAndConditions && !support.privacyPolicy && (
              <p className="text-sm text-muted">No support information has been configured yet.</p>
            )}

            {(support.supportPhone || support.supportEmail) && (
              <div className="flex flex-col gap-2">
                {support.supportPhone && (
                  <a href={`tel:${support.supportPhone}`} className="text-sm font-semibold text-brand-600 hover:underline dark:text-brand-400">
                    Call support: {support.supportPhone}
                  </a>
                )}
                {support.supportEmail && (
                  <a href={`mailto:${support.supportEmail}`} className="text-sm font-semibold text-brand-600 hover:underline dark:text-brand-400">
                    Email support: {support.supportEmail}
                  </a>
                )}
              </div>
            )}

            {support.termsAndConditions && (
              <HelpSection
                label="Terms & Conditions"
                content={support.termsAndConditions}
                open={expanded === 'terms'}
                onToggle={() => setExpanded((cur) => (cur === 'terms' ? null : 'terms'))}
              />
            )}

            {support.privacyPolicy && (
              <HelpSection
                label="Privacy Policy"
                content={support.privacyPolicy}
                open={expanded === 'privacy'}
                onToggle={() => setExpanded((cur) => (cur === 'privacy' ? null : 'privacy'))}
              />
            )}
          </div>
        )}
      </Modal>
    </>
  )
}

interface HelpSectionProps {
  label: string
  content: string
  open: boolean
  onToggle: () => void
}

function HelpSection({ label, content, open, onToggle }: HelpSectionProps) {
  return (
    <div className="rounded-lg border border-app">
      <button
        type="button"
        onClick={onToggle}
        className="flex w-full items-center justify-between px-3 py-2 text-left text-sm font-semibold text-app"
      >
        {label}
        <span className="text-muted">{open ? '−' : '+'}</span>
      </button>
      {open && (
        <div className="max-h-64 overflow-y-auto whitespace-pre-wrap border-t border-app px-3 py-2 text-sm text-muted">
          {content}
        </div>
      )}
    </div>
  )
}
