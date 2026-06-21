import { useEffect, useRef } from "react";
import uPlot from "uplot";

/**
 * Thin React wrapper around uPlot — a tiny, very fast canvas charting lib well suited to the high-update
 * latency/throughput time-series. The chart is created imperatively and fed new data via setData (no React
 * re-render of the canvas), and resized to its container width via a ResizeObserver.
 */
export default function UplotChart({
  data,
  options,
  height,
}: {
  data: uPlot.AlignedData;
  options: Omit<uPlot.Options, "width" | "height">;
  height: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const chart = useRef<uPlot | null>(null);

  useEffect(() => {
    if (!ref.current) return;
    const width = ref.current.clientWidth || 600;
    chart.current = new uPlot({ ...options, width, height } as uPlot.Options, data, ref.current);

    const ro = new ResizeObserver((entries) => {
      const w = entries[0].contentRect.width;
      chart.current?.setSize({ width: w, height });
    });
    ro.observe(ref.current);
    return () => {
      ro.disconnect();
      chart.current?.destroy();
      chart.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    chart.current?.setData(data);
  }, [data]);

  return <div className="uplot" ref={ref} />;
}
