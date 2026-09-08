import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowLeft,
  ArrowRight,
  ChefHat,
  FolderTree,
  ImagePlus,
  Loader2,
  Lock,
  Merge,
  Pencil,
  Plus,
  RotateCcw,
  Search,
  Sparkles,
  Trash2,
  UploadCloud,
  X,
} from 'lucide-react'
import { useMemo, useRef, useState } from 'react'

import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Modal } from '@/components/ui/Modal'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { useEntitlements } from '@/hooks/useEntitlements'
import { api } from '@/lib/api'
import { cn, formatCurrency } from '@/lib/utils'
import { useAuthStore } from '@/store/auth'
import { ApiError } from '@/types/api'
import type {
  AccessibleBranchDto,
  AiMenuImportAnalyzeResponse,
  AiMenuImportDraftItem,
  CreateCategoryRequest,
  CreateMenuItemRequest,
  MenuCategoryDto,
  MenuItemDto,
  MergeCategoryRequest,
  StationDto,
  UpdateCategoryRequest,
  UpdateDescriptionRequest,
  UpdateMenuItemRequest,
} from '@/types/api'

// ---- Local request/response shapes for the AI Bulk Import endpoints (not yet in types/api.ts). ----

interface AiAnalyzeRequest {
  imageBase64: string
  mimeType?: string
}

/** Round 18: apply-time item shape gained an optional `categoryId` alongside the draft's
 * `categoryName` - when set, the backend uses that exact category and ignores `categoryName`
 * entirely for matching/creation purposes (see the category-mapping step in AiImportModal below,
 * which is what actually decides whether to populate this per item). */
interface AiApplyItem extends AiMenuImportDraftItem {
  categoryId?: string | null
}

interface AiApplyRequest {
  items: AiApplyItem[]
  // Bistrodesk branch-isolation release (requirement #1) - see AiMenuImportDtos.ApplyRequest's
  // javadoc: one import batch always lands on exactly one branch, resolved server-side when
  // omitted (a multi-branch/unrestricted caller with no default must supply one).
  branchId?: string | null
}

interface AiApplyResponse {
  itemsCreated: number
  categoriesCreated: number
}

/** Normalizes a category name for loose matching: trim + lowercase + strip a single trailing "s"
 * so e.g. "Beverage" and "Beverages" (or "beverages" from a photo vs "Beverage" already on file)
 * are treated as the same category rather than spawning a near-duplicate. */
function normalizeCategoryKey(name: string): string {
  const lower = name.trim().toLowerCase()
  return lower.length > 1 && lower.endsWith('s') ? lower.slice(0, -1) : lower
}

function findBestCategoryMatch(name: string, candidates: MenuCategoryDto[]): MenuCategoryDto | null {
  const target = normalizeCategoryKey(name)
  if (!target) return null
  return candidates.find((c) => normalizeCategoryKey(c.name) === target) ?? null
}

type FoodType = 'VEG' | 'EGG' | 'NON_VEG'

const FOOD_TYPE_META: Record<FoodType, { label: string; ring: string; dot: string }> = {
  VEG: { label: 'Vegetarian', ring: 'border-success', dot: 'bg-success' },
  EGG: { label: 'Contains Egg', ring: 'border-warning', dot: 'bg-warning' },
  NON_VEG: { label: 'Non-Vegetarian', ring: 'border-danger', dot: 'bg-danger' },
}

function normalizeFoodType(raw: string | null | undefined): FoodType {
  return raw === 'EGG' || raw === 'NON_VEG' ? raw : 'VEG'
}

/** Small square-with-dot marker matching the familiar Indian-restaurant veg/egg/non-veg convention
 * (green square = veg, amber/brown = egg, red/maroon = non-veg) used throughout printed menus and
 * most Indian POS systems - purely a visual convention, doesn't need any extra data beyond foodType. */
function FoodTypeMark({ foodType, className }: { foodType: string; className?: string }) {
  const meta = FOOD_TYPE_META[normalizeFoodType(foodType)]
  return (
    <span
      title={meta.label}
      className={cn('inline-flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded-[3px] border-[1.5px] bg-surface', meta.ring, className)}
    >
      <span className={cn('h-1.5 w-1.5 rounded-full', meta.dot)} />
    </span>
  )
}

/** Most mutations here carry an optimistic-lock `version` - this centralizes the one friendly
 * message they should show on a genuine VERSION_CONFLICT instead of the server's raw "updated by
 * another terminal" string, plus a readable fallback for a plain 403 (no MENU_MANAGE) on an
 * individual action.
 *
 * <p>Round 19 hotfix: this used to also collapse on bare {@code err.status === 409}, which
 * swallowed every OTHER 409 too - and delete/merge (which carry no version at all, so a 409 from
 * them is NEVER a version conflict) return exactly that: {@code CATEGORY_NOT_EMPTY}/{@code
 * CATEGORY_HAS_SUBCATEGORIES}, each with its own specific, already-correct message from {@code
 * MenuController} (e.g. "'Beverages' has 2 item(s). Merge it into another category first..."). The
 * blanket check silently replaced that with the generic version-conflict text instead, which is
 * exactly what {@link DeleteCategoryModal}'s own comment already said should NOT happen. Checking
 * only the specific {@code errorCode} (the one thing {@code GlobalExceptionHandler} uniformly
 * stamps on every real optimistic-lock failure app-wide) fixes this without touching any other 409
 * this function still needs to catch. */
function describeError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.errorCode === 'VERSION_CONFLICT') {
      return 'This was updated elsewhere - refresh the page and try again.'
    }
    if (err.status === 403) {
      return "You don't have permission to do that."
    }
    return err.message || fallback
  }
  return fallback
}

function fileToBase64(file: File): Promise<{ base64: string; mimeType: string }> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const result = typeof reader.result === 'string' ? reader.result : ''
      const commaIndex = result.indexOf(',')
      resolve({ base64: commaIndex >= 0 ? result.slice(commaIndex + 1) : result, mimeType: file.type || 'image/jpeg' })
    }
    reader.onerror = () => reject(reader.error ?? new Error('Could not read file'))
    reader.readAsDataURL(file)
  })
}

// -------------------------------------------------------------------------------------------
// Category sidebar
// -------------------------------------------------------------------------------------------

function CategoryRow({
  label,
  count,
  selected,
  inactive,
  canManage,
  depth = 0,
  isSubcategory = false,
  onClick,
  onEdit,
  onDelete,
  onMerge,
  onSetParent,
}: {
  label: string
  count: number
  selected: boolean
  inactive?: boolean
  canManage: boolean
  /** 0 = top-level, 1 = subcategory - only one level is ever used since the backend only allows
   * one level of nesting, but this stays a number rather than a boolean in case that ever changes. */
  depth?: number
  isSubcategory?: boolean
  onClick: () => void
  onEdit?: () => void
  onDelete?: () => void
  onMerge?: () => void
  onSetParent?: () => void
}) {
  return (
    <div
      className={cn(
        'group flex items-center gap-0.5 rounded-xl',
        depth > 0 && 'ml-2.5 border-l border-app pl-1.5',
        selected && 'bg-brand-50 dark:bg-brand-900/30',
      )}
    >
      <button
        type="button"
        onClick={onClick}
        className={cn(
          'flex flex-1 items-center justify-between gap-2 rounded-xl px-3 py-2 text-left text-sm font-semibold transition-colors',
          selected ? 'text-brand-700 dark:text-brand-200' : 'text-app hover:bg-app',
          inactive && !selected && 'text-muted',
        )}
      >
        <span className="truncate">
          {label}
          {inactive && <span className="ml-1.5 text-[10px] font-bold uppercase tracking-wide text-danger">Inactive</span>}
        </span>
        <span
          className={cn(
            'shrink-0 rounded-full px-2 py-0.5 text-[11px] font-bold',
            selected ? 'bg-brand-600 text-white' : 'bg-app text-muted',
          )}
        >
          {count}
        </span>
      </button>
      {canManage && (
        <>
          {onEdit && (
            <button
              type="button"
              onClick={onEdit}
              title="Edit category"
              className="shrink-0 rounded-lg p-1.5 text-muted opacity-0 hover:bg-app hover:text-brand-600 group-hover:opacity-100"
            >
              <Pencil className="h-3.5 w-3.5" />
            </button>
          )}
          {onMerge && (
            <button
              type="button"
              onClick={onMerge}
              title="Merge into another category"
              className="shrink-0 rounded-lg p-1.5 text-muted opacity-0 hover:bg-app hover:text-brand-600 group-hover:opacity-100"
            >
              <Merge className="h-3.5 w-3.5" />
            </button>
          )}
          {onSetParent && (
            <button
              type="button"
              onClick={onSetParent}
              title={isSubcategory ? 'Remove from parent category' : 'Make this a subcategory of another category'}
              className="shrink-0 rounded-lg p-1.5 text-muted opacity-0 hover:bg-app hover:text-brand-600 group-hover:opacity-100"
            >
              <FolderTree className="h-3.5 w-3.5" />
            </button>
          )}
          {onDelete && (
            <button
              type="button"
              onClick={onDelete}
              title="Delete category"
              className="shrink-0 rounded-lg p-1.5 text-muted opacity-0 hover:bg-danger-soft hover:text-danger group-hover:opacity-100"
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
          )}
        </>
      )}
    </div>
  )
}

function CategoryFormModal({
  open,
  onClose,
  category,
  categories,
  nextDisplayOrder,
}: {
  open: boolean
  onClose: () => void
  category: MenuCategoryDto | null
  categories: MenuCategoryDto[]
  nextDisplayOrder: number
}) {
  const queryClient = useQueryClient()
  const isEdit = !!category
  const [name, setName] = useState(category?.name ?? '')
  const [displayOrder, setDisplayOrder] = useState(category?.displayOrder ?? nextDisplayOrder)
  const [active, setActive] = useState(category?.active ?? true)
  const [parentCategoryId, setParentCategoryId] = useState('')
  const [error, setError] = useState<string | null>(null)

  // Only a top-level category can be picked as a parent - the backend only allows one level of
  // nesting, so a category that already has a parent of its own can't take on a child.
  const parentOptions = useMemo(() => categories.filter((c) => !c.parentCategoryId), [categories])

  const resetAndClose = () => {
    setError(null)
    onClose()
  }

  const save = useMutation({
    mutationFn: () => {
      if (category) {
        const body: UpdateCategoryRequest = { name, displayOrder, active, version: category.version }
        return api.patch<MenuCategoryDto>(`/menu/categories/${category.id}`, body)
      }
      const body: CreateCategoryRequest = { name, displayOrder, parentCategoryId: parentCategoryId || null }
      return api.post<MenuCategoryDto>('/menu/categories', body)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['menu', 'editor'] })
      resetAndClose()
    },
    onError: (err) => setError(describeError(err, isEdit ? 'Could not update this category' : 'Could not create this category')),
  })

  return (
    <Modal open={open} onClose={resetAndClose} title={isEdit ? 'Edit category' : 'Add category'} widthClassName="max-w-sm">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          save.mutate()
        }}
      >
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Category name</label>
          <input
            required
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. Starters"
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Display order</label>
          <input
            type="number"
            value={displayOrder}
            onChange={(e) => setDisplayOrder(Number(e.target.value))}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
          <p className="mt-1 text-[11px] text-muted">Lower numbers show first in the menu.</p>
        </div>
        {!isEdit && (
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Parent category (optional)</label>
            <select
              value={parentCategoryId}
              onChange={(e) => setParentCategoryId(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            >
              <option value="">None (top-level category)</option>
              {parentOptions.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
            <p className="mt-1 text-[11px] text-muted">
              Makes this a subcategory (e.g. "Veg" under "Main Course"). Only one level of nesting is supported.
            </p>
          </div>
        )}
        {isEdit && (
          <label className="flex items-center gap-2 text-sm font-medium text-app">
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} className="h-4 w-4 rounded border-app" />
            Active (visible on POS)
          </label>
        )}
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <Button type="submit" className="w-full" disabled={save.isPending}>
          {save.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
          {isEdit ? (save.isPending ? 'Saving…' : 'Save category') : save.isPending ? 'Adding…' : 'Add category'}
        </Button>
      </form>
    </Modal>
  )
}

// -------------------------------------------------------------------------------------------
// Delete / merge / re-parent category modals
// -------------------------------------------------------------------------------------------

/** The backend only allows a hard delete when the category has zero items (active or inactive) and
 * no subcategories - rather than guess that client-side (an inactive item wouldn't even be visible
 * with "Show inactive" off), this always offers Delete and surfaces the 409's message verbatim,
 * since the backend already writes CATEGORY_NOT_EMPTY / CATEGORY_HAS_SUBCATEGORIES for a person to read. */
function DeleteCategoryModal({ category, onClose }: { category: MenuCategoryDto; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)

  const del = useMutation({
    mutationFn: () => api.delete<void>(`/menu/categories/${category.id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['menu', 'editor'] })
      onClose()
    },
    onError: (err) => setError(describeError(err, 'Could not delete this category')),
  })

  return (
    <Modal open onClose={onClose} title={`Delete "${category.name}"?`} widthClassName="max-w-sm">
      <div className="space-y-4">
        <p className="text-sm text-muted">
          This permanently removes the category. It only succeeds if it has no items - active or inactive - and no
          subcategories under it.
        </p>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button variant="secondary" className="flex-1" type="button" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="danger" className="flex-1" disabled={del.isPending} onClick={() => del.mutate()}>
            {del.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
            {del.isPending ? 'Deleting…' : 'Delete category'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

function MergeCategoryModal({
  category,
  categories,
  onClose,
}: {
  category: MenuCategoryDto
  categories: MenuCategoryDto[]
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  // Merge targets are restricted to other top-level, active categories - merging is meant to fold
  // one live category into another, not into something already hidden or itself nested.
  const options = useMemo(
    () => categories.filter((c) => c.id !== category.id && c.active && !c.parentCategoryId),
    [categories, category.id],
  )
  const [targetId, setTargetId] = useState(options[0]?.id ?? '')
  const [error, setError] = useState<string | null>(null)

  const merge = useMutation({
    mutationFn: () =>
      api.post<MenuCategoryDto>(`/menu/categories/${category.id}/merge`, {
        targetCategoryId: targetId,
      } satisfies MergeCategoryRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['menu', 'editor'] })
      onClose()
    },
    onError: (err) => setError(describeError(err, 'Could not merge this category')),
  })

  return (
    <Modal open onClose={onClose} title={`Merge "${category.name}" into…`} widthClassName="max-w-sm">
      <div className="space-y-4">
        <p className="text-sm text-muted">
          Every item in "{category.name}" - active or inactive - moves into the category you pick below, then "
          {category.name}" itself is removed.
        </p>
        {options.length === 0 ? (
          <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
            There's no other top-level category to merge into yet.
          </div>
        ) : (
          <select
            value={targetId}
            onChange={(e) => setTargetId(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          >
            {options.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        )}
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button variant="secondary" className="flex-1" type="button" onClick={onClose}>
            Cancel
          </Button>
          <Button className="flex-1" disabled={!targetId || merge.isPending} onClick={() => merge.mutate()}>
            {merge.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Merge className="h-4 w-4" />}
            {merge.isPending ? 'Merging…' : 'Merge category'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

/** Doubles as both "make a subcategory" (category has no parent yet) and "remove from parent"
 * (category already has one) - which mode applies is decided entirely by `category.parentCategoryId`. */
function ParentCategoryModal({
  category,
  categories,
  onClose,
}: {
  category: MenuCategoryDto
  categories: MenuCategoryDto[]
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const isSubcategory = !!category.parentCategoryId
  const options = useMemo(
    () => categories.filter((c) => c.id !== category.id && c.active && !c.parentCategoryId),
    [categories, category.id],
  )
  const [targetId, setTargetId] = useState(options[0]?.id ?? '')
  const [error, setError] = useState<string | null>(null)

  const setParent = useMutation({
    mutationFn: () => {
      const body: UpdateCategoryRequest = isSubcategory
        ? { clearParentCategory: true, version: category.version }
        : { parentCategoryId: targetId, version: category.version }
      return api.patch<MenuCategoryDto>(`/menu/categories/${category.id}`, body)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['menu', 'editor'] })
      onClose()
    },
    onError: (err) => setError(describeError(err, 'Could not update this category')),
  })

  return (
    <Modal
      open
      onClose={onClose}
      title={isSubcategory ? `Remove "${category.name}" from its parent?` : `Make "${category.name}" a subcategory of…`}
      widthClassName="max-w-sm"
    >
      <div className="space-y-4">
        {isSubcategory ? (
          <p className="text-sm text-muted">
            "{category.name}" is currently a subcategory of "{category.parentCategoryName}". This makes it a normal
            top-level category again - its items are unaffected either way.
          </p>
        ) : options.length === 0 ? (
          <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
            There's no other top-level category to nest this under yet.
          </div>
        ) : (
          <>
            <p className="text-sm text-muted">
              Only one level of nesting is supported - pick a top-level category for "{category.name}" to live under
              (e.g. "Veg" under "Main Course").
            </p>
            <select
              value={targetId}
              onChange={(e) => setTargetId(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            >
              {options.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </>
        )}
        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
        <div className="flex gap-2">
          <Button variant="secondary" className="flex-1" type="button" onClick={onClose}>
            Cancel
          </Button>
          <Button
            className="flex-1"
            disabled={setParent.isPending || (!isSubcategory && options.length === 0)}
            onClick={() => setParent.mutate()}
          >
            {setParent.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <FolderTree className="h-4 w-4" />}
            {setParent.isPending ? 'Saving…' : isSubcategory ? 'Remove from parent' : 'Make subcategory'}
          </Button>
        </div>
      </div>
    </Modal>
  )
}

// -------------------------------------------------------------------------------------------
// Item card + form modal
// -------------------------------------------------------------------------------------------

function MenuItemCard({
  item,
  categoryName,
  canManage,
  showBranchBadge,
  onEdit,
  onToggleAvailable,
  onToggleActive,
  busyAction,
}: {
  item: MenuItemDto
  categoryName: string
  canManage: boolean
  showBranchBadge: boolean
  onEdit: () => void
  onToggleAvailable: () => void
  onToggleActive: () => void
  busyAction: 'available' | 'active' | null
}) {
  return (
    <Card className={cn('flex flex-col gap-3 p-4', !item.active && 'opacity-60')}>
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 items-start gap-2">
          <FoodTypeMark foodType={item.foodType} className="mt-1" />
          <div className="min-w-0">
            <div className="truncate text-sm font-bold text-app">{item.name}</div>
            <div className="truncate text-xs text-muted">{categoryName}</div>
          </div>
        </div>
        {canManage && (
          <button
            type="button"
            onClick={onEdit}
            title="Edit item"
            className="shrink-0 rounded-lg p-1.5 text-muted hover:bg-app hover:text-brand-600"
          >
            <Pencil className="h-3.5 w-3.5" />
          </button>
        )}
      </div>

      <div className="flex items-baseline gap-2">
        <span className="text-lg font-extrabold text-app">{formatCurrency(item.price)}</span>
        {item.halfPrice != null && <span className="text-xs font-medium text-muted">Half {formatCurrency(item.halfPrice)}</span>}
      </div>

      <div className="flex flex-wrap items-center gap-1.5">
        {/* Bistrodesk branch-isolation release (requirement #1): every item now always belongs to
            exactly one branch - only worth showing as a badge when this caller can see more than
            one branch's items at once (an aggregate multi-branch/unrestricted view), same "only
            show what's meaningfully in question" convention as CustomerModal's branch picker. */}
        {showBranchBadge && item.branchName && <Badge tone="neutral">{item.branchName}</Badge>}
        {item.stationName && <Badge tone="info">{item.stationName}</Badge>}
        {item.directSale && <Badge tone="brand">Direct sale</Badge>}
        {item.active && !item.available && <Badge tone="warning">86'd</Badge>}
        {!item.active && <Badge tone="danger">Inactive</Badge>}
      </div>

      {canManage && item.active && (
        <div className="mt-1 flex items-center justify-between border-t border-app pt-2.5">
          <button
            type="button"
            disabled={busyAction !== null}
            onClick={onToggleAvailable}
            className="flex items-center gap-2 text-xs font-semibold text-app disabled:opacity-50"
            title={item.available ? 'Mark as temporarily unavailable' : 'Mark as available again'}
          >
            <span className={cn('relative inline-flex h-5 w-9 items-center rounded-full transition-colors', item.available ? 'bg-brand-600' : 'border border-app bg-app')}>
              <span className={cn('inline-block h-3.5 w-3.5 translate-x-1 rounded-full bg-white shadow transition-transform', item.available && 'translate-x-4')} />
            </span>
            {busyAction === 'available' ? 'Saving…' : item.available ? 'Available' : "86'd"}
          </button>
          <button
            type="button"
            disabled={busyAction !== null}
            onClick={onToggleActive}
            className="text-xs font-semibold text-danger hover:underline disabled:opacity-50"
          >
            {busyAction === 'active' ? 'Removing…' : 'Remove'}
          </button>
        </div>
      )}

      {canManage && !item.active && (
        <Button size="sm" variant="secondary" className="mt-1" disabled={busyAction !== null} onClick={onToggleActive}>
          <RotateCcw className="h-3.5 w-3.5" /> {busyAction === 'active' ? 'Restoring…' : 'Reactivate'}
        </Button>
      )}
    </Card>
  )
}

function ItemFormModal({
  open,
  onClose,
  item,
  categories,
  stations,
  defaultCategoryId,
  branches,
}: {
  open: boolean
  onClose: () => void
  item: MenuItemDto | null
  categories: MenuCategoryDto[]
  stations: StationDto[]
  defaultCategoryId: string | null
  branches: AccessibleBranchDto[]
}) {
  const queryClient = useQueryClient()
  const isEdit = !!item

  // Bistrodesk branch-isolation release (requirement #1, user-confirmed decision: "strictly for
  // the branch where it is getting created, not shared with any other branch"): a menu item now
  // always belongs to exactly one branch, resolved server-side (a single-branch caller or one with
  // a default branch never needs to choose) - same "only show a picker when there's a real choice"
  // convention as CustomersPage's CustomerModal/InventoryPage's AddItemModal. Only offered on
  // create - moving an existing item to a different branch isn't part of this form.
  const showBranchPicker = !isEdit && branches.length > 1
  const [branchId, setBranchId] = useState('')

  // The picker only offers active categories to create a brand new item under, but an item being
  // EDITED must still show its own current category even if that category has since been
  // deactivated (the field is disabled in edit mode anyway - this just avoids a blank <select>).
  const availableCategories = useMemo(
    () => categories.filter((c) => c.active || c.id === item?.categoryId),
    [categories, item?.categoryId],
  )
  // A selected sidebar category can be an inactive one (only reachable with "Show inactive" on) -
  // don't default a brand new item into a category this list doesn't offer.
  const validDefaultCategoryId = defaultCategoryId && availableCategories.some((c) => c.id === defaultCategoryId) ? defaultCategoryId : null
  const [categoryId, setCategoryId] = useState(item?.categoryId ?? validDefaultCategoryId ?? availableCategories[0]?.id ?? '')
  const [name, setName] = useState(item?.name ?? '')
  const [price, setPrice] = useState(item ? String(item.price) : '')
  const [halfPrice, setHalfPrice] = useState(item?.halfPrice != null ? String(item.halfPrice) : '')
  const [description, setDescription] = useState(item?.description ?? '')
  const [foodType, setFoodType] = useState<FoodType>(normalizeFoodType(item?.foodType))
  const [stationId, setStationId] = useState(item?.stationId ?? '')
  const [directSale, setDirectSale] = useState(item?.directSale ?? false)
  const [sku, setSku] = useState(item?.sku ?? '')
  const [plu, setPlu] = useState(item?.plu ?? '')
  const [taxCode, setTaxCode] = useState(item?.taxCode ?? '')
  const [barcode, setBarcode] = useState(item?.barcode ?? '')
  const [prepTimeMinutes, setPrepTimeMinutes] = useState(item?.prepTimeMinutes != null ? String(item.prepTimeMinutes) : '')
  const [error, setError] = useState<string | null>(null)

  const resetAndClose = () => {
    setError(null)
    onClose()
  }

  const activeStations = stations.filter((s) => s.active)

  const save = useMutation({
    mutationFn: () => {
      const parsedPrice = Number(price)
      const parsedHalfPrice = halfPrice.trim() === '' ? null : Number(halfPrice)
      const parsedPrepTime = prepTimeMinutes.trim() === '' ? null : Number(prepTimeMinutes)

      if (item) {
        const body: UpdateMenuItemRequest = {
          name,
          price: parsedPrice,
          taxCode: taxCode.trim() === '' ? null : taxCode.trim(),
          vegetarian: foodType === 'VEG',
          foodType,
          directSale,
          halfPrice: parsedHalfPrice,
          barcode: barcode.trim() === '' ? null : barcode.trim(),
          prepTimeMinutes: parsedPrepTime,
          version: item.version,
        }
        if (stationId) {
          body.stationId = stationId
        } else {
          body.clearStation = true
        }
        const itemId = item.id
        const previousDescription = item.description
        return api.patch<MenuItemDto>(`/menu/items/${itemId}`, body).then(async (saved) => {
          // The description field has its own dedicated endpoint on this backend (separate from the
          // general item PATCH) - only call it when the description actually changed, to avoid an
          // extra request (and an extra version bump) on every plain item edit.
          if ((description || null) !== (previousDescription || null)) {
            const descriptionBody: UpdateDescriptionRequest = {
              description: description.trim() === '' ? null : description,
              version: saved.version,
            }
            return api.patch<MenuItemDto>(`/menu/items/${itemId}/description`, descriptionBody)
          }
          return saved
        })
      }

      const body: CreateMenuItemRequest = {
        categoryId,
        name,
        sku: sku.trim() === '' ? null : sku.trim(),
        plu: plu.trim() === '' ? null : plu.trim(),
        description: description.trim() === '' ? null : description,
        price: parsedPrice,
        taxCode: taxCode.trim() === '' ? null : taxCode.trim(),
        stationId: stationId || null,
        vegetarian: foodType === 'VEG',
        foodType,
        directSale,
        halfPrice: parsedHalfPrice,
        barcode: barcode.trim() === '' ? null : barcode.trim(),
        prepTimeMinutes: parsedPrepTime,
        branchId: branchId || null,
      }
      return api.post<MenuItemDto>('/menu/items', body)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['menu', 'editor'] })
      resetAndClose()
    },
    onError: (err) => setError(describeError(err, isEdit ? 'Could not save this item' : 'Could not create this item')),
  })

  return (
    <Modal open={open} onClose={resetAndClose} title={item ? `Edit ${item.name}` : 'Add menu item'} widthClassName="max-w-lg">
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          save.mutate()
        }}
      >
        <div className="grid grid-cols-2 gap-3">
          <div className="col-span-2">
            <label className="mb-1.5 block text-xs font-semibold text-muted">Item name</label>
            <input
              required
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. Paneer Tikka"
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div className="col-span-2">
            <label className="mb-1.5 block text-xs font-semibold text-muted">Category</label>
            <select
              required
              disabled={isEdit}
              value={categoryId}
              onChange={(e) => setCategoryId(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500 disabled:opacity-60"
            >
              {availableCategories.length === 0 && <option value="">No categories yet</option>}
              {availableCategories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                  {!c.active && ' (inactive)'}
                </option>
              ))}
            </select>
            {isEdit && <p className="mt-1 text-[11px] text-muted">Moving an item between categories isn't supported here yet.</p>}
          </div>
          {showBranchPicker && (
            <div className="col-span-2">
              <label className="mb-1.5 block text-xs font-semibold text-muted">Branch</label>
              <select
                required
                value={branchId}
                onChange={(e) => setBranchId(e.target.value)}
                className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
              >
                <option value="">Select branch…</option>
                {branches.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </select>
            </div>
          )}
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Price</label>
            <input
              required
              type="number"
              min={0}
              step="0.01"
              value={price}
              onChange={(e) => setPrice(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-xs font-semibold text-muted">Half price (optional)</label>
            <input
              type="number"
              min={0}
              step="0.01"
              value={halfPrice}
              onChange={(e) => setHalfPrice(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div className="col-span-2">
            <label className="mb-1.5 block text-xs font-semibold text-muted">Description (optional)</label>
            <textarea
              rows={2}
              value={description ?? ''}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Shown to guests on QR/online menus, if enabled"
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
        </div>

        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Food type</label>
          <div className="grid grid-cols-3 gap-2">
            {(Object.keys(FOOD_TYPE_META) as FoodType[]).map((ft) => (
              <button
                key={ft}
                type="button"
                onClick={() => setFoodType(ft)}
                className={cn(
                  'flex items-center justify-center gap-1.5 rounded-lg border px-2 py-2 text-xs font-semibold transition-colors',
                  foodType === ft ? 'border-brand-500 bg-brand-50 text-brand-700 dark:bg-brand-900/40 dark:text-brand-200' : 'border-app text-app hover:bg-app',
                )}
              >
                <FoodTypeMark foodType={ft} />
                {FOOD_TYPE_META[ft].label}
              </button>
            ))}
          </div>
        </div>

        <div>
          <label className="mb-1.5 block text-xs font-semibold text-muted">Kitchen station (optional)</label>
          <select
            value={stationId}
            onChange={(e) => setStationId(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          >
            <option value="">No station (not routed to a kitchen screen)</option>
            {activeStations.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        </div>

        <label className="flex items-center gap-2 text-sm font-medium text-app">
          <input type="checkbox" checked={directSale} onChange={(e) => setDirectSale(e.target.checked)} className="h-4 w-4 rounded border-app" />
          Direct sale item (skips the kitchen entirely, e.g. bottled drinks)
        </label>

        <details className="rounded-lg border border-app px-3 py-2">
          <summary className="cursor-pointer text-xs font-bold uppercase tracking-wide text-muted">Advanced (SKU, tax, barcode…)</summary>
          <div className="mt-3 grid grid-cols-2 gap-3">
            <div>
              <label className="mb-1.5 block text-xs font-semibold text-muted">SKU</label>
              <input value={sku} onChange={(e) => setSku(e.target.value)} className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500" />
            </div>
            <div>
              <label className="mb-1.5 block text-xs font-semibold text-muted">PLU</label>
              <input value={plu} onChange={(e) => setPlu(e.target.value)} className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500" />
            </div>
            <div>
              <label className="mb-1.5 block text-xs font-semibold text-muted">Tax code</label>
              <input value={taxCode} onChange={(e) => setTaxCode(e.target.value)} className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500" />
            </div>
            <div>
              <label className="mb-1.5 block text-xs font-semibold text-muted">Barcode</label>
              <input value={barcode} onChange={(e) => setBarcode(e.target.value)} className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500" />
            </div>
            <div>
              <label className="mb-1.5 block text-xs font-semibold text-muted">Prep time (minutes)</label>
              <input
                type="number"
                min={0}
                value={prepTimeMinutes}
                onChange={(e) => setPrepTimeMinutes(e.target.value)}
                className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
              />
            </div>
          </div>
        </details>

        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}

        <Button type="submit" className="w-full" disabled={save.isPending || (!isEdit && !categoryId)}>
          {save.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
          {isEdit ? (save.isPending ? 'Saving…' : 'Save item') : save.isPending ? 'Adding…' : 'Add item'}
        </Button>
      </form>
    </Modal>
  )
}

// -------------------------------------------------------------------------------------------
// AI Bulk Import (secondary modal - photo -> draft list -> apply)
// -------------------------------------------------------------------------------------------

type DraftRow = AiMenuImportDraftItem & { rowId: string; selected: boolean }

/** One entry per distinct (trimmed) `categoryName` among the currently-selected drafts - the unit
 * the category-mapping step lets a person decide on once, instead of per item. */
interface DraftCategoryGroup {
  key: string
  itemCount: number
  matchedCategoryId: string | null
  matchedCategoryName: string | null
}

interface CategoryChoice {
  mode: 'existing' | 'create'
  existingCategoryId: string
  /** The name to create if `mode` is 'create' - prefilled with the AI's original categoryName so a
   * person can fix a typo (e.g. "Beverage" -> "Beverages") without retyping the whole thing. */
  newName: string
}

function defaultChoiceForGroup(group: DraftCategoryGroup): CategoryChoice {
  return group.matchedCategoryId
    ? { mode: 'existing', existingCategoryId: group.matchedCategoryId, newName: group.key }
    : { mode: 'create', existingCategoryId: '', newName: group.key }
}

function AiImportModal({
  open,
  onClose,
  categories,
  branches,
}: {
  open: boolean
  onClose: () => void
  categories: MenuCategoryDto[]
  branches: AccessibleBranchDto[]
}) {
  const queryClient = useQueryClient()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [preview, setPreview] = useState<string | null>(null)
  const [pending, setPending] = useState<{ base64: string; mimeType: string } | null>(null)
  const [drafts, setDrafts] = useState<DraftRow[] | null>(null)
  // 'items' is the flat per-item review list (unchanged from before); 'categories' is the new
  // once-per-category mapping step inserted between it and the actual Apply call.
  const [stage, setStage] = useState<'items' | 'categories'>('items')
  const [categoryChoices, setCategoryChoices] = useState<Record<string, CategoryChoice>>({})
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<AiApplyResponse | null>(null)
  // Bistrodesk branch-isolation release (requirement #1): one import batch always lands on
  // exactly one branch - only worth asking when there's a real choice (multi-branch/unrestricted
  // caller), same convention as ItemFormModal's own branch picker.
  const [branchId, setBranchId] = useState('')
  const showBranchPicker = branches.length > 1

  const reset = () => {
    setPreview(null)
    setPending(null)
    setDrafts(null)
    setStage('items')
    setCategoryChoices({})
    setError(null)
    setResult(null)
    setBranchId('')
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  const analyze = useMutation({
    mutationFn: (payload: { base64: string; mimeType: string }) =>
      api.post<AiMenuImportAnalyzeResponse>('/ai/menu-import/analyze', {
        imageBase64: payload.base64,
        mimeType: payload.mimeType,
      } satisfies AiAnalyzeRequest),
    onSuccess: (res) => {
      setError(null)
      setStage('items')
      setCategoryChoices({})
      setDrafts(
        res.items.map((it, i) => ({ ...it, rowId: `draft-${i}-${it.name}`, selected: true })),
      )
      if (res.items.length === 0) {
        setError('The AI could not confidently read any items from this photo - try a clearer or better-lit shot.')
      }
    },
    onError: (err) =>
      setError(describeError(err, 'AI menu import is not available right now (it may not be configured for this restaurant).')),
  })

  const apply = useMutation({
    mutationFn: (items: AiApplyItem[]) =>
      api.post<AiApplyResponse>('/ai/menu-import/apply', { items, branchId: branchId || null } satisfies AiApplyRequest),
    onSuccess: (res) => {
      setResult(res)
      setDrafts(null)
      queryClient.invalidateQueries({ queryKey: ['menu', 'editor'] })
    },
    onError: (err) => setError(describeError(err, 'Could not import these items')),
  })

  const handleFile = async (file: File) => {
    setError(null)
    setResult(null)
    setDrafts(null)
    setPreview(URL.createObjectURL(file))
    try {
      const { base64, mimeType } = await fileToBase64(file)
      setPending({ base64, mimeType })
    } catch {
      setError('Could not read that image file - try a different one.')
    }
  }

  const updateDraft = (rowId: string, patch: Partial<DraftRow>) => {
    setDrafts((rows) => rows?.map((r) => (r.rowId === rowId ? { ...r, ...patch } : r)) ?? rows)
  }

  const selectedDrafts = (drafts ?? []).filter((r) => r.selected)

  // Only match/offer active categories - dropping a fresh import into a category that's currently
  // hidden from the POS would be confusing, and the create-new fallback is right there anyway.
  const activeCategories = useMemo(() => categories.filter((c) => c.active), [categories])

  const categoryGroups = useMemo<DraftCategoryGroup[]>(() => {
    const byKey = new Map<string, DraftCategoryGroup>()
    for (const row of selectedDrafts) {
      const key = row.categoryName.trim()
      const existing = byKey.get(key)
      if (existing) {
        existing.itemCount += 1
        continue
      }
      const match = findBestCategoryMatch(key, activeCategories)
      byKey.set(key, { key, itemCount: 1, matchedCategoryId: match?.id ?? null, matchedCategoryName: match?.name ?? null })
    }
    return [...byKey.values()]
    // eslint-disable-next-line react-hooks/exhaustive-deps -- selectedDrafts is derived fresh from `drafts` every render, not a stable dep
  }, [drafts, activeCategories])

  const choiceForGroup = (group: DraftCategoryGroup): CategoryChoice => categoryChoices[group.key] ?? defaultChoiceForGroup(group)

  const setChoice = (key: string, choice: CategoryChoice) => setCategoryChoices((prev) => ({ ...prev, [key]: choice }))

  const canProceedToApply = categoryGroups.every((g) => {
    const choice = choiceForGroup(g)
    return choice.mode === 'existing' ? !!choice.existingCategoryId : choice.newName.trim() !== ''
  })

  const buildApplyItems = (): AiApplyItem[] => {
    const groupByKey = new Map(categoryGroups.map((g) => [g.key, g] as const))
    return selectedDrafts.map((r) => {
      const group = groupByKey.get(r.categoryName.trim())
      const choice = group ? choiceForGroup(group) : { mode: 'create' as const, existingCategoryId: '', newName: r.categoryName }
      return {
        name: r.name,
        categoryName: choice.mode === 'create' ? choice.newName.trim() : r.categoryName,
        price: r.price,
        foodType: r.foodType,
        description: r.description,
        categoryId: choice.mode === 'existing' ? choice.existingCategoryId : null,
      }
    })
  }

  return (
    <Modal
      open={open}
      onClose={() => {
        reset()
        onClose()
      }}
      title="AI Bulk Import from Photo"
      widthClassName="max-w-2xl"
    >
      <div className="space-y-4">
        <p className="text-sm text-muted">
          Upload a photo of a printed or handwritten menu. The AI drafts a list of items for you to review, edit, and
          deselect - nothing is saved until you press Import below.
        </p>

        {!drafts && !result && (
          <div className="space-y-3">
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              className="flex w-full flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed border-app py-8 text-muted hover:bg-app"
            >
              {preview ? (
                <img src={preview} alt="Menu preview" className="max-h-40 rounded-lg object-contain" />
              ) : (
                <>
                  <ImagePlus className="h-8 w-8" />
                  <span className="text-sm font-medium">Click to choose a menu photo</span>
                </>
              )}
            </button>
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*"
              className="hidden"
              onChange={(e) => {
                const file = e.target.files?.[0]
                if (file) handleFile(file)
              }}
            />
            {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
            <Button className="w-full" disabled={!pending || analyze.isPending} onClick={() => pending && analyze.mutate(pending)}>
              {analyze.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
              {analyze.isPending ? 'Reading menu…' : 'Analyze photo'}
            </Button>
          </div>
        )}

        {drafts && stage === 'items' && (
          <div className="space-y-3">
            <div className="max-h-80 space-y-2 overflow-y-auto pr-1">
              {drafts.map((row) => (
                <div key={row.rowId} className="flex items-start gap-2 rounded-lg border border-app p-2.5">
                  <input
                    type="checkbox"
                    checked={row.selected}
                    onChange={(e) => updateDraft(row.rowId, { selected: e.target.checked })}
                    className="mt-2 h-4 w-4 rounded border-app"
                  />
                  <div className="grid flex-1 grid-cols-2 gap-2">
                    <input
                      value={row.name}
                      onChange={(e) => updateDraft(row.rowId, { name: e.target.value })}
                      placeholder="Item name"
                      className="col-span-2 rounded-lg border border-app bg-app px-2 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
                    />
                    <input
                      value={row.categoryName}
                      onChange={(e) => updateDraft(row.rowId, { categoryName: e.target.value })}
                      placeholder="Category"
                      className="rounded-lg border border-app bg-app px-2 py-1.5 text-xs text-app outline-none focus:ring-2 focus:ring-brand-500"
                    />
                    <input
                      type="number"
                      min={0}
                      step="0.01"
                      value={row.price}
                      onChange={(e) => updateDraft(row.rowId, { price: Number(e.target.value) })}
                      placeholder="Price"
                      className="rounded-lg border border-app bg-app px-2 py-1.5 text-xs text-app outline-none focus:ring-2 focus:ring-brand-500"
                    />
                    <select
                      value={normalizeFoodType(row.foodType)}
                      onChange={(e) => updateDraft(row.rowId, { foodType: e.target.value })}
                      className="col-span-2 rounded-lg border border-app bg-app px-2 py-1.5 text-xs text-app outline-none focus:ring-2 focus:ring-brand-500"
                    >
                      {(Object.keys(FOOD_TYPE_META) as FoodType[]).map((ft) => (
                        <option key={ft} value={ft}>
                          {FOOD_TYPE_META[ft].label}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>
              ))}
            </div>
            {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
            <div className="flex gap-2">
              <Button variant="secondary" className="flex-1" type="button" onClick={reset}>
                Start over
              </Button>
              <Button className="flex-1" type="button" disabled={selectedDrafts.length === 0} onClick={() => setStage('categories')}>
                <ArrowRight className="h-4 w-4" />
                {`Next: map categories (${selectedDrafts.length})`}
              </Button>
            </div>
          </div>
        )}

        {drafts && stage === 'categories' && (
          <div className="space-y-3">
            <p className="text-sm text-muted">
              Map each category name the AI read off the photo to an existing menu category, or create a new one -
              this is what stops the import from creating a duplicate like a second "Beverages".
            </p>
            {showBranchPicker && (
              <div>
                <label className="mb-1.5 block text-xs font-semibold text-muted">Branch</label>
                <select
                  required
                  value={branchId}
                  onChange={(e) => setBranchId(e.target.value)}
                  className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
                >
                  <option value="">Select branch…</option>
                  {branches.map((b) => (
                    <option key={b.id} value={b.id}>
                      {b.name}
                    </option>
                  ))}
                </select>
                <p className="mt-1 text-[11px] text-muted">Every item in this import will be created at this one branch.</p>
              </div>
            )}
            <div className="max-h-80 space-y-2 overflow-y-auto pr-1">
              {categoryGroups.map((group) => {
                const choice = choiceForGroup(group)
                const selectValue = choice.mode === 'existing' ? `existing:${choice.existingCategoryId}` : 'create'
                return (
                  <div key={group.key || '(blank)'} className="space-y-2 rounded-lg border border-app p-2.5">
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate text-sm font-bold text-app">{group.key || '(no category read)'}</span>
                      <Badge tone="neutral">
                        {group.itemCount} item{group.itemCount === 1 ? '' : 's'}
                      </Badge>
                    </div>
                    <select
                      value={selectValue}
                      onChange={(e) => {
                        const v = e.target.value
                        if (v === 'create') {
                          setChoice(group.key, { mode: 'create', existingCategoryId: '', newName: choice.mode === 'create' ? choice.newName : group.key })
                        } else {
                          setChoice(group.key, { mode: 'existing', existingCategoryId: v.slice('existing:'.length), newName: choice.newName })
                        }
                      }}
                      className="w-full rounded-lg border border-app bg-app px-2 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
                    >
                      {group.matchedCategoryId && (
                        <option value={`existing:${group.matchedCategoryId}`}>Use existing: {group.matchedCategoryName}</option>
                      )}
                      <option value="create">{group.matchedCategoryId ? 'Create a new category instead' : 'Create new category (no existing match found)'}</option>
                      {activeCategories
                        .filter((c) => c.id !== group.matchedCategoryId)
                        .map((c) => (
                          <option key={c.id} value={`existing:${c.id}`}>
                            Use existing: {c.name}
                          </option>
                        ))}
                    </select>
                    {choice.mode === 'create' && (
                      <input
                        value={choice.newName}
                        onChange={(e) => setChoice(group.key, { mode: 'create', existingCategoryId: '', newName: e.target.value })}
                        placeholder="New category name"
                        className="w-full rounded-lg border border-app bg-app px-2 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
                      />
                    )}
                  </div>
                )
              })}
              {categoryGroups.length === 0 && <p className="px-1 py-2 text-xs text-muted">No selected items to map.</p>}
            </div>
            {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
            <div className="flex gap-2">
              <Button variant="secondary" className="flex-1" type="button" onClick={() => setStage('items')}>
                <ArrowLeft className="h-4 w-4" /> Back
              </Button>
              <Button
                className="flex-1"
                disabled={selectedDrafts.length === 0 || !canProceedToApply || apply.isPending || (showBranchPicker && !branchId)}
                onClick={() => apply.mutate(buildApplyItems())}
              >
                {apply.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <UploadCloud className="h-4 w-4" />}
                {apply.isPending ? 'Importing…' : `Import ${selectedDrafts.length} item${selectedDrafts.length === 1 ? '' : 's'}`}
              </Button>
            </div>
          </div>
        )}

        {result && (
          <div className="space-y-3">
            <div className="rounded-xl bg-success-soft px-4 py-3 text-sm font-semibold text-success">
              Imported {result.itemsCreated} item{result.itemsCreated === 1 ? '' : 's'}
              {result.categoriesCreated > 0 && ` and created ${result.categoriesCreated} new categor${result.categoriesCreated === 1 ? 'y' : 'ies'}`}.
            </div>
            <Button
              className="w-full"
              onClick={() => {
                reset()
                onClose()
              }}
            >
              Done
            </Button>
          </div>
        )}
      </div>
    </Modal>
  )
}

// -------------------------------------------------------------------------------------------
// Page
// -------------------------------------------------------------------------------------------

export function MenuEditorPage() {
  const { hasPermission } = useAuthStore()
  const canManage = hasPermission('MENU_MANAGE')
  // Bistrodesk branch-isolation release (requirement #5): AI Bulk Import calls
  // AiMenuImportController, which the server already gates on AI_FEATURES - a single-control lock
  // (not a whole-screen one) since the rest of this screen (plain category/item CRUD) is never
  // AI-gated. See useEntitlements' own javadoc, no longer "not retrofit-gated" as of this release.
  const { isEnabled: isFeatureEnabled } = useEntitlements()
  const aiFeatureEnabled = isFeatureEnabled('AI_FEATURES')
  const queryClient = useQueryClient()

  const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [showInactive, setShowInactive] = useState(false)
  const [categoryModal, setCategoryModal] = useState<{ open: boolean; category: MenuCategoryDto | null }>({ open: false, category: null })
  const [itemModal, setItemModal] = useState<{ open: boolean; item: MenuItemDto | null }>({ open: false, item: null })
  const [aiImportOpen, setAiImportOpen] = useState(false)
  const [rowBusy, setRowBusy] = useState<{ itemId: string; action: 'available' | 'active' } | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<MenuCategoryDto | null>(null)
  const [mergeTarget, setMergeTarget] = useState<MenuCategoryDto | null>(null)
  const [parentTarget, setParentTarget] = useState<MenuCategoryDto | null>(null)

  const menuQuery = useQuery({
    queryKey: ['menu', 'editor'],
    // includeInactive is always true here - the Menu Editor's whole point is letting staff find and
    // reactivate soft-deleted categories/items, unlike the POS-facing menu views elsewhere.
    queryFn: () => api.get<MenuCategoryDto[]>('/menu', { includeInactive: true }),
  })

  const stationsQuery = useQuery({
    queryKey: ['kitchen', 'stations'],
    queryFn: () => api.get<StationDto[]>('/kitchen/stations'),
    enabled: canManage,
  })

  // Bistrodesk branch-isolation release (requirement #1): only fetched to decide whether the Add
  // Item form's branch picker should render, and whether the item cards' branch badge is
  // meaningful - a single-branch/already-scoped caller never sees more than one entry here, so
  // both stay hidden and the server-side default resolution (this caller's own branch) is all
  // that's ever needed.
  const branchesQuery = useQuery({
    queryKey: ['branches', 'accessible'],
    queryFn: () => api.get<AccessibleBranchDto[]>('/branches/accessible'),
  })
  const branches = branchesQuery.data ?? []
  const showBranchBadge = branches.length > 1

  const invalidateMenu = () => queryClient.invalidateQueries({ queryKey: ['menu', 'editor'] })

  const toggleAvailable = useMutation({
    mutationFn: (item: MenuItemDto) => {
      const body: UpdateMenuItemRequest = { available: !item.available, version: item.version }
      return api.patch<MenuItemDto>(`/menu/items/${item.id}`, body)
    },
    onMutate: (item) => {
      setActionError(null)
      setRowBusy({ itemId: item.id, action: 'available' })
    },
    onSuccess: invalidateMenu,
    onError: (err) => setActionError(describeError(err, 'Could not update availability')),
    onSettled: () => setRowBusy(null),
  })

  const toggleItemActive = useMutation({
    mutationFn: (item: MenuItemDto) => {
      const body: UpdateMenuItemRequest = { active: !item.active, version: item.version }
      return api.patch<MenuItemDto>(`/menu/items/${item.id}`, body)
    },
    onMutate: (item) => {
      setActionError(null)
      setRowBusy({ itemId: item.id, action: 'active' })
    },
    onSuccess: invalidateMenu,
    onError: (err) => setActionError(describeError(err, 'Could not update this item')),
    onSettled: () => setRowBusy(null),
  })

  const categories = useMemo(() => {
    const list = menuQuery.data ?? []
    return [...list].sort((a, b) => a.displayOrder - b.displayOrder)
  }, [menuQuery.data])

  const visibleCategories = useMemo(() => (showInactive ? categories : categories.filter((c) => c.active)), [categories, showInactive])

  // Groups visibleCategories into a two-level tree for the sidebar: top-level categories in order,
  // each followed by its own subcategories (also in order). A subcategory whose parent got filtered
  // out of visibleCategories (e.g. an active subcategory under an inactive parent, with "Show
  // inactive" off) falls back to rendering flat rather than silently disappearing.
  const { topLevelCategories, subcategoriesByParent, orphanSubcategories } = useMemo(() => {
    const topLevel: MenuCategoryDto[] = []
    const byParent = new Map<string, MenuCategoryDto[]>()
    for (const c of visibleCategories) {
      if (c.parentCategoryId) {
        const arr = byParent.get(c.parentCategoryId) ?? []
        arr.push(c)
        byParent.set(c.parentCategoryId, arr)
      } else {
        topLevel.push(c)
      }
    }
    const topLevelIds = new Set(topLevel.map((c) => c.id))
    const orphans = visibleCategories.filter((c) => c.parentCategoryId && !topLevelIds.has(c.parentCategoryId))
    return { topLevelCategories: topLevel, subcategoriesByParent: byParent, orphanSubcategories: orphans }
  }, [visibleCategories])

  const categoryNameById = useMemo(() => {
    const map = new Map<string, string>()
    for (const c of categories) map.set(c.id, c.name)
    return map
  }, [categories])

  const allItems = useMemo(() => categories.flatMap((c) => c.items), [categories])

  const visibleItems = useMemo(() => {
    let items = selectedCategoryId ? (categories.find((c) => c.id === selectedCategoryId)?.items ?? []) : allItems
    if (!showInactive) items = items.filter((i) => i.active)
    const q = search.trim().toLowerCase()
    if (q) {
      items = items.filter(
        (i) =>
          i.name.toLowerCase().includes(q) ||
          (i.sku ?? '').toLowerCase().includes(q) ||
          (i.plu ?? '').toLowerCase().includes(q) ||
          (i.barcode ?? '').toLowerCase().includes(q),
      )
    }
    return [...items].sort((a, b) => a.name.localeCompare(b.name))
  }, [categories, allItems, selectedCategoryId, showInactive, search])

  if (menuQuery.isLoading) return <FullPageSpinner label="Loading menu…" />

  if (menuQuery.isError) {
    const is403 = menuQuery.error instanceof ApiError && menuQuery.error.status === 403
    return (
      <div className="flex h-64 flex-col items-center justify-center gap-3 text-center text-muted">
        <Lock className="h-8 w-8" />
        <p className="max-w-sm text-sm font-medium">
          {is403 ? "You don't have permission to manage the menu. Ask an admin to grant you the MENU_MANAGE permission." : (menuQuery.error as Error).message}
        </p>
        <Button variant="secondary" size="sm" onClick={() => menuQuery.refetch()}>
          Try again
        </Button>
      </div>
    )
  }

  const stations = stationsQuery.data ?? []
  const nextDisplayOrder = categories.length === 0 ? 0 : Math.max(...categories.map((c) => c.displayOrder)) + 10

  const renderCategoryRow = (category: MenuCategoryDto, depth: number) => (
    <CategoryRow
      key={category.id}
      depth={depth}
      isSubcategory={!!category.parentCategoryId}
      label={category.name}
      count={showInactive ? category.items.length : category.items.filter((i) => i.active).length}
      selected={selectedCategoryId === category.id}
      inactive={!category.active}
      canManage={canManage}
      onClick={() => setSelectedCategoryId(category.id)}
      onEdit={() => setCategoryModal({ open: true, category })}
      onDelete={() => setDeleteTarget(category)}
      onMerge={() => setMergeTarget(category)}
      onSetParent={() => setParentTarget(category)}
    />
  )

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-bold text-app">
            <ChefHat className="h-5 w-5 text-brand-600" /> Menu Editor
          </h2>
          <p className="mt-0.5 text-sm text-muted">Manage categories, dishes, pricing and kitchen routing.</p>
        </div>
        {canManage && (
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="secondary"
              size="sm"
              disabled={!aiFeatureEnabled}
              title={aiFeatureEnabled ? undefined : 'This feature is not included in your subscription'}
              onClick={() => aiFeatureEnabled && setAiImportOpen(true)}
            >
              <Sparkles className="h-3.5 w-3.5" /> AI Bulk Import
            </Button>
            <Button size="sm" disabled={categories.filter((c) => c.active).length === 0} onClick={() => setItemModal({ open: true, item: null })}>
              <Plus className="h-3.5 w-3.5" /> Add Menu Item
            </Button>
          </div>
        )}
      </div>

      {actionError && (
        <div className="flex items-center justify-between gap-2 rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
          {actionError}
          <button type="button" onClick={() => setActionError(null)} className="rounded p-0.5 hover:bg-black/5">
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      <div className="flex flex-col gap-4 lg:flex-row">
        <aside className="w-full shrink-0 lg:w-64">
          <Card className="p-3">
            <div className="mb-2 flex items-center justify-between px-1">
              <span className="text-xs font-bold uppercase tracking-wide text-muted">Categories</span>
              {canManage && (
                <button
                  type="button"
                  title="Add category"
                  onClick={() => setCategoryModal({ open: true, category: null })}
                  className="rounded-lg p-1 text-muted hover:bg-app hover:text-brand-600"
                >
                  <Plus className="h-4 w-4" />
                </button>
              )}
            </div>
            <div className="space-y-1">
              <CategoryRow
                label="All Items"
                count={showInactive ? allItems.length : allItems.filter((i) => i.active).length}
                selected={selectedCategoryId === null}
                canManage={false}
                onClick={() => setSelectedCategoryId(null)}
              />
              {topLevelCategories.map((category) => (
                <div key={category.id}>
                  {renderCategoryRow(category, 0)}
                  {(subcategoriesByParent.get(category.id) ?? []).map((sub) => renderCategoryRow(sub, 1))}
                </div>
              ))}
              {orphanSubcategories.map((sub) => renderCategoryRow(sub, 0))}
              {categories.length === 0 && <p className="px-1 py-2 text-xs text-muted">No categories yet - add one to get started.</p>}
            </div>
          </Card>
        </aside>

        <div className="min-w-0 flex-1 space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative max-w-xs flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search items, SKU, barcode…"
                className="w-full rounded-lg border border-app bg-surface py-2 pl-9 pr-3 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
              />
            </div>
            <label className="flex items-center gap-1.5 text-xs font-semibold text-muted">
              <input type="checkbox" checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} className="h-3.5 w-3.5 rounded border-app" />
              Show inactive
            </label>
          </div>

          {visibleItems.length === 0 ? (
            <div className="flex h-56 flex-col items-center justify-center gap-2 text-muted">
              <ChefHat className="h-8 w-8" />
              <span className="text-sm font-medium">No menu items match here yet</span>
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {visibleItems.map((item) => (
                <MenuItemCard
                  key={item.id}
                  item={item}
                  categoryName={categoryNameById.get(item.categoryId) ?? '—'}
                  canManage={canManage}
                  showBranchBadge={showBranchBadge}
                  busyAction={rowBusy && rowBusy.itemId === item.id ? rowBusy.action : null}
                  onEdit={() => setItemModal({ open: true, item })}
                  onToggleAvailable={() => toggleAvailable.mutate(item)}
                  onToggleActive={() => toggleItemActive.mutate(item)}
                />
              ))}
            </div>
          )}
        </div>
      </div>

      <CategoryFormModal
        open={categoryModal.open}
        onClose={() => setCategoryModal({ open: false, category: null })}
        category={categoryModal.category}
        categories={categories}
        nextDisplayOrder={nextDisplayOrder}
      />

      {/* Bug #14 fix: ItemFormModal is always mounted (Modal itself just returns null while closed -
          see Modal.tsx), so its internal useState(item?.xxx ?? ...) initializers only ever run once,
          on this component instance's first-ever mount. Without a key tied to which item is being
          edited, editing item A then closing and editing item B re-used A's already-initialized form
          state instead of resetting to B's - the confirmed "stale menu-item-edit state" bug. Keying
          on the item id (or a fixed 'new' key for Add) forces a fresh instance - and fresh
          useState initializers - every time a different item (or a brand new item) is opened. */}
      <ItemFormModal
        key={itemModal.item?.id ?? 'new'}
        open={itemModal.open}
        onClose={() => setItemModal({ open: false, item: null })}
        item={itemModal.item}
        categories={categories}
        stations={stations}
        defaultCategoryId={selectedCategoryId}
        branches={branches}
      />

      {canManage && <AiImportModal open={aiImportOpen} onClose={() => setAiImportOpen(false)} categories={categories} branches={branches} />}

      {deleteTarget && <DeleteCategoryModal category={deleteTarget} onClose={() => setDeleteTarget(null)} />}
      {mergeTarget && <MergeCategoryModal category={mergeTarget} categories={categories} onClose={() => setMergeTarget(null)} />}
      {parentTarget && <ParentCategoryModal category={parentTarget} categories={categories} onClose={() => setParentTarget(null)} />}
    </div>
  )
}
