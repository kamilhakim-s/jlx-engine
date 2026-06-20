package com.lowlatency.engine.disruptor;

/** The kind of command carried by an {@link OrderCommand} event through the ring buffer. */
public enum CommandType {
    NEW_ORDER,
    CANCEL
}
