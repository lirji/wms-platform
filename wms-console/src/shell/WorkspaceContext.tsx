import { createContext, useContext } from "react";

export type WorkspaceValue = {
  token?: string;
  warehouseId: string;
};

const WorkspaceContext = createContext<WorkspaceValue>({ warehouseId: "" });

export const WorkspaceProvider = WorkspaceContext.Provider;

export function useWorkspace(): WorkspaceValue {
  return useContext(WorkspaceContext);
}
