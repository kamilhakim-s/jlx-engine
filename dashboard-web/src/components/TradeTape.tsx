import type { TradeRow } from "../types";
import { price } from "../format";

export default function TradeTape({ tape }: { tape: TradeRow[] }) {
  const rows = [...tape].slice(-16).reverse();
  return (
    <div className="panel col-4">
      <h3>Trade tape<span className="hint">most recent prints</span></h3>
      <table className="tape">
        <tbody>
          {rows.map((r, i) => (
            <tr key={`${r.seq}-${i}`}>
              <td className={"side " + (r.takerBuy ? "buy" : "sell")}>{r.takerBuy ? "BUY" : "SELL"}</td>
              <td style={{ textAlign: "right" }}>{price(r.price)}</td>
              <td style={{ textAlign: "right", color: "var(--muted)" }}>{r.qty}</td>
            </tr>
          ))}
          {rows.length === 0 && (
            <tr><td className="side" style={{ color: "var(--muted)" }}>waiting for trades…</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
