import { createContext, useContext } from 'react';

export const DirtyFormContext = createContext<(dirty: boolean) => void>(() => undefined);

/** 业务回执到达后才通知弹层；DOM submit 不能证明提交已受理。 */
export const CommandDialogContext = createContext({
  submitted: () => undefined as void,
  setBusy: (_busy: boolean) => undefined as void,
  close: () => undefined as void,
});

export function useCommandDialog() {
  return useContext(CommandDialogContext);
}

export function useMarkDirty(): (dirty: boolean) => void {
  return useContext(DirtyFormContext);
}
