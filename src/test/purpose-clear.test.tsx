import { describe, expect, it } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { useState } from "react";
import { Sparkles, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { translate } from "@/lib/translations";

function PurposeInputHarness({ initialValue = "" }: { initialValue?: string }) {
  const [purpose, setPurpose] = useState(initialValue);
  const t = (k: string) => translate("en", k);

  return (
    <div>
      <label htmlFor="purpose">WHAT DO YOU WANT TO USE THE VPN FOR?</label>
      <div className="purpose-input-wrap">
        <Sparkles size={16} />
        <input
          id="purpose"
          className="engraved-input purpose-input"
          placeholder="Type a platform..."
          value={purpose}
          onChange={(e) => setPurpose(e.target.value)}
        />
        {purpose && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="purpose-clear-btn"
            aria-label={t("Clear purpose")}
            onClick={() => setPurpose("")}
          >
            <X size={15} />
          </Button>
        )}
      </div>
    </div>
  );
}

describe("Purpose input clear button micro-UX", () => {
  it("does not render clear button when input is empty", () => {
    render(<PurposeInputHarness initialValue="" />);
    expect(screen.queryByRole("button", { name: "Clear purpose" })).toBeNull();
  });

  it("renders accessible clear button when input has text and clears on click", () => {
    render(<PurposeInputHarness initialValue="PUBG Mobile" />);
    const input = screen.getByRole("textbox") as HTMLInputElement;
    expect(input.value).toBe("PUBG Mobile");

    const clearBtn = screen.getByRole("button", { name: "Clear purpose" });
    expect(clearBtn).toBeDefined();

    fireEvent.click(clearBtn);

    expect(input.value).toBe("");
    expect(screen.queryByRole("button", { name: "Clear purpose" })).toBeNull();
  });

  it("provides Myanmar translation for Clear purpose label", () => {
    expect(translate("my", "Clear purpose")).toBe("ရည်ရွယ်ချက်ကို ရှင်းရန်");
  });
});
