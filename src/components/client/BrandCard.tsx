const brandMarkSource = "/aiv2ray-mark.png";
const brandWordmarkSource = "/aiv2ray-wordmark.png";

export function BrandCard({ compact = false }: { compact?: boolean }) {
  return (
    <span className={`brand-card${compact ? " compact" : ""}`}>
      <span className="brand-card-logo" role="img" aria-label="aiV2Ray shield and wordmark">
        <img
          className="brand-card-mark"
          src={brandMarkSource}
          alt=""
          width={313}
          height={265}
          decoding="async"
          fetchPriority={compact ? undefined : "high"}
          draggable={false}
        />
        <img
          className="brand-card-wordmark"
          src={brandWordmarkSource}
          alt=""
          width={1218}
          height={265}
          decoding="async"
          draggable={false}
        />
      </span>
    </span>
  );
}
