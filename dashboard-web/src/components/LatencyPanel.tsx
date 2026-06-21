import { useMemo } from "react";
import type uPlot from "uplot";
import type { MetricsFrame } from "../types";
import { us, usNum } from "../format";
import UplotChart from "./UplotChart";

function Tile({ label, value, warn }: { label: string; value: number; warn?: boolean }) {
  return (
    <div className="tile">
      <div className="label">{label}</div>
      <div className={"value" + (warn ? " warn" : "")}>
        {us(value)}<span className="unit">µs</span>
      </div>
    </div>
  );
}

const clampLog = (v: number) => Math.max(0.01, usNum(v));

export default function LatencyPanel({
  metrics, history,
}: { metrics?: MetricsFrame; history: MetricsFrame[] }) {
  const data = useMemo<uPlot.AlignedData>(() => {
    const xs = history.map((m) => m.ts / 1000);
    return [
      xs,
      history.map((m) => clampLog(m.p50)),
      history.map((m) => clampLog(m.p99)),
      history.map((m) => clampLog(m.p999)),
    ];
  }, [history]);

  const options = useMemo<Omit<uPlot.Options, "width" | "height">>(() => ({
    scales: { x: { time: true }, y: { distr: 3 } }, // log y so p50 and the tail are both legible
    axes: [
      { stroke: "#7488a0", grid: { stroke: "#161d2b" }, ticks: { stroke: "#161d2b" } },
      {
        stroke: "#7488a0", grid: { stroke: "#161d2b" }, ticks: { stroke: "#161d2b" },
        values: (_u, vals) => vals.map((v) => (v >= 1000 ? v / 1000 + "ms" : v + "µs")),
      },
    ],
    series: [
      {},
      { label: "p50", stroke: "#7dd3fc", width: 1.5 },
      { label: "p99", stroke: "#ffb454", width: 1.5 },
      { label: "p99.9", stroke: "#ff7088", width: 1.5 },
    ],
    legend: { show: true },
    cursor: { show: true },
  }), []);

  return (
    <div className="panel col-12">
      <h3>End-to-end latency
        <span className="hint">client → ring buffer → match · {metrics ? metrics.count.toLocaleString() : 0} samples / interval</span>
      </h3>
      <div className="tiles">
        <Tile label="p50" value={metrics?.p50 ?? 0} />
        <Tile label="p99" value={metrics?.p99 ?? 0} />
        <Tile label="p99.9" value={metrics?.p999 ?? 0} />
        <Tile label="p99.99" value={metrics?.p9999 ?? 0} warn />
        <Tile label="max" value={metrics?.max ?? 0} warn />
      </div>
      <div className="chartbox">
        <UplotChart data={data} options={options} height={210} />
      </div>
    </div>
  );
}
