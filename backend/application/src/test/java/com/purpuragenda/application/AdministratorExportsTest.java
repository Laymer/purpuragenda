package com.purpuragenda.application;

import com.purpuragenda.application.privacy.AdministratorExports;
import com.purpuragenda.application.port.out.CustomerExportStore;
import com.purpuragenda.application.port.out.CustomerExportStore.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorExportsTest {
    final UUID id=UUID.randomUUID();final SessionBinding session=new SessionBinding(id,"a".repeat(64));
    final Document document=new Document(id,id,Instant.EPOCH,"a".repeat(64),false,"{}");
    final Receipt receipt=new Receipt(Code.OK,id,1);
    final CustomerExportStore store=new CustomerExportStore(){
        public Receipt prepare(SessionBinding s,UUID c,String key,String reason){assertEquals(session,s);assertEquals(id,c);assertEquals("key",key);assertEquals("Synthetic café reason",reason);return receipt;}
        public Receipt release(SessionBinding s,UUID export,String key,Review review){assertEquals(session,s);assertEquals(id,export);assertEquals("key",key);assertTrue(review.reviewedThirdPartyInformation());return receipt;}
        public Document read(SessionBinding s,UUID export,boolean download){assertEquals(session,s);assertEquals(id,export);assertTrue(download);return document;}
        public int purge(int limit){throw new AssertionError("NO_IMPLICIT_PURGE");}
    };
    final AdministratorExports service=new AdministratorExports(store);
    @Test void validatesAndNormalizesBeforeTheAuthoritativeStore(){
        assertEquals("ExportRequest[REDACTED]",new AdministratorExports.Request("Synthetic reason").toString());
        assertEquals(receipt,service.prepare(session,id,"key"," Synthetic cafe\u0301 reason "));
        assertEquals(receipt,service.release(session,id,"key",new Review("a".repeat(64),true)));
        assertSame(document,service.read(session,id,true));assertEquals("CustomerExport[REDACTED]",document.toString());assertEquals("ExportReview[REDACTED]",new Review("a".repeat(64),true).toString());
    }
    @Test void invalidReasonKeyReviewAndMissingAuthorityNeverReachStore(){
        assertThrows(DomainFailure.class,()->service.prepare(session,id,"key","bad\ud800synthetic reason"));
        assertThrows(DomainFailure.class,()->service.prepare(session,id,"key","short"));assertThrows(DomainFailure.class,()->service.prepare(session,id,"","Synthetic reason"));
        assertThrows(DomainFailure.class,()->service.release(session,id,"",new Review("a".repeat(64),true)));
        assertThrows(DomainFailure.class,()->new Review(null,true));assertThrows(DomainFailure.class,()->new Review("invalid",true));assertThrows(DomainFailure.class,()->new Review("a".repeat(64),false));
        assertThrows(NullPointerException.class,()->new AdministratorExports(null));assertThrows(NullPointerException.class,()->service.prepare(null,id,"key","Synthetic reason"));assertThrows(NullPointerException.class,()->service.prepare(session,null,"key","Synthetic reason"));
        assertThrows(NullPointerException.class,()->service.release(null,id,"key",new Review("a".repeat(64),true)));assertThrows(NullPointerException.class,()->service.release(session,null,"key",new Review("a".repeat(64),true)));assertThrows(NullPointerException.class,()->service.release(session,id,"key",null));
        assertThrows(NullPointerException.class,()->service.read(null,id,false));assertThrows(NullPointerException.class,()->service.read(session,null,false));
    }
}
