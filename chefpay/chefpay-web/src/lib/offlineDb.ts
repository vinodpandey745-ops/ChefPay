/**
 * Offline-first storage layer for chefpay-web, built directly on the browser's native `indexedDB`
 * API - no external library (per the locked-in decision: IndexedDB-based offline cache, explicitly
 * NOT WASM SQLite). This file is deliberately free of any `window`-only dependency (no
 * localStorage, no zustand store reads) so it can be imported unmodified from BOTH the main thread
 * (api.ts, syncEngine.ts) and the dedicated sync.worker.ts background thread - IndexedDB itself is
 * fully available inside a Worker's global scope, which is what makes that possible.
 *
 * Two object stores in one database:
 *   - `cache`  - a read-through cache of GET responses, keyed by a string built from the request
 *                path + serialized query string (see buildCacheKey). Read by api.ts's apiRequest()
 *                as a fallback when a GET fails due to a genuine network error, and opportunistically
 *                written on every successful GET.
 *   - `outbox` - a durable, auto-incrementing, FIFO queue of write mutations (POST/PUT/PATCH/DELETE)
 *                made while the server was unreachable. Replayed in id order by syncCore.ts.
 *
 * Also exports exportAllData()/importAllData() - the plain-JSON "backup"/"restore" feature
 * (explicitly a JSON export, not a literal downloadable .db file - also locked in already).
 */

const DB_NAME = 'chefpay-offline-v1'
const DB_VERSION = 1
const CACHE_STORE = 'cache'
const OUTBOX_STORE = 'outbox'

/** Loose enough to describe both a GET's query-string params (api.ts) and a queued write's
 * query-string params (outbox entries for e.g. `api.post(path, body, query)` calls) without this
 * file needing to import anything from api.ts (which would drag in the zustand auth store and,
 * through it, localStorage - unavailable in a Worker). */
export type QueryParams = Record<string, string | number | boolean | undefined>

export interface CacheEntry<T = unknown> {
  key: string
  data: T
  cachedAt: number
}

export type OutboxMethod = 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface OutboxEntry {
  id: number
  method: OutboxMethod
  path: string
  query?: QueryParams
  body?: unknown
  createdAt: number
  attempts: number
  lastError?: string
  /** Set once `attempts` exceeds the retry cap (see syncCore.ts) - a parked entry is no longer
   * retried automatically, and is surfaced to the UI as a distinct "failed" count rather than
   * silently retried forever. */
  failed?: boolean
  /** Offline-order-creation fix (PosTerminalPage.tsx / syncCore.ts): set ONLY on the one outbox
   * entry that is a `POST /orders` standing in for an order the POS created while genuinely
   * offline (no server id existed yet, so the UI synthesized a client-side `local-...` placeholder
   * id to keep working - see LOCAL_ORDER_PREFIX). Optional and absent on every other entry,
   * including every entry queued before this field existed, so it's fully backward-compatible with
   * anything already sitting in a running install's outbox. When this entry's replay succeeds,
   * syncCore.ts's drainOutbox records `createsLocalId -> <real server id>` so any LATER queued
   * entry that still references the placeholder in its path (e.g. `/orders/local-xxx/items`) gets
   * that segment rewritten to the real id before it's sent - see drainOutbox's own javadoc. */
  createsLocalId?: string
}

export type NewOutboxEntry = {
  method: OutboxMethod
  path: string
  query?: QueryParams
  body?: unknown
}

export interface BackupPayload {
  version: number
  exportedAt: string
  cache: CacheEntry[]
  outbox: OutboxEntry[]
}

/** Builds the same cache key for a given path+query regardless of caller (api.ts on write,
 * syncCore.ts's core-cache refresh on write, api.ts again on read) - keeping this in one place is
 * what keeps those three call sites from silently drifting apart. Query keys are sorted so the
 * same logical request always produces the same string regardless of object-key insertion order. */
export function buildCacheKey(path: string, query?: QueryParams): string {
  if (!query) return `GET:${path}`
  const params = Object.entries(query)
    .filter(([, value]) => value !== undefined)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, value]) => `${key}=${String(value)}`)
    .join('&')
  return params ? `GET:${path}?${params}` : `GET:${path}`
}

let dbPromise: Promise<IDBDatabase> | null = null

function openDb(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise
  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(CACHE_STORE)) {
        db.createObjectStore(CACHE_STORE, { keyPath: 'key' })
      }
      if (!db.objectStoreNames.contains(OUTBOX_STORE)) {
        db.createObjectStore(OUTBOX_STORE, { keyPath: 'id', autoIncrement: true })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
  return dbPromise
}

function promisify<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

function txDone(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
    tx.onabort = () => reject(tx.error)
  })
}

/** Returns `null` on any IndexedDB failure (private-browsing/storage-disabled included) rather
 * than throwing - a cache-fallback read failing is a safe "no offline data available" case, not a
 * crash. */
export async function getCached<T>(key: string): Promise<T | null> {
  try {
    const db = await openDb()
    const tx = db.transaction(CACHE_STORE, 'readonly')
    const entry = await promisify<CacheEntry<T> | undefined>(tx.objectStore(CACHE_STORE).get(key))
    return entry ? entry.data : null
  } catch {
    return null
  }
}

export async function setCached<T>(key: string, data: T): Promise<void> {
  const db = await openDb()
  const tx = db.transaction(CACHE_STORE, 'readwrite')
  const entry: CacheEntry<T> = { key, data, cachedAt: Date.now() }
  tx.objectStore(CACHE_STORE).put(entry)
  await txDone(tx)
}

export async function clearCache(): Promise<void> {
  const db = await openDb()
  const tx = db.transaction(CACHE_STORE, 'readwrite')
  tx.objectStore(CACHE_STORE).clear()
  await txDone(tx)
}

export async function enqueueOutbox(entry: NewOutboxEntry): Promise<number> {
  const db = await openDb()
  const tx = db.transaction(OUTBOX_STORE, 'readwrite')
  const store = tx.objectStore(OUTBOX_STORE)
  const record: Omit<OutboxEntry, 'id'> = {
    method: entry.method,
    path: entry.path,
    query: entry.query,
    body: entry.body,
    createdAt: Date.now(),
    attempts: 0,
    failed: false,
  }
  const request = store.add(record)
  let id = 0
  request.onsuccess = () => {
    id = request.result as number
  }
  await txDone(tx)
  return id
}

/** Returns entries in FIFO (ascending id / insertion) order - queued writes are frequently
 * causally dependent (e.g. "create order" then "add item to order X"), so callers must replay
 * them in this order, not in parallel. */
export async function listOutbox(): Promise<OutboxEntry[]> {
  const db = await openDb()
  const tx = db.transaction(OUTBOX_STORE, 'readonly')
  const entries = await promisify<OutboxEntry[]>(tx.objectStore(OUTBOX_STORE).getAll())
  return entries.sort((a, b) => a.id - b.id)
}

export async function removeOutboxEntry(id: number): Promise<void> {
  const db = await openDb()
  const tx = db.transaction(OUTBOX_STORE, 'readwrite')
  tx.objectStore(OUTBOX_STORE).delete(id)
  await txDone(tx)
}

export async function updateOutboxEntry(id: number, patch: Partial<Omit<OutboxEntry, 'id'>>): Promise<void> {
  const db = await openDb()
  const tx = db.transaction(OUTBOX_STORE, 'readwrite')
  const store = tx.objectStore(OUTBOX_STORE)
  const existing = await promisify<OutboxEntry | undefined>(store.get(id))
  if (existing) {
    store.put({ ...existing, ...patch })
  }
  await txDone(tx)
}

export async function countOutbox(): Promise<{ pending: number; failed: number }> {
  const entries = await listOutbox()
  let pending = 0
  let failed = 0
  for (const entry of entries) {
    if (entry.failed) failed++
    else pending++
  }
  return { pending, failed }
}

/** Exports both stores' full contents as one plain JSON-serializable object - the "backup" feature
 * (a JSON export, not a literal .db file, per the locked-in product decision). */
export async function exportAllData(): Promise<BackupPayload> {
  const db = await openDb()
  const tx = db.transaction([CACHE_STORE, OUTBOX_STORE], 'readonly')
  const cache = await promisify<CacheEntry[]>(tx.objectStore(CACHE_STORE).getAll())
  const outbox = await promisify<OutboxEntry[]>(tx.objectStore(OUTBOX_STORE).getAll())
  await txDone(tx)
  return { version: DB_VERSION, exportedAt: new Date().toISOString(), cache, outbox }
}

/** Restores both stores from a previously exported backup, replacing whatever is currently stored.
 * Validates the shape defensively since this reads a user-supplied file. */
export async function importAllData(payload: unknown): Promise<void> {
  const data = payload as Partial<BackupPayload> | null
  if (!data || typeof data !== 'object' || !Array.isArray(data.cache) || !Array.isArray(data.outbox)) {
    throw new Error('Invalid backup file: expected an object with "cache" and "outbox" arrays.')
  }
  const db = await openDb()
  const tx = db.transaction([CACHE_STORE, OUTBOX_STORE], 'readwrite')
  const cacheStore = tx.objectStore(CACHE_STORE)
  const outboxStore = tx.objectStore(OUTBOX_STORE)
  cacheStore.clear()
  outboxStore.clear()
  for (const entry of data.cache) cacheStore.put(entry)
  for (const entry of data.outbox) outboxStore.put(entry)
  await txDone(tx)
}
