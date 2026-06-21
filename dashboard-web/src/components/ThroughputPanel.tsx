import { useState } from "react";
import type { MetricsFrame, StatusFrame } from "../types";
import type { Control } from "../useFrames";
import { compact, num } from "../format";
import Sparkline from "./Sparkline";

export default function ThroughputPanel({
  metrics, history, status, sendControl,
}: {
  metrics?: MetricsFrame;
  history: MetricsFrame[];
  status?: StatusFrame;
  sendControl: (c: Control) => void;
}) {
  const stress = status?.stress ?? false;
  const [rate, setRate] = useState(status?.stressRate ?? 200_000);

  return (
    <div className="panel col-4">
      <h3>Throughput<span className="hint">single-writer engine</span></h3>
      <div className="bignum">{compact(metrics?.ordersPerSec ?? 0)}<span className="unit">orders/s</span></div>
      <Sparkline values={history.map((m) => m.ordersPerSec)} color="#7dd3fc" />
      <div className="kv"><span className="k">trades/s</span><span className="v">{compact(metrics?.tradesPerSec ?? 0)}</span></div>
      <div className="kv"><span className="k">processed (total)</span><span className="v">{num(metrics?.processedTotal ?? 0)}</span></div>

      <div className="control-row">
        <button
          className={"toggle" + (stress ? " on" : "")}
          onClick={() => sendControl({ stress: !stress })}
          disabled={import.meta.env.VITE_DEMO === "1"}
        >
          {stress ? "■ Stress load ON" : "▶ Drive stress load"}
        </button>
      </div>
      <div className="control-row">
        <span className="k" style={{ color: "var(--muted)", fontSize: 12 }}>{compact(rate)}/s</span>
        <input
          type="range" min={10_000} max={1_000_000} step={10_000} value={rate}
          onChange={(e) => {
            const r = Number(e.target.value);
            setRate(r);
            sendControl({ rate: r });
          }}
          disabled={import.meta.env.VITE_DEMO === "1"}
        />
      </div>
    </div>
  );
}
