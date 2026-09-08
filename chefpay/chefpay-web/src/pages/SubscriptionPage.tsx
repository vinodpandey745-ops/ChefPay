import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertTriangle,
  CalendarClock,
  CheckCircle2,
  CreditCard,
  History,
  Loader2,
  Phone,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
  XCircle,
} from 'lucide-react'
import { useRef, useState } from 'react'

import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/Card'
import { LockedFeatureChip } from '@/components/ui/LockedFeature'
import { Modal } from '@/components/ui/Modal'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { useEntitlements } from '@/hooks/useEntitlements'
import { api } from '@/lib/api'
import { cn, formatCurrency } from '@/lib/utils'
import { useAuthStore } from '@/store/auth'
import {
  ApiError,
  type CancelRenewalRequest,
  type InitiateRenewalRequest,
  type InitiateRenewalResponse,
  type PlanDto,
  type SubscriptionDto,
  type SubscriptionPaymentDto,
  type VerifyRenewalRequest,
} from '@/types/api'

/** Subscription Renewal and Plan Upgrade requirement: Razorpay Checkout.js is a real third-party
 * script the production app loads at runtime (never bundled/self-hosted - Razorpay's own terms
 * require loading it from their CDN so it can be updated server-side without a client redeploy).
 * No page in this app already lazy-loads an external script, so this is the simplest workable
 * pattern: inject the tag once, on first use, and resolve once `window.Razorpay` exists. */
const RAZORPAY_SCRIPT_SRC = 'https://checkout.razorpay.com/v1/checkout.js'
let razorpayScriptPromise: Promise<void> | null = null

function loadRazorpayScript(): Promise<void> {
  if (typeof window !== 'undefined' && window.Razorpay) {
    return Promise.resolve()
  }
  if (!razorpayScriptPromise) {
    razorpayScriptPromise = new Promise<void>((resolve, reject) => {
      const existing = document.querySelector<HTMLScriptElement>(`script[src="${RAZORPAY_SCRIPT_SRC}"]`)
      if (existing) {
        existing.addEventListener('load', () => resolve())
        existing.addEventListener('error', () => reject(new Error('Failed to load Razorpay Checkout')))
        return
      }
      const script = document.createElement('script')
      script.src = RAZORPAY_SCRIPT_SRC
      script.async = true
      script.onload = () => resolve()
      script.onerror = () => reject(new Error('Failed to load Razorpay Checkout'))
      document.body.appendChild(script)
    })
  }
  return razorpayScriptPromise
}

interface RazorpayCheckoutResponse {
  razorpay_order_id: string
  razorpay_payment_id: string
  razorpay_signature: string
}

interface RazorpayOptions {
  key: string
  amount: number
  currency: string
  name: string
  description: string
  order_id: string
  handler: (response: RazorpayCheckoutResponse) => void
  modal?: { ondismiss?: () => void }
  theme?: { color?: string }
}

declare global {
  interface Window {
    Razorpay?: new (options: RazorpayOptions) => { open: () => void }
  }
}

/** Item 20/26: status -> banner tone + copy. EXPIRING_SOON/GRACE_PERIOD/EXPIRED are deliberately
 * visually distinct (color-coded) per the design doc's explicit ask - never a blocking modal, this
 * is a dismissible-for-this-session strip, so it never interrupts a cashier mid-sale. */
const STATUS_META: Record<
  SubscriptionDto['status'],
  { tone: 'success' | 'warning' | 'danger' | 'neutral'; label: string; Icon: typeof ShieldCheck }
> = {
  ACTIVE: { tone: 'success', label: 'Active', Icon: ShieldCheck },
  EXPIRING_SOON: { tone: 'warning', label: 'Expiring Soon', Icon: AlertTriangle },
  GRACE_PERIOD: { tone: 'warning', label: 'Grace Period', Icon: AlertTriangle },
  EXPIRED: { tone: 'danger', label: 'Expired', Icon: XCircle },
  SUSPENDED: { tone: 'danger', label: 'Suspended', Icon: XCircle },
}

const BANNER_BG: Record<'success' | 'warning' | 'danger' | 'neutral', string> = {
  success: 'border-success/30 bg-success-soft',
  warning: 'border-warning/30 bg-warning-soft',
  danger: 'border-danger/30 bg-danger-soft',
  neutral: 'border-app bg-app/40',
}

const BANNER_TEXT: Record<'success' | 'warning' | 'danger' | 'neutral', string> = {
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-danger',
  neutral: 'text-muted',
}

const PAYMENT_STATUS_TONE: Record<SubscriptionPaymentDto['status'], 'success' | 'danger' | 'neutral' | 'warning'> = {
  CREATED: 'neutral',
  SUCCESS: 'success',
  FAILED: 'danger',
  CANCELLED: 'warning',
}

/** Round 20's fixed convention (see OrganizationPage/MenuEditorPage's own `describeError`). */
function describeError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    return err.message || fallback
  }
  return fallback
}

function formatDate(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

function formatDateTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
}

function StatusBanner({ subscription }: { subscription: SubscriptionDto }) {
  const meta = STATUS_META[subscription.status]
  const daysLabel =
    subscription.remainingDays >= 0
      ? `${subscription.remainingDays} day${subscription.remainingDays === 1 ? '' : 's'} remaining`
      : `${Math.abs(subscription.remainingDays)} day${Math.abs(subscription.remainingDays) === 1 ? '' : 's'} past expiry`

  return (
    <div className={cn('flex flex-wrap items-center justify-between gap-3 rounded-2xl border px-5 py-4', BANNER_BG[meta.tone])}>
      <div className="flex items-center gap-3">
        <meta.Icon className={cn('h-8 w-8 shrink-0', BANNER_TEXT[meta.tone])} />
        <div>
          <div className="flex items-center gap-2">
            <span className="text-base font-bold text-app">{subscription.planName}</span>
            <Badge tone={meta.tone === 'neutral' ? 'neutral' : meta.tone}>{meta.label}</Badge>
          </div>
          <p className={cn('text-sm font-medium', BANNER_TEXT[meta.tone])}>{daysLabel}</p>
        </div>
      </div>
      <div className="text-right text-xs text-muted">
        <div>Started {formatDate(subscription.startDate)}</div>
        <div>Expires {formatDate(subscription.expiryDate)}</div>
      </div>
    </div>
  )
}

function PlanCard({ plan, isCurrent, enabledFeatures }: { plan: PlanDto; isCurrent: boolean; enabledFeatures: Set<string> }) {
  return (
    <Card className={cn(isCurrent && 'border-brand-500 ring-1 ring-brand-500')}>
      <CardHeader className="flex-col items-start gap-1">
        <div className="flex w-full items-center justify-between">
          <CardTitle className="text-base">{plan.name}</CardTitle>
          {isCurrent && <Badge tone="brand">Current Plan</Badge>}
          {plan.trial && !isCurrent && <Badge tone="info">Trial</Badge>}
        </div>
        {plan.description && <p className="text-xs text-muted">{plan.description}</p>}
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex items-baseline gap-1">
          <span className="text-2xl font-bold text-app">{formatCurrency(plan.price)}</span>
          <span className="text-xs text-muted">/ {plan.durationDays} days{plan.gstPercent ? ` + ${plan.gstPercent}% GST` : ''}</span>
        </div>
        <div className="grid grid-cols-3 gap-2 text-center text-xs text-muted">
          <div className="rounded-lg bg-app/60 px-2 py-1.5">
            <div className="text-sm font-bold text-app">{plan.maxBranches ?? '∞'}</div>
            Branches
          </div>
          <div className="rounded-lg bg-app/60 px-2 py-1.5">
            <div className="text-sm font-bold text-app">{plan.maxTerminals ?? '∞'}</div>
            Terminals
          </div>
          <div className="rounded-lg bg-app/60 px-2 py-1.5">
            <div className="text-sm font-bold text-app">{plan.maxUsers ?? '∞'}</div>
            Users
          </div>
        </div>
        {plan.featureCodes.length > 0 && (
          <div className="flex flex-wrap gap-1.5 pt-1">
            {plan.featureCodes.map((code) => (
              <LockedFeatureChip key={code} label={code.replaceAll('_', ' ')} enabled={enabledFeatures.has(code)} planHint={plan.name} />
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

/** "Choose Another Plan" per the requirement's own wording ("a dropdown/list of all available
 * plans... may include different durations such as 3 months, 6 months, and 1 year"). */
function PlanPickerList({
  plans,
  onSelect,
  busy,
}: {
  plans: PlanDto[]
  onSelect: (plan: PlanDto) => void
  busy: boolean
}) {
  if (plans.length === 0) {
    return <p className="text-sm text-muted">No other plans are currently available.</p>
  }
  return (
    <div className="space-y-2">
      {plans.map((plan) => (
        <div
          key={plan.id}
          className="flex items-center justify-between gap-3 rounded-xl border border-app px-4 py-3"
        >
          <div>
            <div className="flex items-center gap-2">
              <span className="text-sm font-bold text-app">{plan.name}</span>
              {plan.trial && <Badge tone="info">Trial</Badge>}
            </div>
            <p className="text-xs text-muted">
              {plan.durationDays} days{plan.description ? ` · ${plan.description}` : ''}
            </p>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-sm font-bold text-app">{formatCurrency(plan.price)}</span>
            <Button size="sm" disabled={busy} onClick={() => onSelect(plan)}>
              Select
            </Button>
          </div>
        </div>
      ))}
    </div>
  )
}

function RecentPayments({ payments }: { payments: SubscriptionPaymentDto[] }) {
  if (payments.length === 0) {
    return null
  }
  return (
    <div>
      <h3 className="mb-2 flex items-center gap-2 text-sm font-bold text-app">
        <History className="h-4 w-4 text-brand-600" /> Recent Payments
      </h3>
      <Card>
        <CardContent className="divide-y divide-app p-0">
          {payments.map((payment) => (
            <div key={payment.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-sm font-semibold text-app">{payment.planName}</span>
                  <Badge tone="neutral">{payment.purpose === 'REACTIVATE' ? 'Renewal' : 'Plan Change'}</Badge>
                </div>
                <p className="text-xs text-muted">{formatDateTime(payment.createdAt)}</p>
              </div>
              <div className="flex items-center gap-3">
                <span className="text-sm font-bold text-app">{formatCurrency(payment.amount)}</span>
                <Badge tone={PAYMENT_STATUS_TONE[payment.status]}>{payment.status}</Badge>
              </div>
            </div>
          ))}
        </CardContent>
      </Card>
    </div>
  )
}

export function SubscriptionPage() {
  const { hasPermission } = useAuthStore()
  const canView = hasPermission('SUBSCRIPTION_VIEW')
  const queryClient = useQueryClient()

  const subscriptionQuery = useQuery({
    queryKey: ['subscription'],
    queryFn: () => api.get<SubscriptionDto>('/subscription'),
    enabled: canView,
    staleTime: 30_000,
  })
  const plansQuery = useQuery({
    queryKey: ['subscription', 'plans'],
    queryFn: () => api.get<PlanDto[]>('/subscription/plans'),
    enabled: canView,
    staleTime: 60_000,
  })
  const paymentsQuery = useQuery({
    queryKey: ['subscription', 'payments'],
    queryFn: () => api.get<SubscriptionPaymentDto[]>('/subscription/payments'),
    enabled: canView,
    staleTime: 15_000,
  })
  const { enabledFeatures } = useEntitlements()

  const [planPickerOpen, setPlanPickerOpen] = useState(false)
  const [renewalError, setRenewalError] = useState<string | null>(null)
  const [renewalSuccess, setRenewalSuccess] = useState<{ planName: string; startDate: string; expiryDate: string } | null>(null)
  const [checkoutBusy, setCheckoutBusy] = useState(false)
  const pendingPaymentIdRef = useRef<string | null>(null)

  const initiateMutation = useMutation({
    mutationFn: (request: InitiateRenewalRequest) => api.post<InitiateRenewalResponse>('/subscription/renew/initiate', request),
  })
  const verifyMutation = useMutation({
    mutationFn: (request: VerifyRenewalRequest) => api.post<SubscriptionDto>('/subscription/renew/verify', request),
  })
  const cancelMutation = useMutation({
    mutationFn: (request: CancelRenewalRequest) => api.post<void>('/subscription/renew/cancel', request),
  })

  const refreshAfterPayment = () => {
    queryClient.invalidateQueries({ queryKey: ['subscription'] })
    queryClient.invalidateQueries({ queryKey: ['subscription', 'plans'] })
    queryClient.invalidateQueries({ queryKey: ['subscription', 'payments'] })
  }

  async function startRenewal(purpose: 'REACTIVATE' | 'PLAN_CHANGE', targetPlanId?: string) {
    setRenewalError(null)
    setRenewalSuccess(null)
    setCheckoutBusy(true)
    try {
      const initiated = await initiateMutation.mutateAsync({
        branchId: subscriptionQuery.data?.branchId ?? undefined,
        purpose,
        targetPlanId,
      })
      pendingPaymentIdRef.current = initiated.subscriptionPaymentId
      await loadRazorpayScript()
      setPlanPickerOpen(false)

      if (!window.Razorpay) {
        throw new Error('Could not load the payment window. Check your connection and try again.')
      }
      const razorpay = new window.Razorpay({
        key: initiated.razorpayKeyId,
        amount: initiated.amountPaise,
        currency: initiated.currency,
        name: 'Bistrodesk',
        description: initiated.description,
        order_id: initiated.razorpayOrderId,
        theme: { color: '#4f46e5' },
        handler: (response) => {
          void (async () => {
            try {
              const updated = await verifyMutation.mutateAsync({
                subscriptionPaymentId: initiated.subscriptionPaymentId,
                razorpayOrderId: response.razorpay_order_id,
                razorpayPaymentId: response.razorpay_payment_id,
                razorpaySignature: response.razorpay_signature,
              })
              pendingPaymentIdRef.current = null
              setRenewalSuccess({ planName: updated.planName, startDate: updated.startDate, expiryDate: updated.expiryDate })
              refreshAfterPayment()
            } catch (err) {
              setRenewalError(describeError(err, 'Payment verification failed. Your subscription has not been changed.'))
              refreshAfterPayment()
            }
          })()
        },
        modal: {
          ondismiss: () => {
            const paymentId = pendingPaymentIdRef.current
            pendingPaymentIdRef.current = null
            if (paymentId) {
              cancelMutation.mutate({ subscriptionPaymentId: paymentId })
            }
          },
        },
      })
      razorpay.open()
    } catch (err) {
      setRenewalError(describeError(err, 'Could not start the payment. Please try again.'))
    } finally {
      setCheckoutBusy(false)
    }
  }

  if (!canView) {
    return (
      <Card className="flex flex-col items-center justify-center gap-3 px-6 py-20 text-center">
        <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-danger-soft text-danger">
          <ShieldAlert className="h-7 w-7" />
        </div>
        <h2 className="text-lg font-bold text-app">Access Restricted</h2>
        <p className="max-w-sm text-sm text-muted">
          You don't have permission to view subscription and billing details. Ask an administrator to grant you
          SUBSCRIPTION_VIEW if you believe this is a mistake.
        </p>
      </Card>
    )
  }

  if (subscriptionQuery.isLoading || plansQuery.isLoading) {
    return <FullPageSpinner label="Loading subscription…" />
  }

  if (subscriptionQuery.isError) {
    return (
      <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
        {subscriptionQuery.error instanceof ApiError
          ? subscriptionQuery.error.message
          : 'Could not load subscription details'}
      </div>
    )
  }

  const subscription = subscriptionQuery.data!
  const plans = plansQuery.data ?? []
  const otherPlans = plans.filter((p) => p.active && p.id !== subscription.planId)
  const needsRenewal =
    subscription.status === 'EXPIRING_SOON' || subscription.status === 'GRACE_PERIOD' || subscription.status === 'EXPIRED'
  const busy = checkoutBusy || initiateMutation.isPending || verifyMutation.isPending

  return (
    <div className="space-y-4">
      <div>
        <h2 className="flex items-center gap-2 text-lg font-bold text-app">
          <CreditCard className="h-5 w-5 text-brand-600" /> Subscription
        </h2>
        <p className="mt-0.5 text-sm text-muted">Your current plan, status, and every plan Bistrodesk currently offers.</p>
      </div>

      <StatusBanner subscription={subscription} />

      {renewalSuccess && (
        <div className="flex items-start gap-2 rounded-lg border border-success/30 bg-success-soft px-3 py-2.5 text-sm text-success">
          <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            Payment successful - <strong>{renewalSuccess.planName}</strong> is now active, from{' '}
            {formatDate(renewalSuccess.startDate)} to {formatDate(renewalSuccess.expiryDate)}.
          </span>
        </div>
      )}
      {renewalError && (
        <div className="flex items-start gap-2 rounded-lg border border-danger/30 bg-danger-soft px-3 py-2.5 text-sm text-danger">
          <XCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{renewalError}</span>
        </div>
      )}

      {needsRenewal && (
        <Card>
          <CardContent className="flex flex-wrap items-center justify-between gap-3 py-4">
            <div>
              <h3 className="text-sm font-bold text-app">Renew your subscription</h3>
              <p className="text-xs text-muted">Pay by Credit/Debit Card or UPI to reactivate your current plan or switch to another one.</p>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" disabled={busy} onClick={() => setPlanPickerOpen(true)}>
                Choose Another Plan
              </Button>
              <Button disabled={busy} onClick={() => startRenewal('REACTIVATE')}>
                {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
                Reactivate Current Plan
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {needsRenewal && subscription.supportPhone && (
        <div className="flex items-center gap-2 rounded-lg border border-info/30 bg-info-soft px-3 py-2.5 text-sm text-info">
          <Phone className="h-4 w-4 shrink-0" />
          <span>
            Prefer to talk to someone instead? Contact Bistrodesk support at{' '}
            <a href={`tel:${subscription.supportPhone}`} className="font-semibold underline">
              {subscription.supportPhone}
            </a>
            .
          </span>
        </div>
      )}

      <div className="flex items-center gap-2 text-xs text-muted">
        <CalendarClock className="h-3.5 w-3.5" />
        Grace period: {subscription.gracePeriodDays} day{subscription.gracePeriodDays === 1 ? '' : 's'} after expiry ·
        Warnings at {subscription.warningThresholdsDays.split(',').join(', ')} days before expiry
      </div>

      <div>
        <h3 className="mb-2 flex items-center gap-2 text-sm font-bold text-app">
          <CheckCircle2 className="h-4 w-4 text-brand-600" /> Available Plans
        </h3>
        {plans.length === 0 ? (
          <p className="text-sm text-muted">No plans configured yet.</p>
        ) : (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
            {plans.map((plan) => (
              <PlanCard key={plan.id} plan={plan} isCurrent={plan.id === subscription.planId} enabledFeatures={enabledFeatures} />
            ))}
          </div>
        )}
      </div>

      <RecentPayments payments={paymentsQuery.data ?? []} />

      <Modal open={planPickerOpen} onClose={() => setPlanPickerOpen(false)} title="Choose Another Plan" widthClassName="max-w-xl">
        <PlanPickerList plans={otherPlans} busy={busy} onSelect={(plan) => startRenewal('PLAN_CHANGE', plan.id)} />
      </Modal>
    </div>
  )
}
