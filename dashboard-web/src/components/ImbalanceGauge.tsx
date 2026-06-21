import type { CandleFrame } from "../types";
import { num, price } from "../format";

export default function ImbalanceGauge({ candle }: { candle?: CandleFrame }) {
  const imb = candle?.imbalance ?? 0;
  const pct = ((imb + 1) / 2) * 100;
  return (
    <div className="panel col-4">
      <h3>Trade-flow imbalance<span className="hint">1s window</span></h3>
      <div className="bignum" style={{ color: imb >= 0 ? "var(--buy)" : "var(--sell)" }}>
        {(imb >= 0 ? "+" : "") + imb.toFixed(3)}
      </div>
      <div className="gauge"><div className="needle" style={{ left: `${pct}%` }} /></div>
      <div className="gauge-labels"><span>sell pressure</span><span>balanced</span><span>buy pressure</span></div>
      <div className="kv" style={{ marginTop: 12 }}><span className="k">vwap</span><span className="v">{candle ? price(candle.vwap) : "–"}</span></div>
      <div className="kv"><span className="k">window volume</span><span className="v">{candle ? num(candle.volume) : "–"}</span></div>
      <div className="kv"><span className="k">window trades</span><span className="v">{candle ? num(candle.trades) : "–"}</span></div>
    </div>
  );
}
