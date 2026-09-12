const KEY = "wms.warehouseId";

export function rememberWarehouse(id: string) {
  sessionStorage.setItem(KEY, id);
}

export function lastWarehouse(): string {
  return sessionStorage.getItem(KEY) ?? "";
}
