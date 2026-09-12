import { ReactNode, useState } from "react";
import { Button, Drawer, Modal } from "antd";
import { hasScope } from "../../auth/can";
import { useWorkspace } from "../../shell/WorkspaceContext";
import { DirtyFormContext } from "./dirtyForm";

export function CommandDrawer({
  triggerLabel,
  title,
  hint,
  disabled,
  width = 440,
  requireScope,
  triggerType = "primary",
  onSubmitted,
  children
}: {
  triggerLabel: string;
  title: string;
  hint?: string;
  disabled?: boolean;
  width?: number;
  requireScope?: string | string[];
  triggerType?: "primary" | "default";
  onSubmitted?: () => void;
  children: ReactNode;
}) {
  const { scopes } = useWorkspace();
  const [open, setOpen] = useState(false);
  const [dirty, setDirty] = useState(false);
  if (!hasScope(scopes, requireScope)) {
    return null;
  }

  function requestClose() {
    if (!dirty) {
      setOpen(false);
      return;
    }
    Modal.confirm({
      title: "放弃未提交的内容？",
      content: "抽屉里的表单已改，关闭后不会保存。幂等键不会更换。",
      okText: "放弃",
      cancelText: "继续编辑",
      onOk: () => {
        setDirty(false);
        setOpen(false);
      }
    });
  }

  return (
    <>
      <Button type={triggerType} disabled={disabled} onClick={() => setOpen(true)}>
        {triggerLabel}
      </Button>
      <Drawer
        title={title}
        size={width}
        open={open}
        onClose={requestClose}
        destroyOnHidden={false}
      >
        {hint ? <p style={{ color: "rgba(0,0,0,0.45)", marginTop: 0 }}>{hint}</p> : null}
        <DirtyFormContext.Provider value={setDirty}>
          <div
            onSubmitCapture={() => {
              setDirty(false);
              onSubmitted?.();
            }}
          >
            {children}
          </div>
        </DirtyFormContext.Provider>
      </Drawer>
    </>
  );
}
