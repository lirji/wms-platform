import type { ReactNode } from "react";
import { Flex, Typography } from "antd";

export function PageHead({
  eyebrow,
  title,
  sub,
  extra
}: {
  eyebrow?: string;
  title: string;
  sub: string;
  extra?: ReactNode;
}) {
  return (
    <Flex className="page-head" justify="space-between" align="flex-start" gap={16} wrap="wrap">
      <div>
        {eyebrow ? <Typography.Text type="secondary">{eyebrow}</Typography.Text> : null}
        <Typography.Title level={3} style={{ margin: eyebrow ? "4px 0 0" : 0 }}>{title}</Typography.Title>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0, maxWidth: 640 }}>{sub}</Typography.Paragraph>
      </div>
      {extra ? <div>{extra}</div> : null}
    </Flex>
  );
}
