import { Form, Input } from "antd";
import { SERIAL_MAX } from "./serialIds";

export function SerialIdsField({
  name,
  label,
  extra,
  required
}: {
  name: string;
  label: string;
  extra?: string;
  required?: boolean;
}) {
  return (
    <Form.Item
      label={label}
      name={name}
      extra={extra || `每行一个，最多 ${SERIAL_MAX}。普通 SKU 留空。数量必须等于身份数。`}
      rules={required ? [{ required: true, message: "请填写身份清单" }] : undefined}
    >
      <Input.TextArea rows={4} placeholder={"SN-001\nSN-002"} />
    </Form.Item>
  );
}
