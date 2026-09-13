import { Form, Input } from "antd";
import { SERIAL_MAX } from "./serialIds";

export function SerialExecutionField({
  name = "serialExecution",
  label = "出库身份",
  extra,
  required
}: {
  name?: string;
  label?: string;
  extra?: string;
  required?: boolean;
}) {
  return (
    <Form.Item
      label={label}
      name={name}
      extra={extra || `每行「序列号 当前ownerEpoch」，最多 ${SERIAL_MAX}。epoch 必须来自仓内查询，不能默认 1。普通 SKU 留空。`}
      rules={required ? [{ required: true, message: "请填写序列号与当前 ownerEpoch" }] : undefined}
    >
      <Input.TextArea rows={4} placeholder={"SN-001 1\nSN-002 1"} />
    </Form.Item>
  );
}
