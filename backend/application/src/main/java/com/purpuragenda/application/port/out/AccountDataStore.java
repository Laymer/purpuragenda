package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.booking.Money;
import com.purpuragenda.domain.customer.Input;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.UnaryOperator;
import static com.purpuragenda.domain.DomainFailure.require;

/** Optional profile and account-only history. Reads use one read-only snapshot; writes lock account then session. */
public interface AccountDataStore {
    Snapshot profile(SessionBinding session);
    History history(SessionBinding session,Cursor before,int limit);
    Receipt change(Command command,UnaryOperator<Profile> update);
    record Fields(String firstName,String lastName,String telephone) {
        public Fields { firstName=Input.text(firstName,0,100);lastName=Input.text(lastName,0,100);telephone=Input.text(telephone,0,40);if(!telephone.isEmpty())telephone=Input.phone(telephone); }
        @Override public String toString() { return "ProfileFields[REDACTED]"; }
    }
    record Profile(long version,Fields fields) {
        public Profile { require(version>0 && fields!=null,"INVALID_PROFILE"); }
        public static Profile empty() { return new Profile(1,new Fields("","","")); }
        @Override public String toString() { return "Profile[REDACTED]"; }
    }
    record Snapshot(String loginEmail,Profile profile,Instant observedAt) {
        public Snapshot { Objects.requireNonNull(loginEmail);Objects.requireNonNull(profile);Objects.requireNonNull(observedAt); }
        @Override public String toString() { return "AccountProfile[REDACTED]"; }
    }
    record Cursor(Instant start,UUID id) {
        public Cursor { require(start!=null && id!=null && start.equals(start.truncatedTo(ChronoUnit.MICROS)),"INVALID_HISTORY_CURSOR"); }
        @Override public String toString() { return "HistoryCursor[REDACTED]"; }
    }
    record BookingSummary(UUID id,String reference,Booking.State state,Instant start,Instant sessionEnd,boolean pendingExpired,String serviceName,Money price) {
        public BookingSummary { Objects.requireNonNull(id);Objects.requireNonNull(reference);Objects.requireNonNull(state);Objects.requireNonNull(start);Objects.requireNonNull(sessionEnd);Objects.requireNonNull(serviceName);Objects.requireNonNull(price); }
        @Override public String toString() { return "HistoryBooking[REDACTED]"; }
    }
    record History(List<BookingSummary> bookings,Cursor next,Instant observedAt) {
        public History { bookings=List.copyOf(bookings);Objects.requireNonNull(observedAt); }
        @Override public String toString() { return "AccountHistory[REDACTED]"; }
    }
    record Command(SessionBinding session,String key,String fingerprint) {
        public Command { Objects.requireNonNull(session);new CommandKey(new Scope(new UUID(0,0),"ACCOUNT_PROFILE",null),key,fingerprint); }
        @Override public String toString() { return "ProfileCommand[REDACTED]"; }
    }
}
