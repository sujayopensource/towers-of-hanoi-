package io.towersofhanoi.core;

import java.io.Serializable;

/**
 * Sentinel stored in both L1 and L2 when a null value is explicitly cached.
 * Non-final so Jackson embeds type info and can round-trip it through Redis.
 */
public class NullValueMarker implements Serializable {

    public static final NullValueMarker INSTANCE = new NullValueMarker();

    public NullValueMarker() {}
}
