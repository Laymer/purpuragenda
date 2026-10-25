package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.in.Actor;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.payment.PaymentLedger;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** All commands execute with the same resource lock and a single authoritative time. */
public interface SchedulingUnitOfWork {
    <T> T execute(Function<Transaction,T> work);
    <T> T read(Function<View,T> work);
    interface View {
        Instant now();
        List<ServiceDefinition> services();
        SchedulingPolicy policy();
        AvailabilityCalendar calendar();
        List<Booking> bookings();
        String email(UUID customer);
        PaymentLedger ledger(UUID booking);
        int requestCount(UUID customer, LocalDate day);
    }
    interface Transaction extends View {
        Optional<Receipt> replay(String scope,String key,String fingerprint);
        void saveReceipt(String scope,String key,String fingerprint,Receipt receipt);
        Receipt attempt(Supplier<Receipt> mutation);
        void save(Booking booking);
        void saveLedger(UUID booking,PaymentLedger ledger);
        void charge(Booking booking, LocalDate submissionDay);
        void bindCreationGrant(Actor actor,UUID booking);
        void event(Booking booking,String type,Actor actor,String reason);
        void saveService(ServiceDefinition service);
        void saveConfiguration(SchedulingPolicy policy,AvailabilityCalendar calendar,Actor actor,String reason);
        void addNote(UUID booking,String note,Actor actor);
        String verifyContact(UUID booking,String tokenHash,String browserHash);
        void contactChanged(Booking previous,Booking updated,Actor actor);
        void recordOverride(Booking booking,SchedulingRules.Override override,Actor actor);
    }
    record Receipt(String code, UUID bookingId, long version, String warning) {
        public boolean success() { return "OK".equals(code); }
        public static Receipt ok(Booking booking) { return new Receipt("OK",booking.id(),booking.version(),null); }
    }
}
