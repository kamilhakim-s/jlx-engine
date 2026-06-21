import type { MetricsFrame } from "../types";
import { mbPerSec } from "../format";
import Sparkline from "./Sparkline";

export default function GcPanel({
  metrics, history,
}: { metrics?: MetricsFrame; history: MetricsFrame[] }) {
  const pauses = history.reduce((a, m) => a + m.gcPauseMillis, 0);
  return (
    <div className="panel col-4">
      <h3>GC / allocation<span className="hint">past the seam</span></h3>
      <div className="bignum">{mbPerSec(metrics?.allocBytesPerSec ?? 0)}</div>
      <Sparkline values={history.map((m) => m.allocBytesPerSec / 1e6)} color="#ffb454" />
      <div className="kv"><span className="k">GC collections (interval)</span><span className="v">{metrics?.gcCollections ?? 0}</span></div>
      <div className="kv"><span className="k">GC pause (interval)</span><span className="v">{metrics?.gcPauseMillis ?? 0} ms</span></div>
      <div className="kv"><span className="k">GC pause (window total)</span><span className="v">{pauses} ms</span></div>
      <p style={{ color: "var(--muted)", fontSize: 11, marginTop: 10, lineHeight: 1.5 }}>
        The matching core is allocation-free; what you see is the streaming <em>seam</em> snapshotting one
        event per trade — allocation by design, off the hot path.
      </p>
    </div>
  );
}
