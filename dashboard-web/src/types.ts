// Frame shapes — must match the backend records in com.lowlatency.dashboard.Frames.

export interface MetricsFrame {
  ts: number;
  p50: number; p99: number; p999: number; p9999: number; max: number; count: number;
  ordersPerSec: number; tradesPerSec: number; processedTotal: number; tradeTotal: number;
  gcCollections: number; gcPauseMillis: number; allocBytes: number; allocBytesPerSec: number;
}

export interface TradeRow {
  seq: number; price: number; qty: number; takerBuy: boolean; ts: number;
}

export interface TapeFrame {
  ts: number;
  trades: TradeRow[];
}

export interface CandleFrame {
  start: number; end: number;
  open: number; high: number; low: number; close: number;
  volume: number; vwap: number; imbalance: number; trades: number;
}

export interface StatusFrame {
  symbol: string; source: string; stress: boolean; stressRate: number;
  uptimeMillis: number; live: boolean;
}

export interface DashboardState {
  connected: boolean;
  status?: StatusFrame;
  metrics?: MetricsFrame;
  metricsHistory: MetricsFrame[]; // bounded ring for time-series charts
  tape: TradeRow[];
  candles: CandleFrame[];         // bounded ring for the candlestick chart
  lastCandle?: CandleFrame;
}
