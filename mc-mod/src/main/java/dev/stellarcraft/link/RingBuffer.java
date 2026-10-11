package dev.stellarcraft.link;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A single-producer / single-consumer byte ring over a fixed region of shared memory.
 *
 * <p>Layout (relative to the ring's base):
 * <ul>
 *   <li>{@code headOff} — producer's write cursor (absolute bytes)</li>
 *   <li>{@code tailOff} — consumer's read cursor (absolute bytes)</li>
 *   <li>{@code dataOff} — start of the payload buffer</li>
 * </ul>
 *
 * <p>Cursors are monotonically increasing byte counts (never wrapped), so the reader
 * computes {@code cursor % capacity} to index into the buffer. This is the classic
 * LMAX/Distruptor-style scheme and avoids the "full vs empty" ambiguity of wrapped cursors.
 */
public final class RingBuffer {
    private final ByteBuffer region;   // whole ring region (includes head/tail + data)
    private final long headOff, tailOff, dataOff;
    private final int capacity;        // bytes of payload

    public RingBuffer(ByteBuffer region, long headOff, long tailOff, long dataOff, int capacity) {
        this.region = region.order(ByteOrder.LITTLE_ENDIAN);
        this.headOff = headOff;
        this.tailOff = tailOff;
        this.dataOff = dataOff;
        this.capacity = capacity;
    }

    public long head() { return region.getLong((int) headOff); }
    public long tail() { return region.getLong((int) tailOff); }
    public void setTail(long v) { region.putLong((int) tailOff, v); }

    public boolean isEmpty() { return head() == tail(); }

    /**
     * Producer: writes {@code n} bytes and returns the absolute byte offset where the
     * payload was placed, or {@code -1} if there isn't room.
     */
    public long claim(int n) {
        long h = head();
        long t = tail();
        if (h + n - t > capacity) return -1; // no room
        return h;
    }

    public void publish(long claimed, int n) {
        region.putLong((int) headOff, claimed + n);
    }

    /** Producer helper: write raw bytes at an absolute cursor. */
    public void writeBytes(long cursor, ByteBuffer src) {
        int start = (int) dataOff + (int) (cursor % capacity);
        int n = src.remaining();
        int first = Math.min(n, capacity - (start - (int) dataOff));
        // copy first chunk
        for (int i = 0; i < first; i++) region.put(start + i, src.get());
        // wrap-around chunk
        if (first < n) {
            int wrap = (int) dataOff;
            for (int i = first; i < n; i++) region.put(wrap + (i - first), src.get());
        }
    }

    /** Consumer helper: read {@code n} bytes from an absolute cursor into {@code dst}. */
    public void readBytes(long cursor, int n, ByteBuffer dst) {
        dst.clear();
        int start = (int) dataOff + (int) (cursor % capacity);
        int first = Math.min(n, capacity - (start - (int) dataOff));
        for (int i = 0; i < first; i++) dst.put(region.get(start + i));
        if (first < n) {
            int wrap = (int) dataOff;
            for (int i = first; i < n; i++) dst.put(region.get(wrap + (i - first)));
        }
        dst.flip();
    }

    public int capacity() { return capacity; }
}
