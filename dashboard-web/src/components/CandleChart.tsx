import { useEffect, useRef } from "react";
import {
  createChart, ColorType, type IChartApi, type ISeriesApi, type UTCTimestamp,
} from "lightweight-charts";
import type { CandleFrame } from "../types";
import { toUsd } from "../format";

/** OHLC candlestick + VWAP overlay (TradingView lightweight-charts), updated imperatively. */
export default function CandleChart({ candles }: { candles: CandleFrame[] }) {
  const box = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const candleSeries = useRef<ISeriesApi<"Candlestick"> | null>(null);
  const vwapSeries = useRef<ISeriesApi<"Line"> | null>(null);

  useEffect(() => {
    if (!box.current) return;
    const c = createChart(box.current, {
      width: box.current.clientWidth,
      height: 300,
      layout: { background: { type: ColorType.Solid, color: "transparent" }, textColor: "#7488a0" },
      grid: { vertLines: { color: "#161d2b" }, horzLines: { color: "#161d2b" } },
      timeScale: { timeVisible: true, secondsVisible: true, borderColor: "#1b2333" },
      rightPriceScale: { borderColor: "#1b2333" },
      crosshair: { mode: 0 },
    });
    candleSeries.current = c.addCandlestickSeries({
      upColor: "#43d18b", downColor: "#ff7088", wickUpColor: "#43d18b",
      wickDownColor: "#ff7088", borderVisible: false,
    });
    vwapSeries.current = c.addLineSeries({ color: "#7dd3fc", lineWidth: 1, priceLineVisible: false });
    chart.current = c;

    const ro = new ResizeObserver((e) => c.applyOptions({ width: e[0].contentRect.width }));
    ro.observe(box.current);
    return () => { ro.disconnect(); c.remove(); chart.current = null; };
  }, []);

  useEffect(() => {
    if (!candleSeries.current || !vwapSeries.current) return;
    // De-dupe by window start (lightweight-charts needs strictly ascending, unique times).
    const seen = new Set<number>();
    const ordered = candles.filter((c) => {
      const t = Math.floor(c.start / 1000);
      if (seen.has(t)) return false;
      seen.add(t);
      return true;
    });
    candleSeries.current.setData(ordered.map((c) => ({
      time: Math.floor(c.start / 1000) as UTCTimestamp,
      open: toUsd(c.open), high: toUsd(c.high), low: toUsd(c.low), close: toUsd(c.close),
    })));
    vwapSeries.current.setData(ordered.map((c) => ({
      time: Math.floor(c.start / 1000) as UTCTimestamp,
      value: toUsd(c.vwap),
    })));
  }, [candles]);

  return (
    <div className="panel col-8">
      <h3>Price (1s candles) + VWAP<span className="hint">OHLC from the trade tape</span></h3>
      <div className="chartbox" ref={box} />
    </div>
  );
}
