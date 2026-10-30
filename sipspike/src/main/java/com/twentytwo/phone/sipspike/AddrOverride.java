package com.twentytwo.phone.sipspike;

/**
 * Pins one hostname inside PJSIP's {@code pj_getaddrinfo}.
 * Implemented in libpjsua2.so. A null host clears the pin.
 */
public final class AddrOverride {
    private AddrOverride() {}

    public static native boolean set(String host, String literal);
}
