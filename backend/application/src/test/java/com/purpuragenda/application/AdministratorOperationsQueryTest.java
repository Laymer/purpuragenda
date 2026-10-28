package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AdministratorOperationsQuery.*;
import com.purpuragenda.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorOperationsQueryTest {
    @Test void pagesAreBoundedImmutableAndRedacted() {
        UUID id=UUID.randomUUID();assertEquals(1,new Request(null,1).limit());assertEquals(id,new Request(id,100).after());
        for(int limit:new int[]{0,101})assertThrows(DomainFailure.class,()->new Request(null,limit));
        var entries=new ArrayList<>(List.of("synthetic-private"));var page=new Page<>(entries,id,Instant.EPOCH);entries.clear();assertEquals(1,page.entries().size());assertThrows(UnsupportedOperationException.class,()->page.entries().clear());
        assertEquals(id,page.next());assertEquals(Instant.EPOCH,page.observedAt());assertFalse(page.toString().contains("synthetic"));assertTrue(new Request(id,1).toString().contains("REDACTED"));
    }
    @Test void financialAndNotificationProjectionsExcludeDeliverySecrets() {
        UUID id=UUID.randomUUID();var refund=new Refund(id,id,"synthetic-reference","CANCELLED","EUR",1000,400,1000,600,"LATE_RECEIPT",Instant.EPOCH);
        var notification=new Notification(id,id,"synthetic-reference","CUSTOMER","BOOKING_CONFIRMED",1,NotificationState.FAILED,7,Instant.EPOCH,"SMTP_DELIVERY_FAILED");
        assertEquals(1000,refund.dueMinor());assertEquals(600,refund.outstandingMinor());assertEquals(NotificationState.FAILED,notification.state());
        for(Object value:List.of(refund,notification)){assertTrue(value.toString().contains("REDACTED"));assertFalse(value.toString().contains("synthetic"));}
        var fields=Arrays.stream(Notification.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();for(String privateField:List.of("recipient","encryptedEnvelope","token","leaseToken","challengeId"))assertFalse(fields.contains(privateField));
    }
}
