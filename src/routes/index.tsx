import { useLanguage, LanguageSelector } from '@/components/client/LanguageProvider';
import { createFileRoute } from '@tanstack/react-router';
import { useServerFn } from '@tanstack/react-start';
import { useEffect, useState } from 'react';
import { Zap, Power, Settings, Info, Sparkles, ArrowUpRight, ChevronRight, ShieldCheck, LockKeyhole, ArrowDown, ArrowUp, Radio, Eye, EyeOff, Crosshair, Shield, KeyRound, Check, RefreshCw } from 'lucide-react';
import { siFacebook, siInstagram, siYoutube, siPubg } from 'simple-icons';
import { Button } from '@/components/ui/button';
import { Gauge } from '@/components/client/Gauge';
import { Flag } from '@/components/client/Flag';
import { ServerManager } from '@/components/client/ServerManager';
import { SettingsPanel } from '@/components/client/SettingsPanel';
import { SettingsDashboard } from '@/components/client/SettingsDashboard';
import { InfoPanel } from '@/components/client/InfoPanel';
import { Odometer } from '@/components/client/Odometer';
import { defaultSettings, parseUri, type ServerNode } from '@/lib/v2ray';
import { acceptNativeTraffic, connectNativeVpn, disconnectNativeVpn, getNativeVpnStatus, isNativeVpnAvailable, openNativeVpnSettings, type NativeVpnStatus } from '@/lib/native-vpn';
import { recommendServer } from '@/lib/recommend.functions';
import steelTexture from '@/assets/brushed-steel.jpg';
import { WelcomeScreens } from '@/components/client/WelcomeScreens';
import { welcomeStorageKey } from '@/lib/welcome';
import { BrandCard } from '@/components/client/BrandCard';

export const Route = createFileRoute('/')({
  head: () => ({ meta: [{ title: 'AIV2Ray — AI V2Ray Connection Control' }, { name: 'description', content: 'AIV2Ray: browse public V2Ray servers, import configurations locally, get Gemini recommendations, and manage the Android VPN service.' }, { property: 'og:title', content: 'AIV2Ray — AI V2Ray Connection Control' }, { property: 'og:description', content: 'AIV2Ray. Local configuration, Gemini recommendations, and Android VPN controls.' }, { property: 'og:type', content: 'website' }, { property: 'og:image', content: 'https://hebbkx1anhila5yf.public.blob.vercel-storage.com/file_00000000aafc81f89b7df4121aa4df24-HMBXCE5Z5UQDiRSDtdNHZP41CekAzA.png' }, { name: 'twitter:card', content: 'summary_large_image' }, { name: 'twitter:image', content: 'https://hebbkx1anhila5yf.public.blob.vercel-storage.com/file_00000000aafc81f89b7df4121aa4df24-HMBXCE5Z5UQDiRSDtdNHZP41CekAzA.png' }] }),
  component: Index,
});
function BrandIcon({ path, brand }: { path: string; brand: string }) {
  return <svg viewBox="0 0 24 24" className={`preset-brand preset-brand-${brand}`} aria-hidden="true"><path d={path} /></svg>;
}
const quickActions = [
  { label: 'PUBG Mobile', icon: () => <BrandIcon path={siPubg.path} brand="pubg" /> },
  { label: 'Facebook & IG', icon: () => <span className="preset-brand-pair"><BrandIcon path={siFacebook.path} brand="facebook" /><BrandIcon path={siInstagram.path} brand="instagram" /></span> },
  { label: 'YouTube 4K', icon: () => <BrandIcon path={siYoutube.path} brand="youtube" /> },
  { label: 'Low Latency Gaming', icon: Crosshair },
  { label: 'Anti-Censorship', icon: Shield },
];
function Index() {
  const { t } = useLanguage();
  const [welcome, setWelcome] = useState(false);
  useEffect(() => { try { setWelcome(localStorage.getItem(welcomeStorageKey) !== 'done'); } catch { setWelcome(true); } }, []);
  function finishWelcome() { setWelcome(false); try { localStorage.setItem(welcomeStorageKey, 'done'); } catch { /* Storage may be disabled. */ } }
  const [tab, setTab] = useState<'connection' | 'settings' | 'info'>('connection');
  const [nodes, setNodes] = useState<ServerNode[]>([]); const [node, setNode] = useState<ServerNode | null>(null);
  const [settings, setSettings] = useState(defaultSettings); const [manager, setManager] = useState(false); const [subscription, setSubscription] = useState(false);
  const [nativeAvailable, setNativeAvailable] = useState(false);
  const [status, setStatus] = useState<'offline' | 'connecting' | 'connected' | 'error'>('offline');
  const [nativeMetrics, setNativeMetrics] = useState<NativeVpnStatus | null>(null);
  const [purpose, setPurpose] = useState(''); const [apiKey, setApiKey] = useState(''); const [showKey, setShowKey] = useState(false); const [aiBusy, setAiBusy] = useState(false); const [recommendation, setRecommendation] = useState<{ serverId: string; reason: string } | null>(null); const [error, setError] = useState('');
  const recommend = useServerFn(recommendServer); const connected = status === 'connected';
  useEffect(() => {
    const available = isNativeVpnAvailable();
    setNativeAvailable(available);
    if (!available) return;
    let active = true;
    let timer = 0;
    const poll = async () => {
      try {
        const current = await getNativeVpnStatus();
        if (!active) return;
        if (current.state === 'connected' && !acceptNativeTraffic(current, current.sessionId, true)) {
          throw new Error('Android VPN returned invalid session telemetry.');
        }
        setNativeMetrics(current);
        setStatus(current.state === 'disconnected' ? 'offline' : current.state);
        if (current.state === 'error') setError(current.error);
      } catch (cause) {
        if (!active) return;
        setStatus('error');
        setError(cause instanceof Error ? cause.message : 'Unable to read Android VPN status.');
      } finally {
        if (active) timer = window.setTimeout(() => void poll(), 1000);
      }
    };
    void poll();
    return () => { active = false; window.clearTimeout(timer); };
  }, []);
  const elapsed = nativeMetrics?.elapsedSeconds ?? 0;
  const downloadMbps = (nativeMetrics?.downloadBytesPerSecond ?? 0) * 8 / 1_000_000;
  const uploadMbps = (nativeMetrics?.uploadBytesPerSecond ?? 0) * 8 / 1_000_000;
  const telemetry = {
    ping: nativeMetrics?.latencyMs ?? 0,
    speed: downloadMbps + uploadMbps,
    down: downloadMbps,
    up: uploadMbps,
    received: (nativeMetrics?.receivedBytes ?? 0) / 1_000_000,
    sent: (nativeMetrics?.sentBytes ?? 0) / 1_000_000,
  };
  async function stopNativeVpn() {
    try {
      await disconnectNativeVpn();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Unable to stop the Android VPN service.');
    }
  }
  function selectNode(selected: ServerNode) {
    if (status === 'connected' || status === 'connecting') void stopNativeVpn();
    setNode(selected);
  }
  function addNodes(items: ServerNode[]) { setNodes(old => [...old, ...items.filter(n => !old.some(o => o.id === n.id))]); if (items.length > 1) setSubscription(true); }
  async function askAi() {
    setError(''); setRecommendation(null);
    if (!purpose.trim()) { setError('Enter a purpose or choose a quick action.'); return; }
    if (/^(vmess|vless|trojan|ss):\/\//.test(purpose.trim())) { try { const imported = parseUri(purpose); addNodes([imported]); selectNode(imported); setRecommendation({ serverId: imported.id, reason: 'Configuration parsed locally and selected. Your credentials were not sent to Gemini.' }); } catch (e) { setError(e instanceof Error ? e.message : t('Invalid link.')); } return; }
    const available = nodes.filter(n => settings.protocols.includes(n.protocol));
    if (!available.length) { setError('Import a server from the directory or add your configuration first.'); return; }
    setAiBusy(true);
    try { const result = await recommend({ data: { purpose, apiKey: apiKey || undefined, servers: available.slice(0, 30).map(n => ({ id: n.id.slice(0, 100), name: n.name, protocol: n.protocol, ping: n.ping })) } }); if ('error' in result) setError(result.error || 'Recommendation unavailable.'); else setRecommendation(result); }
    catch { setError('Unable to reach Gemini. Please try again.'); } finally { setAiBusy(false); }
  }
  async function power() {
    setError('');
    if (!nativeAvailable) { setError('A real VPN connection is available only in the Android app.'); return; }
    if (!node) { setManager(true); return; }
    if (status === 'connected' || status === 'connecting' || status === 'error') { await stopNativeVpn(); return; }
    if (node.demo) { setError('This is a demonstration server and cannot establish a real VPN connection. Import a working server configuration first.'); return; }
    if (!settings.protocols.includes(node.protocol)) { setError('The selected server’s protocol is disabled. Enable it in CONFIG.'); return; }
    setStatus('connecting');
    try { await connectNativeVpn(node, settings); }
    catch (cause) { setStatus('error'); setError(cause instanceof Error ? cause.message : 'Unable to start the Android VPN service.'); }
  }
  async function showAndroidVpnSettings() {
    try { await openNativeVpnSettings(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Unable to open Android VPN settings.'); }
  }
  const duration = `${Math.floor(elapsed / 3600).toString().padStart(2, '0')}:${Math.floor(elapsed / 60 % 60).toString().padStart(2, '0')}:${(elapsed % 60).toString().padStart(2, '0')}`;
  return <main className="client-stage"><img className="ambient-metal" src={steelTexture} width={1024} height={1024} alt="" aria-hidden="true" /><div className="outer-brand"><span>AIV2Ray</span><span className="outer-brand-rule" /><span className="micro-label">AI V2RAY CONNECTION SYSTEM</span></div>
    <div className="client-chassis"><img className="chassis-texture" src={steelTexture} width={1024} height={1024} alt="" aria-hidden="true" /><span className="screw screw-tl" /><span className="screw screw-tr" /><span className="screw screw-bl" /><span className="screw screw-br" />
      <header className="client-header"><div className="client-identity"><h1 className="brand-heading"><BrandCard /></h1></div><div className={`status-badge ${status}`}><span className="status-led" />{status === 'connected' ? t('CONNECTED') : status === 'connecting' ? t('CONNECTING') : status === 'error' ? t('VPN ERROR') : t('OFFLINE')}</div></header>
      <nav className="instrument-nav" aria-label="Client sections">{([{ id: 'connection', label: 'CONNECTION', icon: Zap }, { id: 'settings', label: 'CONFIG', icon: Settings }, { id: 'info', label: 'INFO', icon: Info }] as const).map(item => <Button key={item.id} variant="nav" className={tab === item.id ? 'nav-active' : ''} onClick={() => setTab(item.id)} aria-current={tab === item.id ? 'page' : undefined}><item.icon size={16} /><span>{t(item.label)}</span>{tab === item.id && <span className="tab-indicator" />}</Button>)}</nav>
      {tab === 'connection' ? <div className="connection-content">
      <div className="connection-main">
        <section className="steel-tray ai-tray"><div className="ai-heading"><div className="ai-badge"><Sparkles size={15} /><span>AI <b>·</b>{t("Ask Gemini")}</span></div><span className="micro-label">A SMARTER WAY TO CONNECT</span></div><label htmlFor="purpose" className="field-label">{t("WHAT DO YOU WANT TO USE THE VPN FOR?")}</label><div className="purpose-input-wrap"><Sparkles size={16} /><input id="purpose" className="engraved-input purpose-input" placeholder={t("Type a platform, describe your purpose, or paste a config link\u2026")} value={purpose} onChange={e => setPurpose(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') void askAi(); }} /></div><div className="quick-actions">{quickActions.map(item => <Button variant="keycap" size="sm" className={purpose === item.label ? 'chip-active' : ''} key={item.label} aria-pressed={purpose === item.label} onClick={() => setPurpose(item.label)}><item.icon />{t(item.label)}</Button>)}</div><div className="ai-bottom"><div className="api-field"><KeyRound size={14} /><input aria-label={t("Gemini API key")} type={showKey ? 'text' : 'password'} placeholder={t("Gemini API key (optional)")} value={apiKey} onChange={e => setApiKey(e.target.value)} autoComplete="off" /><Button variant="ghost" size="icon" aria-label={showKey ? t('Hide API key') : t('Show API key')} title={showKey ? t('Hide API key') : t('Show API key')} onClick={() => setShowKey(!showKey)}>{showKey ? <EyeOff /> : <Eye />}</Button></div><Button variant="copper" onClick={askAi} disabled={aiBusy}>{aiBusy ? <RefreshCw className="animate-spin" /> : <Sparkles />}{aiBusy ? t('ANALYZING…') : t('FIND MY CONFIG')}<ArrowUpRight size={14} /></Button></div><div className="ai-security"><LockKeyhole size={11} /><span>{t("Keys stay in this session. Config links are parsed on your device.")}</span></div>
        {error && <p className="ai-error" role="alert">{t(error)}</p>}{recommendation && <div className="recommendation" role="status"><Sparkles size={18} /><p>{t(recommendation.reason)}</p>{recommendation.serverId !== node?.id && <Button size="sm" className="copper-button" onClick={() => { const found = nodes.find(n => n.id.slice(0, 100) === recommendation.serverId); if (found) selectNode(found); }}><Check />{t("USE SERVER")}</Button>}</div>}</section>
        <section className="server-summary">
          <div className="section-heading"><span><Radio size={14} />{t("ACTIVE SERVER")}</span><Button variant="ghost" className="manage-link" onClick={() => setManager(true)}>{t("MANAGE SERVERS")}<ChevronRight size={12} /></Button></div>
          <Button variant="ghost" className="steel-tray active-server" onClick={() => setManager(true)}><span className="server-flag"><Flag country={node?.country || 'Unknown'} /></span><span className="active-server-name"><strong>{node?.name || t('No Node Selected')}</strong><small>{node ? `${node.country} · ${node.source ? t('UNTRUSTED PUBLIC') : t('Imported configuration')}` : t('Public server directory')}</small></span>{node && <span className="server-metrics"><span className="protocol-badge">{node.protocol === 'shadowsocks' ? 'SS' : node.protocol.toUpperCase()}</span><span className="server-ping">{t("Unmeasured")}</span></span>}<ChevronRight size={18} className="text-muted-foreground" /></Button>
        </section>
      </div>
      <div className="connection-side">
        <section className="control-deck"><div className="deck-heading"><span className="micro-label">{t("LIVE CONNECTION INSTRUMENTS")}</span><span className="micro-label"><span className={`tiny-light ${connected ? 'live' : ''}`} />{connected ? t('NATIVE VPN TELEMETRY') : nativeAvailable ? t('NATIVE VPN READY') : t('ANDROID APP REQUIRED')}</span></div><div className="instrument-grid"><Gauge label={t("LATENCY PING")} value={telemetry.ping} unit="ms" max={180} active={connected} /><div className="power-control"><div className={`power-housing ${status}`}><Button variant="ghost" className="power-button" aria-label={status === 'offline' ? t('Connect VPN') : status === 'connecting' ? t('Cancel connection') : status === 'error' ? t('Disconnect failed VPN') : t('Disconnect VPN')} onClick={power}><Power className="power-bolt" /><span className="power-button-dot" /></Button></div><div className={`power-state ${status}`}>{status === 'offline' ? t('TAP TO CONNECT') : status === 'connecting' ? t('STARTING NATIVE VPN') : status === 'error' ? t('VPN ERROR · TAP TO STOP') : t('TAP TO DISCONNECT')}</div><div className="connection-duration">{duration}</div></div><Gauge label={t("SPEED RATE")} value={telemetry.speed} unit="Mbps" max={120} active={connected} /></div><div className="connection-state-line"><span className={`status-led ${connected ? 'online-led' : ''}`} /><span>{connected ? t('Native VPN tunnel connected') : status === 'connecting' ? t('Starting Android VPN service…') : status === 'error' ? t('Android VPN service error') : nativeAvailable ? t('Your VPN tunnel is disconnected') : t('Install the Android app to connect')}</span><span className="simulation-tag">{nativeAvailable ? t("ANDROID VPN") : t("NOT CONNECTED")}</span></div>{status === 'error' && error && <p className="ai-error" role="alert">{t(error)}</p>}</section>
      </div>
        <section className="telemetry-strip"><div><span><ArrowDown size={13} />{t("DOWNLOAD")}</span><strong>{telemetry.down.toFixed(1)}<small>Mbps</small></strong></div><div><span><ArrowUp size={13} />{t("UPLOAD")}</span><strong>{telemetry.up.toFixed(1)}<small>Mbps</small></strong></div><div><span>{t("DATA RECEIVED")}</span><strong><Odometer value={telemetry.received} label={t("Data received")} /><small>MB</small></strong></div><div><span>{t("DATA SENT")}</span><strong><Odometer value={telemetry.sent} label={t("Data sent")} /><small>MB</small></strong></div></section><div className="connection-footer"><span><ShieldCheck size={13} />{t("ZERO LOGS. ZERO COMPROMISE.")}</span><span><LockKeyhole size={12} />{t("LOCAL CONFIG ENGINE")}</span></div>
      </div> : tab === 'settings' ? <SettingsPanel settings={settings} onChange={s => { setSettings(s); if (node && !s.protocols.includes(node.protocol) && (status === 'connected' || status === 'connecting')) void stopNativeVpn(); }} node={node} onOpenVpnSettings={showAndroidVpnSettings} /> : <InfoPanel node={node} connected={connected} nativeAvailable={nativeAvailable} subscription={subscription} onWelcome={() => setWelcome(true)} />}
      <footer className="chassis-footer"><span>AIV2Ray <span className="footer-divider">/</span>{t("ENGINEERED FOR PRIVACY")}</span><span>XRAY CORE <span className="mini-led" />{nativeAvailable ? t("ANDROID VPN") : t("WEB UI · VPN OFFLINE")}</span></footer>
    </div><div className="below-chassis"><span>BRUSHED STEEL <b>×</b> BURNISHED COPPER</span><span>EST. 2024 <span>·</span> BUILD 2.4.1</span></div>
    <SettingsDashboard onWelcome={() => setWelcome(true)} />
    <LanguageSelector />
    <ServerManager open={manager} onOpenChange={setManager} nodes={nodes.filter(n => settings.protocols.includes(n.protocol))} selected={node?.id || ''} protocols={settings.protocols} onSelect={selectNode} onAdd={addNodes} onDelete={id => { setNodes(old => old.filter(n => n.id !== id)); if (node?.id === id) { if (status === 'connected' || status === 'connecting') void stopNativeVpn(); setNode(null); } }} />
    <WelcomeScreens key={welcome ? 'open' : 'closed'} open={welcome} onFinish={finishWelcome} />
  </main>;
}
