package com.purpuragenda.application;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.AdministratorOperationsQuery.NotificationState;
import com.purpuragenda.application.port.out.NotificationRetryUnitOfWork;
import com.purpuragenda.application.port.out.NotificationRetryUnitOfWork.*;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.AdministratorNotificationRetry;
import com.purpuragenda.application.scheduling.AdministratorNotificationRetry.Request;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorNotificationRetryTest {
    final Fixture f=new Fixture();final SessionBinding session=new SessionBinding(UUID.randomUUID(),"b".repeat(64));final UUID job=UUID.randomUUID();
    final AdministratorNotificationRetry retry=new AdministratorNotificationRetry(f);
    Receipt retry(long attempts,String reason){return retry.retry(session,job,"synthetic-retry",new Request(attempts,reason));}
    @Test void queuesOneAuditedRetryWithoutChangingBookingOrAttemptHistory() {
        Booking original=f.current;var result=retry(7," Synthetic retry reason ");assertEquals(new Receipt(Code.OK,f.audit,1),result);assertEquals("Synthetic retry reason",f.reason);assertEquals(1,f.queued);assertEquals(7,f.count);
        assertSame(original,f.current);assertEquals(0,f.mutationSaves);assertEquals(0,f.effects);assertEquals(List.of("expiry","attempt","authorized-job","queued"),f.steps);
        assertSame(session,f.command.session());assertEquals(job,f.command.jobId());assertEquals("synthetic-retry",f.command.key());
    }
    @Test void retriesRequireTheCurrentFailedAttemptAndNeverBypassOperatorSuppression() {
        assertEquals(Code.STALE_VERSION,retry(6,"Synthetic retry reason").code());assertEquals(0,f.queued);
        for(var state:NotificationState.values())if(state!=NotificationState.FAILED){f.state=state;assertEquals(Code.NOTIFICATION_NOT_RETRYABLE,retry(7,"Synthetic retry reason").code());}
        f.state=NotificationState.FAILED;f.suppressed=true;assertEquals(Code.DELIVERY_SUPPRESSED,retry(7,"Synthetic retry reason").code());assertEquals(0,f.queued);
    }
    @Test void expiryReclamationPrecedesRetryAndAlsoSurvivesExpectedRejection() {
        f.current=f.pending(f.current.start());f.now=f.current.expiresAt();assertTrue(retry(7,"Synthetic retry reason").success());assertEquals(Booking.State.EXPIRED,f.current.state());assertEquals(1,f.expiryEffects);assertEquals(0,f.mutationSaves);
        f.current=f.pending(f.current.start().plusSeconds(86400));f.now=f.current.expiresAt();f.suppressed=true;assertEquals(Code.DELIVERY_SUPPRESSED,retry(7,"Synthetic retry reason").code());assertEquals(Booking.State.EXPIRED,f.current.state());assertEquals(2,f.expiryEffects);
    }
    @Test void normalizedReasonsAndExpectedAttemptCountsBindTheFingerprintWithoutLoggingPrivateData() {
        f.replay=new Receipt(Code.OK,UUID.randomUUID(),1);retry(7,"Synthetic cafe\u0301 reason");String first=f.command.fingerprint();retry(7," Synthetic café reason ");assertEquals(first,f.command.fingerprint());retry(8,"Synthetic café reason");assertNotEquals(first,f.command.fingerprint());retry(7,"Synthetic other reason");assertNotEquals(first,f.command.fingerprint());
        for(Object value:List.of(f.command,new Request(7,"Synthetic private reason"),new Job(job,f.current.id(),NotificationState.FAILED,7,false))){assertTrue(value.toString().contains("REDACTED"));assertFalse(value.toString().contains("Synthetic"));}
    }
    @Test void replayDoesNotQueueAgainAndPersistedAuthorityStillPrecedesIt() {
        var first=retry(7,"Synthetic retry reason");f.replay=first;f.steps.clear();assertSame(first,retry(7,"Synthetic retry reason"));assertEquals(1,f.queued);assertTrue(f.steps.isEmpty());
        for(var kind:List.of(Failure.Kind.AUTHENTICATION_REQUIRED,Failure.Kind.FRESH_AUTHENTICATION_REQUIRED,Failure.Kind.FORBIDDEN)){f.authority=new Failure(kind);assertSame(f.authority,assertThrows(Failure.class,()->retry(7,"Synthetic retry reason")));}assertEquals(1,f.queued);
    }
    @Test void invalidInputsNeverReachPersistenceAndValidReasonBoundsIncludeSupplementaryUnicode() {
        for(long attempts:new long[]{-1,0,Integer.MAX_VALUE,Long.MAX_VALUE})assertThrows(DomainFailure.class,()->new Request(attempts,"Synthetic reason"));
        for(String reason:Arrays.asList(null,"short","x".repeat(501),"bad\ncontrol reason","bad\ud800reason"))assertThrows(DomainFailure.class,()->new Request(7,reason));
        assertEquals(500,new Request(1,"x".repeat(500)).reason().length());assertEquals("😀".repeat(10),new Request(Integer.MAX_VALUE-1,"😀".repeat(10)).reason());
        var request=new Request(7,"Synthetic reason");assertThrows(NullPointerException.class,()->new AdministratorNotificationRetry(null));assertThrows(NullPointerException.class,()->retry.retry(session,job,"key",null));assertThrows(NullPointerException.class,()->retry.retry(null,job,"key",request));assertThrows(DomainFailure.class,()->retry.retry(session,null,"key",request));assertThrows(DomainFailure.class,()->retry.retry(session,job," ",request));assertThrows(DomainFailure.class,()->new Command(session,job,"key","bad"));assertThrows(DomainFailure.class,()->new Job(job,f.current.id(),NotificationState.FAILED,-1,false));assertNull(f.command);
    }
    @Test void lookupAndUnexpectedStorageFailuresCannotReportSuccess() {
        f.lookup=new Failure(Failure.Kind.NOT_FOUND);assertSame(f.lookup,assertThrows(Failure.class,()->retry(7,"Synthetic retry reason")));f.lookup=null;f.write=new Failure(Failure.Kind.STORAGE_FAILURE);assertSame(f.write,assertThrows(Failure.class,()->retry(7,"Synthetic retry reason")));assertEquals(0,f.queued);
    }
    static final class Fixture extends CustomerBookingChangesTest.Fixture implements NotificationRetryUnitOfWork,Retry {
        NotificationRetryUnitOfWork.Command command;NotificationState state=NotificationState.FAILED;int count=7,queued;boolean suppressed;UUID audit;String reason;RuntimeException authority,lookup,write;
        public Receipt retry(NotificationRetryUnitOfWork.Command value,Function<Retry,Receipt> work){if(authority!=null)throw authority;command=value;return replay==null?work.apply(this):replay;}
        public Job authorizedJob(){assertTrue(attempting);steps.add("authorized-job");if(lookup!=null)throw lookup;return new Job(command.jobId(),current.id(),state,count,suppressed);}
        public void queued(UUID id,String value){assertTrue(attempting);steps.add("queued");if(write!=null)throw write;queued++;audit=id;reason=value;}
    }
}
