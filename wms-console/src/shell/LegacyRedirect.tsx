import { Navigate } from "react-router-dom";
import { lastWarehouse } from "./warehouseSession";

export function LegacyRedirect({ leaf }: { leaf: string }) {
  const warehouseId = lastWarehouse();
  if (!warehouseId) {
    return <Navigate to="/" replace />;
  }
  const suffix = leaf ? `/${leaf}` : "";
  return <Navigate to={`/w/${encodeURIComponent(warehouseId)}${suffix}`} replace />;
}

export function RootRedirect() {
  const warehouseId = lastWarehouse();
  return <Navigate to={warehouseId ? `/w/${encodeURIComponent(warehouseId)}` : "/w/_"} replace />;
}

export function PdaLegacyRedirect() {
  const warehouseId = lastWarehouse();
  if (!warehouseId) {
    return <Navigate to="/" replace />;
  }
  return <Navigate to={`/pda/${encodeURIComponent(warehouseId)}/receive`} replace />;
}
