import { Empty } from "antd";

export function EmptyState({ title, detail }: { title: string; detail?: string }) {
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={<span>{title}{detail ? ` · ${detail}` : ""}</span>} />;
}
