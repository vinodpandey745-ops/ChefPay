import { useEffect, useState } from 'react'

/** Chrome/Edge only fire `beforeinstallprompt` when the page qualifies as an installable PWA
 * (manifest + registered service worker, both already shipped from Phase 1 - see public/manifest.json
 * and public/sw.js) and the browser hasn't already installed it. The event has to be captured and
 * stashed the moment it fires (it's only dispatchable once) so a later button click can replay it -
 * this is the same underlying browser mechanism the reference POS's own "Install POS App" banner
 * uses (confirmed by testing it directly: no real binary, just this native prompt). */
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

export function usePwaInstall() {
  const [deferredEvent, setDeferredEvent] = useState<BeforeInstallPromptEvent | null>(null)
  const [installed, setInstalled] = useState(
    () => window.matchMedia?.('(display-mode: standalone)').matches || (navigator as { standalone?: boolean }).standalone === true,
  )

  useEffect(() => {
    function onBeforeInstall(event: Event) {
      event.preventDefault()
      setDeferredEvent(event as BeforeInstallPromptEvent)
    }
    function onInstalled() {
      setInstalled(true)
      setDeferredEvent(null)
    }
    window.addEventListener('beforeinstallprompt', onBeforeInstall)
    window.addEventListener('appinstalled', onInstalled)
    return () => {
      window.removeEventListener('beforeinstallprompt', onBeforeInstall)
      window.removeEventListener('appinstalled', onInstalled)
    }
  }, [])

  async function promptInstall() {
    if (!deferredEvent) return
    await deferredEvent.prompt()
    await deferredEvent.userChoice
    setDeferredEvent(null)
  }

  return { canInstall: !!deferredEvent && !installed, installed, promptInstall }
}

/** Simple online/offline flag for the login screen's connectivity indicator - a POS terminal that
 * can't reach the server is important to surface before a login attempt silently times out. */
export function useOnlineStatus() {
  const [online, setOnline] = useState(navigator.onLine)
  useEffect(() => {
    const goOnline = () => setOnline(true)
    const goOffline = () => setOnline(false)
    window.addEventListener('online', goOnline)
    window.addEventListener('offline', goOffline)
    return () => {
      window.removeEventListener('online', goOnline)
      window.removeEventListener('offline', goOffline)
    }
  }, [])
  return online
}
