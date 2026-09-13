import { Button, Space } from "antd";

/** 游标翻页放表底右侧。不编造页码。下一页是本区唯一 Primary。 */
export function ListPager({
  prevLabel = "首页",
  nextLabel = "下一页",
  prevDisabled,
  nextDisabled,
  countLabel,
  onPrev,
  onNext
}: {
  prevLabel?: string;
  nextLabel?: string;
  prevDisabled?: boolean;
  nextDisabled?: boolean;
  countLabel?: string;
  onPrev?: () => void;
  onNext?: () => void;
}) {
  return (
    <div className="list-pager">
      {countLabel ? <span className="list-pager-count">{countLabel}</span> : null}
      <Space>
        <Button type="default" autoInsertSpace={false} disabled={prevDisabled} onClick={onPrev}>
          {prevLabel}
        </Button>
        <Button type={nextDisabled ? "default" : "primary"} autoInsertSpace={false} disabled={nextDisabled} onClick={onNext}>
          {nextLabel}
        </Button>
      </Space>
    </div>
  );
}
