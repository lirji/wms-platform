/** 与 OpenAPI CursorPage / ResourceEnvelope 对齐，数量保持字符串。 */
export type Quantity = string;

export type CursorPage<T extends object = ResourceEnvelope> = {
  items: T[];
  nextCursor?: string;
  limit: number;
  asOf?: string;
  lagSeconds?: number;
};

export type ResourceEnvelope = {
  id: string;
  version?: number | string;
  status?: string;
  physicalStatus?: string;
  stockSyncStatus?: string;
  operationId?: string;
  [key: string]: unknown;
};

export type ContractError = {
  code?: string;
  message?: string;
  retryable?: boolean;
};
