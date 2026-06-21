import { useEffect, useRef, useState } from "react";
import type {
  CandleFrame, DashboardState, MetricsFrame, StatusFrame, TapeFrame,
} from "./types";

const METRICS_HISTORY = 240; // ~1 min at 4 Hz
const CANDLE_HISTORY = 180;

const DEMO = import.meta.env.VITE_DEMO === "1";

const empty: DashboardState = {
  connected: false, metricsHistory: [], tape: [], candles: [],
};

function applyMetrics(s: DashboardState, m: MetricsFrame): DashboardState {
  const hist = [...s.metricsHistory, m];
  if (hist.length > METRICS_HISTORY) hist.shift();
  return { ...s, metrics: m, metricsHistory: hist };
}

function applyCandle(s: DashboardState, c: CandleFrame): DashboardState {
  const candles = [...s.candles];
  const last = candles[candles.length - 1];
  if (last && last.start === c.start) candles[candles.length - 1] = c;
  else candles.push(c);
  if (candles.length > CANDLE_HISTORY) candles.shift();
  return { ...s, candles, lastCandle: c };
}

export interface Control {
  stress?: boolean;
  rate?: number;
}

export function useFrames(): { state: DashboardState; sendControl: (c: Control) => void } {
  const [state, setState] = useState<DashboardState>(empty);
  const timers = useRef<number[]>([]);

  useEffect(() => {
    if (DEMO) {
      startDemoReplay(setState, timers);
      return () => timers.current.forEach(clearTimeout);
    }
    const es = new EventSource("/api/stream");
    es.onopen = () => setState((s) => ({ ...s, connected: true }));
    es.onerror = () => setState((s) => ({ ...s, connected: false }));
    es.addEventListener("metrics", (e) =>
      setState((s) => applyMetrics(s, JSON.parse((e as MessageEvent).data) as MetricsFrame)));
    es.addEventListener("tape", (e) =>
      setState((s) => ({ ...s, tape: (JSON.parse((e as MessageEvent).data) as TapeFrame).trades })));
    es.addEventListener("candle", (e) =>
      setState((s) => applyCandle(s, JSON.parse((e as MessageEvent).data) as CandleFrame)));
    es.addEventListener("status", (e) =>
      setState((s) => ({ ...s, status: JSON.parse((e as MessageEvent).data) as StatusFrame })));
    return () => es.close();
  }, []);

  const sendControl = (c: Control) => {
    if (DEMO) return; // the hosted demo is a recording — controls are inert
    fetch("/api/control", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(c),
    }).catch(() => {});
  };

  return { state, sendControl };
}

interface RecordedFrame { event: string; data: unknown; t: number; }

// Hosted GitHub Pages demo: replay a recorded session (no backend / no JVM available).
async function startDemoReplay(
  setState: React.Dispatch<React.SetStateAction<DashboardState>>,
  timers: React.MutableRefObject<number[]>,
) {
  const url = import.meta.env.BASE_URL + "demo-frames.ndjson";
  let frames: RecordedFrame[] = [];
  try {
    const text = await (await fetch(url)).text();
    frames = text.split("\n").filter(Boolean).map((l) => JSON.parse(l) as RecordedFrame);
  } catch {
    setState((s) => ({ ...s, connected: false }));
    return;
  }
  if (frames.length === 0) return;
  setState((s) => ({ ...s, connected: true }));

  const t0 = frames[0].t;
  const dispatch = (f: RecordedFrame) => {
    setState((s) => {
      switch (f.event) {
        case "metrics": return applyMetrics(s, f.data as MetricsFrame);
        case "candle": return applyCandle(s, f.data as CandleFrame);
        case "tape": return { ...s, tape: (f.data as TapeFrame).trades };
        case "status": return { ...s, status: f.data as StatusFrame };
        default: return s;
      }
    });
  };
  const schedule = (offset: number) => {
    frames.forEach((f) => {
      const id = window.setTimeout(() => dispatch(f), offset + (f.t - t0));
      timers.current.push(id);
    });
    const span = frames[frames.length - 1].t - t0;
    const loop = window.setTimeout(() => {
      timers.current = [];
      schedule(0);
    }, offset + span + 1500);
    timers.current.push(loop);
  };
  schedule(0);
}
