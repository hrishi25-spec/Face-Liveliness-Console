package io.mosip.liveness.core;

import java.util.Objects;

/**
 * A single captured frame from any compliant L0/L1 device. Device adapters are
 * responsible for producing frames in one of the supported formats; the engine
 * is format-agnostic and delegates decoding to the active backend.
 */
public final class Frame {

    public enum Format { RGB_GRAY, RGB_888, NV21, YUV420 }

    private final byte[] data;
    private final int width;
    private final int height;
    private final Format format;
    private final long timestampMillis;
    private final int sequenceNumber;

    public Frame(byte[] data, int width, int height, Format format, long timestampMillis, int sequenceNumber) {
        if (data == null || data.length == 0) {
            throw new LivenessException(LivenessErrorCode.INVALID_FRAME_DATA, "Frame payload is null or empty");
        }
        if (width <= 0 || height <= 0) {
            throw new LivenessException(LivenessErrorCode.INVALID_FRAME_DATA,
                    "Frame dimensions invalid: " + width + "x" + height);
        }
        Objects.requireNonNull(format, "format");
        this.data = data;
        this.width = width;
        this.height = height;
        this.format = format;
        this.timestampMillis = timestampMillis;
        this.sequenceNumber = sequenceNumber;
    }

    public static Frame of(byte[] data, int width, int height, Format format, long timestampMillis, int seq) {
        return new Frame(data, width, height, format, timestampMillis, seq);
    }

    /** Payload bytes (not defensively copied for performance; treat as read-only). */
    public byte[] data() { return data; }
    public int width() { return width; }
    public int height() { return height; }
    public Format format() { return format; }
    public long timestampMillis() { return timestampMillis; }
    public int sequenceNumber() { return sequenceNumber; }

    @Override
    public String toString() {
        return "Frame[seq=" + sequenceNumber + " " + width + "x" + height + " " + format
                + " len=" + data.length + " t=" + timestampMillis + "]";
    }

}
