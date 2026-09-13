import type { ReactNode } from "react";

/** 表顶工具条：左标题与条数，右操作。 */
export function WmsToolbar({
  title,
  count,
  extra
}: {
  title: string;
  count?: string;
  extra?: ReactNode;
}) {
  return (
    <div className="wms-toolbar">
      <div className="wms-toolbar-start">
        <span className="wms-toolbar-title">{title}</span>
        {count != null ? <span className="wms-toolbar-count">共{count}条</span> : null}
      </div>
      {extra ? <div className="wms-toolbar-extra">{extra}</div> : null}
    </div>
  );
}
