package io.mosip.liveness.core;

/** Attack categories that must be detected per the PAD requirements. */
public enum PadAttackType {
    PRINTED_PHOTO,
    SCREEN_REPLAY,
    VIDEO_REPLAY,
    MASK,
    THREE_D,
    OTHER
}
