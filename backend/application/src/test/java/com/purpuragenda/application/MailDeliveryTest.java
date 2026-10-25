package com.purpuragenda.application;

import com.purpuragenda.application.port.out.MailDelivery;
import com.purpuragenda.application.port.out.MailDelivery.Message;
import com.purpuragenda.domain.DomainFailure;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MailDeliveryTest {
    @Test void privateMessageNormalizesHeadersPreservesBodyAndRedactsDiagnostics() {
        UUID id=UUID.randomUUID(); String body="Synthetic private " + UUID.randomUUID() + "\n.leading line\n";
        var message=new Message(id,"SYNTHETIC@example.test"," Synthetic subject ",body);
        assertTrue(id.equals(message.jobId())); assertTrue(message.recipient().equals("synthetic@example.test"));
        assertTrue(message.subject().equals("Synthetic subject")); assertTrue(message.text().equals(body));
        assertTrue(message.toString().contains("REDACTED"));
        assertFalse(message.toString().contains(body)); assertFalse(message.toString().contains(message.recipient()));
        assertEquals(4,MailDelivery.Result.values().length);
        assertTrue(new Message(id,"synthetic@example.test","Subject","").text().isEmpty());
        assertEquals(65536,new Message(id,"synthetic@example.test","Subject","x".repeat(65536)).text().length());
    }
    @Test void headerInjectionAndInvalidBodyCannotReachTransport() {
        UUID id=UUID.randomUUID();
        assertThrows(NullPointerException.class,()->new Message(null,"synthetic@example.test","Subject","Body"));
        assertThrows(DomainFailure.class,()->new Message(id,"synthetic@example.test\r\nBcc: other@example.test","Subject","Body"));
        assertThrows(DomainFailure.class,()->new Message(id,"synthetic@example.test","Subject\r\nBcc: other@example.test","Body"));
        for(String text:new String[]{null,"x".repeat(65537),"\0","é".repeat(40000)})
            assertThrows(IllegalArgumentException.class,()->new Message(id,"synthetic@example.test","Subject",text));
    }
}
