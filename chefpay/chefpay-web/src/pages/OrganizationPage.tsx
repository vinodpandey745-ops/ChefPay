import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Building2, Landmark, Mail, MapPin, Percent, Save, ShieldAlert } from 'lucide-react'
import { useEffect, useState } from 'react'

import { Badge } from '@/components/ui/Badge'
import { Button } from '@/components/ui/Button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/Card'
import { FullPageSpinner } from '@/components/ui/Spinner'
import { api } from '@/lib/api'
import { useAuthStore } from '@/store/auth'
import { ApiError, type RestaurantDto, type UpdateRestaurantRequest } from '@/types/api'

/** Round 20's fixed convention (see MenuEditorPage/BranchesTerminalsPage's own `describeError`):
 * only collapse a genuine optimistic-lock conflict, never a blanket 409/403 - a PW-required 403's
 * specific "sign in with a username and password" message must show through untouched. */
function describeError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.errorCode === 'VERSION_CONFLICT') {
      return 'The organization profile was updated elsewhere - refresh the page and try again.'
    }
    return err.message || fallback
  }
  return fallback
}

const STATUS_TONE: Record<RestaurantDto['status'], 'success' | 'warning' | 'danger'> = {
  ACTIVE: 'success',
  SUSPENDED: 'warning',
  CLOSED: 'danger',
}

interface Draft {
  name: string
  organizationName: string
  gstin: string
  contactEmail: string
  supportPhone: string
  addressLine1: string
  addressLine2: string
  city: string
  state: string
  postalCode: string
  country: string
}

function toDraft(r: RestaurantDto): Draft {
  return {
    name: r.name,
    organizationName: r.organizationName ?? '',
    gstin: r.gstin ?? '',
    contactEmail: r.contactEmail ?? '',
    supportPhone: r.supportPhone ?? '',
    addressLine1: r.addressLine1 ?? '',
    addressLine2: r.addressLine2 ?? '',
    city: r.city ?? '',
    state: r.state ?? '',
    postalCode: r.postalCode ?? '',
    country: r.country ?? '',
  }
}

function Field({
  label,
  value,
  onChange,
  placeholder,
  disabled,
  type = 'text',
}: {
  label: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  disabled?: boolean
  type?: string
}) {
  return (
    <div>
      <label className="mb-1.5 block text-xs font-semibold text-muted">{label}</label>
      <input
        type={type}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full rounded-lg border border-app bg-app px-3 py-2 text-sm text-app outline-none focus:ring-2 focus:ring-brand-500 disabled:opacity-60"
      />
    </div>
  )
}

export function OrganizationPage() {
  const queryClient = useQueryClient()
  const { hasPermission, hasPasswordLogin } = useAuthStore()
  const canEdit = (hasPermission('ORGANIZATION_MANAGE') || hasPermission('RESTAURANT_MANAGE')) && hasPasswordLogin()
  const canSeeEditGateReason =
    hasPermission('ORGANIZATION_MANAGE') || hasPermission('RESTAURANT_MANAGE') ? !hasPasswordLogin() : false

  const orgQuery = useQuery({
    queryKey: ['organization'],
    queryFn: () => api.get<RestaurantDto>('/organization'),
    staleTime: 30_000,
  })

  const [draft, setDraft] = useState<Draft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    if (orgQuery.data) setDraft(toDraft(orgQuery.data))
  }, [orgQuery.data])

  const save = useMutation({
    mutationFn: () => {
      if (!orgQuery.data || !draft) throw new Error('Not loaded yet')
      return api.patch<RestaurantDto>('/organization', {
        organizationName: draft.organizationName.trim() || null,
        gstin: draft.gstin.trim() || null,
        contactEmail: draft.contactEmail.trim() || null,
        supportPhone: draft.supportPhone.trim() || null,
        addressLine1: draft.addressLine1.trim() || null,
        addressLine2: draft.addressLine2.trim() || null,
        city: draft.city.trim() || null,
        state: draft.state.trim() || null,
        postalCode: draft.postalCode.trim() || null,
        country: draft.country.trim() || null,
        version: orgQuery.data.version,
      } satisfies UpdateRestaurantRequest)
    },
    onSuccess: (data) => {
      queryClient.setQueryData(['organization'], data)
      queryClient.invalidateQueries({ queryKey: ['restaurant'] })
      setError(null)
      setSaved(true)
      setTimeout(() => setSaved(false), 2000)
    },
    onError: (err) => setError(describeError(err, 'Could not save the organization profile')),
  })

  if (orgQuery.isLoading || !draft) return <FullPageSpinner label="Loading organization…" />

  if (orgQuery.isError) {
    return (
      <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
        {orgQuery.error instanceof ApiError ? orgQuery.error.message : 'Could not load the organization profile'}
      </div>
    )
  }

  const org = orgQuery.data!

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-lg font-bold text-app">
            <Landmark className="h-5 w-5 text-brand-600" /> Organization
          </h2>
          <p className="mt-0.5 text-sm text-muted">Profile, contact, address, and tax details for this business.</p>
        </div>
        <Badge tone={STATUS_TONE[org.status]}>{org.status}</Badge>
      </div>

      {org.status !== 'ACTIVE' && (
        <div className="flex items-start gap-2 rounded-lg border border-warning/30 bg-warning-soft px-3 py-2.5 text-sm text-warning">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            This organization is currently <strong>{org.status}</strong>. This is set by Bistrodesk support, not from
            this screen - contact support if you believe this is a mistake.
          </span>
        </div>
      )}

      {canSeeEditGateReason && (
        <div className="flex items-start gap-2 rounded-lg border border-info/30 bg-info-soft px-3 py-2.5 text-xs text-info">
          <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            You have permission to edit the organization profile, but this action requires signing in with a
            username and password, not a PIN. Sign in again from the Manager/Admin login screen.
          </span>
        </div>
      )}

      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          save.mutate()
        }}
      >
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Building2 className="h-4 w-4 text-brand-600" /> Profile
            </CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="Legal / trading name" value={org.name} onChange={() => {}} disabled />
            <Field
              label="Organization display name"
              value={draft.organizationName}
              onChange={(v) => setDraft({ ...draft, organizationName: v })}
              placeholder={org.name}
              disabled={!canEdit}
            />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Mail className="h-4 w-4 text-brand-600" /> Contact
            </CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field
              label="Contact email"
              type="email"
              value={draft.contactEmail}
              onChange={(v) => setDraft({ ...draft, contactEmail: v })}
              placeholder="ops@yourrestaurant.com"
              disabled={!canEdit}
            />
            <Field
              label="Support phone"
              value={draft.supportPhone}
              onChange={(v) => setDraft({ ...draft, supportPhone: v })}
              placeholder="+91 98765 43210"
              disabled={!canEdit}
            />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <MapPin className="h-4 w-4 text-brand-600" /> Address
            </CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field
              label="Address line 1"
              value={draft.addressLine1}
              onChange={(v) => setDraft({ ...draft, addressLine1: v })}
              disabled={!canEdit}
            />
            <Field
              label="Address line 2"
              value={draft.addressLine2}
              onChange={(v) => setDraft({ ...draft, addressLine2: v })}
              disabled={!canEdit}
            />
            <Field label="City" value={draft.city} onChange={(v) => setDraft({ ...draft, city: v })} disabled={!canEdit} />
            <Field label="State" value={draft.state} onChange={(v) => setDraft({ ...draft, state: v })} disabled={!canEdit} />
            <Field
              label="Postal code"
              value={draft.postalCode}
              onChange={(v) => setDraft({ ...draft, postalCode: v })}
              disabled={!canEdit}
            />
            <Field label="Country" value={draft.country} onChange={(v) => setDraft({ ...draft, country: v })} disabled={!canEdit} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Percent className="h-4 w-4 text-brand-600" /> Tax / GST
            </CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="GSTIN" value={draft.gstin} onChange={(v) => setDraft({ ...draft, gstin: v })} disabled={!canEdit} />
          </CardContent>
        </Card>

        {error && <div className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">{error}</div>}

        {canEdit && (
          <div className="flex items-center justify-end gap-2">
            {saved && <span className="text-xs font-semibold text-success">Saved</span>}
            <Button type="submit" disabled={save.isPending}>
              <Save className="h-4 w-4" /> {save.isPending ? 'Saving…' : 'Save changes'}
            </Button>
          </div>
        )}
      </form>

      {!canEdit && !canSeeEditGateReason && (
        <p className="text-xs text-muted">Ask an administrator for ORGANIZATION_MANAGE permission to edit this profile.</p>
      )}
    </div>
  )
}
