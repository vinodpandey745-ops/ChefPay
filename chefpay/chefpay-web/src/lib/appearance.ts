/**
 * ChefPay Web's centralized appearance configuration (UI Modernization directive #4 - "a theme/
 * configuration system... Ideally there should be a centralized configuration where the appearance
 * of the application can be changed without manually modifying every screen").
 *
 * Design: every screen already reads color/spacing through the CSS custom properties defined in
 * index.css (--color-brand-500, --color-bg, --color-surface, etc.) rather than hard-coded hex
 * values - that was true even before this file existed. This module is the single place that
 * OVERRIDES those custom properties at runtime from one `AppearanceConfig` object, so changing a
 * color here repaints every card, button, badge, and chart across the whole app without touching a
 * single component file. The backend (`GET/PUT /api/theme`) only stores the JSON blob; it is
 * intentionally opaque to the server (see ThemeSettings' javadoc) and fully owned by this file's
 * shape.
 */

export type SidebarStyle = 'light' | 'dark' | 'brand'

export interface AppearanceConfig {
  /** Restaurant/brand name shown in the sidebar and login screen. */
  brandName: string
  /** Optional data-URL logo shown instead of the default icon mark. Null = default mark. */
  logoDataUrl: string | null
  /** Primary brand hue (buttons, active nav, links, focus rings) - a single hex; shades are derived. */
  primaryColor: string
  /** Secondary accent (used sparingly - secondary CTAs, highlights). */
  secondaryColor: string
  /** Corner radius scale applied to cards/buttons/inputs, in pixels. */
  radius: number
  /** Sidebar visual treatment. */
  sidebarStyle: SidebarStyle
  /** Which color scheme a brand-new browser session starts in, before the user's own toggle
   * (Topbar's sun/moon button) takes over and is remembered locally. */
  defaultColorMode: 'light' | 'dark' | 'system'
  /** Base font stack. Keep to safe, widely-available families since this renders on POS
   * terminals/tablets that may not have every Google Font installed. */
  fontFamily: string
}

export const DEFAULT_APPEARANCE: AppearanceConfig = {
  brandName: 'Bistrodesk',
  logoDataUrl: null,
  primaryColor: '#4f46e5',
  secondaryColor: '#0ea5e9',
  radius: 16,
  sidebarStyle: 'light',
  defaultColorMode: 'system',
  fontFamily: 'Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
}

/** Derives a light->dark ramp (50-900) from a single hex primary color by mixing toward white/black
 * in OKLab-ish perceptual steps (approximated with simple linear RGB mixing, which is more than
 * good enough for UI chrome - a full color-science ramp is overkill for a restaurant POS theme
 * picker and would pull in a color library this project doesn't otherwise need). */
function hexToRgb(hex: string): [number, number, number] {
  const clean = hex.replace('#', '')
  const full = clean.length === 3 ? clean.split('').map((c) => c + c).join('') : clean
  const num = Number.parseInt(full, 16)
  if (Number.isNaN(num)) return [79, 70, 229] // brand-600 fallback
  return [(num >> 16) & 255, (num >> 8) & 255, num & 255]
}

function mix(a: [number, number, number], b: [number, number, number], t: number): [number, number, number] {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]
}

function toHex([r, g, b]: [number, number, number]): string {
  const clamp = (n: number) => Math.max(0, Math.min(255, Math.round(n)))
  return `#${[r, g, b].map((c) => clamp(c).toString(16).padStart(2, '0')).join('')}`
}

/** Step -> mix-toward-white ratio (light end) / mix-toward-black ratio (dark end), matching the
 * rough visual spacing of Tailwind's default indigo ramp so swapping the primary color doesn't
 * change how "steep" the ramp feels. */
const LIGHT_STEPS: Record<number, number> = { 50: 0.94, 100: 0.86, 200: 0.72, 300: 0.5, 400: 0.25 }
const DARK_STEPS: Record<number, number> = { 700: 0.15, 800: 0.3, 900: 0.5 }

export function deriveRamp(hex: string): Record<number, string> {
  const base = hexToRgb(hex)
  const white: [number, number, number] = [255, 255, 255]
  const black: [number, number, number] = [0, 0, 0]
  const ramp: Record<number, string> = { 500: hex, 600: hex }
  for (const [step, t] of Object.entries(LIGHT_STEPS)) {
    ramp[Number(step)] = toHex(mix(base, white, t))
  }
  for (const [step, t] of Object.entries(DARK_STEPS)) {
    ramp[Number(step)] = toHex(mix(base, black, t))
  }
  // 600 is the "hover/active" shade, one notch darker than the base 500.
  ramp[600] = toHex(mix(base, black, 0.1))
  return ramp
}

/** Applies an AppearanceConfig as CSS custom-property overrides on the document root. Called once
 * on boot (with whatever was cached locally, for an instant correct-looking first paint) and again
 * whenever `/api/theme` resolves or the Appearance settings page live-previews a change. */
export function applyAppearance(config: AppearanceConfig) {
  const root = document.documentElement
  const primaryRamp = deriveRamp(config.primaryColor)
  for (const [step, value] of Object.entries(primaryRamp)) {
    root.style.setProperty(`--color-brand-${step}`, value)
  }
  root.style.setProperty('--color-secondary', config.secondaryColor)
  root.style.setProperty('--radius-app', `${config.radius}px`)
  root.style.setProperty('--font-sans', config.fontFamily)
  root.dataset.sidebarStyle = config.sidebarStyle
  root.dataset.brandName = config.brandName
}

const CACHE_KEY = 'chefpay_web_appearance_v1'

export function loadCachedAppearance(): AppearanceConfig {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    if (raw) return { ...DEFAULT_APPEARANCE, ...(JSON.parse(raw) as Partial<AppearanceConfig>) }
  } catch {
    // fall through to default
  }
  return DEFAULT_APPEARANCE
}

export function cacheAppearance(config: AppearanceConfig) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(config))
  } catch {
    // best-effort only - a failed cache write just means next boot re-fetches from the server
  }
}
