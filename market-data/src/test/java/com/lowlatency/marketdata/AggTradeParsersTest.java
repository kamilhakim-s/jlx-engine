package com.lowlatency.marketdata;

import com.lowlatency.engine.Side;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AggTradeParsersTest {

    private final SymbolScale scale = SymbolScale.BTCUSDT; // price scale 2, qty scale 8

    @Test
    void csvLineParsesToNormalisedTrade() {
        // id, price, qty, firstId, lastId, transactTime(ms), isBuyerMaker, isBestMatch
        String line = "12345,42123.45,0.01,100,105,1700000000000,true,true";
        AggTrade t = new AggTradeCsvParser().parseLine(line, scale);

        assertThat(t.aggTradeId()).isEqualTo(12345L);
        assertThat(t.priceTicks()).isEqualTo(4_212_345L);
        assertThat(t.quantityUnits()).isEqualTo(1_000_000L); // 0.01 * 1e8
        assertThat(t.timestampMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(t.buyerIsMaker()).isTrue();
        assertThat(t.takerSide()).isEqualTo(Side.SELL); // buyer is maker ⇒ seller aggressed
    }

    @Test
    void csvHeaderRowIsSkipped() {
        String header = "agg_trade_id,price,quantity,first_trade_id,last_trade_id,transact_time,is_buyer_maker,is_best_match";
        assertThat(new AggTradeCsvParser().parseLine(header, scale)).isNull();
    }

    @Test
    void csvNormalisesMicrosecondTimestampsToMillis() {
        String microsLine = "1,50000.00,0.5,1,1,1700000000000000,false,true"; // 16-digit micros
        AggTrade t = new AggTradeCsvParser().parseLine(microsLine, scale);
        assertThat(t.timestampMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(t.takerSide()).isEqualTo(Side.BUY); // buyer not maker ⇒ buyer aggressed
    }

    @Test
    void liveJsonMessageParsesToSameShape() {
        String json = "{\"e\":\"aggTrade\",\"E\":1700000000000,\"s\":\"BTCUSDT\",\"a\":777,"
                + "\"p\":\"42123.45\",\"q\":\"0.01\",\"f\":1,\"l\":2,\"T\":1700000000123,\"m\":false,\"M\":true}";
        AggTrade t = new AggTradeJsonParser().parse(json, scale);

        assertThat(t.aggTradeId()).isEqualTo(777L);
        assertThat(t.priceTicks()).isEqualTo(4_212_345L);
        assertThat(t.quantityUnits()).isEqualTo(1_000_000L);
        assertThat(t.timestampMillis()).isEqualTo(1_700_000_000_123L);
        assertThat(t.takerSide()).isEqualTo(Side.BUY);
    }
}
