import { useMemo } from "react";
import type uPlot from "uplot";
import UplotChart from "./UplotChart";

/** A minimal axis-less area sparkline for a single series. */
export default function Sparkline({
  values, color, height = 56,
}: { values: number[]; color: string; height?: number }) {
  const data = useMemo<uPlot.AlignedData>(
    () => [values.map((_, i) => i), values],
    [values],
  );
  const options = useMemo<Omit<uPlot.Options, "width" | "height">>(() => ({
    scales: { x: { time: false }, y: {} },
    axes: [{ show: false }, { show: false }],
    legend: { show: false },
    cursor: { show: false },
    series: [
      {},
      { stroke: color, width: 1.5, fill: color + "22", points: { show: false } },
    ],
  }), [color]);
  return <UplotChart data={data} options={options} height={height} />;
}
