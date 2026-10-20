import { Sparkles } from 'lucide-react';

export function BrandCard({ compact = false }: { compact?: boolean }) {
  if (compact) return <div className="brand-card compact" aria-label="AIV2Ray"><span className="brand-card-icon"><Sparkles aria-hidden="true" /></span><span className="brand-card-name">AIV2Ray</span></div>;

  return <div className="brand-card" aria-label="AI V2Ray Client">
    <span className="brand-card-screw brand-card-screw-left" aria-hidden="true" />
    <span className="brand-card-screw brand-card-screw-right" aria-hidden="true" />
    <span className="brand-card-ecg" aria-hidden="true"><span /></span>
    <span className="brand-card-title">AI V2RAY CLIENT</span>
    <span className="brand-card-subtitle">PRECISION TUNNEL ENGINE <b>■</b> XRAY CORE V1.8.16</span>
    <span className="brand-card-footer"><strong>DEVELOPED BY VICTOR GEEK</strong><span>TILT X: 0° Y: 0°</span></span>
  </div>;
}
