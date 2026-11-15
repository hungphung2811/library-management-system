package lms.support.notification.domain;

public class InvalidNotificationException extends RuntimeException {

    public InvalidNotificationException(String message) {
        super(message);
    }
}
