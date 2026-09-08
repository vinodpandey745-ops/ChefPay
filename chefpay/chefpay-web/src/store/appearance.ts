import { create } from 'zustand'

import { api } from '@/lib/api'
import {
  applyAppearance,
  cacheAppearance,
  DEFAULT_APPEARANCE,
  loadCachedAppearance,
  type AppearanceConfig,
} from '@/lib/appearance'
import type { ThemeSettingsDto } from '@/types/api'

interface AppearanceState {
  config: AppearanceConfig
  serverVersion: number
  loaded: boolean
  error: string | null
  /** Fetches the restaurant-wide config from `/api/theme` (any authenticated user can read it) and
   * applies it. Safe to call before login fails silently is NOT desired here - callers should only
   * invoke this once authenticated, since the endpoint requires a bearer token like every other. */
  hydrate: () => Promise<void>
}

function parseThemeJson(json: string | null): AppearanceConfig {
  if (!json) return DEFAULT_APPEARANCE
  try {
    return { ...DEFAULT_APPEARANCE, ...(JSON.parse(json) as Partial<AppearanceConfig>) }
  } catch {
    return DEFAULT_APPEARANCE
  }
}

const cached = loadCachedAppearance()
applyAppearance(cached)

export const useAppearanceStore = create<AppearanceState>((set) => ({
  config: cached,
  serverVersion: 0,
  loaded: false,
  error: null,

  hydrate: async () => {
    try {
      const dto = await api.get<ThemeSettingsDto>('/theme')
      const config = parseThemeJson(dto.themeJson)
      applyAppearance(config)
      cacheAppearance(config)
      set({ config, serverVersion: dto.version, loaded: true, error: null })
    } catch {
      // Offline/first-load-before-auth-settles - keep whatever was cached locally and try again
      // next time hydrate() is called (e.g. after sign-in).
      set({ loaded: true })
    }
  },
}))
