export function BrandCard({ compact = false }: { compact?: boolean }) {
  if (compact) return <div className="brand-card compact" aria-label="AIV2Ray"><span className="brand-card-icon" aria-hidden="true">V</span><span className="brand-card-name">AIV2Ray</span></div>;

  return <div className="brand-card" aria-label="AI V2Ray Client">
    <span className="brand-card-screw brand-card-screw-left" aria-hidden="true" />
    <span className="brand-card-screw brand-card-screw-right" aria-hidden="true" />
    <span className="brand-card-ecg" aria-hidden="true">
      <svg viewBox="0 0 1000 140" preserveAspectRatio="none">
        <path d="M0 70 C85 70 92 70 112 70 C132 70 138 22 154 22 C170 22 176 118 192 118 C208 118 214 70 234 70 H1000" />
        <circle cx="0" cy="70" r="9" />
      </svg>
    </span>
    <span className="brand-card-title">AI V2RAY CLIENT</span>
    <span className="brand-card-subtitle">PRECISION TUNNEL ENGINE <b>■</b> XRAY CORE V1.8.16</span>
    <span className="brand-card-footer"><strong>DEVELOPED BY VICTOR GEEK</strong><span>TILT X: 0° Y: 0°</span></span>
  </div>;
}
