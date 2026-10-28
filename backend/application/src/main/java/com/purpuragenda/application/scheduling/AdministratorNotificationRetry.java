package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.AdministratorOperationsQuery.NotificationState;
import com.purpuragenda.application.port.out.NotificationRetryUnitOfWork;
import com.purpuragenda.application.port.out.NotificationRetryUnitOfWork.Command;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.customer.Input;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Retry does not bypass relevance checks: the ordinary booking worker still decides whether a message may be sent. */
public final class AdministratorNotificationRetry {
    private final NotificationRetryUnitOfWork unit;
    public AdministratorNotificationRetry(NotificationRetryUnitOfWork unit){this.unit=Objects.requireNonNull(unit);}
    public record Request(long expectedAttempts,String reason) {
        public Request { require(expectedAttempts>0 && expectedAttempts<Integer.MAX_VALUE,"INVALID_NOTIFICATION_RETRY");reason=Input.reason(reason);require(reason.codePoints().noneMatch(c->c>=0xd800 && c<=0xdfff),"INVALID_NOTIFICATION_RETRY"); }
        @Override public String toString(){return "NotificationRetryRequest[REDACTED]";}
    }
    public Receipt retry(SessionBinding session,UUID jobId,String key,Request request) {
        Objects.requireNonNull(request);String fingerprint=CommandKey.fingerprint(List.of(Long.toString(request.expectedAttempts()),request.reason()));
        return unit.retry(new Command(session,jobId,key,fingerprint),tx->SchedulingCommands.within(tx,()->{
            var job=tx.authorizedJob();require(!job.deliverySuppressed(),"DELIVERY_SUPPRESSED");require(job.attempts()==request.expectedAttempts(),"STALE_VERSION");
            require(job.state()==NotificationState.FAILED,"NOTIFICATION_NOT_RETRYABLE");UUID audit=UUID.randomUUID();tx.queued(audit,request.reason());return new Receipt(Code.OK,audit,1);
        }));
    }
}
