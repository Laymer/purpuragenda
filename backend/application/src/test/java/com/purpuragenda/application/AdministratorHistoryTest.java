package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AdministratorHistoryQuery.*;
import com.purpuragenda.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorHistoryTest {
    final Instant now=Instant.parse("2026-10-28T12:00:00Z");final UUID id=UUID.randomUUID();
    @Test void paginationRejectsUnboundedOrUnrepresentableInputs() {
        for(int limit:new int[]{-1,0,101,Integer.MAX_VALUE})assertThrows(DomainFailure.class,()->new Request(null,limit));
        assertEquals(1,new Request(null,1).limit());assertEquals(100,new Request(null,100).limit());
        for(Instant time:List.of(Instant.MIN,Instant.MAX,now.plusNanos(1),Instant.parse("0000-12-31T23:59:59Z"),Instant.parse("+10000-01-01T00:00:00Z")))
            assertThrows(DomainFailure.class,()->new Cursor(time,id));
        for(Instant time:List.of(Instant.parse("0001-01-01T00:00:00Z"),Instant.parse("9999-12-31T23:59:59.999999Z"),now))assertEquals(time,new Cursor(time,id).time());
        assertThrows(NullPointerException.class,()->new Cursor(null,id));assertThrows(NullPointerException.class,()->new Cursor(now,null));
    }
    @Test void privateProjectionsCannotLeakThroughLoggingOrMutablePageLists() {
        Note note=new Note(id,"Synthetic private text",now);Audit audit=new Audit(id,null,"ADMIN","SYNTHETIC","Synthetic private reason",now,null);
        Cursor cursor=new Cursor(now,id);Request request=new Request(cursor,1);
        var notes=new ArrayList<>(List.of(note));var events=new ArrayList<>(List.of(audit));
        NotePage np=new NotePage(notes,cursor,now);AuditPage ap=new AuditPage(events,cursor,now);notes.clear();events.clear();
        assertEquals(List.of(note),np.entries());assertEquals(List.of(audit),ap.entries());
        assertThrows(UnsupportedOperationException.class,()->np.entries().clear());assertThrows(UnsupportedOperationException.class,()->ap.entries().clear());
        for(Object value:List.of(note,audit,cursor,request,np,ap)) {assertTrue(value.toString().contains("REDACTED"));assertFalse(value.toString().contains("Synthetic"));assertFalse(value.toString().contains(id.toString()));}
        assertEquals(Set.of("id","bookingId","actorKind","type","reason","occurredAt","version"),Arrays.stream(Audit.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()));
    }
}
