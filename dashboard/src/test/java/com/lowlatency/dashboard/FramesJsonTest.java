package com.lowlatency.dashboard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The frame protocol must serialise to the field names the SPA reads. */
class FramesJsonTest {

    @Test
    void metricsFrameSerialisesExpectedFields() {
        String json = Json.toJson(new Frames.MetricsFrame(
                1L, 80, 170, 6130, 48400, 153700, 1000,
                500_000.0, 250_000.0, 12345, 6789,
                1, 3, 4_200_000, 16_800_000.0));
        assertThat(json).contains("\"p50\":80", "\"p999\":6130", "\"ordersPerSec\":500000.0",
                "\"gcCollections\":1", "\"allocBytes\":4200000");
    }

    @Test
    void tapeFrameRoundTripsTrades() {
        Frames.TapeFrame frame = new Frames.TapeFrame(7L, List.of(
                new Frames.TradeRow(1, 4_200_000, 3, true, 100),
                new Frames.TradeRow(2, 4_200_100, 1, false, 101)));
        String json = Json.toJson(frame);
        assertThat(json).contains("\"trades\":[", "\"takerBuy\":true", "\"price\":4200000");
    }

    @Test
    void controlRequestParsesPartialBodies() {
        DashboardServer.ControlRequest a = Json.fromJson("{\"stress\":true}", DashboardServer.ControlRequest.class);
        assertThat(a.stress()).isTrue();
        assertThat(a.rate()).isNull();

        DashboardServer.ControlRequest b = Json.fromJson("{\"rate\":150000}", DashboardServer.ControlRequest.class);
        assertThat(b.stress()).isNull();
        assertThat(b.rate()).isEqualTo(150_000L);
    }
}
