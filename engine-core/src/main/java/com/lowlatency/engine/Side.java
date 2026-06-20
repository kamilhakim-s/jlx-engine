package com.lowlatency.engine;

/** Which side of the book an order sits on. */
public enum Side {
    BUY,
    SELL;

    /** The opposing side — i.e. the side an incoming order matches against. */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
