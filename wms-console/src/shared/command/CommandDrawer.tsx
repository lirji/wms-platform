import { ReactNode, useState } from "react";
import { Button, Drawer } from "antd";

export function CommandDrawer({
  triggerLabel,
  title,
  hint,
  disabled,
  width = 440,
  onSubmitted,
  children
}: {
  triggerLabel: string;
  title: string;
  hint?: string;
  disabled?: boolean;
  width?: number;
  onSubmitted?: () => void;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button type="primary" disabled={disabled} onClick={() => setOpen(true)}>
        {triggerLabel}
      </Button>
      <Drawer
        title={title}
        size={width}
        open={open}
        onClose={() => setOpen(false)}
        destroyOnHidden={false}
        forceRender
      >
        {hint ? <p style={{ color: "rgba(0,0,0,0.45)", marginTop: 0 }}>{hint}</p> : null}
        <div onSubmitCapture={() => onSubmitted?.()}>
          {children}
        </div>
      </Drawer>
    </>
  );
}
