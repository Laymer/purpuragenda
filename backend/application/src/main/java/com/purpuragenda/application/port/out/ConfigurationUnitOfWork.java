package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.scheduling.ConfigurationConflicts;
import com.purpuragenda.domain.scheduling.*;
import java.util.*;
import java.util.function.Function;
import static com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;

/**
 * One resource-locked administrator configuration transaction. The adapter derives the administrator only from the
 * persisted ADMIN session (enabled, enrolled, unrevoked, fresh MFA, current security epoch) before any receipt replay.
 */
public interface ConfigurationUnitOfWork {
    Receipt execute(Command command,Function<Configuration,Receipt> work);
    enum Operation { REPLACE_WEEKLY, SET_DATE_EXCEPTION, REMOVE_DATE_EXCEPTION, ADD_BLACKOUT, REMOVE_BLACKOUT, UPDATE_POLICY }
    record Command(SessionBinding session,Operation operation,String key,String fingerprint) {
        public Command {
            Objects.requireNonNull(session);Objects.requireNonNull(operation);
            new CommandKey(new Scope(session.sessionId(),operation.name(),null),key,fingerprint);
        }
        @Override public String toString() { return "ConfigurationCommand[REDACTED]"; }
    }
    interface Configuration extends Transaction {
        /** Persist the next calendar; its version must be exactly the current version plus one. */
        void saveCalendar(AvailabilityCalendar next);
        /** Append the next immutable policy version (current plus one) and make it current. */
        void savePolicy(SchedulingPolicy next);
        /** Audit the change with its reason and, per acknowledged conflicting booking, an audit and outbox follow-up. */
        void changedEffects(Operation operation,long version,String reason,List<ConfigurationConflicts.Conflict> acknowledged);
    }
}
