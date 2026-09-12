import { createContext, useContext } from "react";

export const DirtyFormContext = createContext<(dirty: boolean) => void>(() => undefined);

export function useMarkDirty(): (dirty: boolean) => void {
  return useContext(DirtyFormContext);
}
