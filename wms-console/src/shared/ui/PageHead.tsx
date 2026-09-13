import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { Flex, Typography } from "antd";
import { StatusChip } from "./StatusChip";

export function PageHead({
  eyebrow,
  breadcrumb,
  title,
  status,
  sub,
  extra
}: {
  eyebrow?: string;
  breadcrumb?: { label: string; to?: string }[];
  title: string;
  status?: string;
  sub: string;
  extra?: ReactNode;
}) {
  return (
    <Flex className="page-head" justify="space-between" align="flex-start" gap={16} wrap="wrap">
      <div>
        {breadcrumb?.length ? (
          <nav className="wms-breadcrumb" aria-label="面包屑">
            {breadcrumb.map((item, index) => (
              <span key={`${item.label}-${index}`}>
                {index > 0 ? " / " : null}
                {item.to ? <Link to={item.to}>{item.label}</Link> : item.label}
              </span>
            ))}
          </nav>
        ) : null}
        {eyebrow && !breadcrumb?.length ? <Typography.Text type="secondary">{eyebrow}</Typography.Text> : null}
        <div className="page-head-title">
          <Typography.Title
            id="page-title"
            level={1}
            tabIndex={-1}
            style={{ margin: eyebrow || breadcrumb?.length ? "4px 0 0" : 0, fontSize: 20, fontWeight: 600 }}
          >
            {title}
          </Typography.Title>
          {status ? <StatusChip value={status} /> : null}
        </div>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0, maxWidth: 640, lineHeight: 1.55 }}>{sub}</Typography.Paragraph>
      </div>
      {extra ? <div>{extra}</div> : null}
    </Flex>
  );
}
