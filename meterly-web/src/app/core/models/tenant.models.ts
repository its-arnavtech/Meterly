import { Instant, IsoDate, Money, Uuid } from './common.models';

/** docs/api-contract.md §0 "Roles". */
export type Role = 'TENANT_ADMIN' | 'TENANT_USER';

export type TenantStatus = 'ACTIVE' | 'SUSPENDED';
export type UserStatus = 'ACTIVE' | 'DISABLED';
export type ResourceStatus = 'ACTIVE' | 'ARCHIVED';

export interface Tenant {
  id: Uuid;
  name: string;
  status: TenantStatus;
  created_at: Instant;
}

export interface User {
  id: Uuid;
  email: string;
  role: Role;
  status: UserStatus;
  created_at: Instant;
}

/** `user` object embedded in the login/refresh response. */
export interface AuthUser {
  id: Uuid;
  email: string;
  role: Role;
  tenant_id: Uuid;
  tenant_name: string;
}

/** POST /v1/auth/login and POST /v1/auth/refresh → 200. */
export interface AuthResponse {
  access_token: string;
  token_type: 'Bearer';
  /** Seconds until the access token expires (900 = 15 minutes). */
  expires_in: number;
  user: AuthUser;
}

export interface LoginRequest {
  email: string;
  password: string;
}

/** GET /v1/me */
export interface MeResponse {
  user: User;
  tenant: Tenant;
}

// ---------------------------------------------------------------- plans & subscriptions

export interface PricingTier {
  /** Tier prices cumulative units in (from_unit, to_unit]. */
  from_unit: number;
  /** `null` = open-ended last tier. */
  to_unit: number | null;
  unit_price: Money;
}

export interface Plan {
  id: Uuid;
  code: string;
  name: string;
  base_price: Money;
  /** Free units per metered resource per period. */
  included_units: number;
  currency: string;
  tiers: PricingTier[];
}

export interface SubscriptionTerm {
  id: Uuid;
  plan: Plan;
  start_date: IsoDate;
  /** Exclusive end; set to the next period start once a plan change is scheduled. */
  end_date: IsoDate | null;
  status: string;
}

/** GET/PUT /v1/subscription */
export interface SubscriptionState {
  current: SubscriptionTerm | null;
  scheduled: SubscriptionTerm | null;
}

export interface ChangePlanRequest {
  plan_code: string;
}

// ---------------------------------------------------------------- signup

/** POST /v1/tenants */
export interface SignupRequest {
  tenant_name: string;
  admin_email: string;
  admin_password: string;
  plan_code?: string;
}

export interface SignupResponse {
  tenant: Tenant;
  admin: User;
  subscription: SubscriptionTerm;
  resources: MeteredResource[];
}

// ---------------------------------------------------------------- resources

export interface MeteredResource {
  id: Uuid;
  name: string;
  unit_label: string;
  status: ResourceStatus;
  created_at: Instant;
}

export interface CreateResourceRequest {
  name: string;
  unit_label: string;
}

export interface UpdateResourceRequest {
  name?: string;
  unit_label?: string;
  status?: ResourceStatus;
}

// ---------------------------------------------------------------- users

export interface CreateUserRequest {
  email: string;
  password: string;
  role: Role;
}

export interface UpdateUserRequest {
  role?: Role;
  status?: UserStatus;
}

// ---------------------------------------------------------------- API keys

export interface ApiKey {
  id: Uuid;
  name: string;
  /** First 12 characters of the raw key. */
  prefix: string;
  created_at: Instant;
  last_used_at: Instant | null;
  revoked_at: Instant | null;
}

/** POST /v1/api-keys → 201: the only response that carries the raw key. */
export interface CreatedApiKey extends ApiKey {
  key: string;
}

export interface CreateApiKeyRequest {
  name: string;
}
