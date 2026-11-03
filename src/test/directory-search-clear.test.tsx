import { describe, expect, it } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { useState } from "react";
import { X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { translate } from "@/lib/translations";

function SearchHarness({ initialValue = "" }: { initialValue?: string }) {
  const [search, setSearch] = useState(initialValue);
  const [limit, setLimit] = useState(30);
  const t = (k: string) => translate("en", k);

  return (
    <div className="directory-search-wrap">
      <input
        className="engraved-input directory-search-input"
        aria-label={t("Search public servers")}
        placeholder={t("Search name, protocol or location")}
        value={search}
        onChange={(e) => {
          setSearch(e.target.value);
          setLimit(30);
        }}
      />
      {search && (
        <Button
          variant="ghost"
          size="icon"
          className="directory-search-clear-btn"
          aria-label={t("Clear search")}
          onClick={() => {
            setSearch("");
            setLimit(30);
          }}
        >
          <X size={14} />
        </Button>
      )}
      <span data-testid="limit-val">{limit}</span>
    </div>
  );
}

describe("Public directory search clear button micro-UX", () => {
  it("does not render clear button when search input is empty", () => {
    render(<SearchHarness initialValue="" />);
    expect(screen.queryByRole("button", { name: "Clear search" })).toBeNull();
  });

  it("renders accessible clear button when text is present and clears search on click", () => {
    render(<SearchHarness initialValue="Singapore" />);
    const input = screen.getByRole("textbox", {
      name: "Search public servers",
    }) as HTMLInputElement;
    expect(input.value).toBe("Singapore");

    const clearBtn = screen.getByRole("button", { name: "Clear search" });
    expect(clearBtn).toBeDefined();

    fireEvent.click(clearBtn);

    expect(input.value).toBe("");
    expect(screen.queryByRole("button", { name: "Clear search" })).toBeNull();
  });

  it("provides Myanmar translation for Clear search label", () => {
    expect(translate("my", "Clear search")).toBe("ရှာဖွေမှု ရှင်းလင်းရန်");
  });
});
