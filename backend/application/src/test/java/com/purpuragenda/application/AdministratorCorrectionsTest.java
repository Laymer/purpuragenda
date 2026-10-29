package com.purpuragenda.application;

import com.purpuragenda.application.privacy.AdministratorCorrections;
import com.purpuragenda.application.port.out.CustomerCorrectionStore;
import com.purpuragenda.application.port.out.CustomerCorrectionStore.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorCorrectionsTest {
    final UUID id=UUID.randomUUID();final SessionBinding session=new SessionBinding(id,"a".repeat(64));
    final Change change=new Change(Kind.NOTE,Map.of("text"," Synthetic cafe\u0301 note "),"Synthetic correction reason");
    final Target target=new Target(Kind.NOTE,id,1,Map.of("text","Original synthetic note"));
    final Preview preview=new Preview(id,id,target,change.values(),change.reason(),"a".repeat(64),Instant.EPOCH,1);
    final Receipt receipt=new Receipt(Code.OK,id,1);
    final CustomerCorrectionStore store=new CustomerCorrectionStore(){
        public Target target(SessionBinding s,UUID c,Kind k,UUID t){assertEquals(session,s);assertEquals(id,c);assertEquals(Kind.NOTE,k);assertEquals(id,t);return target;}
        public Receipt prepare(SessionBinding s,UUID c,UUID t,String key,Change v){assertEquals(session,s);assertEquals(id,c);assertEquals(id,t);assertEquals("key",key);assertEquals("Synthetic café note",v.values().get("text"));return receipt;}
        public Preview preview(SessionBinding s,UUID value){assertEquals(session,s);assertEquals(id,value);return preview;}
        public Receipt confirm(SessionBinding s,UUID value,String key,long version,Review r){assertEquals(session,s);assertEquals(id,value);assertEquals("key",key);assertEquals(1,version);assertTrue(r.confirmed());return receipt;}
        public int purge(int limit){throw new AssertionError("NO_IMPLICIT_PURGE");}
    };
    final AdministratorCorrections service=new AdministratorCorrections(store);
    @Test void normalizedTypedChangesCrossOnlyTheCorrectionPort(){
        assertSame(target,service.target(session,id,Kind.NOTE,id));assertSame(receipt,service.prepare(session,id,id,"key",change));assertSame(preview,service.preview(session,id));assertSame(receipt,service.confirm(session,id,"key",1,new Review("a".repeat(64),true)));
        var profile=new Change(Kind.PROFILE,Map.of("firstName"," Alex ","lastName","Synthetic","telephone","+32400123456"),change.reason());assertEquals("Alex",profile.values().get("firstName"));
        var intake=new Change(Kind.INTAKE,Map.of("firstName"," Alex ","lastName","Synthetic","telephone","+32400123456","birthDate","1990-01-01","birthTime","10:30:00","birthplace"," Synthetic ","message"," Hello "),change.reason());assertEquals("10:30",intake.values().get("birthTime"));assertEquals("Hello",intake.values().get("message"));
        assertEquals("CorrectionChange[REDACTED]",change.toString());assertEquals("CorrectionTarget[REDACTED]",target.toString());assertEquals("CorrectionPreview[REDACTED]",preview.toString());assertEquals("CorrectionReview[REDACTED]",new Review("a".repeat(64),true).toString());assertThrows(UnsupportedOperationException.class,()->change.values().put("text","altered"));
    }
    @Test void invalidShapesReasonsUnicodeReviewsAndKeysFailBeforeStorage(){
        assertThrows(DomainFailure.class,()->new Change(Kind.NOTE,Map.of("text",""),change.reason()));assertThrows(DomainFailure.class,()->new Change(Kind.NOTE,Map.of("text","bad\ud800"),change.reason()));assertThrows(DomainFailure.class,()->new Change(Kind.NOTE,Map.of("extra","Synthetic"),change.reason()));
        assertThrows(DomainFailure.class,()->new Change(Kind.NOTE,Map.of("text","Synthetic"),"short"));assertThrows(DomainFailure.class,()->new Review(null,true));assertThrows(DomainFailure.class,()->new Review("bad",true));assertThrows(DomainFailure.class,()->new Review("a".repeat(64),false));
        assertThrows(DomainFailure.class,()->service.prepare(session,id,id,"",change));assertThrows(DomainFailure.class,()->service.confirm(session,id,"",1,new Review("a".repeat(64),true)));assertThrows(DomainFailure.class,()->new Target(Kind.NOTE,id,0,Map.of()));
        assertThrows(NullPointerException.class,()->new AdministratorCorrections(null));assertThrows(NullPointerException.class,()->service.target(null,id,Kind.NOTE,id));assertThrows(NullPointerException.class,()->service.preview(session,null));assertThrows(NullPointerException.class,()->service.prepare(session,id,id,"key",null));assertThrows(NullPointerException.class,()->service.confirm(session,id,"key",1,null));
        var nullValue=new HashMap<String,String>();nullValue.put("text",null);assertThrows(DomainFailure.class,()->new Change(Kind.NOTE,nullValue,change.reason()));
    }
}
