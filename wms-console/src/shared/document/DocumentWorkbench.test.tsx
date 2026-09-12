import { describe, expect, it } from "vitest";
import { CommandCol, collectCommandTabs } from "./DocumentWorkbench";

describe("collectCommandTabs", () => {
  it("keeps only CommandCol entries the token can attempt", () => {
    const tabs = collectCommandTabs(
      <>
        <CommandCol title="收货" requireScope="inbound.receive"><span>receive</span></CommandCol>
        <CommandCol title="质检" requireScope="quality.inspect"><span>inspect</span></CommandCol>
      </>,
      ["inbound.receive"]
    );
    expect(tabs.map((tab) => tab.label)).toEqual(["收货"]);
  });

  it("hides every command when the token has no job scopes", () => {
    expect(collectCommandTabs(
      <>
        <CommandCol title="收货" requireScope="inbound.receive"><span>receive</span></CommandCol>
      </>,
      []
    )).toEqual([]);
  });
});
