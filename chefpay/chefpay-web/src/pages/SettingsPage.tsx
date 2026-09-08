import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ComponentType } from 'react'
import {
  Bot,
  Building2,
  Check,
  ChefHat,
  Cloud,
  CreditCard,
  Lock,
  Mail,
  MessageCircle,
  Pencil,
  Percent,
  Plus,
  Receipt,
  Save,
  Settings as SettingsIcon,
  Shield,
  ShieldAlert,
  Wallet,
} from 'lucide-react'

import { Button } from '@/components/ui/Button'
import { Badge } from '@/components/ui/Badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/Card'
import { DataSyncPanel } from '@/components/sync/DataSyncPanel'
import { useRestaurantConfig } from '@/hooks/useRestaurantConfig'
import { useAuthStore } from '@/store/auth'
import { api } from '@/lib/api'
import { cn } from '@/lib/utils'
import {
  ApiError,
  type BranchDto,
  type CreateDiscountRequest,
  type CreateTaxRequest,
  type DiscountDto,
  type MenuCategoryDto,
  type RestaurantDto,
  type TaxDto,
  type UpdateBranchRequest,
  type UpdateDiscountRequest,
  type UpdateRestaurantRequest,
  type UpdateTaxRequest,
} from '@/types/api'

type Tab =
  | 'profile'
  | 'operations'
  | 'payments'
  | 'email'
  | 'whatsapp'
  | 'ai'
  | 'security'
  | 'tax-discounts'
  | 'data-sync'

const PAYMENT_METHOD_OPTIONS: { value: string; label: string }[] = [
  { value: 'CASH', label: 'Cash' },
  { value: 'CARD', label: 'Card' },
  { value: 'UPI', label: 'UPI' },
  { value: 'WALLET', label: 'Wallet' },
]

const AI_PROVIDER_OPTIONS: { value: string; label: string }[] = [
  { value: 'OPENAI', label: 'OpenAI' },
  { value: 'ANTHROPIC', label: 'Anthropic (Claude)' },
  { value: 'GEMINI', label: 'Google Gemini' },
]

/** Local editable draft mirroring every field UpdateRestaurantRequest accepts (see types/api.ts's
 * Round 17 expansion) - kept as plain useState synced from the query on first load, rather than
 * pulled into Zustand: this form has no reason to be global/shared state the way AppearancePage's
 * live-preview-everywhere config does.
 *
 * `smtpPasswordNew`/`aiApiKeyNew` are NOT populated from the server (the DTO never returns the
 * actual secret, only `smtpPasswordConfigured`/`aiApiKeyConfigured` booleans) - they always start
 * blank and are only sent to the server (as `smtpPassword`/`aiApiKey`) when the manager actually
 * types a new value. Leaving them blank sends `null`, which the backend's "null/omitted = unchanged"
 * convention treats as "leave the existing secret alone" - this page has no way to inspect or clear
 * an already-configured secret, only replace it. */
interface Draft {
  name: string
  organizationId: string
  organizationName: string
  currencySymbol: string
  gstin: string
  supportPhone: string
  receiptFooterText: string
  serviceChargePercent: string
  requireKitchenSyncForServed: boolean
  kotOptionalEnabled: boolean
  itemLevelKitchenStatusEnabled: boolean
  autoPrintReceiptOnPayment: boolean
  kitchenServiceMode: 'DETAILED' | 'SIMPLE'
  paymentMethods: Set<string>

  upiVpaId: string
  upiPayeeName: string
  cardPaymentEnabled: boolean
  cardTerminalNote: string
  cashDrawerEnabled: boolean
  receiptPrinterName: string
  receiptPaperWidthChars: string
  autoPrintOnlineOrders: boolean
  onlineOrderZomatoEnabled: boolean
  onlineOrderSwiggyEnabled: boolean
  deliveryBoyFeatureEnabled: boolean

  smtpHost: string
  smtpPort: string
  smtpUsername: string
  smtpPasswordNew: string
  smtpFromAddress: string
  smtpUseTls: boolean
  eodZReportRecipientEmails: string
  criticalAlertRecipientEmails: string

  aiFeaturesEnabled: boolean
  aiProvider: string
  aiApiKeyNew: string
  aiModel: string
  aiMenuImportEnabled: boolean
  aiInsightsChatEnabled: boolean
  aiReorderDraftsEnabled: boolean
  aiAnomalyFlaggingEnabled: boolean
  aiMenuDescriptionsEnabled: boolean
  aiNightlySummaryEnabled: boolean
  aiReplenishmentNotesEnabled: boolean
  autoPoFromSuggestionsEnabled: boolean
  nlAssistantWriteCommandsEnabled: boolean

  biometricOverrideEnabled: boolean
  ocrUseAiVisionAssist: boolean
  poApprovalRequired: boolean
  showDiscountConfirmation: boolean
  dashboardViewMode: 'STANDARD' | 'GRAPHICAL' | 'BOTH'
  marginErosionThresholdPercent: string
  criticalAlertEscalationMinutes: string
  autoPurgeEnabled: boolean
  dataRetentionDays: string
  defaultOpeningFloat: string
  cashVarianceThreshold: string
}

function toDraft(dto: RestaurantDto): Draft {
  return {
    name: dto.name ?? '',
    organizationId: dto.organizationId ?? '',
    organizationName: dto.organizationName ?? '',
    currencySymbol: dto.currencySymbol ?? '',
    gstin: dto.gstin ?? '',
    supportPhone: dto.supportPhone ?? '',
    receiptFooterText: dto.receiptFooterText ?? '',
    serviceChargePercent: String(dto.serviceChargePercent ?? 0),
    requireKitchenSyncForServed: dto.requireKitchenSyncForServed,
    kotOptionalEnabled: dto.kotOptionalEnabled,
    itemLevelKitchenStatusEnabled: dto.itemLevelKitchenStatusEnabled,
    autoPrintReceiptOnPayment: dto.autoPrintReceiptOnPayment,
    kitchenServiceMode: dto.kitchenServiceMode,
    paymentMethods: new Set(
      (dto.enabledPaymentMethods || 'CASH,CARD,UPI')
        .split(',')
        .map((m) => m.trim())
        .filter(Boolean),
    ),

    upiVpaId: dto.upiVpaId ?? '',
    upiPayeeName: dto.upiPayeeName ?? '',
    cardPaymentEnabled: dto.cardPaymentEnabled,
    cardTerminalNote: dto.cardTerminalNote ?? '',
    cashDrawerEnabled: dto.cashDrawerEnabled,
    receiptPrinterName: dto.receiptPrinterName ?? '',
    receiptPaperWidthChars: String(dto.receiptPaperWidthChars ?? 0),
    autoPrintOnlineOrders: dto.autoPrintOnlineOrders,
    onlineOrderZomatoEnabled: dto.onlineOrderZomatoEnabled,
    onlineOrderSwiggyEnabled: dto.onlineOrderSwiggyEnabled,
    deliveryBoyFeatureEnabled: dto.deliveryBoyFeatureEnabled,

    smtpHost: dto.smtpHost ?? '',
    smtpPort: dto.smtpPort != null ? String(dto.smtpPort) : '',
    smtpUsername: dto.smtpUsername ?? '',
    smtpPasswordNew: '',
    smtpFromAddress: dto.smtpFromAddress ?? '',
    smtpUseTls: dto.smtpUseTls,
    eodZReportRecipientEmails: dto.eodZReportRecipientEmails ?? '',
    criticalAlertRecipientEmails: dto.criticalAlertRecipientEmails ?? '',

    aiFeaturesEnabled: dto.aiFeaturesEnabled,
    aiProvider: dto.aiProvider ?? 'OPENAI',
    aiApiKeyNew: '',
    aiModel: dto.aiModel ?? '',
    aiMenuImportEnabled: dto.aiMenuImportEnabled,
    aiInsightsChatEnabled: dto.aiInsightsChatEnabled,
    aiReorderDraftsEnabled: dto.aiReorderDraftsEnabled,
    aiAnomalyFlaggingEnabled: dto.aiAnomalyFlaggingEnabled,
    aiMenuDescriptionsEnabled: dto.aiMenuDescriptionsEnabled,
    aiNightlySummaryEnabled: dto.aiNightlySummaryEnabled,
    aiReplenishmentNotesEnabled: dto.aiReplenishmentNotesEnabled,
    autoPoFromSuggestionsEnabled: dto.autoPoFromSuggestionsEnabled,
    nlAssistantWriteCommandsEnabled: dto.nlAssistantWriteCommandsEnabled,

    biometricOverrideEnabled: dto.biometricOverrideEnabled,
    ocrUseAiVisionAssist: dto.ocrUseAiVisionAssist,
    poApprovalRequired: dto.poApprovalRequired,
    showDiscountConfirmation: dto.showDiscountConfirmation,
    dashboardViewMode: dto.dashboardViewMode,
    marginErosionThresholdPercent: String(dto.marginErosionThresholdPercent ?? 0),
    criticalAlertEscalationMinutes: String(dto.criticalAlertEscalationMinutes ?? 0),
    autoPurgeEnabled: dto.autoPurgeEnabled,
    dataRetentionDays: String(dto.dataRetentionDays ?? 0),
    defaultOpeningFloat: String(dto.defaultOpeningFloat ?? 0),
    cashVarianceThreshold: String(dto.cashVarianceThreshold ?? 0),
  }
}

function FieldLabel({ children, hint }: { children: string; hint?: string }) {
  return (
    <div className="mb-1.5">
      <label className="block text-xs font-semibold text-muted">{children}</label>
      {hint && <p className="mt-0.5 text-xs text-muted/80">{hint}</p>}
    </div>
  )
}

function inputClass(disabled: boolean) {
  return cn(
    'w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500',
    disabled && 'cursor-not-allowed opacity-60',
  )
}

/** Shared checkbox-row visual pattern used across every tab on this page. */
function ToggleRow({
  checked,
  disabled,
  onChange,
  label,
  hint,
}: {
  checked: boolean
  disabled: boolean
  onChange: (checked: boolean) => void
  label: string
  hint?: string
}) {
  return (
    <label className="flex items-start gap-3 rounded-lg border border-app bg-app px-3 py-2.5">
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
        className="mt-0.5 h-4 w-4 accent-brand-600"
      />
      <span>
        <span className="block text-sm font-semibold text-app">{label}</span>
        {hint && <span className="block text-xs text-muted">{hint}</span>}
      </span>
    </label>
  )
}

function describeSettingsError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 409) {
      return 'Someone else changed this while you were editing. Refresh the page and try again.'
    }
    return err.message || fallback
  }
  return fallback
}

const TAB_DEFS: { key: Tab; label: string; icon: ComponentType<{ className?: string }> }[] = [
  { key: 'profile', label: 'Restaurant Profile', icon: Building2 },
  { key: 'operations', label: 'Operations & Billing', icon: CreditCard },
  { key: 'payments', label: 'Payments & Ordering', icon: Wallet },
  { key: 'email', label: 'Email & Alerts', icon: Mail },
  { key: 'whatsapp', label: 'WhatsApp Integration', icon: MessageCircle },
  { key: 'ai', label: 'AI Features', icon: Bot },
  { key: 'security', label: 'Security & Retention', icon: Shield },
  { key: 'tax-discounts', label: 'Tax & Discounts', icon: Percent },
  { key: 'data-sync', label: 'Offline & Sync', icon: Cloud },
]

/**
 * Restaurant Settings - reads/writes the full backend RestaurantDto/UpdateRestaurantRequest (see
 * types/api.ts's Round 17 expansion), plus two standalone entities that live on their own REST
 * endpoints rather than as restaurant fields: Tax/GST rates and Discount presets
 * (GET/POST/PATCH /api/billing/taxes and /api/billing/discounts).
 *
 * Two toggles here (`kotOptionalEnabled`, `autoPrintReceiptOnPayment`) are already read and
 * enforced by PosTerminalPage/CheckoutModal via useRestaurantConfig - this page is just the first
 * place a manager can see and change them from the browser instead of asking someone to edit the
 * database directly.
 */
export function SettingsPage() {
  const { data: restaurant, isLoading, isError } = useRestaurantConfig()
  const queryClient = useQueryClient()
  const hasPermission = useAuthStore((s) => s.hasPermission)
  const canManage = hasPermission('RESTAURANT_MANAGE')
  const canViewTaxes = canManage || hasPermission('BILLING_MANAGE')
  const canViewDiscounts = canManage || hasPermission('BILLING_MANAGE') || hasPermission('DISCOUNT_APPROVE')
  // Bistrodesk follow-up requirement #2 ("WhatsApp integration ... configurable from Settings"):
  // this edits Branch.whatsapp* fields (PATCH /api/branches/{id}), which the server already gates
  // on BRANCH_MANAGE + a password-authenticated session - same gate BranchesTerminalsPage's own
  // branch edit form already uses, so this tab can never do something that screen couldn't.
  const canViewWhatsapp = hasPermission('BRANCH_MANAGE')

  const [tab, setTab] = useState<Tab>('profile')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [version, setVersion] = useState<number | null>(null)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Sync the local draft from the fetched config - only when the server's `version` moves past
  // what we last synced, so we don't clobber in-progress edits every time React Query silently
  // refetches this in the background.
  useEffect(() => {
    if (!restaurant) return
    if (version === null || restaurant.version !== version) {
      setDraft(toDraft(restaurant))
      setVersion(restaurant.version)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [restaurant])

  const save = useMutation({
    mutationFn: (body: UpdateRestaurantRequest) => api.put<RestaurantDto>('/restaurant', body),
    onSuccess: (updated) => {
      queryClient.setQueryData(['restaurant'], updated)
      queryClient.invalidateQueries({ queryKey: ['restaurant'] })
      setDraft(toDraft(updated))
      setVersion(updated.version)
      setError(null)
      setSaved(true)
      setTimeout(() => setSaved(false), 2500)
    },
    onError: (err) => {
      if (err instanceof ApiError && err.status === 409) {
        setError('Someone else changed these settings while you were editing. Refresh the page and try again.')
      } else {
        setError(err instanceof ApiError ? err.message : 'Could not save settings. Please try again.')
      }
    },
  })

  function togglePaymentMethod(method: string) {
    setDraft((d) => {
      if (!d) return d
      const next = new Set(d.paymentMethods)
      if (next.has(method)) next.delete(method)
      else next.add(method)
      return { ...d, paymentMethods: next }
    })
  }

  function handleSave() {
    if (!draft || version === null) return
    if (draft.paymentMethods.size === 0) {
      setError('Select at least one accepted payment method before saving.')
      return
    }
    setError(null)
    const body: UpdateRestaurantRequest = {
      name: draft.name.trim(),
      organizationId: draft.organizationId.trim() || null,
      organizationName: draft.organizationName.trim() || null,
      currencySymbol: draft.currencySymbol.trim(),
      gstin: draft.gstin.trim() || null,
      supportPhone: draft.supportPhone.trim() || null,
      receiptFooterText: draft.receiptFooterText.trim() || null,
      serviceChargePercent: Number(draft.serviceChargePercent) || 0,
      requireKitchenSyncForServed: draft.requireKitchenSyncForServed,
      kotOptionalEnabled: draft.kotOptionalEnabled,
      itemLevelKitchenStatusEnabled: draft.itemLevelKitchenStatusEnabled,
      autoPrintReceiptOnPayment: draft.autoPrintReceiptOnPayment,
      kitchenServiceMode: draft.kitchenServiceMode,
      enabledPaymentMethods: [...draft.paymentMethods].join(','),

      onlineOrderZomatoEnabled: draft.onlineOrderZomatoEnabled,
      onlineOrderSwiggyEnabled: draft.onlineOrderSwiggyEnabled,
      upiVpaId: draft.upiVpaId.trim() || null,
      upiPayeeName: draft.upiPayeeName.trim() || null,
      cardPaymentEnabled: draft.cardPaymentEnabled,
      cardTerminalNote: draft.cardTerminalNote.trim() || null,
      cashDrawerEnabled: draft.cashDrawerEnabled,
      receiptPrinterName: draft.receiptPrinterName.trim() || null,
      receiptPaperWidthChars: Number(draft.receiptPaperWidthChars) || 0,
      autoPrintOnlineOrders: draft.autoPrintOnlineOrders,
      deliveryBoyFeatureEnabled: draft.deliveryBoyFeatureEnabled,

      smtpHost: draft.smtpHost.trim() || null,
      smtpPort: draft.smtpPort.trim() ? Number(draft.smtpPort) : null,
      smtpUsername: draft.smtpUsername.trim() || null,
      smtpPassword: draft.smtpPasswordNew.trim() || null,
      smtpFromAddress: draft.smtpFromAddress.trim() || null,
      smtpUseTls: draft.smtpUseTls,
      eodZReportRecipientEmails: draft.eodZReportRecipientEmails.trim() || null,
      criticalAlertRecipientEmails: draft.criticalAlertRecipientEmails.trim() || null,

      aiFeaturesEnabled: draft.aiFeaturesEnabled,
      aiProvider: draft.aiProvider || null,
      aiApiKey: draft.aiApiKeyNew.trim() || null,
      aiModel: draft.aiModel.trim() || null,
      aiMenuImportEnabled: draft.aiMenuImportEnabled,
      aiInsightsChatEnabled: draft.aiInsightsChatEnabled,
      aiReorderDraftsEnabled: draft.aiReorderDraftsEnabled,
      aiAnomalyFlaggingEnabled: draft.aiAnomalyFlaggingEnabled,
      aiMenuDescriptionsEnabled: draft.aiMenuDescriptionsEnabled,
      aiNightlySummaryEnabled: draft.aiNightlySummaryEnabled,
      aiReplenishmentNotesEnabled: draft.aiReplenishmentNotesEnabled,
      autoPoFromSuggestionsEnabled: draft.autoPoFromSuggestionsEnabled,
      nlAssistantWriteCommandsEnabled: draft.nlAssistantWriteCommandsEnabled,

      biometricOverrideEnabled: draft.biometricOverrideEnabled,
      ocrUseAiVisionAssist: draft.ocrUseAiVisionAssist,
      poApprovalRequired: draft.poApprovalRequired,
      showDiscountConfirmation: draft.showDiscountConfirmation,
      dashboardViewMode: draft.dashboardViewMode,
      marginErosionThresholdPercent: Number(draft.marginErosionThresholdPercent) || 0,
      criticalAlertEscalationMinutes: Number(draft.criticalAlertEscalationMinutes) || 0,
      autoPurgeEnabled: draft.autoPurgeEnabled,
      dataRetentionDays: Number(draft.dataRetentionDays) || 0,
      defaultOpeningFloat: Number(draft.defaultOpeningFloat) || 0,
      cashVarianceThreshold: Number(draft.cashVarianceThreshold) || 0,

      version,
    }
    save.mutate(body)
  }

  const disabled = !canManage

  if (isLoading) {
    return <div className="p-6 text-sm text-muted">Loading settings…</div>
  }

  if (isError || !restaurant || !draft) {
    return (
      <div className="rounded-xl bg-danger-soft px-4 py-3 text-sm font-medium text-danger">
        Could not load restaurant settings. Try refreshing the page.
      </div>
    )
  }

  const visibleTabs = TAB_DEFS.filter((t) => t.key !== 'tax-discounts' || canViewTaxes || canViewDiscounts).filter(
    (t) => t.key !== 'whatsapp' || canViewWhatsapp,
  )

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-bold text-app">
            <SettingsIcon className="h-5 w-5 text-brand-600" /> Restaurant Settings
          </h2>
          <p className="mt-0.5 text-sm text-muted">
            Profile details, billing behavior, and kitchen workflow for {restaurant.name}.
          </p>
        </div>
        {canManage ? (
          <Button size="sm" onClick={handleSave} disabled={save.isPending} type="button">
            {saved ? <Check className="h-4 w-4" /> : <Save className="h-4 w-4" />}
            {save.isPending ? 'Saving…' : saved ? 'Saved' : 'Save changes'}
          </Button>
        ) : (
          <Badge tone="neutral" className="gap-1 normal-case">
            <Lock className="h-3 w-3" /> Read only
          </Badge>
        )}
      </div>

      {!canManage && (
        <div className="flex items-start gap-2 rounded-xl border border-warning/30 bg-warning-soft px-4 py-3 text-xs text-warning">
          <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            You can view the current settings, but you don&apos;t have permission to change them. Ask an admin to
            update these settings.
          </span>
        </div>
      )}

      {error && <div className="rounded-xl bg-danger-soft px-4 py-2.5 text-sm font-medium text-danger">{error}</div>}

      <div className="flex flex-wrap gap-2 border-b border-app">
        {visibleTabs.map(({ key, label, icon: Icon }) => (
          <button
            key={key}
            type="button"
            onClick={() => setTab(key)}
            className={cn(
              '-mb-px flex items-center gap-1.5 border-b-2 px-3 py-2 text-sm font-semibold transition-colors',
              tab === key
                ? 'border-brand-600 text-brand-700 dark:text-brand-300'
                : 'border-transparent text-muted hover:text-app',
            )}
          >
            <Icon className="h-4 w-4" /> {label}
          </button>
        ))}
      </div>

      {tab === 'profile' && (
        <>
          <Card>
            <CardHeader>
              <CardTitle>Basics</CardTitle>
            </CardHeader>
            <CardContent className="grid gap-4 pt-3 sm:grid-cols-2">
              <div>
                <FieldLabel>Restaurant name</FieldLabel>
                <input
                  value={draft.name}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                  className={inputClass(disabled)}
                />
              </div>
              <div>
                <FieldLabel>Currency symbol</FieldLabel>
                <input
                  value={draft.currencySymbol}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, currencySymbol: e.target.value })}
                  placeholder="₹"
                  className={inputClass(disabled)}
                />
              </div>
              <div>
                <FieldLabel>GSTIN</FieldLabel>
                <input
                  value={draft.gstin}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, gstin: e.target.value })}
                  placeholder="e.g. 22AAAAA0000A1Z5"
                  className={inputClass(disabled)}
                />
              </div>
              <div>
                <FieldLabel>Support phone</FieldLabel>
                <input
                  value={draft.supportPhone}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, supportPhone: e.target.value })}
                  placeholder="e.g. +91 90000 00000"
                  className={inputClass(disabled)}
                />
              </div>
              <div>
                <FieldLabel hint="Only meaningful for multi-restaurant organizations sharing one back office.">
                  Organization ID
                </FieldLabel>
                <input
                  value={draft.organizationId}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, organizationId: e.target.value })}
                  className={inputClass(disabled)}
                />
              </div>
              <div>
                <FieldLabel>Organization name</FieldLabel>
                <input
                  value={draft.organizationName}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, organizationName: e.target.value })}
                  className={inputClass(disabled)}
                />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-1.5">
                <Receipt className="h-4 w-4 text-brand-600" /> Receipt footer
              </CardTitle>
            </CardHeader>
            <CardContent className="pt-3">
              <FieldLabel hint="Printed at the bottom of every receipt - a thank-you note, return policy, or promo line.">
                Footer text
              </FieldLabel>
              <textarea
                value={draft.receiptFooterText}
                disabled={disabled}
                onChange={(e) => setDraft({ ...draft, receiptFooterText: e.target.value })}
                rows={3}
                placeholder="e.g. Thank you for dining with us! Visit again."
                className={inputClass(disabled)}
              />
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Branches</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2 pt-3">
              {restaurant.branches.length === 0 && <p className="text-sm text-muted">No branches configured.</p>}
              {restaurant.branches.map((branch) => (
                <div
                  key={branch.id}
                  className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-app bg-app px-3 py-2"
                >
                  <div>
                    <p className="text-sm font-semibold text-app">{branch.name}</p>
                    {branch.address && <p className="text-xs text-muted">{branch.address}</p>}
                  </div>
                  {branch.phone && <span className="text-xs text-muted">{branch.phone}</span>}
                </div>
              ))}
              <p className="pt-1 text-xs text-muted/80">
                Branches are read-only here - branch management isn&apos;t available on this page yet.
              </p>
            </CardContent>
          </Card>
        </>
      )}

      {tab === 'operations' && (
        <>
          <Card>
            <CardHeader>
              <CardTitle>Billing</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4 pt-3">
              <div className="max-w-xs">
                <FieldLabel>Service charge (%)</FieldLabel>
                <input
                  type="number"
                  min={0}
                  max={100}
                  step={0.5}
                  value={draft.serviceChargePercent}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, serviceChargePercent: e.target.value })}
                  className={inputClass(disabled)}
                />
              </div>

              <div>
                <FieldLabel>Accepted payment methods</FieldLabel>
                <div className="flex flex-wrap gap-2">
                  {PAYMENT_METHOD_OPTIONS.map((opt) => {
                    const checked = draft.paymentMethods.has(opt.value)
                    return (
                      <button
                        key={opt.value}
                        type="button"
                        disabled={disabled}
                        onClick={() => togglePaymentMethod(opt.value)}
                        className={cn(
                          'flex items-center gap-2 rounded-lg border px-3 py-2 text-sm font-semibold transition-colors',
                          checked
                            ? 'border-brand-500 bg-brand-50 text-brand-700 dark:bg-brand-900/40 dark:text-brand-200'
                            : 'border-app text-muted hover:bg-app',
                          disabled && 'cursor-not-allowed opacity-60',
                        )}
                      >
                        <span
                          className={cn(
                            'flex h-4 w-4 items-center justify-center rounded border',
                            checked ? 'border-brand-600 bg-brand-600 text-white' : 'border-app',
                          )}
                        >
                          {checked && <Check className="h-3 w-3" />}
                        </span>
                        {opt.label}
                      </button>
                    )
                  })}
                </div>
                <p className="mt-1.5 text-xs text-muted">
                  These are the methods offered at checkout on the POS Terminal.
                </p>
              </div>

              <ToggleRow
                checked={draft.autoPrintReceiptOnPayment}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, autoPrintReceiptOnPayment: v })}
                label="Automatically print receipt on payment"
                hint="When on, the thermal receipt prints automatically the moment a bill is fully paid. Either way, a receipt preview always appears on screen with Close/Print buttons."
              />
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-1.5">
                <ChefHat className="h-4 w-4 text-brand-600" /> Kitchen workflow
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4 pt-3">
              <div>
                <FieldLabel>Kitchen service mode</FieldLabel>
                <div className="flex gap-2">
                  {(['DETAILED', 'SIMPLE'] as const).map((mode) => (
                    <button
                      key={mode}
                      type="button"
                      disabled={disabled}
                      onClick={() => setDraft({ ...draft, kitchenServiceMode: mode })}
                      className={cn(
                        'rounded-lg border px-3 py-1.5 text-sm font-semibold transition-colors',
                        draft.kitchenServiceMode === mode
                          ? 'border-brand-500 bg-brand-50 text-brand-700 dark:bg-brand-900/40 dark:text-brand-200'
                          : 'border-app text-muted hover:bg-app',
                        disabled && 'cursor-not-allowed opacity-60',
                      )}
                    >
                      {mode === 'DETAILED' ? 'Detailed' : 'Simple'}
                    </button>
                  ))}
                </div>
                <p className="mt-1.5 text-xs text-muted">
                  Detailed uses the full per-item kitchen ticket flow; Simple is a streamlined kitchen view.
                </p>
              </div>

              <ToggleRow
                checked={draft.requireKitchenSyncForServed}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, requireKitchenSyncForServed: v })}
                label="Require kitchen sync before marking Served"
                hint="When on, an item can't be marked Served until it has been sent to and synced with the kitchen."
              />

              <ToggleRow
                checked={draft.kotOptionalEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, kotOptionalEnabled: v })}
                label="Allow billing without sending to kitchen"
                hint="When on, an order that was never sent to the kitchen can be billed and paid directly (e.g. counter/quick-service sales). An order that WAS sent to the kitchen always still requires the kitchen to mark it Served before it can be billed, regardless of this setting."
              />

              <ToggleRow
                checked={draft.itemLevelKitchenStatusEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, itemLevelKitchenStatusEnabled: v })}
                label="Allow kitchen staff to update individual item status"
                hint="When on, the Kitchen Display shows a status control on each item so it can be advanced on its own, in addition to the existing 'advance all' ticket action - synced live to POS/cart. When off, the kitchen display works exactly as it does today (whole-ticket advance only)."
              />
            </CardContent>
          </Card>
        </>
      )}

      {tab === 'payments' && (
        <>
          <Card>
            <CardHeader>
              <CardTitle>UPI / QR payments</CardTitle>
            </CardHeader>
            <CardContent className="grid gap-4 pt-3 sm:grid-cols-2">
              <div>
                <FieldLabel hint="Used to generate the QR code customers scan to pay.">UPI VPA ID</FieldLabel>
                <input
                  value={draft.upiVpaId}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, upiVpaId: e.target.value })}
                  placeholder="e.g. restaurant@okhdfcbank"
                  className={inputClass(disabled)}
                />
              </div>
              <div>
                <FieldLabel>Payee name</FieldLabel>
                <input
                  value={draft.upiPayeeName}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, upiPayeeName: e.target.value })}
                  className={inputClass(disabled)}
                />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Card payments</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4 pt-3">
              <ToggleRow
                checked={draft.cardPaymentEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, cardPaymentEnabled: v })}
                label="Accept card payments"
                hint="Informational only - recording a card payment just logs the amount against a standalone card terminal; there is no live card-network integration."
              />
              <div>
                <FieldLabel>Card terminal note</FieldLabel>
                <input
                  value={draft.cardTerminalNote}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, cardTerminalNote: e.target.value })}
                  placeholder="e.g. Use the counter PIN pad"
                  className={inputClass(disabled)}
                />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Cash drawer</CardTitle>
            </CardHeader>
            <CardContent className="pt-3">
              <ToggleRow
                checked={draft.cashDrawerEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, cashDrawerEnabled: v })}
                label="Enable cash drawer kick"
                hint="Pulses the cash drawer's open signal on a cash payment or No Sale, if one is wired to the receipt printer."
              />
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Receipt / KOT printer</CardTitle>
            </CardHeader>
            <CardContent className="grid gap-4 pt-3 sm:grid-cols-2">
              <div>
                <FieldLabel>Printer name</FieldLabel>
                <input
                  value={draft.receiptPrinterName}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, receiptPrinterName: e.target.value })}
                  placeholder="e.g. EPSON TM-T82"
                  className={inputClass(disabled)}
                />
              </div>
              <div>
                <FieldLabel>Paper width (characters)</FieldLabel>
                <input
                  type="number"
                  min={0}
                  value={draft.receiptPaperWidthChars}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, receiptPaperWidthChars: e.target.value })}
                  placeholder="e.g. 42"
                  className={inputClass(disabled)}
                />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Online ordering &amp; delivery</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4 pt-3">
              <ToggleRow
                checked={draft.onlineOrderZomatoEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, onlineOrderZomatoEnabled: v })}
                label="Zomato orders enabled"
              />
              <ToggleRow
                checked={draft.onlineOrderSwiggyEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, onlineOrderSwiggyEnabled: v })}
                label="Swiggy orders enabled"
              />
              <ToggleRow
                checked={draft.autoPrintOnlineOrders}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, autoPrintOnlineOrders: v })}
                label="Automatically print online orders"
                hint="Prints a KOT the moment an online order arrives, without waiting for staff to accept it on screen."
              />
              <ToggleRow
                checked={draft.deliveryBoyFeatureEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, deliveryBoyFeatureEnabled: v })}
                label="Delivery boy roster"
                hint="Turns on assigning a delivery rider to an order and tracking their handoff/return."
              />
            </CardContent>
          </Card>
        </>
      )}

      {tab === 'email' && (
        <>
          <Card>
            <CardHeader>
              <CardTitle>Receipt delivery - Email (SMTP)</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4 pt-3">
              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <FieldLabel>SMTP host</FieldLabel>
                  <input
                    value={draft.smtpHost}
                    disabled={disabled}
                    onChange={(e) => setDraft({ ...draft, smtpHost: e.target.value })}
                    placeholder="e.g. smtp.gmail.com"
                    className={inputClass(disabled)}
                  />
                </div>
                <div>
                  <FieldLabel>SMTP port</FieldLabel>
                  <input
                    type="number"
                    min={0}
                    value={draft.smtpPort}
                    disabled={disabled}
                    onChange={(e) => setDraft({ ...draft, smtpPort: e.target.value })}
                    placeholder="e.g. 587"
                    className={inputClass(disabled)}
                  />
                </div>
                <div>
                  <FieldLabel>SMTP username</FieldLabel>
                  <input
                    value={draft.smtpUsername}
                    disabled={disabled}
                    onChange={(e) => setDraft({ ...draft, smtpUsername: e.target.value })}
                    className={inputClass(disabled)}
                  />
                </div>
                <div>
                  <FieldLabel>From address</FieldLabel>
                  <input
                    value={draft.smtpFromAddress}
                    disabled={disabled}
                    onChange={(e) => setDraft({ ...draft, smtpFromAddress: e.target.value })}
                    placeholder="e.g. receipts@yourrestaurant.com"
                    className={inputClass(disabled)}
                  />
                </div>
              </div>

              <div>
                <div className="mb-1.5 flex items-center gap-2">
                  <label className="block text-xs font-semibold text-muted">SMTP password</label>
                  <Badge tone={restaurant.smtpPasswordConfigured ? 'success' : 'neutral'} className="normal-case">
                    {restaurant.smtpPasswordConfigured ? 'Configured' : 'Not configured'}
                  </Badge>
                </div>
                <input
                  type="password"
                  value={draft.smtpPasswordNew}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, smtpPasswordNew: e.target.value })}
                  placeholder={restaurant.smtpPasswordConfigured ? 'Leave blank to keep the current password' : 'Enter a password'}
                  className={inputClass(disabled)}
                  autoComplete="new-password"
                />
                <p className="mt-1.5 text-xs text-muted">
                  The current password is never shown here - only whether one is configured. Type a new one to
                  replace it, or leave this blank to keep it as-is.
                </p>
              </div>

              <ToggleRow
                checked={draft.smtpUseTls}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, smtpUseTls: v })}
                label="Use TLS"
              />
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Alert recipients</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4 pt-3">
              <div>
                <FieldLabel hint="Comma-separated email addresses.">EOD Z-report recipients</FieldLabel>
                <input
                  value={draft.eodZReportRecipientEmails}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, eodZReportRecipientEmails: e.target.value })}
                  placeholder="e.g. owner@restaurant.com, accounts@restaurant.com"
                  className={inputClass(disabled)}
                />
              </div>
              <div>
                <FieldLabel hint="Comma-separated email addresses. Notified when a critical alert (e.g. margin erosion) isn't acknowledged in time.">
                  Critical alert recipients
                </FieldLabel>
                <input
                  value={draft.criticalAlertRecipientEmails}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, criticalAlertRecipientEmails: e.target.value })}
                  placeholder="e.g. manager@restaurant.com"
                  className={inputClass(disabled)}
                />
              </div>
            </CardContent>
          </Card>
        </>
      )}

      {tab === 'whatsapp' && <WhatsAppIntegrationSection />}

      {tab === 'ai' && (
        <>
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-1.5">
                <Bot className="h-4 w-4 text-brand-600" /> AI features
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4 pt-3">
              <ToggleRow
                checked={draft.aiFeaturesEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, aiFeaturesEnabled: v })}
                label="Enable AI features"
                hint="Master switch for everything below. Needs a provider and API key saved too - each feature below also has its own switch, so only turn on what you'll actually use."
              />

              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <FieldLabel>Provider</FieldLabel>
                  <select
                    value={draft.aiProvider}
                    disabled={disabled}
                    onChange={(e) => setDraft({ ...draft, aiProvider: e.target.value })}
                    className={inputClass(disabled)}
                  >
                    {AI_PROVIDER_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <FieldLabel hint="Optional - leave blank to use the provider's default model.">Model override</FieldLabel>
                  <input
                    value={draft.aiModel}
                    disabled={disabled}
                    onChange={(e) => setDraft({ ...draft, aiModel: e.target.value })}
                    placeholder="e.g. gpt-4o-mini / claude-3-5-haiku / gemini-1.5-flash"
                    className={inputClass(disabled)}
                  />
                </div>
              </div>

              <div>
                <div className="mb-1.5 flex items-center gap-2">
                  <label className="block text-xs font-semibold text-muted">API key</label>
                  <Badge tone={restaurant.aiApiKeyConfigured ? 'success' : 'neutral'} className="normal-case">
                    {restaurant.aiApiKeyConfigured ? 'Configured' : 'Not configured'}
                  </Badge>
                </div>
                <input
                  type="password"
                  value={draft.aiApiKeyNew}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, aiApiKeyNew: e.target.value })}
                  placeholder={restaurant.aiApiKeyConfigured ? 'Leave blank to keep the current key' : 'Paste your API key here'}
                  className={inputClass(disabled)}
                  autoComplete="off"
                />
                <p className="mt-1.5 text-xs text-muted">
                  Your key is sent directly to the provider you choose and is never shown again after saving - only
                  whether a key is configured is displayed here. Nothing is sent anywhere unless AI Features is on
                  above and the specific feature below is too.
                </p>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Per-feature switches</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3 pt-3">
              <ToggleRow
                checked={draft.aiMenuImportEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, aiMenuImportEnabled: v })}
                label="AI menu import"
                hint="Analyze a photo or spreadsheet of a menu and draft categories/items for review."
              />
              <ToggleRow
                checked={draft.aiInsightsChatEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, aiInsightsChatEnabled: v })}
                label="Insights chat"
                hint="Ask questions about sales/inventory in plain language."
              />
              <ToggleRow
                checked={draft.aiReorderDraftsEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, aiReorderDraftsEnabled: v })}
                label="Reorder drafts"
                hint="Suggest draft purchase orders from low-stock inventory."
              />
              <ToggleRow
                checked={draft.aiAnomalyFlaggingEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, aiAnomalyFlaggingEnabled: v })}
                label="Anomaly flagging"
                hint="Flag unusual voids, discounts, or cash variances for review."
              />
              <ToggleRow
                checked={draft.aiMenuDescriptionsEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, aiMenuDescriptionsEnabled: v })}
                label="Menu descriptions"
                hint="Draft a menu item's guest-facing description for review."
              />
              <ToggleRow
                checked={draft.aiNightlySummaryEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, aiNightlySummaryEnabled: v })}
                label="Nightly summary"
                hint="Send a short AI-written recap of the day's business each night."
              />
              <ToggleRow
                checked={draft.aiReplenishmentNotesEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, aiReplenishmentNotesEnabled: v })}
                label="Replenishment notes"
                hint="Add a short AI note explaining why an item is suggested for reorder."
              />
              <ToggleRow
                checked={draft.autoPoFromSuggestionsEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, autoPoFromSuggestionsEnabled: v })}
                label="Auto-create POs from suggestions"
                hint="Turn an AI reorder suggestion directly into a draft purchase order awaiting approval, instead of only showing it as a suggestion."
              />
              <ToggleRow
                checked={draft.nlAssistantWriteCommandsEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, nlAssistantWriteCommandsEnabled: v })}
                label="Allow the assistant to make changes"
                hint="Lets the AI assistant chat take write actions (e.g. creating a PO) instead of only answering questions."
              />
            </CardContent>
          </Card>
        </>
      )}

      {tab === 'security' && (
        <>
          <Card>
            <CardHeader>
              <CardTitle>Security &amp; workflow</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4 pt-3">
              <ToggleRow
                checked={draft.biometricOverrideEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, biometricOverrideEnabled: v })}
                label="Allow biometric manager override"
                hint="Lets a manager approve a sensitive action (e.g. a void or discount) with a fingerprint/face scan instead of a PIN, on devices that support it."
              />
              <ToggleRow
                checked={draft.ocrUseAiVisionAssist}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, ocrUseAiVisionAssist: v })}
                label="Use AI vision to assist OCR"
                hint="Falls back to an AI vision model to read a scanned invoice/bill when plain OCR can't parse it confidently."
              />
              <ToggleRow
                checked={draft.poApprovalRequired}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, poApprovalRequired: v })}
                label="Require approval for purchase orders"
                hint="A new purchase order stays in Draft until a manager approves it, instead of being sendable to a supplier immediately."
              />
              <ToggleRow
                checked={draft.showDiscountConfirmation}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, showDiscountConfirmation: v })}
                label="Confirm before applying a discount"
                hint="Shows a confirmation prompt on the POS before a discount is applied to a bill."
              />

              <div className="max-w-xs">
                <FieldLabel>Dashboard view</FieldLabel>
                <select
                  value={draft.dashboardViewMode}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, dashboardViewMode: e.target.value as Draft['dashboardViewMode'] })}
                  className={inputClass(disabled)}
                >
                  <option value="STANDARD">Standard</option>
                  <option value="GRAPHICAL">Graphical</option>
                  <option value="BOTH">Both</option>
                </select>
              </div>

              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <FieldLabel hint="Alert when a category's margin drops below this percent.">
                    Margin erosion threshold (%)
                  </FieldLabel>
                  <input
                    type="number"
                    min={0}
                    max={100}
                    step={0.5}
                    value={draft.marginErosionThresholdPercent}
                    disabled={disabled}
                    onChange={(e) => setDraft({ ...draft, marginErosionThresholdPercent: e.target.value })}
                    className={inputClass(disabled)}
                  />
                </div>
                <div>
                  <FieldLabel hint="Escalate to the critical alert recipients if not acknowledged within this many minutes.">
                    Critical alert escalation (minutes)
                  </FieldLabel>
                  <input
                    type="number"
                    min={0}
                    value={draft.criticalAlertEscalationMinutes}
                    disabled={disabled}
                    onChange={(e) => setDraft({ ...draft, criticalAlertEscalationMinutes: e.target.value })}
                    className={inputClass(disabled)}
                  />
                </div>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>End of day</CardTitle>
            </CardHeader>
            <CardContent className="grid gap-4 pt-3 sm:grid-cols-2">
              <div>
                <FieldLabel hint="The expected cash amount a drawer opens with.">Default opening float</FieldLabel>
                <input
                  type="number"
                  min={0}
                  step={1}
                  value={draft.defaultOpeningFloat}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, defaultOpeningFloat: e.target.value })}
                  className={inputClass(disabled)}
                />
              </div>
              <div>
                <FieldLabel hint="Flag an EOD cash count as a variance once it differs from the expected amount by more than this.">
                  Cash variance threshold
                </FieldLabel>
                <input
                  type="number"
                  min={0}
                  step={1}
                  value={draft.cashVarianceThreshold}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, cashVarianceThreshold: e.target.value })}
                  className={inputClass(disabled)}
                />
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>Data retention</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4 pt-3">
              <div className="flex items-start gap-2 rounded-xl border border-warning/30 bg-warning-soft px-4 py-3 text-xs text-warning">
                <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
                <span>
                  Turning on auto-purge <strong>permanently deletes</strong> records older than the retention window
                  below. This cannot be undone - make sure any reporting or backups you need have already run before
                  enabling it.
                </span>
              </div>
              <ToggleRow
                checked={draft.autoPurgeEnabled}
                disabled={disabled}
                onChange={(v) => setDraft({ ...draft, autoPurgeEnabled: v })}
                label="Automatically purge old data"
              />
              <div className="max-w-xs">
                <FieldLabel hint="Records older than this are eligible for auto-purge, once enabled above.">
                  Keep records for at least (days)
                </FieldLabel>
                <input
                  type="number"
                  min={0}
                  value={draft.dataRetentionDays}
                  disabled={disabled}
                  onChange={(e) => setDraft({ ...draft, dataRetentionDays: e.target.value })}
                  className={inputClass(disabled)}
                />
              </div>
            </CardContent>
          </Card>
        </>
      )}

      {tab === 'tax-discounts' && (
        <>
          {canViewTaxes && <TaxesSection canManage={canManage} />}
          {canViewDiscounts && <DiscountsSection canManage={canManage} />}
        </>
      )}

      {tab === 'data-sync' && (
        <>
          <p className="text-sm text-muted">
            This terminal keeps working offline - changes queue locally and sync automatically once the connection
            to the server returns.
          </p>
          <DataSyncPanel />
        </>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------------------------
// Tax / GST rates - its own entity with its own REST endpoints (GET/POST/PATCH /api/billing/taxes),
// not a field on the restaurant draft above. Follows the same list + inline-edit-row pattern as
// BranchesTerminalsPage's terminal rows.
// ---------------------------------------------------------------------------------------------

function TaxRow({ tax, canManage }: { tax: TaxDto; canManage: boolean }) {
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(tax.name)
  const [ratePercent, setRatePercent] = useState(String(tax.ratePercent))
  const [active, setActive] = useState(tax.active)
  const [defaultRate, setDefaultRate] = useState(tax.defaultRate)
  const [error, setError] = useState<string | null>(null)

  const update = useMutation({
    mutationFn: () =>
      api.patch<TaxDto>(`/billing/taxes/${tax.id}`, {
        name: name.trim() || null,
        ratePercent: Number(ratePercent) || 0,
        active,
        defaultRate,
        version: tax.version,
      } satisfies UpdateTaxRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['billing', 'taxes'] })
      setEditing(false)
    },
    onError: (err) => setError(describeSettingsError(err, 'Could not update this tax rate.')),
  })

  if (editing) {
    return (
      <div className="space-y-2 rounded-lg border border-app bg-app/40 p-3">
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-4">
          <div>
            <label className="mb-1 block text-[11px] font-semibold text-muted">Name</label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div>
            <label className="mb-1 block text-[11px] font-semibold text-muted">Rate %</label>
            <input
              type="number"
              min={0}
              max={100}
              step={0.01}
              value={ratePercent}
              onChange={(e) => setRatePercent(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <label className="flex items-end gap-1.5 pb-2 text-xs font-semibold text-app">
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} className="h-4 w-4 accent-brand-600" />
            Active
          </label>
          <label className="flex items-end gap-1.5 pb-2 text-xs font-semibold text-app">
            <input
              type="checkbox"
              checked={defaultRate}
              onChange={(e) => setDefaultRate(e.target.checked)}
              className="h-4 w-4 accent-brand-600"
            />
            Default rate
          </label>
        </div>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-1.5 text-xs font-medium text-danger">{error}</div>}
        <div className="flex justify-end gap-2">
          <Button type="button" size="sm" variant="secondary" onClick={() => setEditing(false)} disabled={update.isPending}>
            Cancel
          </Button>
          <Button
            type="button"
            size="sm"
            onClick={() => {
              setError(null)
              update.mutate()
            }}
            disabled={update.isPending}
          >
            {update.isPending ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-app bg-app px-3 py-2.5">
      <div>
        <div className="flex flex-wrap items-center gap-1.5 text-sm font-semibold text-app">
          {tax.name}
          <span className="rounded bg-app px-1.5 py-0.5 font-mono text-[10px] font-bold text-muted">{tax.code}</span>
          {tax.defaultRate && <Badge tone="brand">Default</Badge>}
        </div>
        <div className="text-xs text-muted">{tax.ratePercent}%</div>
      </div>
      <div className="flex items-center gap-2">
        <Badge tone={tax.active ? 'success' : 'neutral'}>{tax.active ? 'Active' : 'Inactive'}</Badge>
        {canManage && (
          <Button type="button" size="sm" variant="secondary" onClick={() => setEditing(true)}>
            <Pencil className="h-3.5 w-3.5" /> Edit
          </Button>
        )}
      </div>
    </div>
  )
}

function TaxCreateForm({ onDone }: { onDone: () => void }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [ratePercent, setRatePercent] = useState('')
  const [defaultRate, setDefaultRate] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: () =>
      api.post<TaxDto>('/billing/taxes', {
        name: name.trim(),
        code: code.trim(),
        ratePercent: Number(ratePercent) || 0,
        defaultRate,
      } satisfies CreateTaxRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['billing', 'taxes'] })
      onDone()
    },
    onError: (err) => setError(describeSettingsError(err, 'Could not create this tax rate.')),
  })

  return (
    <form
      className="space-y-2 rounded-lg border border-dashed border-app bg-app/40 p-3"
      onSubmit={(e) => {
        e.preventDefault()
        if (!name.trim() || !code.trim()) {
          setError('Name and code are required.')
          return
        }
        setError(null)
        create.mutate()
      }}
    >
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-4">
        <div>
          <label className="mb-1 block text-[11px] font-semibold text-muted">Name</label>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. GST 5%"
            className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1 block text-[11px] font-semibold text-muted">Code</label>
          <input
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="e.g. GST5"
            className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1 block text-[11px] font-semibold text-muted">Rate %</label>
          <input
            type="number"
            min={0}
            max={100}
            step={0.01}
            value={ratePercent}
            onChange={(e) => setRatePercent(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <label className="flex items-end gap-1.5 pb-1.5 text-xs font-semibold text-app">
          <input type="checkbox" checked={defaultRate} onChange={(e) => setDefaultRate(e.target.checked)} className="h-4 w-4 accent-brand-600" />
          Default rate
        </label>
      </div>
      <p className="text-[11px] text-muted/80">
        If this is your first tax rate, mark it default so it auto-applies to items without their own tax code.
      </p>
      {error && <div className="rounded-lg bg-danger-soft px-3 py-1.5 text-xs font-medium text-danger">{error}</div>}
      <div className="flex justify-end gap-2">
        <Button type="button" size="sm" variant="secondary" onClick={onDone} disabled={create.isPending}>
          Cancel
        </Button>
        <Button type="submit" size="sm" disabled={create.isPending}>
          {create.isPending ? 'Creating…' : 'Add tax rate'}
        </Button>
      </div>
    </form>
  )
}

// ---------------------------------------------------------------------------------------------
// WhatsApp Business API integration - Bistrodesk follow-up requirement #2 ("configurable from
// the Settings section"). The underlying config (provider/sender number/API key/account id) is
// still stored per-branch (Branch.whatsapp*, PATCH /api/branches/{id}) exactly as before - this
// section is a convenience entry point onto THIS terminal's own effective branch, so a branch
// owner finds it where they now expect it. BranchesTerminalsPage's per-branch edit form keeps the
// identical fields too (deliberately not removed) - that's still the only place a multi-branch
// owner can configure a branch OTHER than the one they're currently signed into.
// ---------------------------------------------------------------------------------------------
function WhatsAppIntegrationSection() {
  const queryClient = useQueryClient()
  const { hasPermission, hasPasswordLogin, terminal, defaultBranchId } = useAuthStore()
  const canManage = hasPermission('BRANCH_MANAGE') && hasPasswordLogin()
  const effectiveBranchId = terminal?.branchId ?? defaultBranchId ?? undefined

  // GET /api/branches is already scoped to what this account can see (Bistrodesk follow-up
  // requirement #8's fix) - a branch-restricted owner/manager gets back exactly their own
  // branch(es), so resolving "the current branch" from that list (rather than a second endpoint)
  // reuses data the page needs anyway and can never leak another branch's config.
  const branchesQuery = useQuery({
    queryKey: ['branches'],
    queryFn: () => api.get<BranchDto[]>('/branches'),
    enabled: canManage,
  })

  if (!canManage) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-1.5">
            <MessageCircle className="h-4 w-4 text-brand-600" /> WhatsApp Integration
          </CardTitle>
        </CardHeader>
        <CardContent className="pt-3">
          <div className="flex items-start gap-2 rounded-xl border border-warning/30 bg-warning-soft px-4 py-3 text-xs text-warning">
            <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <span>You don&apos;t have permission to configure WhatsApp integration. Ask an owner or admin.</span>
          </div>
        </CardContent>
      </Card>
    )
  }

  if (branchesQuery.isLoading) {
    return (
      <Card>
        <CardContent className="pt-3 text-sm text-muted">Loading…</CardContent>
      </Card>
    )
  }

  if (branchesQuery.isError) {
    return (
      <Card>
        <CardContent className="pt-3 text-sm text-danger">
          {branchesQuery.error instanceof ApiError ? branchesQuery.error.message : 'Could not load branches'}
        </CardContent>
      </Card>
    )
  }

  const branches = branchesQuery.data ?? []
  const branch =
    branches.find((b) => b.id === effectiveBranchId) ?? (branches.length === 1 ? branches[0] : undefined)

  if (!branch) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-1.5">
            <MessageCircle className="h-4 w-4 text-brand-600" /> WhatsApp Integration
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 pt-3 text-sm text-muted">
          <p>
            This account can see more than one branch, and this terminal isn&apos;t bound to a specific one, so
            there&apos;s no single &quot;current branch&quot; to configure here.
          </p>
          <p>Open Branches &amp; Terminals and edit the branch you want to configure instead.</p>
        </CardContent>
      </Card>
    )
  }

  return <WhatsAppBranchForm branch={branch} onSaved={() => queryClient.invalidateQueries({ queryKey: ['branches'] })} />
}

function WhatsAppBranchForm({ branch, onSaved }: { branch: BranchDto; onSaved: () => void }) {
  const [whatsappProvider, setWhatsappProvider] = useState(branch.whatsappProvider ?? '')
  const [whatsappSenderNumber, setWhatsappSenderNumber] = useState(branch.whatsappSenderNumber ?? '')
  const [whatsappAccountId, setWhatsappAccountId] = useState(branch.whatsappAccountId ?? '')
  const [whatsappApiKeyNew, setWhatsappApiKeyNew] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const save = useMutation({
    mutationFn: () =>
      api.patch<BranchDto>(`/branches/${branch.id}`, {
        whatsappProvider: whatsappProvider || null,
        whatsappSenderNumber: whatsappSenderNumber.trim(),
        whatsappApiKey: whatsappApiKeyNew.trim() || null,
        whatsappAccountId: whatsappAccountId.trim(),
        version: branch.version,
      } satisfies UpdateBranchRequest),
    onSuccess: () => {
      setWhatsappApiKeyNew('')
      setError(null)
      setSaved(true)
      setTimeout(() => setSaved(false), 2500)
      onSaved()
    },
    onError: (err) =>
      setError(
        err instanceof ApiError
          ? err.status === 409
            ? 'Someone else changed this branch while you were editing. Refresh the page and try again.'
            : err.message
          : 'Could not save WhatsApp settings',
      ),
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-1.5">
          <MessageCircle className="h-4 w-4 text-brand-600" /> WhatsApp Integration — {branch.name}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4 pt-3">
        <p className="text-xs text-muted">
          Configure once you&apos;ve purchased a WhatsApp Business API plan from Meta, Twilio, or 360dialog, using
          this branch owner&apos;s number as the sender. Until then, sharing a purchase order via WhatsApp keeps
          opening the WhatsApp app instead of sending through the API.
        </p>
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault()
            setError(null)
            save.mutate()
          }}
        >
          <div>
            <FieldLabel>Provider</FieldLabel>
            <select
              value={whatsappProvider}
              onChange={(e) => setWhatsappProvider(e.target.value)}
              className={inputClass(false)}
            >
              <option value="">Not configured</option>
              <option value="META">Meta Cloud API</option>
              <option value="TWILIO">Twilio</option>
              <option value="DIALOG360">360dialog</option>
            </select>
          </div>
          <div>
            <FieldLabel>Sender number (branch owner&apos;s WhatsApp Business number)</FieldLabel>
            <input
              value={whatsappSenderNumber}
              onChange={(e) => setWhatsappSenderNumber(e.target.value)}
              placeholder="e.g. +919876543210"
              className={inputClass(false)}
            />
          </div>
          <div>
            <FieldLabel>Account ID (Meta Phone Number ID / Twilio Account SID — not needed for 360dialog)</FieldLabel>
            <input
              value={whatsappAccountId}
              onChange={(e) => setWhatsappAccountId(e.target.value)}
              className={inputClass(false)}
            />
          </div>
          <div>
            <div className="mb-1.5 flex items-center gap-2">
              <label className="text-xs font-semibold text-muted">API key / auth token</label>
              <Badge tone={branch.whatsappApiKeyConfigured ? 'success' : 'neutral'} className="normal-case">
                {branch.whatsappApiKeyConfigured ? 'Configured' : 'Not configured'}
              </Badge>
            </div>
            <input
              type="password"
              value={whatsappApiKeyNew}
              onChange={(e) => setWhatsappApiKeyNew(e.target.value)}
              placeholder={
                branch.whatsappApiKeyConfigured ? 'Leave blank to keep the current key' : 'Paste the API key / auth token here'
              }
              className={inputClass(false)}
              autoComplete="off"
            />
          </div>
          {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}
          <div className="flex justify-end">
            <Button type="submit" size="sm" disabled={save.isPending}>
              {saved ? <Check className="h-4 w-4" /> : <Save className="h-4 w-4" />}
              {save.isPending ? 'Saving…' : saved ? 'Saved' : 'Save changes'}
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  )
}

function TaxesSection({ canManage }: { canManage: boolean }) {
  const taxesQuery = useQuery({ queryKey: ['billing', 'taxes'], queryFn: () => api.get<TaxDto[]>('/billing/taxes') })
  const [showCreate, setShowCreate] = useState(false)

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-1.5">
          <Percent className="h-4 w-4 text-brand-600" /> Tax / GST rates
        </CardTitle>
        {canManage && !showCreate && (
          <Button type="button" size="sm" variant="secondary" onClick={() => setShowCreate(true)}>
            <Plus className="h-3.5 w-3.5" /> Add tax rate
          </Button>
        )}
      </CardHeader>
      <CardContent className="space-y-2 pt-3">
        <p className="text-xs text-muted">
          Tax rates applied to menu items at billing time. Mark one rate as default to auto-apply it to items that
          don&apos;t specify a tax code of their own.
        </p>
        {showCreate && <TaxCreateForm onDone={() => setShowCreate(false)} />}
        {taxesQuery.isLoading && <p className="text-sm text-muted">Loading tax rates…</p>}
        {taxesQuery.isError && <p className="text-sm text-danger">Could not load tax rates.</p>}
        {taxesQuery.data?.length === 0 && <p className="text-sm text-muted">No tax rates configured yet.</p>}
        {taxesQuery.data?.map((tax) => <TaxRow key={tax.id} tax={tax} canManage={canManage} />)}
      </CardContent>
    </Card>
  )
}

// ---------------------------------------------------------------------------------------------
// Discount presets - same shape of its-own-entity management as taxes above (GET/POST/PATCH
// /api/billing/discounts). `applicableCategoryId` is picked from the live menu category list.
// ---------------------------------------------------------------------------------------------

function DiscountRow({
  discount,
  categories,
  canManage,
}: {
  discount: DiscountDto
  categories: MenuCategoryDto[]
  canManage: boolean
}) {
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(discount.name)
  const [value, setValue] = useState(String(discount.value))
  const [maxDiscountAmount, setMaxDiscountAmount] = useState(
    discount.maxDiscountAmount != null ? String(discount.maxDiscountAmount) : '',
  )
  const [applicableCategoryId, setApplicableCategoryId] = useState(discount.applicableCategoryId ?? '')
  const [active, setActive] = useState(discount.active)
  const [error, setError] = useState<string | null>(null)

  const update = useMutation({
    mutationFn: () => {
      const body: UpdateDiscountRequest = {
        name: name.trim() || null,
        value: Number(value) || 0,
        maxDiscountAmount: maxDiscountAmount.trim() ? Number(maxDiscountAmount) : null,
        active,
        version: discount.version,
      }
      if (applicableCategoryId) {
        body.applicableCategoryId = applicableCategoryId
      } else {
        body.clearCategory = true
      }
      return api.patch<DiscountDto>(`/billing/discounts/${discount.id}`, body)
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['billing', 'discounts'] })
      setEditing(false)
    },
    onError: (err) => setError(describeSettingsError(err, 'Could not update this discount preset.')),
  })

  if (editing) {
    return (
      <div className="space-y-2 rounded-lg border border-app bg-app/40 p-3">
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-5">
          <div>
            <label className="mb-1 block text-[11px] font-semibold text-muted">Name</label>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div>
            <label className="mb-1 block text-[11px] font-semibold text-muted">Value ({discount.type === 'PERCENTAGE' ? '%' : 'amount'})</label>
            <input
              type="number"
              min={0}
              step={0.01}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div>
            <label className="mb-1 block text-[11px] font-semibold text-muted">Max cap (optional)</label>
            <input
              type="number"
              min={0}
              step={0.01}
              value={maxDiscountAmount}
              onChange={(e) => setMaxDiscountAmount(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div>
            <label className="mb-1 block text-[11px] font-semibold text-muted">Applies to</label>
            <select
              value={applicableCategoryId}
              onChange={(e) => setApplicableCategoryId(e.target.value)}
              className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            >
              <option value="">All categories</option>
              {categories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>
          <label className="flex items-end gap-1.5 pb-2 text-xs font-semibold text-app">
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} className="h-4 w-4 accent-brand-600" />
            Active
          </label>
        </div>
        {error && <div className="rounded-lg bg-danger-soft px-3 py-1.5 text-xs font-medium text-danger">{error}</div>}
        <div className="flex justify-end gap-2">
          <Button type="button" size="sm" variant="secondary" onClick={() => setEditing(false)} disabled={update.isPending}>
            Cancel
          </Button>
          <Button
            type="button"
            size="sm"
            onClick={() => {
              setError(null)
              update.mutate()
            }}
            disabled={update.isPending}
          >
            {update.isPending ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-app bg-app px-3 py-2.5">
      <div>
        <div className="text-sm font-semibold text-app">{discount.name}</div>
        <div className="text-xs text-muted">
          {discount.type === 'PERCENTAGE' ? `${discount.value}%` : discount.value}
          {discount.maxDiscountAmount != null && ` · capped at ${discount.maxDiscountAmount}`}
          {' · '}
          {discount.applicableCategoryName ?? 'All categories'}
        </div>
      </div>
      <div className="flex items-center gap-2">
        <Badge tone={discount.active ? 'success' : 'neutral'}>{discount.active ? 'Active' : 'Inactive'}</Badge>
        {canManage && (
          <Button type="button" size="sm" variant="secondary" onClick={() => setEditing(true)}>
            <Pencil className="h-3.5 w-3.5" /> Edit
          </Button>
        )}
      </div>
    </div>
  )
}

function DiscountCreateForm({ categories, onDone }: { categories: MenuCategoryDto[]; onDone: () => void }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [type, setType] = useState<'PERCENTAGE' | 'FIXED_AMOUNT'>('PERCENTAGE')
  const [value, setValue] = useState('')
  const [maxDiscountAmount, setMaxDiscountAmount] = useState('')
  const [applicableCategoryId, setApplicableCategoryId] = useState('')
  const [error, setError] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: () =>
      api.post<DiscountDto>('/billing/discounts', {
        name: name.trim(),
        type,
        value: Number(value) || 0,
        maxDiscountAmount: maxDiscountAmount.trim() ? Number(maxDiscountAmount) : null,
        applicableCategoryId: applicableCategoryId || null,
      } satisfies CreateDiscountRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['billing', 'discounts'] })
      onDone()
    },
    onError: (err) => setError(describeSettingsError(err, 'Could not create this discount preset.')),
  })

  return (
    <form
      className="space-y-2 rounded-lg border border-dashed border-app bg-app/40 p-3"
      onSubmit={(e) => {
        e.preventDefault()
        if (!name.trim()) {
          setError('Name is required.')
          return
        }
        setError(null)
        create.mutate()
      }}
    >
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-5">
        <div>
          <label className="mb-1 block text-[11px] font-semibold text-muted">Name</label>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. Staff Discount"
            className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1 block text-[11px] font-semibold text-muted">Type</label>
          <select
            value={type}
            onChange={(e) => setType(e.target.value as 'PERCENTAGE' | 'FIXED_AMOUNT')}
            className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          >
            <option value="PERCENTAGE">Percentage</option>
            <option value="FIXED_AMOUNT">Fixed amount</option>
          </select>
        </div>
        <div>
          <label className="mb-1 block text-[11px] font-semibold text-muted">Value</label>
          <input
            type="number"
            min={0}
            step={0.01}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1 block text-[11px] font-semibold text-muted">Max cap (optional)</label>
          <input
            type="number"
            min={0}
            step={0.01}
            value={maxDiscountAmount}
            onChange={(e) => setMaxDiscountAmount(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          />
        </div>
        <div>
          <label className="mb-1 block text-[11px] font-semibold text-muted">Applies to (optional)</label>
          <select
            value={applicableCategoryId}
            onChange={(e) => setApplicableCategoryId(e.target.value)}
            className="w-full rounded-lg border border-app bg-app px-3 py-1.5 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
          >
            <option value="">All categories</option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </div>
      </div>
      {error && <div className="rounded-lg bg-danger-soft px-3 py-1.5 text-xs font-medium text-danger">{error}</div>}
      <div className="flex justify-end gap-2">
        <Button type="button" size="sm" variant="secondary" onClick={onDone} disabled={create.isPending}>
          Cancel
        </Button>
        <Button type="submit" size="sm" disabled={create.isPending}>
          {create.isPending ? 'Creating…' : 'Add discount preset'}
        </Button>
      </div>
    </form>
  )
}

function DiscountsSection({ canManage }: { canManage: boolean }) {
  const discountsQuery = useQuery({
    queryKey: ['billing', 'discounts'],
    queryFn: () => api.get<DiscountDto[]>('/billing/discounts'),
  })
  const categoriesQuery = useQuery({
    queryKey: ['menu', 'categories-lite'],
    queryFn: () => api.get<MenuCategoryDto[]>('/menu'),
    enabled: canManage,
  })
  const categories = categoriesQuery.data ?? []
  const [showCreate, setShowCreate] = useState(false)

  return (
    <Card>
      <CardHeader>
        <CardTitle>Discount presets</CardTitle>
        {canManage && !showCreate && (
          <Button type="button" size="sm" variant="secondary" onClick={() => setShowCreate(true)}>
            <Plus className="h-3.5 w-3.5" /> Add discount
          </Button>
        )}
      </CardHeader>
      <CardContent className="space-y-2 pt-3">
        <p className="text-xs text-muted">
          Real, named discount presets the POS Terminal's Discount section offers - not hardcoded percentages.
        </p>
        {showCreate && <DiscountCreateForm categories={categories} onDone={() => setShowCreate(false)} />}
        {discountsQuery.isLoading && <p className="text-sm text-muted">Loading discount presets…</p>}
        {discountsQuery.isError && <p className="text-sm text-danger">Could not load discount presets.</p>}
        {discountsQuery.data?.length === 0 && <p className="text-sm text-muted">No discount presets configured yet.</p>}
        {discountsQuery.data?.map((discount) => (
          <DiscountRow key={discount.id} discount={discount} categories={categories} canManage={canManage} />
        ))}
      </CardContent>
    </Card>
  )
}
