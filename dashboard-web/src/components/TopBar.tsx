import type { DashboardState } from "../types";
import { duration } from "../format";

const DEMO = import.meta.env.VITE_DEMO === "1";

export default function TopBar({ state }: { state: DashboardState }) {
  const s = state.status;
  const live = s?.live ?? false;
  return (
    <div className="topbar">
      <span className="logo">⚡ Low-Latency <span>Matching Engine</span></span>
      <span className="sym">{s?.symbol ?? "BTCUSDT"}</span>
      {DEMO ? (
        <span className="badge demo">RECORDED DEMO</span>
      ) : live ? (
        <span className="badge live"><span className="dot on" />LIVE · {s?.source}</span>
      ) : (
        <span className="badge off"><span className="dot off" />{s?.source ?? "connecting…"}</span>
      )}
      {s?.stress && <span className="badge demo">STRESS @ {Math.round(s.stressRate / 1000)}k/s</span>}
      <span className="spacer" />
      <span className="sym">{state.connected ? "● stream connected" : "○ disconnected"}</span>
      {s && <span className="sym">uptime {duration(s.uptimeMillis)}</span>}
    </div>
  );
}
