import { Button, Card, Input, Space } from "antd";

/** 列表查询区：已有筛选字段 + 查询 / 重置，不发明新过滤条件。 */
export function WmsSearchForm({
  value,
  onChange,
  onSearch,
  onReset,
  placeholder = "筛选已返回字段"
}: {
  value: string;
  onChange: (value: string) => void;
  onSearch: () => void;
  onReset: () => void;
  placeholder?: string;
}) {
  return (
    <Card size="small" className="wms-search-form">
      <div className="wms-search-form-row">
        <Input
          allowClear
          value={value}
          placeholder={placeholder}
          onChange={(event) => onChange(event.target.value)}
          onPressEnter={onSearch}
        />
        <Space className="wms-search-form-actions">
          <Button type="primary" onClick={onSearch}>查询</Button>
          <Button onClick={onReset}>重置</Button>
        </Space>
      </div>
    </Card>
  );
}
