package tw.dky.rokidfiles.storage;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class DuplicateScannerPrefixTest {
    @Test
    public void prefixHashReadsAtMostConfiguredBytes() throws IOException {
        byte[] content = new byte[DuplicateScanner.PREFIX_HASH_BYTES * 2];
        CountingInputStream input = new CountingInputStream(content);

        DuplicateScanner.hashPrefix(input);

        assertEquals(DuplicateScanner.PREFIX_HASH_BYTES, input.bytesRead);
    }

    @Test
    public void prefixHashSeparatesSameSizeFilesEarly() throws IOException {
        byte[] left = new byte[DuplicateScanner.PREFIX_HASH_BYTES + 1];
        byte[] right = new byte[left.length];
        left[0] = 1;
        right[0] = 2;

        assertNotEquals(
                DuplicateScanner.hashPrefix(new ByteArrayInputStream(left)),
                DuplicateScanner.hashPrefix(new ByteArrayInputStream(right)));
    }

    private static final class CountingInputStream extends ByteArrayInputStream {
        int bytesRead;

        CountingInputStream(byte[] buffer) {
            super(buffer);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        @Override
        public synchronized int read() {
            int value = super.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }
    }
}
