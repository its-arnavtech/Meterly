export interface Session {
  accessToken: string;
  tenantId: string;
  email: string;
  role: string;
  ingestionApiKey: string;
}
export interface UsageUpdate {
  tenantId: string;
  resourceId: string;
  totalUnits: number;
  publishedAt: string;
}

export interface InvoiceLineItem {
  id: string;
  description: string;
  quantity: number;
  unitPrice: number;
  amount: number;
}

export interface Invoice {
  id: string;
  tenantId: string;
  periodStart: string;
  periodEnd: string;
  status: string;
  totalAmount: number;
  lineItems: InvoiceLineItem[];
}
