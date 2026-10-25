package com.purpuragenda.application;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.*;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.Intake;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerBookingTest {
    final Fixture f=new Fixture();
    final CustomerBooking booking=new CustomerBooking(f,"local-v1");
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"a".repeat(64));
    Intake intake(){return new Intake("Synthetic","Customer","+32123456789",LocalDate.of(2000,1,1),LocalTime.NOON,"Synthetic city","Synthetic message");}
    CustomerBooking.Request request(){return request(Instant.parse("2026-01-02T08:00:00Z"));}
    CustomerBooking.Request request(Instant start){return new CustomerBooking.Request(f.service.id(),f.service.version(),f.policy.version(),start,intake(),"America/New_York","local-v1");}
    Receipt create(CustomerBooking.Request request){return booking.create(session,"synthetic-request",request);}
    void fails(Code code,CustomerBooking.Request request){assertEquals(Receipt.failure(code),create(request));assertNull(f.created);assertEquals(0,f.charges);}

    @Test void creationUsesVerifiedIdentityAndAuthoritativeSnapshotsBeforeBindingGrantAndEffects() {
        var request=request();Receipt receipt=create(request);Booking value=f.created;
        assertEquals(new Receipt(Code.OK,value.id(),1),receipt);assertSame(session,f.command.session());
        assertEquals(f.identity.id(),value.customerId());assertEquals(f.identity.verifiedEmail(),value.deliveryEmail());
        assertEquals(f.identity.verifiedEmail(),value.snapshot().creationEmail());assertSame(f.service,value.snapshot().service());
        assertSame(f.policy,value.snapshot().policy());assertSame(request.intake(),value.snapshot().intake());
        assertEquals("America/New_York",value.snapshot().displayZone());assertEquals("local-v1",value.snapshot().privacyNoticeVersion());
        assertEquals(Booking.Origin.CUSTOMER,value.origin());assertEquals(Booking.State.PENDING_CONFIRMATION,value.state());
        assertEquals(f.now,value.createdAt());assertEquals(f.now.plus(Duration.ofDays(1)),value.expiresAt());
        assertEquals(Duration.ofHours(2),Duration.between(value.start(),value.reservedEnd()));
        assertEquals(List.of("expiry","attempt","quota","save","charge","bind","effects"),f.steps);assertEquals(1,f.charges);
        assertEquals("CustomerAuthority[REDACTED]",f.identity.toString());assertEquals("CustomerBookingRequest[REDACTED]",request.toString());
        assertEquals("SessionBinding[REDACTED]",session.toString());assertEquals("CustomerCreationCommand[REDACTED]",f.command.toString());
    }

    @Test void removedOrChangedServicesPoliciesAndNoticesRequireFreshReviewWithoutWrites() {
        var original=request();f.services=List.of();fails(Code.SERVICE_UNAVAILABLE,original);
        f.services=List.of(new ServiceDefinition(f.service.id(),1,"Archived",60,f.service.price(),false));fails(Code.SERVICE_UNAVAILABLE,original);
        f.services=List.of(new ServiceDefinition(UUID.randomUUID(),1,"Other",60,f.service.price(),true));fails(Code.SERVICE_UNAVAILABLE,original);
        f.services=List.of(new ServiceDefinition(f.service.id(),2,"Edited",60,f.service.price(),true));fails(Code.SERVICE_CHANGED,original);
        f.services=List.of(f.service);var p=f.policy;f.policy=new SchedulingPolicy(2,p.gridMinutes(),p.dailyCapacity(),p.minimumNotice(),p.pendingLifetime(),p.expiryBuffer(),p.customerCutoff());
        fails(Code.POLICY_CHANGED,original);f.policy=p;
        fails(Code.NOTICE_CHANGED,new CustomerBooking.Request(original.serviceId(),1,1,original.start(),intake(),"UTC","old-notice"));
    }

    @Test void elapsedNoticeEqualityIsInclusiveAtMicrosecondPrecision() {
        f.policy=SchedulingPolicy.defaults();var request=request();f.now=request.start().minus(Duration.ofHours(24)).plusNanos(1000);
        fails(Code.MINIMUM_NOTICE,request);f.now=f.now.minusNanos(1000);
        assertTrue(create(request).success());assertEquals(Duration.ofHours(23),Duration.between(f.now,f.created.expiresAt()));
        f.created=null;f.charges=0;f.now=f.now.minusNanos(1000);assertTrue(create(request).success());
    }

    @Test void calendarMonthEndHorizonIsInclusiveAndUsesBrussels() {
        f.now=Instant.parse("2026-01-31T10:00:00Z").minusNanos(1000);
        var request=request(Instant.parse("2026-02-28T10:00:00Z"));fails(Code.OUTSIDE_HORIZON,request);
        f.now=f.now.plusNanos(1000);assertTrue(create(request).success());
        assertEquals(LocalDate.of(2026,2,28),f.created.businessDate());
    }

    @Test void pendingDeadlineMustBeStrictlyAfterCreationEvenWhenNoticeAllowsTheStart() {
        f.policy=new SchedulingPolicy(1,30,3,Duration.ZERO,Duration.ofDays(1),Duration.ofHours(1),Duration.ZERO);
        var request=request();f.now=request.start().minusSeconds(3600);
        fails(Code.IMPOSSIBLE_PENDING_HOLD,request);f.now=f.now.minusNanos(1000);
        assertTrue(create(request).success());assertEquals(f.now.plusNanos(1000),f.created.expiresAt());
    }

    @Test void dailyCapacityIncludesCompletedAppointmentsEvenAfterTheirSessionEnds() {
        f.now=Instant.parse("2026-01-02T10:00:00Z");f.policy=new SchedulingPolicy(1,30,1,Duration.ZERO,Duration.ofDays(1),Duration.ZERO,Duration.ZERO);
        var s=new Booking.Snapshot(f.service,f.policy,intake(),"synthetic@example.test","UTC","local-v1");
        Booking completed=Booking.confirmed(UUID.randomUUID(),"SYNTHETIC",UUID.randomUUID(),s,Booking.Origin.ADMINISTRATOR,
                f.now.minus(Duration.ofDays(1)),Instant.parse("2026-01-02T08:00:00Z"),Meeting.parse("https://zoom.us/j/123456789",Set.of("zoom.us")))
                .outcome(f.now,Booking.State.COMPLETED,"");f.bookings=List.of(completed);
        fails(Code.DAILY_CAPACITY_REACHED,request(Instant.parse("2026-01-02T18:00:00Z")));
    }

    @Test void activeAndSubmissionAllowancesRemainSeparateAndAreNotDisplayDateBased() {
        f.now=Instant.parse("2026-01-01T23:30:00Z");f.active=3;fails(Code.ACTIVE_LIMIT_REACHED,request());
        f.active=0;f.requests=3;fails(Code.REQUEST_LIMIT_REACHED,request());
        f.requests=2;assertTrue(create(request()).success());assertEquals(LocalDate.of(2026,1,2),f.quotaDay);
        assertEquals(f.identity.id(),f.quotaCustomer);
    }

    @Test void crossMidnightReservationChecksTheNextBusinessDateAndAllowsExactAdjacency() {
        f.calendar=new AvailabilityCalendar(Map.of(DayOfWeek.FRIDAY,List.of(new AvailabilityCalendar.Window(0,1440))),Map.of(),List.of(),1);
        Instant start=Instant.parse("2026-01-02T22:30:00Z");Booking next=f.pending(Instant.parse("2026-01-03T00:00:00Z"));
        f.bookings=List.of(next);fails(Code.SLOT_UNAVAILABLE,request(start));
        assertEquals(start.plusSeconds(7200),f.loaded.end());
        f.bookings=List.of(f.pending(start.plusSeconds(7200)));assertTrue(create(request(start)).success());
    }

    @Test void fullReservationBlackoutAndCustomerStartRulesCannotBeOverridden() {
        Instant start=request().start();
        var blackout=new AvailabilityCalendar.Blackout(UUID.randomUUID(),new ReservationInterval(start.plusSeconds(5400),start.plusSeconds(7200)),"Synthetic blackout");
        f.calendar=new AvailabilityCalendar(AvailabilityCalendar.defaults().weekly(),Map.of(),List.of(blackout),1);
        fails(Code.HARD_BLACKOUT,request());f.calendar=AvailabilityCalendar.defaults();
        fails(Code.START_NOT_AVAILABLE,request(start.plusSeconds(60)));
        f.now=start;fails(Code.START_NOT_FUTURE,request());
    }

    @Test void birthDateValidationUsesTheCapturedBrusselsDayAndNeverChargesRejectedIntake() {
        f.now=Instant.parse("2026-01-01T22:59:59.999999Z");var r=request();
        var future=new Intake("Synthetic","Customer","+32123456789",LocalDate.of(2026,1,2),LocalTime.NOON,"Synthetic city","");
        var request=new CustomerBooking.Request(r.serviceId(),1,1,r.start(),future,"America/New_York","local-v1");
        fails(Code.INVALID_BIRTH_DATE,request);f.now=f.now.plusNanos(1000);assertTrue(create(request).success());
    }

    @Test void idempotencyFingerprintIncludesAllReviewedAndIntakeFieldsAndNormalizesInput() {
        f.replay=Receipt.failure(Code.SLOT_UNAVAILABLE);var r=request();create(r);String original=f.command.fingerprint();
        var variants=List.of(new CustomerBooking.Request(UUID.randomUUID(),1,1,r.start(),intake(),"America/New_York","local-v1"),
                new CustomerBooking.Request(r.serviceId(),2,1,r.start(),intake(),"America/New_York","local-v1"),
                new CustomerBooking.Request(r.serviceId(),1,2,r.start(),intake(),"America/New_York","local-v1"),
                request(r.start().plusSeconds(1800)),new CustomerBooking.Request(r.serviceId(),1,1,r.start(),intake(),"UTC","local-v1"),
                new CustomerBooking.Request(r.serviceId(),1,1,r.start(),intake(),"America/New_York","next-notice"));
        for(var changed:variants){create(changed);assertNotEquals(original,f.command.fingerprint());}
        Intake i=intake();var changes=List.of(new Intake("Other",i.lastName(),i.telephone(),i.birthDate(),i.birthTime(),i.birthplace(),i.message()),
                new Intake(i.firstName(),"Other",i.telephone(),i.birthDate(),i.birthTime(),i.birthplace(),i.message()),
                new Intake(i.firstName(),i.lastName(),"+32123456780",i.birthDate(),i.birthTime(),i.birthplace(),i.message()),
                new Intake(i.firstName(),i.lastName(),i.telephone(),i.birthDate().plusDays(1),i.birthTime(),i.birthplace(),i.message()),
                new Intake(i.firstName(),i.lastName(),i.telephone(),i.birthDate(),i.birthTime().plusSeconds(1),i.birthplace(),i.message()),
                new Intake(i.firstName(),i.lastName(),i.telephone(),i.birthDate(),i.birthTime(),"Other city",i.message()),
                new Intake(i.firstName(),i.lastName(),i.telephone(),i.birthDate(),i.birthTime(),i.birthplace(),"Other message"));
        for(Intake changed:changes){create(new CustomerBooking.Request(r.serviceId(),1,1,r.start(),changed,r.displayZone(),r.noticeVersion()));assertNotEquals(original,f.command.fingerprint());}
        Intake normalized=new Intake(" Synthetic "," Customer ","+32 123 456 789",i.birthDate(),i.birthTime()," Synthetic city "," Synthetic message ");
        assertSame(f.replay,create(new CustomerBooking.Request(r.serviceId(),1,1,r.start(),normalized,r.displayZone(),r.noticeVersion())));
        assertEquals(original,f.command.fingerprint());assertTrue(f.steps.isEmpty());assertNull(f.created);
    }

    @Test void malformedReferencesAndRequestsNeverReachTheTransaction() {
        for(String hash:Arrays.asList(null,"","x".repeat(64),"a".repeat(63)))assertThrows(DomainFailure.class,()->new SessionBinding(UUID.randomUUID(),hash));
        assertThrows(DomainFailure.class,()->new SessionBinding(null,"a".repeat(64)));
        assertThrows(DomainFailure.class,()->new Customer(null,"synthetic@example.test"));
        assertThrows(DomainFailure.class,()->new Customer(UUID.randomUUID(),"invalid"));
        assertThrows(NullPointerException.class,()->booking.create(null,"key",request()));
        assertThrows(DomainFailure.class,()->booking.create(session," ",request()));
        assertThrows(DomainFailure.class,()->new Command(session,"key","invalid"));
        assertThrows(NullPointerException.class,()->booking.create(session,"key",null));
        var r=request();
        assertThrows(DomainFailure.class,()->new CustomerBooking.Request(null,1,1,r.start(),intake(),"UTC","local-v1"));
        assertThrows(DomainFailure.class,()->new CustomerBooking.Request(r.serviceId(),0,1,r.start(),intake(),"UTC","local-v1"));
        assertThrows(DomainFailure.class,()->new CustomerBooking.Request(r.serviceId(),1,0,r.start(),intake(),"UTC","local-v1"));
        assertThrows(DomainFailure.class,()->new CustomerBooking.Request(r.serviceId(),1,1,null,intake(),"UTC","local-v1"));
        assertThrows(DomainFailure.class,()->new CustomerBooking.Request(r.serviceId(),1,1,r.start(),null,"UTC","local-v1"));
        assertThrows(DomainFailure.class,()->request(r.start().plusNanos(1)));
        assertThrows(DomainFailure.class,()->request(Instant.MAX));
        assertThrows(DomainFailure.class,()->request(Instant.MAX.truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
        assertThrows(DomainFailure.class,()->new CustomerBooking.Request(r.serviceId(),1,1,r.start(),intake(),"invalid","local-v1"));
        assertThrows(NullPointerException.class,()->new CustomerBooking(null,"local-v1"));
        assertThrows(DomainFailure.class,()->new CustomerBooking(f,""));assertNull(f.command);
    }

    @Test void missingChargeOrFailedGrantBindingCannotReturnASuccessfulReceipt() {
        f.charge=false;assertEquals("CREATION_CHARGE_NOT_RECORDED",assertThrows(IllegalStateException.class,()->create(request())).getMessage());
        assertFalse(f.steps.contains("bind"));assertFalse(f.steps.contains("effects"));
        f.charge=true;f.failBinding=true;
        assertThrows(IllegalStateException.class,()->create(request()));assertFalse(f.steps.contains("effects"));
    }

    static class Fixture extends PublicSchedulingTest.Fixture implements CustomerBookingUnitOfWork,Creation {
        final Customer identity=new Customer(UUID.randomUUID(),"verified@example.test");
        final List<String> steps=new ArrayList<>();Command command;Receipt replay;Booking created;
        int active,requests,charges;boolean charge=true,failBinding;UUID quotaCustomer;LocalDate quotaDay;
        public Receipt create(Command command,Function<Creation,Receipt> work){this.command=command;return replay==null?work.apply(this):replay;}
        public Customer customer(){return identity;}
        public List<Booking> duePending(){steps.add("expiry");return List.of();}
        public void expiryEffects(UUID id,long version){throw new AssertionError("Unexpected expiry effect");}
        public Receipt attempt(Supplier<Receipt> mutation){steps.add("attempt");return mutation.get();}
        public CustomerQuota quota(UUID id){steps.add("quota");quotaCustomer=id;quotaDay=CustomerQuota.dayAt(now);return new CustomerQuota(active,requests,quotaDay);}
        public void save(Booking value){steps.add("save");created=value;}
        public boolean chargeCreated(Booking value){steps.add("charge");assertSame(created,value);if(charge)charges++;return charge;}
        public void bindCreation(Booking value){steps.add("bind");assertSame(created,value);if(failBinding)throw new IllegalStateException("SYNTHETIC_BIND_FAILURE");}
        public void createdEffects(Booking value){steps.add("effects");assertSame(created,value);}
    }
}
