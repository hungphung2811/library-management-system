package lms.demo.runner;

/** Seed bị {@link DemoSeedGuard} từ chối: chưa ghi gì. */
public class DemoSeedRefusedException extends RuntimeException {

    public DemoSeedRefusedException(String message) {
        super(message);
    }
}
