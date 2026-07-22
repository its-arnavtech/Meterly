export type TenantRole = 'TENANT_ADMIN' | 'TENANT_USER';

export interface Session {
  accessToken: string;
  tenantId: string;
  email: string;
  role: TenantRole;
}

export interface Resource { id: string; name: string; unitLabel: string; }
export interface UsageUpdate { tenantId: string; resourceId: string; totalUnits: number; updatedAt: string; }
export interface PricingTier { fromUnit: number; toUnit: number | null; unitPrice: number; }
export interface Plan { id: string; name: string; basePrice: number; includedUnits: number; tiers: PricingTier[]; }
export interface InvoiceLineItem { id: string; description: string; quantity: number; unitPrice: number; amount: number; }
export interface Invoice {
  id: string; tenantId: string; periodStart: string; periodEnd: string;
  status: string; totalAmount: number; lineItems: InvoiceLineItem[];
}
