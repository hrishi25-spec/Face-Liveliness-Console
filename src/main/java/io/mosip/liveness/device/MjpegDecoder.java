package io.mosip.liveness.device;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Incremental parser that extracts complete JPEG images from a raw
 * multipart/x-mixed-replace (MJPEG) byte stream, tolerating chunk splits at
 * arbitrary byte positions.
 *
 * <p>Rather than depending on each vendor's exact boundary string (which the
 * SBI STREAM spec leaves loosely constrained in practice), the decoder scans
 * for JPEG SOI (FFD8) / EOI (FFD9) markers. This is the standard pragmatic
 * approach for MJPEG consumers and works with every multipart framing seen
 * in the wild. A partial trailing image is retained until its EOI arrives.</p>
 */
public final class MjpegDecoder {

    private static final byte[] SOI = {(byte) 0xFF, (byte) 0xD8};
    private static final byte[] EOI = {(byte) 0xFF, (byte) 0xD9};
    /** Safety valve: discard buffered bytes if no image start is ever found. */
    private static final int MAX_BUFFERED_BYTES = 8 * 1024 * 1024;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /**
     * Feed raw stream bytes and return every JPEG image completed by this chunk,
     * in order. The returned arrays are fresh copies; the internal buffer keeps
     * only an incomplete trailing image.
     */
    public synchronized List<byte[]> feed(byte[] chunk, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > chunk.length) {
            throw new IndexOutOfBoundsException("bad chunk slice off=" + offset + " len=" + length);
        }
        buffer.write(chunk, offset, length);
        return extract();
    }

    public synchronized List<byte[]> feed(byte[] chunk) {
        return feed(chunk, 0, chunk.length);
    }

    /** Discard all buffered (incomplete) data, e.g. after a stream reconnect. */
    public synchronized void reset() {
        buffer.reset();
    }

    public synchronized int bufferedBytes() {
        return buffer.size();
    }

    private List<byte[]> extract() {
        List<byte[]> images = new ArrayList<>();
        byte[] b = buffer.toByteArray();
        int pos = 0;
        while (true) {
            int soi = indexOf(b, pos, SOI);
            if (soi < 0) {
                pos = Math.max(pos, b.length - SOI.length + 1); // keep nothing useful before this
                break;
            }
            int eoi = indexOf(b, soi + SOI.length, EOI);
            if (eoi < 0) {
                break; // image still streaming in
            }
            images.add(Arrays.copyOfRange(b, soi, eoi + EOI.length));
            pos = eoi + EOI.length;
        }
        buffer.reset();
        if (pos < b.length) {
            buffer.write(b, pos, b.length - pos);
        }
        // Garbage guard: nothing but non-JPEG noise and no room to make progress.
        if (images.isEmpty() && buffer.size() > MAX_BUFFERED_BYTES && indexOf(b, 0, SOI) < 0) {
            buffer.reset();
        }
        return images;
    }

    private static int indexOf(byte[] haystack, int from, byte[] needle) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
