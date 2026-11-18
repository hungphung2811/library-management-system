package lms.identity.tenantdata.infrastructure.storage;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Mã hóa AES-256-GCM theo khung (frame) để mã hóa/giải mã theo luồng với bộ nhớ cố định (GCM của JDK giải mã phải đệm cả
 * thông điệp, nên không dùng một khung cho cả file).
 *
 * <p>Định dạng: {@code "LMSE1"} (5 byte) + tiền tố nonce ngẫu nhiên (8 byte), rồi các khung {@code [độ dài int32][mã
 * hóa + tag 16 byte]}. Nonce mỗi khung = tiền tố(8) + bộ đếm khung(4). AAD mỗi khung = bộ đếm(4) + cờ "khung cuối"(1):
 * đổi/sắp xếp lại khung hoặc CẮT bớt đuôi file đều làm giải mã thất bại (khung cuối bị cắt thì khung trước đó được xác
 * thực với cờ "không phải cuối" nhưng bị đọc như cuối). Khung cuối luôn có mặt (có thể rỗng).
 */
final class ChunkedAesGcm {

    static final int CHUNK = 64 * 1024;
    static final byte[] MAGIC = {'L', 'M', 'S', 'E', '1'};
    private static final int NONCE_PREFIX = 8;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ChunkedAesGcm() {
    }

    private static Cipher cipher(int mode, SecretKey key, byte[] prefix, int counter, boolean last) {
        try {
            ByteBuffer nonce = ByteBuffer.allocate(12).put(prefix).putInt(counter);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce.array()));
            c.updateAAD(ByteBuffer.allocate(5).putInt(counter).put((byte) (last ? 1 : 0)).array());
            return c;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không khởi tạo được AES-GCM", e);
        }
    }

    static OutputStream encrypt(OutputStream out, SecretKey key) throws IOException {
        return new EncryptingStream(out, key);
    }

    static InputStream decrypt(InputStream in, SecretKey key) throws IOException {
        return new DecryptingStream(in, key);
    }

    private static final class EncryptingStream extends OutputStream {

        private final OutputStream out;
        private final SecretKey key;
        private final byte[] prefix = new byte[NONCE_PREFIX];
        private final byte[] buf = new byte[CHUNK];
        private int len;
        private int counter;
        private boolean closed;

        EncryptingStream(OutputStream out, SecretKey key) throws IOException {
            this.out = out;
            this.key = key;
            RANDOM.nextBytes(prefix);
            out.write(MAGIC);
            out.write(prefix);
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int n) throws IOException {
            if (closed) {
                throw new IOException("Luồng đã đóng");
            }
            while (n > 0) {
                if (len == CHUNK) {
                    emit(false);
                }
                int take = Math.min(n, CHUNK - len);
                System.arraycopy(b, off, buf, len, take);
                len += take;
                off += take;
                n -= take;
            }
        }

        private void emit(boolean last) throws IOException {
            try {
                byte[] enc = cipher(Cipher.ENCRYPT_MODE, key, prefix, counter, last).doFinal(buf, 0, len);
                out.write(ByteBuffer.allocate(4).putInt(enc.length).array());
                out.write(enc);
            } catch (GeneralSecurityException e) {
                throw new IOException("Mã hóa thất bại", e);
            }
            counter++;
            len = 0;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                emit(true);
            } finally {
                out.close();
            }
        }
    }

    private static final class DecryptingStream extends FilterInputStream {

        private final SecretKey key;
        private final byte[] prefix = new byte[NONCE_PREFIX];
        private byte[] plain = new byte[0];
        private int pos;
        private int counter;
        private boolean finished;
        private byte[] nextLen;

        DecryptingStream(InputStream in, SecretKey key) throws IOException {
            super(in);
            this.key = key;
            byte[] magic = in.readNBytes(MAGIC.length);
            if (magic.length != MAGIC.length || !java.util.Arrays.equals(magic, MAGIC)) {
                throw new IOException("Định dạng file mã hóa không hợp lệ");
            }
            if (in.readNBytes(prefix, 0, NONCE_PREFIX) != NONCE_PREFIX) {
                throw new EOFException("File mã hóa bị cắt");
            }
            nextLen = in.readNBytes(4);
        }

        private boolean fill() throws IOException {
            if (finished) {
                return false;
            }
            if (nextLen == null || nextLen.length != 4) {
                throw new EOFException("File mã hóa bị cắt");
            }
            int frame = ByteBuffer.wrap(nextLen).getInt();
            if (frame < TAG_BYTES || frame > CHUNK + TAG_BYTES) {
                throw new IOException("Khung mã hóa không hợp lệ");
            }
            byte[] enc = in.readNBytes(frame);
            if (enc.length != frame) {
                throw new EOFException("File mã hóa bị cắt");
            }
            byte[] after = in.readNBytes(4);
            boolean last = after.length == 0;
            if (!last && after.length != 4) {
                throw new EOFException("File mã hóa bị cắt");
            }
            try {
                plain = cipher(Cipher.DECRYPT_MODE, key, prefix, counter, last).doFinal(enc);
            } catch (GeneralSecurityException e) {
                throw new IOException("File mã hóa bị sửa hoặc sai khóa", e);
            }
            counter++;
            pos = 0;
            nextLen = after;
            finished = last;
            return true;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int n) throws IOException {
            if (n == 0) {
                return 0;
            }
            while (pos >= plain.length) {
                if (!fill()) {
                    return -1;
                }
            }
            int take = Math.min(n, plain.length - pos);
            System.arraycopy(plain, pos, b, off, take);
            pos += take;
            return take;
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }
}
