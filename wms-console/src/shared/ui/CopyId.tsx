import { App, Button, Typography } from "antd";

export function CopyId({ value, kind, hideValue }: { value: string; kind: string; hideValue?: boolean }) {
  const { message } = App.useApp();
  if (!value) {
    return <span>—</span>;
  }
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      message.success(`已复制 ${kind}`);
    } catch {
      message.error("无法复制，请手动选择");
    }
  }
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 6, maxWidth: "100%" }}>
      {hideValue ? null : (
        <Typography.Text ellipsis={{ tooltip: value }} style={{ maxWidth: 148, fontVariantNumeric: "tabular-nums" }}>
          {value}
        </Typography.Text>
      )}
      <Button type="text" size="small" aria-label={`复制 ${kind}`} onClick={() => void copy()}>
        复制
      </Button>
    </span>
  );
}
