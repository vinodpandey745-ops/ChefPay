import { useMutation } from '@tanstack/react-query'
import { Banknote, Check, CreditCard, Loader2, Printer, Smartphone, Wallet, X as XIcon } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import { Button } from '@/components/ui/Button'
import { Modal } from '@/components/ui/Modal'
import { useRestaurantConfig } from '@/hooks/useRestaurantConfig'
import { api } from '@/lib/api'
import { cn, formatCurrency, formatDateTime } from '@/lib/utils'
import { ApiError } from '@/types/api'
import type { BillDto, OrderDto, ReceiptDto } from '@/types/api'

const PAYMENT_METHODS: { value: string; label: string; icon: typeof Banknote }[] = [
  { value: 'CASH', label: 'Cash', icon: Banknote },
  { value: 'CARD', label: 'Card', icon: CreditCard },
  { value: 'UPI', label: 'UPI', icon: Smartphone },
  { value: 'WALLET', label: 'Wallet', icon: Wallet },
]

const BILLABLE_STATUSES = new Set(['BILL_REQUESTED', 'BILLED', 'PAYMENT_PENDING', 'PAID', 'CLOSED'])

// OrderStatus is a strictly linear one-step-at-a-time state machine server-side (see
// OrderStatus#canTransitionTo in chefpay-core - SERVED can only go to BILL_REQUESTED, PREPARING can
// only go to READY, etc; there is no "jump straight to BILL_REQUESTED" transition from an earlier
// status). Checkout has to walk every intermediate status explicitly rather than PATCHing directly
// to BILL_REQUESTED - a direct jump only worked by coincidence when an order happened to already be
// exactly at SERVED, and threw INVALID_ORDER_TRANSITION (409) for anything sent to the kitchen but
// not yet marked Served, which is the normal case right after the Kitchen Display marks the last
// item done.
//
// When an order was never sent to kitchen (still DRAFT/PLACED, every item ADDED) this walk also
// covers the PLACED -> SENT_TO_KITCHEN step, which OrderService only allows without a real KOT
// dispatch when the restaurant has `kotOptionalEnabled` turned on (see Settings > Operations) -
// PosTerminalPage already gates the Checkout button itself on that same flag, so by the time this
// walk runs here the caller has already established the walk is allowed.
const ORDER_STATUS_SEQUENCE = [
  'DRAFT',
  'PLACED',
  'SENT_TO_KITCHEN',
  'ACCEPTED',
  'PREPARING',
  'READY',
  'SERVED',
  'BILL_REQUESTED',
]

/** `isCancelled` is checked between every step so an unmounted/re-run effect (React 18 StrictMode
 * double-invokes effects in dev, and in production the modal can legitimately unmount mid-walk if
 * the user closes it quickly) stops issuing further PATCHes instead of racing a second walk over
 * the same order - without this, two concurrent walks both try to advance the same order and the
 * loser gets a 409 (e.g. "BILL_REQUESTED -> BILL_REQUESTED") for a walk that already succeeded. */
async function walkToBillRequested(order: OrderDto, isCancelled: () => boolean): Promise<OrderDto> {
  let current = order
  const targetIndex = ORDER_STATUS_SEQUENCE.indexOf('BILL_REQUESTED')
  let currentIndex = ORDER_STATUS_SEQUENCE.indexOf(current.status)
  if (currentIndex < 0) {
    // Already past BILL_REQUESTED (BILLED/PAYMENT_PENDING) or an unrecognized status - nothing to walk.
    return current
  }
  while (currentIndex < targetIndex) {
    if (isCancelled()) return current
    const nextStatus = ORDER_STATUS_SEQUENCE[currentIndex + 1]
    current = await api.patch<OrderDto>(`/orders/${order.id}/status`, {
      status: nextStatus,
      reason: null,
      version: current.version,
    })
    currentIndex += 1
  }
  return current
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] as string)
}

/** Opens the same narrow, monospace print window OrdersLogPage's re-print action uses, fed by the
 * server-authoritative receipt text (BillingService#generateReceiptText) rather than a client-side
 * reconstruction - that keeps "Print Thermal" byte-for-byte identical to what the JavaFX desktop
 * client and a real re-print produce, instead of a second formatter that could quietly drift from
 * the backend's GST/discount/multi-payment rules. */
function openPrintWindow(receipt: ReceiptDto): boolean {
  const win = window.open('', '_blank', 'width=400,height=640')
  if (!win) return false
  win.document.write(
    `<title>${escapeHtml(receipt.orderNumber)}</title>` +
      `<pre style="font-family: 'Courier New', monospace; font-size: 12px; white-space: pre-wrap; padding: 16px;">${escapeHtml(receipt.text)}</pre>`,
  )
  win.document.close()
  win.focus()
  win.print()
  return true
}

interface CheckoutModalProps {
  order: OrderDto
  onClose: () => void
  /** Called once the balance reaches zero and the receipt has been dismissed - the caller (POS
   * Terminal) clears the active order and returns to a fresh state, since a fully-paid order has
   * nothing left to edit. */
  onSettled: () => void
}

/**
 * Generates the bill (moving the order through BILL_REQUESTED -> BILLED, exactly the transition
 * OrderService/BillingService already model) and records a payment against it - the "Checkout /
 * Pay" half of the reference POS's cart footer. Once the balance reaches zero this shows a proper
 * receipt (restaurant header, itemized lines, totals, payment method) with Close/Print controls
 * instead of silently closing - the receipt no longer disappears in ~1s, and whether it also fires
 * the thermal printer automatically is controlled by the restaurant's `autoPrintReceiptOnPayment`
 * setting (Settings > Operations), same as ChefPay's JavaFX BillingView#maybeAutoPrintReceipt.
 */
export function CheckoutModal({ order, onClose, onSettled }: CheckoutModalProps) {
  const [bill, setBill] = useState<BillDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [method, setMethod] = useState('CASH')
  const [tendered, setTendered] = useState('')
  const [busy, setBusy] = useState(false)
  const [paid, setPaid] = useState(false)
  const [paidAt, setPaidAt] = useState<string | null>(null)
  const [printError, setPrintError] = useState<string | null>(null)
  const autoPrintedRef = useRef(false)
  // Bug #13 fix: an order walked to BILL_REQUESTED is ready for the actual "generate the bill" call
  // (the BILL_REQUESTED -> BILLED transition that locks the discount in for good - see
  // BillingService), but that call is no longer fired automatically. It's held here until the
  // confirmation gate just below either shows a prompt the cashier accepts, or auto-clears it
  // because the restaurant has turned the prompt off.
  const [pendingGenerate, setPendingGenerate] = useState<OrderDto | null>(null)
  const [generating, setGenerating] = useState(false)
  const autoGeneratedRef = useRef(false)
  // POS patch (tip on the entire order): collected here, on the "Generate the final bill?" screen,
  // per the requirement - optional, order-level, not per item. Sent along with the /generate call so
  // it's already folded into totalAmount by the time the bill is generated; nothing downstream
  // (payment, receipt) needs to recalculate anything for it.
  const [tipInput, setTipInput] = useState('')

  const { data: restaurant, isLoading: restaurantLoading } = useRestaurantConfig()

  useEffect(() => {
    let cancelled = false
    async function prepare() {
      setError(null)
      try {
        let current = order
        if (!BILLABLE_STATUSES.has(current.status)) {
          current = await walkToBillRequested(current, () => cancelled)
        }
        if (cancelled) return
        if (current.status === 'BILL_REQUESTED') {
          // Don't call .../generate yet - see the confirmation gate below.
          setPendingGenerate(current)
          return
        }
        const dto = await api.get<BillDto>(`/billing/orders/${order.id}`)
        if (!cancelled) {
          setBill(dto)
          if (dto.balanceDue <= 0 && dto.amountPaid > 0) {
            // Reopening an already-fully-paid order (e.g. re-checking a tableless order from the
            // Orders Log) - go straight to the receipt instead of showing a payment form for a
            // balance of zero.
            setPaid(true)
          }
        }
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof ApiError
              ? err.message
              : 'Could not prepare the bill for this order.',
          )
        }
      }
    }
    prepare()
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [order.id])

  async function generateBill(current: OrderDto) {
    setGenerating(true)
    setError(null)
    try {
      const parsedTip = Number(tipInput)
      const tipAmount = tipInput.trim() !== '' && Number.isFinite(parsedTip) && parsedTip > 0 ? parsedTip : undefined
      const dto = await api.post<BillDto>(`/billing/orders/${order.id}/generate`, undefined, {
        version: current.version,
        ...(tipAmount !== undefined ? { tipAmount } : {}),
      })
      setBill(dto)
      setPendingGenerate(null)
      if (dto.balanceDue <= 0 && dto.amountPaid > 0) {
        setPaid(true)
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not generate the bill for this order.')
    } finally {
      setGenerating(false)
    }
  }

  // Bug #13 fix: mirrors the JavaFX desktop client's own gate (BillingView#confirmGenerateBillIfConfigured,
  // Settings > Billing Workflow's "show discount confirmation" toggle) which chefpay-web never had -
  // DiscountRow's own discount-apply call already required nothing more than the normal Served/Bill
  // Requested eligibility check, but the actual point of no return for a discount is generating the
  // bill itself (it can't be changed after BILLED), so that's where the confirmation belongs, exactly
  // where the desktop client puts it. Only auto-skips the prompt once restaurant config has actually
  // loaded AND explicitly turned it off - "still loading" and "failed to load" both fall back to
  // showing the prompt, the same null-safe default BillingView's own check uses.
  useEffect(() => {
    if (pendingGenerate && !restaurantLoading && restaurant?.showDiscountConfirmation === false && !autoGeneratedRef.current) {
      autoGeneratedRef.current = true
      generateBill(pendingGenerate)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingGenerate, restaurantLoading, restaurant?.showDiscountConfirmation])

  const showGeneratePrompt = !!pendingGenerate && !restaurantLoading && restaurant?.showDiscountConfirmation !== false

  const printReceipt = useMutation({
    mutationFn: () => api.get<ReceiptDto>(`/billing/orders/${order.id}/receipt`),
    onSuccess: (receipt) => {
      setPrintError(null)
      if (!openPrintWindow(receipt)) {
        setPrintError('Pop-up blocked - allow pop-ups for this site to print receipts.')
      }
    },
    onError: (err) => setPrintError(err instanceof ApiError ? err.message : 'Could not generate this receipt yet'),
  })

  // Configurable per Settings > Operations: when `autoPrintReceiptOnPayment` is on, fire the
  // thermal print automatically the moment the balance hits zero - but the receipt itself always
  // stays on screen with a Close button (this used to auto-close after ~1.1s, which is exactly
  // what read as "the receipt isn't showing" - now nothing dismisses it until the cashier does).
  useEffect(() => {
    if (paid && bill && restaurant?.autoPrintReceiptOnPayment && !autoPrintedRef.current) {
      autoPrintedRef.current = true
      printReceipt.mutate()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paid, bill, restaurant?.autoPrintReceiptOnPayment])

  async function handlePay() {
    if (!bill) return
    setBusy(true)
    setError(null)
    try {
      const body =
        method === 'CASH'
          ? { method, tenderedAmount: Number(tendered || bill.balanceDue), orderVersion: bill.orderVersion }
          : { method, amount: bill.balanceDue, orderVersion: bill.orderVersion }
      const updated = await api.post<BillDto>(`/billing/orders/${order.id}/payments`, body)
      setBill(updated)
      if (updated.balanceDue <= 0) {
        setPaidAt(new Date().toISOString())
        setPaid(true)
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not record payment')
    } finally {
      setBusy(false)
    }
  }

  const currency = restaurant?.currencySymbol ?? '₹'
  const activeItems = order.items.filter((i) => i.status !== 'CANCELLED' && i.status !== 'VOIDED')
  const branch = restaurant?.branches?.[0]
  const paidPayments = bill?.payments.filter((p) => !p.voided) ?? []
  const latestPayment = paidPayments[paidPayments.length - 1] ?? null

  return (
    <Modal open onClose={paid ? onSettled : onClose} title={paid ? 'Receipt' : `Checkout · ${order.orderNumber}`} widthClassName={paid ? 'max-w-sm' : 'max-w-lg'}>
      {error && <div className="mb-4 rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}

      {!bill && !error && !showGeneratePrompt && (
        <div className="flex h-40 flex-col items-center justify-center gap-2 text-muted">
          <Loader2 className="h-6 w-6 animate-spin" />
          <span className="text-sm">Preparing bill…</span>
        </div>
      )}

      {!bill && !error && showGeneratePrompt && pendingGenerate && (
        <div className="space-y-4 py-2 text-center">
          <p className="text-sm font-medium text-app">Generate the final bill for {order.orderNumber}?</p>
          <p className="text-xs text-muted">Discounts can't be changed after this.</p>
          {order.discountAmount > 0 && (
            <p className="text-xs font-semibold text-success">Discount currently applied: {formatCurrency(order.discountAmount)}</p>
          )}
          <div className="text-left">
            <label className="mb-1.5 block text-xs font-semibold text-muted">Tip (optional)</label>
            <input
              type="number"
              min={0}
              step="0.01"
              placeholder="0.00"
              value={tipInput}
              onChange={(e) => setTipInput(e.target.value)}
              disabled={generating}
              className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
            />
          </div>
          <div className="flex gap-2">
            <Button variant="secondary" className="flex-1" type="button" disabled={generating} onClick={onClose}>
              Cancel
            </Button>
            <Button className="flex-1" type="button" disabled={generating} onClick={() => generateBill(pendingGenerate)}>
              {generating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Check className="h-4 w-4" />}
              {generating ? 'Generating…' : 'Generate Bill'}
            </Button>
          </div>
        </div>
      )}

      {bill && paid && (
        <div className="space-y-4">
          <div className="flex flex-col items-center gap-1 text-success">
            <div className="rounded-full bg-success-soft p-2.5">
              <Check className="h-6 w-6" />
            </div>
            <span className="text-sm font-semibold">Payment recorded · order settled</span>
          </div>

          <div className="space-y-2 rounded-xl border border-dashed border-app bg-app/40 p-4 font-mono text-xs">
            <div className="space-y-0.5 text-center">
              <div className="text-sm font-extrabold uppercase tracking-wide text-app">{restaurant?.name ?? 'Restaurant'}</div>
              {branch?.address && <div className="text-muted">{branch.address}</div>}
              {restaurant?.gstin && <div className="text-muted">GSTIN: {restaurant.gstin}</div>}
              {restaurant?.supportPhone && <div className="text-muted">Ph: {restaurant.supportPhone}</div>}
            </div>

            <div className="border-t border-dashed border-app pt-2 text-app">
              <div className="flex justify-between">
                <span>Bill No</span>
                <span className="font-semibold">{latestPayment?.receiptNumber ?? order.orderNumber}</span>
              </div>
              <div className="flex justify-between">
                <span>Date</span>
                <span>{formatDateTime(paidAt ?? order.updatedAt)}</span>
              </div>
              <div className="flex justify-between">
                <span>Table / Type</span>
                <span>{order.tableName ?? order.orderType.replaceAll('_', ' ')}</span>
              </div>
              {order.waiterName && (
                <div className="flex justify-between">
                  <span>Waiter</span>
                  <span>{order.waiterName}</span>
                </div>
              )}
            </div>

            <div className="space-y-0.5 border-t border-dashed border-app pt-2 text-app">
              {activeItems.map((item) => (
                <div key={item.id} className="flex justify-between gap-2">
                  <span className="truncate">
                    {item.quantity}× {item.menuItemName}
                  </span>
                  <span className="shrink-0">{formatCurrency(item.lineTotal, currency)}</span>
                </div>
              ))}
            </div>

            <div className="space-y-0.5 border-t border-dashed border-app pt-2 text-app">
              <div className="flex justify-between">
                <span>Subtotal</span>
                <span>{formatCurrency(bill.subtotal, currency)}</span>
              </div>
              {bill.discountAmount > 0 && (
                <div className="flex justify-between">
                  <span>Discount</span>
                  <span>-{formatCurrency(bill.discountAmount, currency)}</span>
                </div>
              )}
              {bill.taxLines.map((line) => (
                <div key={line.name} className="flex justify-between">
                  <span>
                    {line.name} ({line.ratePercent}%)
                  </span>
                  <span>{formatCurrency(line.amount, currency)}</span>
                </div>
              ))}
              {bill.serviceChargeAmount > 0 && (
                <div className="flex justify-between">
                  <span>Service charge</span>
                  <span>{formatCurrency(bill.serviceChargeAmount, currency)}</span>
                </div>
              )}
              {bill.tipAmount > 0 && (
                <div className="flex justify-between">
                  <span>Tip</span>
                  <span>{formatCurrency(bill.tipAmount, currency)}</span>
                </div>
              )}
              <div className="flex justify-between border-t border-dashed border-app pt-1 text-sm font-extrabold">
                <span>TOTAL</span>
                <span>{formatCurrency(bill.totalAmount, currency)}</span>
              </div>
            </div>

            <div className="space-y-0.5 border-t border-dashed border-app pt-2 text-app">
              {paidPayments.map((p) => (
                <div key={p.id} className="flex justify-between">
                  <span>Paid via {p.method}</span>
                  <span>{formatCurrency(p.amount, currency)}</span>
                </div>
              ))}
              {latestPayment?.changeAmount != null && latestPayment.changeAmount > 0 && (
                <div className="flex justify-between font-semibold">
                  <span>Change</span>
                  <span>{formatCurrency(latestPayment.changeAmount, currency)}</span>
                </div>
              )}
            </div>

            <div className="border-t border-dashed border-app pt-2 text-center text-muted">
              {restaurant?.receiptFooterText || 'Thank you, visit again!'}
            </div>
          </div>

          {printError && <div className="rounded-lg bg-danger-soft px-2.5 py-1.5 text-xs text-danger">{printError}</div>}

          <div className="flex gap-2">
            <Button variant="secondary" className="flex-1" onClick={onSettled} type="button">
              <XIcon className="h-4 w-4" /> Close Receipt
            </Button>
            <Button className="flex-1" disabled={printReceipt.isPending} onClick={() => printReceipt.mutate()} type="button">
              {printReceipt.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Printer className="h-4 w-4" />} Print Thermal
            </Button>
          </div>
        </div>
      )}

      {bill && !paid && (
        <div className="space-y-5">
          <div className="space-y-1.5 rounded-xl border border-app bg-app/50 p-4 text-sm">
            <div className="flex justify-between text-muted">
              <span>Subtotal</span>
              <span>{formatCurrency(bill.subtotal, currency)}</span>
            </div>
            {bill.discountAmount > 0 && (
              <div className="flex justify-between text-muted">
                <span>Discount</span>
                <span>-{formatCurrency(bill.discountAmount, currency)}</span>
              </div>
            )}
            {bill.taxLines.map((line) => (
              <div key={line.name} className="flex justify-between text-muted">
                <span>
                  {line.name} ({line.ratePercent}%)
                </span>
                <span>{formatCurrency(line.amount, currency)}</span>
              </div>
            ))}
            {bill.serviceChargeAmount > 0 && (
              <div className="flex justify-between text-muted">
                <span>Service charge</span>
                <span>{formatCurrency(bill.serviceChargeAmount, currency)}</span>
              </div>
            )}
            {bill.tipAmount > 0 && (
              <div className="flex justify-between text-muted">
                <span>Tip</span>
                <span>{formatCurrency(bill.tipAmount, currency)}</span>
              </div>
            )}
            <div className="mt-1.5 flex justify-between border-t border-app pt-1.5 text-base font-bold text-app">
              <span>Total</span>
              <span>{formatCurrency(bill.totalAmount, currency)}</span>
            </div>
            {bill.amountPaid > 0 && (
              <div className="flex justify-between text-success">
                <span>Paid</span>
                <span>{formatCurrency(bill.amountPaid, currency)}</span>
              </div>
            )}
            <div className="flex justify-between text-sm font-bold text-app">
              <span>Balance due</span>
              <span>{formatCurrency(bill.balanceDue, currency)}</span>
            </div>
          </div>

          <div>
            <div className="mb-2 text-xs font-bold uppercase tracking-wide text-muted">Payment method</div>
            <div className="grid grid-cols-4 gap-2">
              {PAYMENT_METHODS.map(({ value, label, icon: Icon }) => (
                <button
                  key={value}
                  type="button"
                  onClick={() => setMethod(value)}
                  className={cn(
                    'flex flex-col items-center gap-1.5 rounded-xl border p-3 text-xs font-semibold transition-colors',
                    method === value
                      ? 'border-brand-500 bg-brand-50 text-brand-700 dark:bg-brand-900/40 dark:text-brand-200'
                      : 'border-app text-muted hover:bg-app',
                  )}
                >
                  <Icon className="h-4.5 w-4.5" />
                  {label}
                </button>
              ))}
            </div>
          </div>

          {method === 'CASH' && (
            <div>
              <label className="mb-1.5 block text-xs font-semibold text-muted">Amount tendered</label>
              <input
                type="number"
                min={0}
                step="0.01"
                placeholder={bill.balanceDue.toFixed(2)}
                value={tendered}
                onChange={(e) => setTendered(e.target.value)}
                className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500"
              />
            </div>
          )}

          <Button className="w-full" size="lg" disabled={busy} onClick={handlePay}>
            {busy ? 'Processing…' : `Confirm Payment · ${formatCurrency(bill.balanceDue, currency)}`}
          </Button>
        </div>
      )}
    </Modal>
  )
}
