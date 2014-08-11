package io.divolte.server;

import javax.annotation.ParametersAreNonnullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

@ParametersAreNonnullByDefault
public final class AvroRecordBuffer<T extends SpecificRecord> {
    private static final int INITIAL_BUFFER_SIZE = 100;
    private static final AtomicInteger BUFFER_SIZE = new AtomicInteger(INITIAL_BUFFER_SIZE);

    private final String partyId;
    private final ByteBuffer byteBuffer;

    private AvroRecordBuffer(final String partyId, final T record) throws IOException {
        this.partyId = Objects.requireNonNull(partyId);
        /*
         * We avoid ByteArrayOutputStream as it is fully synchronized and performs
         * a lot of copying. Instead, we create a byte array and point a
         * ByteBuffer to it and create a custom OutputStream implementation that
         * writes directly to the ByteBuffer. If we under-allocate, we recreate
         * the entire object using a larger byte array. All subsequent instances
         * will also allocate the larger size array from that point onward.
         */
        final ByteBuffer byteBuffer = ByteBuffer.wrap(new byte[BUFFER_SIZE.get()]);
        final DatumWriter<T> writer = new SpecificDatumWriter<>(record.getSchema());
        final Encoder encoder = EncoderFactory.get().directBinaryEncoder(new ByteBufferOutputStream(byteBuffer), null);

        writer.write(record, encoder);

        // Prepare buffer for reading, and store it (read-only).
        byteBuffer.flip();
        this.byteBuffer = byteBuffer.asReadOnlyBuffer();
    }

    public String getPartyId() {
        return partyId;
    }

    public static <T extends SpecificRecord> AvroRecordBuffer<T> fromRecord(final String partyId, final T record) {
        for (;;) {
            try {
                return new AvroRecordBuffer<>(partyId, record);
            } catch (final BufferOverflowException boe) {
                // Increase the buffer size by about 10%
                // Because we only ever increase the buffer size, we discard the
                // scenario where this thread fails to set the new size,
                // as we can assume another thread increased it.
                int currentSize = BUFFER_SIZE.get();
                BUFFER_SIZE.compareAndSet(currentSize, (int) (currentSize * 1.1));
            } catch (final IOException ioe) {
                throw new RuntimeException("Serialization error.", ioe);
            }
        }
    }

    public ByteBuffer getByteBuffer() {
        return byteBuffer.slice();
    }

    /**
     * Convenience getter for determining the size without materializing a slice of the buffer.
     * @return The internal buffer's size.
     */
    public int size() {
        return byteBuffer.limit();
    }

    @ParametersAreNonnullByDefault
    private final class ByteBufferOutputStream extends OutputStream {
        private final ByteBuffer underlying;

        public ByteBufferOutputStream(final ByteBuffer underlying) {
            this.underlying = Objects.requireNonNull(underlying);
        }

        @Override
        public void write(final int b) throws IOException {
            underlying.put((byte) b);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            underlying.put(b, off, len);
        }
    }
}
