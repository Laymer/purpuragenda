package com.purpuragenda.application.port.out;

import com.purpuragenda.domain.customer.Input;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Private in-memory delivery values: never serialize or log message content. */
public interface MailDelivery {
    enum Result { SENT, RETRY_LATER, FAILED, SUPPRESSED }
    record Message(UUID jobId, String recipient, String subject, String text) {
        public Message {
            Objects.requireNonNull(jobId);
            recipient = Input.email(recipient); subject = Input.text(subject, 1, 200);
            if (text == null || text.length() > 65_536 || text.indexOf('\0') >= 0
                    || text.getBytes(StandardCharsets.UTF_8).length > 65_536)
                throw new IllegalArgumentException("INVALID_MAIL_MESSAGE");
        }
        @Override public String toString() { return "MailMessage[job=" + jobId + ", content=REDACTED]"; }
    }
    Result send(Message message);
}
