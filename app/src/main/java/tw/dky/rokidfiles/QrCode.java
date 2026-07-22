package tw.dky.rokidfiles;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 極小型、無網路依賴的 QR Code Model 2 編碼器。
 * 僅使用本 App 分享網址所需的 Version 3-L / byte mode（最多 53 個 UTF-8 bytes）。
 */
final class QrCode {
    static final int SIZE = 29;
    private static final int DATA_CODEWORDS = 55;
    private static final int ECC_CODEWORDS = 15;

    private final boolean[][] modules = new boolean[SIZE][SIZE];
    private final boolean[][] function = new boolean[SIZE][SIZE];

    static QrCode encodeText(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (data.length > 53) {
            throw new IllegalArgumentException("QR payload is too long");
        }
        byte[] codewords = makeCodewords(data);
        QrCode qr = new QrCode();
        qr.drawFunctionPatterns();
        qr.drawCodewords(codewords);
        qr.applyMask(0);
        qr.drawFormatBits(0);
        return qr;
    }

    boolean get(int x, int y) {
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) {
            return false;
        }
        return modules[y][x];
    }

    private static byte[] makeCodewords(byte[] payload) {
        BitBuffer bits = new BitBuffer(DATA_CODEWORDS * 8);
        bits.append(0x4, 4); // byte mode
        bits.append(payload.length, 8);
        for (byte value : payload) bits.append(value & 0xff, 8);
        bits.append(0, Math.min(4, DATA_CODEWORDS * 8 - bits.length));
        while ((bits.length & 7) != 0) bits.append(0, 1);

        byte[] data = new byte[DATA_CODEWORDS];
        int usedBytes = bits.length / 8;
        System.arraycopy(bits.bytes, 0, data, 0, usedBytes);
        for (int index = usedBytes; index < data.length; index++) {
            data[index] = (byte) ((index - usedBytes) % 2 == 0 ? 0xec : 0x11);
        }
        byte[] divisor = reedSolomonDivisor(ECC_CODEWORDS);
        byte[] remainder = reedSolomonRemainder(data, divisor);
        byte[] result = Arrays.copyOf(data, DATA_CODEWORDS + ECC_CODEWORDS);
        System.arraycopy(remainder, 0, result, DATA_CODEWORDS, ECC_CODEWORDS);
        return result;
    }

    private void drawFunctionPatterns() {
        for (int index = 0; index < SIZE; index++) {
            setFunction(6, index, index % 2 == 0);
            setFunction(index, 6, index % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(SIZE - 4, 3);
        drawFinder(3, SIZE - 4);
        drawAlignment(22, 22);
        drawFormatBits(0); // 同時保留格式資訊區域
    }

    private void drawFinder(int centerX, int centerY) {
        for (int y = -4; y <= 4; y++) {
            for (int x = -4; x <= 4; x++) {
                int distance = Math.max(Math.abs(x), Math.abs(y));
                setFunction(centerX + x, centerY + y, distance != 2 && distance != 4);
            }
        }
    }

    private void drawAlignment(int centerX, int centerY) {
        for (int y = -2; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                setFunction(centerX + x, centerY + y,
                        Math.max(Math.abs(x), Math.abs(y)) != 1);
            }
        }
    }

    private void drawFormatBits(int mask) {
        int data = (1 << 3) | mask; // error correction level L = 01
        int remainder = data;
        for (int index = 0; index < 10; index++) {
            remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
        }
        int bits = ((data << 10) | remainder) ^ 0x5412;
        for (int index = 0; index <= 5; index++) setFunction(8, index, bit(bits, index));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int index = 9; index < 15; index++) {
            setFunction(14 - index, 8, bit(bits, index));
        }
        for (int index = 0; index < 8; index++) {
            setFunction(SIZE - 1 - index, 8, bit(bits, index));
        }
        for (int index = 8; index < 15; index++) {
            setFunction(8, SIZE - 15 + index, bit(bits, index));
        }
        setFunction(8, SIZE - 8, true);
    }

    private void drawCodewords(byte[] codewords) {
        int bitIndex = 0;
        for (int right = SIZE - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vertical = 0; vertical < SIZE; vertical++) {
                int y = ((right + 1) & 2) == 0 ? SIZE - 1 - vertical : vertical;
                for (int column = 0; column < 2; column++) {
                    int x = right - column;
                    if (!function[y][x] && bitIndex < codewords.length * 8) {
                        modules[y][x] = bit(codewords[bitIndex >>> 3] & 0xff,
                                7 - (bitIndex & 7));
                        bitIndex++;
                    }
                }
            }
        }
        if (bitIndex != codewords.length * 8) {
            throw new IllegalStateException("QR matrix did not consume every codeword");
        }
    }

    private void applyMask(int mask) {
        if (mask != 0) throw new IllegalArgumentException("Unsupported QR mask");
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (!function[y][x] && ((x + y) & 1) == 0) modules[y][x] ^= true;
            }
        }
    }

    private void setFunction(int x, int y, boolean dark) {
        if (x < 0 || y < 0 || x >= SIZE || y >= SIZE) return;
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private static boolean bit(int value, int index) {
        return ((value >>> index) & 1) != 0;
    }

    private static byte[] reedSolomonDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int index = 0; index < degree; index++) {
            for (int factor = 0; factor < degree; factor++) {
                result[factor] = (byte) multiply(result[factor] & 0xff, root);
                if (factor + 1 < degree) result[factor] ^= result[factor + 1];
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    private static byte[] reedSolomonRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte value : data) {
            int factor = (value ^ result[0]) & 0xff;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int index = 0; index < result.length; index++) {
                result[index] ^= (byte) multiply(divisor[index] & 0xff, factor);
            }
        }
        return result;
    }

    private static int multiply(int left, int right) {
        int result = 0;
        for (int index = 7; index >= 0; index--) {
            result = (result << 1) ^ ((result >>> 7) * 0x11d);
            result ^= ((right >>> index) & 1) * left;
        }
        return result & 0xff;
    }

    private static final class BitBuffer {
        final byte[] bytes;
        int length;

        BitBuffer(int capacityBits) {
            bytes = new byte[(capacityBits + 7) / 8];
        }

        void append(int value, int count) {
            if (count < 0 || length + count > bytes.length * 8) {
                throw new IllegalArgumentException("QR bit buffer overflow");
            }
            for (int index = count - 1; index >= 0; index--, length++) {
                if (((value >>> index) & 1) != 0) {
                    bytes[length >>> 3] |= (byte) (1 << (7 - (length & 7)));
                }
            }
        }
    }
}
