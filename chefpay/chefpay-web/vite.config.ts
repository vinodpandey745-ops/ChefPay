import { fileURLToPath, URL } from 'node:url'

import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Bistrodesk Web (originally "ChefPay Web", Phase 1 UI modernization) - a React/Tailwind client
// for the EXISTING chefpay-server REST API, same origin as the API, no CORS/base-URL configuration
// needed in production.
//
// Bistrodesk Phase 12 (URL restructuring, requirement #26): this used to build to
// `chefpay-server/.../static/app/` and serve from `base: '/app/'` (the same convention
// ManagerAppController's `/manager` still uses). The main POS is now the server's ROOT app instead
// - `base: '/'`, built straight into `static/` itself - so a plain `https://<host>/` reaches it, no
// `/app` segment needed. `/manager` and `/admin` are unaffected (still their own static
// subdirectories, still their own base paths) - only this build's own output location moved.
//
// `outDir` now points at the SHARED `static/` root that `/manager` and `/admin`'s own static
// subdirectories also live under, so `emptyOutDir` is deliberately `false`: the default `true`
// would delete `static/manager/` and `static/admin/` on every rebuild of this app, since Vite empties
// the entire `outDir` tree, not just the files it's about to write. This app's own files (index.html,
// assets/, manifest.json, favicon.svg, the icon-*.png set, apple-touch-icon.png, sw.js) simply
// overwrite their same-named predecessors on each build; a stale previous build's uniquely-hashed
// `assets/*.js` files can accumulate in `static/assets/` over repeated builds (harmless disk bloat,
// never referenced by the freshly-written index.html) rather than being purged automatically - an
// accepted tradeoff for not risking `manager`/`admin`'s own content.
//
// During `npm run dev`, the dev server proxies /api and /ws to a locally running chefpay-server
// (default port 8080, see application.yml) so this can be developed against the real backend
// without a production build round-trip every change.
export default defineConfig({
  base: '/',
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'http://localhost:8080', changeOrigin: true, ws: true },
    },
  },
  // Same proxy as the dev server, so `vite preview` (serving the actual production bundle, without
  // React's dev-mode StrictMode double-effect-invocation) can also be pointed at a local
  // chefpay-server for a closer-to-production smoke test.
  preview: {
    port: 4173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'http://localhost:8080', changeOrigin: true, ws: true },
    },
  },
  build: {
    outDir: '../chefpay-server/src/main/resources/static',
    emptyOutDir: false,
  },
})
