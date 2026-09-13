import { ReactNode, useState } from "react";
import { Button, Modal } from "antd";
import { hasScope } from "../../auth/can";
import { useWorkspace } from "../../shell/WorkspaceContext";
import { DirtyFormContext } from "./dirtyForm";

/** 命令入口仍叫 Drawer，实际是居中弹层，避免右侧挤占密表。 */
export function CommandDrawer({
  triggerLabel,
  title,
  hint,
  disabled,
  width = 560,
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
      content: "弹层里的表单已改，关闭后不会保存。幂等键不会更换。",
      okText: "放弃",
      cancelText: "继续编辑",
      centered: true,
      onOk: () => {
        setDirty(false);
        setOpen(false);
      }
    });
  }

  return (
    <>
      <Button className="list-action" type={triggerType} disabled={disabled} onClick={() => setOpen(true)}>
        {triggerLabel}
      </Button>
      <Modal
        title={title}
        open={open}
        onCancel={requestClose}
        footer={null}
        centered
        width={width}
        destroyOnHidden={false}
        styles={{ body: { maxHeight: "70vh", overflow: "auto" } }}
      >
        {hint ? <p className="command-dialog-hint">{hint}</p> : null}
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
      </Modal>
    </>
  );
}
