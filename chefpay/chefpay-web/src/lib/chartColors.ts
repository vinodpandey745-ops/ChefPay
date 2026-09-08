/**
 * Categorical palette for dashboard charts (payment methods, category breakdown).
 * Fixed hue order: indigo, teal, amber, rose, violet — never cycled/reassigned
 * per the dataviz skill's rule that color follows the entity, not its rank.
 *
 * Validated with scripts/validate_palette.js from the bundled `dataviz` skill:
 *   light: node validate_palette.js "#4f46e5,#0d9488,#d97706,#e11d48,#7c3aed" --mode light
 *     -> ALL CHECKS PASS (CVD adjacent >= 8.0 target met on every pair, normal-vision floor 16.6)
 *   dark:  node validate_palette.js "#6366f1,#0aa38f,#b45309,#f43f5e,#8b5cf6" --mode dark
 *     -> ALL CHECKS PASS (lightness band 0.48-0.67, normal-vision floor 15.1)
 *
 * Each mode's palette was tuned independently against its own chart surface
 * (#fcfcfb light / #1a1a19 dark) rather than derived by an automatic filter,
 * per the skill's "dark mode is selected, not an automatic flip" rule.
 */
export const CHART_PALETTE_LIGHT = ['#4f46e5', '#0d9488', '#d97706', '#e11d48', '#7c3aed'] as const

export const CHART_PALETTE_DARK = ['#6366f1', '#0aa38f', '#b45309', '#f43f5e', '#8b5cf6'] as const

export const CHART_LABELS = ['Indigo', 'Teal', 'Amber', 'Rose', 'Violet'] as const

/** Pick the right categorical palette for the active theme. */
export function chartPalette(isDark: boolean): readonly string[] {
  return isDark ? CHART_PALETTE_DARK : CHART_PALETTE_LIGHT
}

/** Single-hue sequential ramp (indigo, light->dark) for magnitude charts like the sales trend line. */
export const SEQUENTIAL_LIGHT = { stroke: '#4f46e5', fill: 'rgba(79, 70, 229, 0.12)' }
export const SEQUENTIAL_DARK = { stroke: '#818cf8', fill: 'rgba(129, 140, 248, 0.18)' }
