import type { ReactNode } from "react";

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
    <div className="page-head">
      <div>
        {eyebrow ? <p className="page-eyebrow">{eyebrow}</p> : null}
        <h1>{title}</h1>
        <p className="page-sub">{sub}</p>
      </div>
      {extra ? <div className="page-head-extra">{extra}</div> : null}
    </div>
  );
}
