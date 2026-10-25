package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.AdminBookingUnitOfWork;
import com.purpuragenda.application.port.out.AdminBookingUnitOfWork.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.Customer;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.*;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import static com.purpuragenda.domain.DomainFailure.require;
import static com.purpuragenda.domain.scheduling.SchedulingPolicy.BUSINESS_ZONE;

/**
 * Administrator creation. No identity or administrator flag is accepted; authority comes from the persisted session
 * inside the transaction. Overrides name explicit rules plus a reason; reservation overlap is never overridable and
 * the submission quota is never charged.
 */
public final class AdminBooking {
    private final AdminBookingUnitOfWork unit;
    private final String noticeVersion;
    private final Set<String> meetingHosts;
    public AdminBooking(AdminBookingUnitOfWork unit,String noticeVersion,Set<String> meetingHosts) {
        this.unit=Objects.requireNonNull(unit);this.noticeVersion=Input.text(noticeVersion,1,120);this.meetingHosts=Set.copyOf(meetingHosts);
    }
    /** A participant URL confirms immediately (manual basis); without one the request is pending with a normal hold. */
    public record Request(UUID serviceId,long serviceVersion,long policyVersion,long calendarVersion,Instant start,String customerEmail,
                          Intake intake,String displayZone,String noticeVersion,String meetingUrl,SchedulingRules.Override override) {
        public Request {
            require(serviceId!=null && serviceVersion>0 && policyVersion>0 && calendarVersion>0 && start!=null && intake!=null && override!=null,"INVALID_BOOKING_REQUEST");
            require(start.equals(start.truncatedTo(ChronoUnit.MICROS)),"INVALID_BOOKING_REQUEST");
            customerEmail=Input.email(customerEmail);Input.zone(displayZone);noticeVersion=Input.text(noticeVersion,1,120);
            try { start.atZone(BUSINESS_ZONE).toLocalDate().plusDays(1);ReservationInterval.reserve(start); }
            catch(DateTimeException | ArithmeticException invalid) { throw new DomainFailure("INVALID_BOOKING_REQUEST"); }
            // Only an explicit non-overridable subset may be named: nothing may name a blackout without the rule.
            require(override.affectedBlackoutIds().isEmpty() || override.rules().contains(SchedulingRules.Rule.HARD_BLACKOUT),"INVALID_BOOKING_REQUEST");
        }
        @Override public String toString() { return "AdminBookingRequest[REDACTED]"; }
        String fingerprint() {
            List<String> fields=new ArrayList<>(List.of(serviceId.toString(),Long.toString(serviceVersion),Long.toString(policyVersion),
                    Long.toString(calendarVersion),start.toString(),customerEmail,displayZone,noticeVersion,intake.firstName(),intake.lastName(),
                    intake.telephone(),intake.birthDate().toString(),intake.birthTime().toString(),intake.birthplace(),intake.message(),
                    meetingUrl==null?"-":meetingUrl,override.rules().isEmpty()?"":override.reason()));
            fields.add(override.rules().stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
            fields.add(override.affectedBlackoutIds().stream().map(UUID::toString).sorted().collect(Collectors.joining(",")));
            return CommandKey.fingerprint(fields);
        }
    }
    public Receipt create(SessionBinding session,String idempotencyKey,Request request) {
        Objects.requireNonNull(request);
        // Request validation before any persistence. A malformed participant URL is a caller error, not a receipt.
        Meeting meeting=request.meetingUrl()==null?null:Meeting.parse(request.meetingUrl(),meetingHosts);
        return unit.create(new Command(session,idempotencyKey,request.fingerprint()),
                tx -> SchedulingCommands.within(tx,() -> create(tx,request,meeting)));
    }
    private Receipt create(Creation tx,Request request,Meeting meeting) {
        Instant now=tx.now();
        ServiceDefinition service=tx.services().stream().filter(s -> s.id().equals(request.serviceId()) && s.active())
                .findFirst().orElseThrow(() -> new DomainFailure("SERVICE_UNAVAILABLE"));
        require(service.version()==request.serviceVersion(),"SERVICE_CHANGED");
        SchedulingPolicy policy=tx.policy();require(policy.version()==request.policyVersion(),"POLICY_CHANGED");
        AvailabilityCalendar calendar=tx.calendar();require(calendar.version()==request.calendarVersion(),"CALENDAR_CHANGED");
        require(noticeVersion.equals(request.noticeVersion()),"NOTICE_CHANGED");request.intake().validateAt(now);
        ReservationInterval reservation=ReservationInterval.reserve(request.start());
        LocalDate day=request.start().atZone(BUSINESS_ZONE).toLocalDate();
        Instant end=day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        if(reservation.end().isAfter(end))end=reservation.end();
        List<Booking> occupied=tx.bookings(new ReservationInterval(day.atStartOfDay(BUSINESS_ZONE).toInstant(),end))
                .stream().filter(b -> b.occupies(now)).toList();
        int daily=(int)occupied.stream().filter(b -> b.businessDate().equals(day)).count();
        Customer customer=tx.customer(request.customerEmail());
        // The submission limit is a customer-request rule: administrator creation neither checks nor charges it.
        SchedulingRules.validate(request.start(),now,policy,calendar,daily,tx.quota(customer.id()).active(),0,
                occupied.stream().map(Booking::interval).toList(),request.override());
        var snapshot=new Booking.Snapshot(service,policy,request.intake(),customer.verifiedEmail(),request.displayZone(),noticeVersion);
        UUID id=UUID.randomUUID();String reference="B-"+UUID.randomUUID();
        Booking booking=meeting==null
                ? Booking.pending(id,reference,customer.id(),snapshot,Booking.Origin.ADMINISTRATOR,now,request.start())
                : Booking.confirmed(id,reference,customer.id(),snapshot,Booking.Origin.ADMINISTRATOR,now,request.start(),meeting);
        tx.save(booking);
        tx.createdEffects(booking,request.override());
        return new Receipt(Code.OK,booking.id(),booking.version());
    }
}
