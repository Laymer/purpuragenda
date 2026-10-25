package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.*;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.*;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;
import static com.purpuragenda.domain.scheduling.SchedulingPolicy.BUSINESS_ZONE;

/** Customer creation only; neither caller-supplied identity nor administrator overrides are accepted. */
public final class CustomerBooking {
    private final CustomerBookingUnitOfWork unit;
    private final String noticeVersion;
    public CustomerBooking(CustomerBookingUnitOfWork unit,String noticeVersion) {
        this.unit=Objects.requireNonNull(unit);this.noticeVersion=Input.text(noticeVersion,1,120);
    }
    public record Request(UUID serviceId,long serviceVersion,long policyVersion,Instant start,Intake intake,
                          String displayZone,String noticeVersion) {
        public Request {
            require(serviceId!=null && serviceVersion>0 && policyVersion>0 && start!=null && intake!=null,"INVALID_BOOKING_REQUEST");
            require(start.equals(start.truncatedTo(ChronoUnit.MICROS)),"INVALID_BOOKING_REQUEST");
            Input.zone(displayZone);noticeVersion=Input.text(noticeVersion,1,120);
            try { start.atZone(BUSINESS_ZONE).toLocalDate().plusDays(1);ReservationInterval.reserve(start); }
            catch(DateTimeException | ArithmeticException invalid) { throw new DomainFailure("INVALID_BOOKING_REQUEST"); }
        }
        @Override public String toString() { return "CustomerBookingRequest[REDACTED]"; }
        String fingerprint() {
            return CommandKey.fingerprint(List.of(serviceId.toString(),Long.toString(serviceVersion),Long.toString(policyVersion),
                    start.toString(),displayZone,noticeVersion,intake.firstName(),intake.lastName(),intake.telephone(),
                    intake.birthDate().toString(),intake.birthTime().toString(),intake.birthplace(),intake.message()));
        }
    }
    public Receipt create(SessionBinding session,String idempotencyKey,Request request) {
        Objects.requireNonNull(request);
        return unit.create(new Command(session,idempotencyKey,request.fingerprint()),
                tx -> SchedulingCommands.within(tx,() -> create(tx,request)));
    }
    private Receipt create(Creation tx,Request request) {
        Instant now=tx.now();Customer customer=tx.customer();
        ServiceDefinition service=tx.services().stream().filter(s -> s.id().equals(request.serviceId()) && s.active())
                .findFirst().orElseThrow(() -> new DomainFailure("SERVICE_UNAVAILABLE"));
        require(service.version()==request.serviceVersion(),"SERVICE_CHANGED");
        SchedulingPolicy policy=tx.policy();require(policy.version()==request.policyVersion(),"POLICY_CHANGED");
        require(noticeVersion.equals(request.noticeVersion()),"NOTICE_CHANGED");request.intake().validateAt(now);
        ReservationInterval reservation=ReservationInterval.reserve(request.start());
        LocalDate day=request.start().atZone(BUSINESS_ZONE).toLocalDate();
        Instant end=day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        if(reservation.end().isAfter(end))end=reservation.end();
        List<Booking> occupied=tx.bookings(new ReservationInterval(day.atStartOfDay(BUSINESS_ZONE).toInstant(),end))
                .stream().filter(b -> b.occupies(now)).toList();
        int daily=(int)occupied.stream().filter(b -> b.businessDate().equals(day)).count();
        CustomerQuota quota=tx.quota(customer.id());
        SchedulingRules.validate(request.start(),now,policy,tx.calendar(),daily,quota.active(),quota.requests(),
                occupied.stream().map(Booking::interval).toList(),SchedulingRules.Override.none());
        var snapshot=new Booking.Snapshot(service,policy,request.intake(),customer.verifiedEmail(),request.displayZone(),noticeVersion);
        Booking booking=Booking.pending(UUID.randomUUID(),"B-"+UUID.randomUUID(),customer.id(),snapshot,Booking.Origin.CUSTOMER,now,request.start());
        tx.save(booking);
        if(!tx.chargeCreated(booking))throw new IllegalStateException("CREATION_CHARGE_NOT_RECORDED");
        tx.bindCreation(booking);tx.createdEffects(booking);
        return new Receipt(Code.OK,booking.id(),booking.version());
    }
}
