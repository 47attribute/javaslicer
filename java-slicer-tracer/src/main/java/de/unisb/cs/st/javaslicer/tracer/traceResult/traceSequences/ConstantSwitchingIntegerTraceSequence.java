package de.unisb.cs.st.javaslicer.tracer.traceResult.traceSequences;

import java.io.DataInput;
import java.io.IOException;
import java.util.Iterator;
import java.util.zip.GZIPInputStream;

import de.unisb.cs.st.javaslicer.tracer.util.EmptyIterator;
import de.unisb.cs.st.javaslicer.tracer.util.MultiplexedFileReader;
import de.unisb.cs.st.javaslicer.tracer.util.OptimizedDataInputStream;
import de.unisb.cs.st.javaslicer.tracer.util.MultiplexedFileReader.MultiplexInputStream;

public class ConstantSwitchingIntegerTraceSequence extends ConstantIntegerTraceSequence {

    protected final MultiplexedFileReader file;
    protected final boolean gzipped;
    protected final int streamIndex;

    public ConstantSwitchingIntegerTraceSequence(final MultiplexedFileReader file, final boolean gzipped, final int streamIndex) {
        this.file = file;
        this.gzipped = gzipped;
        this.streamIndex = streamIndex;
    }

    @Override
    public Iterator<Integer> backwardIterator() {
        try {
            return new BackwardIterator();
        } catch (final IOException e) {
            return new EmptyIterator<Integer>();
        }
    }

    public static ConstantSwitchingIntegerTraceSequence readFrom(final DataInput in, final MultiplexedFileReader file)
            throws IOException {
        final boolean gzipped = in.readBoolean();
        final int streamIndex = in.readInt();
        if (!file.getStreamIds().contains(streamIndex))
            throw new IOException("corrupted data");
        return new ConstantSwitchingIntegerTraceSequence(file, gzipped, streamIndex);
    }

    public class BackwardIterator implements Iterator<Integer> {

        private final MultiplexInputStream multiplexedStream;
        private final OptimizedDataInputStream dataIn;
        private boolean error;

        public BackwardIterator() throws IOException {
            this.multiplexedStream = ConstantSwitchingIntegerTraceSequence.this.file.getInputStream(ConstantSwitchingIntegerTraceSequence.this.streamIndex);
            this.dataIn = new OptimizedDataInputStream(
                    ConstantSwitchingIntegerTraceSequence.this.gzipped ? new GZIPInputStream(this.multiplexedStream) : this.multiplexedStream,
                    true);
        }

        public boolean hasNext() {
            if (this.error)
                return false;
            try {
                return !this.multiplexedStream.isEOF();
            } catch (final IOException e) {
                this.error = true;
                return false;
            }
        }

        public Integer next() {
            try {
                return this.dataIn.readInt();
            } catch (final IOException e) {
                this.error = true;
                return null;
            }
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

}
