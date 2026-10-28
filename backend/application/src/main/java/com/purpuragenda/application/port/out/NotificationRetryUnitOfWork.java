package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.AdministratorOperationsQuery.NotificationState;
import java.util.*;
import java.util.function.Function;
import static com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Resource-locked retry of a booking job; persisted fresh administrator authority precedes receipt replay. */
public interface NotificationRetryUnitOfWork {
    Receipt retry(Command command,Function<Retry,Receipt> work);
    record Command(SessionBinding session,UUID jobId,String key,String fingerprint) {
        public Command { Objects.requireNonNull(session);require(jobId!=null,"INVALID_NOTIFICATION_RETRY");new CommandKey(new Scope(session.sessionId(),"RETRY_NOTIFICATION",jobId),key,fingerprint); }
        @Override public String toString(){return "NotificationRetryCommand[REDACTED]";}
    }
    record Job(UUID id,UUID bookingId,NotificationState state,int attempts,boolean deliverySuppressed) {
        public Job { Objects.requireNonNull(id);Objects.requireNonNull(bookingId);Objects.requireNonNull(state);require(attempts>=0,"INVALID_NOTIFICATION_RETRY"); }
        @Override public String toString(){return "NotificationRetryJob[REDACTED]";}
    }
    interface Retry extends Transaction {
        /** Locked booking job, excluding AUTH; current after synchronous hold expiry. */
        Job authorizedJob();
        /** Queue one explicit attempt with a reasoned audit. Preserve attempts, message ID and original delivery facts. */
        void queued(UUID auditId,String reason);
    }
}
