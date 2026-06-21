// Display formatters. Price/quantity are integer ticks (BTCUSDT price scale 2 → divide by 100).

export const PRICE_SCALE = 100;

export const toUsd = (ticks: number): number => ticks / PRICE_SCALE;

export const us = (nanos: number): string => (nanos / 1000).toFixed(2);

export const usNum = (nanos: number): number => nanos / 1000;

export const num = (n: number): string => Math.round(n).toLocaleString();

export const compact = (n: number): string => {
  if (n >= 1e9) return (n / 1e9).toFixed(1) + "B";
  if (n >= 1e6) return (n / 1e6).toFixed(1) + "M";
  if (n >= 1e3) return (n / 1e3).toFixed(1) + "k";
  return Math.round(n).toString();
};

export const mbPerSec = (bytesPerSec: number): string => (bytesPerSec / 1e6).toFixed(1) + " MB/s";

export const price = (ticks: number): string => toUsd(ticks).toLocaleString(undefined, {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export const duration = (ms: number): string => {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return [h, m, sec].map((v) => String(v).padStart(2, "0")).join(":");
};
