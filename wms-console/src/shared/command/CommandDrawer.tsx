import { ReactNode, useState } from 'react';
import { Button, Modal } from 'antd';
import { canOperation } from '../../auth/can';
import { useWorkspace } from '../../shell/WorkspaceContext';
import { CommandDialogContext, DirtyFormContext } from './dirtyForm';

/** 命令入口仍叫 Drawer，实际是居中弹层，避免右侧挤占密表。 */
export function CommandDrawer({
  triggerLabel,
  title,
  hint,
  disabled,
  width = 560,
  requireScope,
  resourceType,
  triggerType = 'primary',
  onSubmitted,
  children,
}: {
  triggerLabel: string;
  title: string;
  hint?: string;
  disabled?: boolean;
  width?: number;
  requireScope?: string | string[];
  resourceType?: 'warehouse' | 'enterprise';
  triggerType?: 'primary' | 'default';
  onSubmitted?: () => void;
  children: ReactNode;
}) {
  const workspace = useWorkspace();
  const [open, setOpen] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [contentVersion, setContentVersion] = useState(0);
  const [modal, modalContext] = Modal.useModal();
  if (!canOperation(workspace, requireScope, resourceType)) {
    return null;
  }

  function requestClose() {
    if (busy) {
      return;
    }
    if (!dirty) {
      setOpen(false);
      return;
    }
    modal.confirm({
      title: '放弃未提交的内容？',
      content: '已填写的内容尚未成功提交。放弃后会清空本次输入。',
      okText: '放弃',
      cancelText: '继续编辑',
      centered: true,
      onOk: () => {
        setDirty(false);
        setOpen(false);
        setContentVersion((current) => current + 1);
      },
    });
  }

  return (
    <>
      {modalContext}
      <Button
        className="list-action"
        type={triggerType}
        disabled={disabled}
        onClick={() => setOpen(true)}
      >
        {triggerLabel}
      </Button>
      <Modal
        title={title}
        open={open}
        onCancel={requestClose}
        closable={!busy}
        keyboard={!busy}
        mask={{ closable: !busy }}
        footer={null}
        centered
        width={width}
        destroyOnHidden={false}
        styles={{ body: { maxHeight: '70vh', overflow: 'auto' } }}
      >
        {hint ? <p className="command-dialog-hint">{hint}</p> : null}
        {busy ? <p role="status">正在提交，请等待服务端结果后再关闭。</p> : null}
        <DirtyFormContext.Provider value={setDirty}>
          <CommandDialogContext.Provider
            value={{
              submitted: () => onSubmitted?.(),
              setBusy,
              close: requestClose,
            }}
          >
            <div key={contentVersion}>{children}</div>
          </CommandDialogContext.Provider>
        </DirtyFormContext.Provider>
      </Modal>
    </>
  );
}
