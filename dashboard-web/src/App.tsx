import { useFrames } from "./useFrames";
import TopBar from "./components/TopBar";
import LatencyPanel from "./components/LatencyPanel";
import ThroughputPanel from "./components/ThroughputPanel";
import GcPanel from "./components/GcPanel";
import CandleChart from "./components/CandleChart";
import TradeTape from "./components/TradeTape";
import ImbalanceGauge from "./components/ImbalanceGauge";

export default function App() {
  const { state, sendControl } = useFrames();
  return (
    <div className="app">
      <TopBar state={state} />
      <div className="grid">
        <LatencyPanel metrics={state.metrics} history={state.metricsHistory} />
        <ThroughputPanel
          metrics={state.metrics} history={state.metricsHistory}
          status={state.status} sendControl={sendControl}
        />
        <GcPanel metrics={state.metrics} history={state.metricsHistory} />
        <ImbalanceGauge candle={state.lastCandle} />
        <CandleChart candles={state.candles} />
        <TradeTape tape={state.tape} />
      </div>
      <div className="footnote">
        The dashboard reads only <code>past the AsyncTradeForwarder seam</code> — it never touches the
        matching hot path. Live Binance trades drive the market panels; the stress toggle drives synthetic
        load so the latency / throughput / GC panels show the engine under real load.
      </div>
    </div>
  );
}
