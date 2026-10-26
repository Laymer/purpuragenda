package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AdminBookingUnitOfWork;
import com.purpuragenda.application.port.out.AdminBookingUnitOfWork.Command;
import com.purpuragenda.application.port.out.AdminBookingUnitOfWork.Creation;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.Customer;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.*;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.Intake;
import com.purpuragenda.domain.scheduling.*;
import com.purpuragenda.domain.scheduling.SchedulingRules.Rule;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminBookingTest {
    final Fixture f=new Fixture();
    final AdminBooking admin=new AdminBooking(f,"local-v1",Set.of("zoom.us"));
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"c".repeat(64));
    static final String ZOOM="https://zoom.us/j/123456789";
    static final String REASON="Synthetic administrator reason";
    Intake intake(){return new Intake("Synthetic","Customer","+32123456789",LocalDate.of(2000,1,1),LocalTime.NOON,"Synthetic city","Synthetic message");}
    Instant start(){return Instant.parse("2026-01-02T08:00:00Z");}
    AdminBooking.Request request(Instant start,String meeting,SchedulingRules.Override override){
        return new AdminBooking.Request(f.service.id(),f.service.version(),f.policy.version(),f.calendar.version(),start,"Customer@Example.TEST",intake(),"Europe/Brussels","local-v1",meeting,override);
    }
    AdminBooking.Request request(){return request(start(),null,SchedulingRules.Override.none());}
    SchedulingRules.Override override(Rule... rules){return new SchedulingRules.Override(Set.of(rules),REASON,Set.of());}
    Receipt create(AdminBooking.Request request){return admin.create(session,"synthetic-admin",request);}
    void fails(Code code,AdminBooking.Request request){assertEquals(Receipt.failure(code),create(request));assertNull(f.created);}
    void succeeds(AdminBooking.Request request){assertTrue(create(request).success(),"expected success");assertNotNull(f.created);f.created=null;f.steps.clear();}
    AvailabilityCalendar blackouts(UUID... ids){
        List<AvailabilityCalendar.Blackout> list=new ArrayList<>();int index=0;
        for(UUID id:ids){Instant from=start().plusSeconds(600L*index++);list.add(new AvailabilityCalendar.Blackout(id,new ReservationInterval(from,from.plusSeconds(60)),"Synthetic closure reason"));}
        return new AvailabilityCalendar(f.calendar.weekly(),Map.of(),list,f.calendar.version());
    }

    @Test void pendingCreationUsesTransactionIdentityAndNeverChargesTheSubmissionQuota() {
        var request=request();Receipt receipt=create(request);Booking value=f.created;
        assertEquals(new Receipt(Code.OK,value.id(),1),receipt);assertSame(session,f.command.session());
        assertEquals(Booking.Origin.ADMINISTRATOR,value.origin());assertEquals(Booking.State.PENDING_CONFIRMATION,value.state());
        assertEquals(f.identity.id(),value.customerId());assertEquals("customer@example.test",f.requestedEmail);
        assertSame(f.service,value.snapshot().service());assertSame(f.policy,value.snapshot().policy());assertSame(request.intake(),value.snapshot().intake());
        assertEquals(f.now.plus(Duration.ofDays(1)),value.expiresAt());assertEquals(Duration.ofHours(2),Duration.between(value.start(),value.reservedEnd()));
        assertEquals(List.of("expiry","attempt","customer","quota","save","effects"),f.steps);assertTrue(f.override.rules().isEmpty());
        assertEquals("AdminBookingRequest[REDACTED]",request.toString());assertEquals("AdminCreationCommand[REDACTED]",f.command.toString());
    }

    @Test void participantUrlConfirmsImmediatelyAndAMalformedOneNeverReachesTheUnit() {
        succeedsConfirmed();
        for(String bad:List.of("https://evil.example/j/123456789","http://zoom.us/j/123456789","not a url")) {
            f.command=null;var failure=assertThrows(DomainFailure.class,()->create(request(start(),bad,SchedulingRules.Override.none())));
            assertEquals("INVALID_MEETING",failure.code());assertNull(f.command);
        }
    }
    void succeedsConfirmed() {
        create(request(start(),ZOOM,SchedulingRules.Override.none()));Booking value=f.created;
        assertEquals(Booking.State.CONFIRMED,value.state());assertEquals(Booking.ConfirmationBasis.MANUAL_APPROVAL,value.confirmationBasis());
        assertNull(value.expiresAt());assertEquals("123456789",value.meeting().meetingId());assertTrue(value.previouslyConfirmed());
    }

    @Test void staleReviewedVersionsAndNoticeAreExpectedReceiptsWithoutWrites() {
        var original=request();f.services=List.of();fails(Code.SERVICE_UNAVAILABLE,original);
        f.services=List.of(new ServiceDefinition(f.service.id(),1,"Archived",60,f.service.price(),false));fails(Code.SERVICE_UNAVAILABLE,original);
        f.services=List.of(new ServiceDefinition(f.service.id(),2,"Edited",60,f.service.price(),true));fails(Code.SERVICE_CHANGED,original);
        f.services=List.of(f.service);var p=f.policy;f.policy=new SchedulingPolicy(2,p.gridMinutes(),p.dailyCapacity(),p.minimumNotice(),p.pendingLifetime(),p.expiryBuffer(),p.customerCutoff());
        fails(Code.POLICY_CHANGED,original);f.policy=p;
        var c=f.calendar;f.calendar=new AvailabilityCalendar(c.weekly(),c.exceptions(),c.blackouts(),c.version()+1);fails(Code.CALENDAR_CHANGED,original);f.calendar=c;
        fails(Code.NOTICE_CHANGED,new AdminBooking.Request(original.serviceId(),1,1,1,start(),"customer@example.test",intake(),"UTC","old",null,SchedulingRules.Override.none()));
        assertEquals(0,f.saves);
    }

    @Test void everyOverridableRuleNeedsItsExplicitNameAndReason() {
        // Start rules: 03:00 Brussels is outside the default windows.
        Instant closed=Instant.parse("2026-01-02T02:00:00Z");
        fails(Code.START_NOT_AVAILABLE,request(closed,null,SchedulingRules.Override.none()));
        fails(Code.START_NOT_AVAILABLE,request(closed,null,override(Rule.NOTICE)));
        succeeds(request(closed,null,override(Rule.START_RULES)));
        f.policy=new SchedulingPolicy(1,30,20,Duration.ofHours(48),Duration.ofDays(1),Duration.ZERO,Duration.ZERO);
        fails(Code.MINIMUM_NOTICE,request());succeeds(request(start(),null,override(Rule.NOTICE)));
        f.policy=SchedulingPolicy.defaults();Instant far=Instant.parse("2026-03-02T08:00:00Z");
        fails(Code.OUTSIDE_HORIZON,request(far,null,override(Rule.NOTICE)));succeeds(request(far,null,override(Rule.HORIZON)));
        f.policy=new SchedulingPolicy(1,30,1,Duration.ZERO,Duration.ofDays(1),Duration.ZERO,Duration.ZERO);
        f.bookings=List.of(f.pending(Instant.parse("2026-01-02T12:00:00Z")));
        fails(Code.DAILY_CAPACITY_REACHED,request());succeeds(request(start(),null,override(Rule.DAILY_CAPACITY)));
        f.policy=SchedulingPolicy.defaults();f.policy=new SchedulingPolicy(1,30,20,Duration.ZERO,Duration.ofDays(1),Duration.ZERO,Duration.ZERO);f.bookings=List.of();
        f.active=3;fails(Code.ACTIVE_LIMIT_REACHED,request());succeeds(request(start(),null,override(Rule.ACTIVE_LIMIT)));
        // The customer submission limit does not apply to administrator creation.
        f.active=0;f.requests=3;succeeds(request());
    }

    @Test void blackoutsRequireTheRuleAndEveryIntersectingIdentifier() {
        UUID one=UUID.randomUUID(),two=UUID.randomUUID();f.calendar=blackouts(one);
        fails(Code.HARD_BLACKOUT,request());
        fails(Code.HARD_BLACKOUT,request(start(),null,override(Rule.NOTICE)));
        fails(Code.BLACKOUT_OVERRIDE_REQUIRED,request(start(),null,override(Rule.HARD_BLACKOUT)));
        fails(Code.BLACKOUT_OVERRIDE_REQUIRED,request(start(),null,new SchedulingRules.Override(Set.of(Rule.HARD_BLACKOUT),REASON,Set.of(two))));
        succeeds(request(start(),null,new SchedulingRules.Override(Set.of(Rule.HARD_BLACKOUT),REASON,Set.of(one))));
        f.calendar=new AvailabilityCalendar(f.calendar.weekly(),Map.of(),List.of(
                new AvailabilityCalendar.Blackout(one,new ReservationInterval(start().minusSeconds(60),start().plusSeconds(60)),"Synthetic closure reason"),
                new AvailabilityCalendar.Blackout(two,new ReservationInterval(start().plusSeconds(3600),start().plusSeconds(3700)),"Synthetic closure reason")),f.calendar.version());
        fails(Code.BLACKOUT_OVERRIDE_REQUIRED,request(start(),null,new SchedulingRules.Override(Set.of(Rule.HARD_BLACKOUT),REASON,Set.of(one))));
        succeeds(request(start(),null,new SchedulingRules.Override(Set.of(Rule.HARD_BLACKOUT),REASON,Set.of(one,two))));
    }

    @Test void malformedOverridesAreRejectedBeforeAnyTransaction() {
        assertThrows(DomainFailure.class,()->new SchedulingRules.Override(Set.of(Rule.NOTICE),"short",Set.of()));
        assertThrows(DomainFailure.class,()->new SchedulingRules.Override(Set.of(Rule.NOTICE),"",Set.of()));
        var ids=new SchedulingRules.Override(Set.of(Rule.NOTICE),REASON,Set.of(UUID.randomUUID()));
        assertEquals("INVALID_BOOKING_REQUEST",assertThrows(DomainFailure.class,()->request(start(),null,ids)).code());
        assertNull(f.command);
    }

    @Test void overlapAndNonfutureStartsAreNeverOverridableEvenWithEveryRule() {
        Rule[] every=Rule.values();
        f.bookings=List.of(f.pending(start().plus(Duration.ofMinutes(119))));
        fails(Code.SLOT_UNAVAILABLE,request(start(),null,override(every)));
        f.bookings=List.of(f.pending(start().minus(Duration.ofMinutes(119))));
        fails(Code.SLOT_UNAVAILABLE,request(start(),null,override(every)));
        f.bookings=List.of(f.pending(start().plus(Duration.ofMinutes(120))));
        succeeds(request());   // adjacency is not overlap
        fails(Code.START_NOT_FUTURE,request(f.now,null,override(every)));
        fails(Code.START_NOT_FUTURE,request(f.now.minusSeconds(60),null,override(every)));
        assertEquals(1,f.saves);
    }

    @Test void unusedRulesAndUnrelatedBlackoutsAreRejectedSoOverridesRecordOnlyWhatWasNeeded() {
        fails(Code.OVERRIDE_UNUSED,request(start(),null,override(Rule.NOTICE)));
        fails(Code.OVERRIDE_UNUSED,request(start(),null,override(Rule.START_RULES,Rule.HORIZON)));
        f.calendar=blackouts(UUID.randomUUID());UUID stray=UUID.randomUUID();UUID real=f.calendar.blackouts().getFirst().id();
        fails(Code.BLACKOUT_OVERRIDE_REQUIRED,request(start(),null,override(Rule.HARD_BLACKOUT)));
        fails(Code.OVERRIDE_UNUSED,request(start(),null,new SchedulingRules.Override(Set.of(Rule.HARD_BLACKOUT),REASON,Set.of(real,stray))));
        succeeds(request(start(),null,new SchedulingRules.Override(Set.of(Rule.HARD_BLACKOUT),REASON,Set.of(real))));
    }

    @Test void aParticipantMeetingIdAlreadyHeldByAnotherBookingIsAStableBusinessReceipt() {
        f.usedMeetings.add("123456789");
        fails(Code.MEETING_IN_USE,request(start(),ZOOM,SchedulingRules.Override.none()));
        assertEquals(0,f.saves);
        f.usedMeetings.clear();succeeds(request(start(),ZOOM,SchedulingRules.Override.none()));
    }

    @Test void shortNoticePendingHoldIsImpossibleUnlessImmediatelyConfirmed() {
        f.policy=new SchedulingPolicy(1,30,20,Duration.ZERO,Duration.ofDays(1),Duration.ofHours(1),Duration.ZERO);
        Instant soon=f.now.plus(Duration.ofMinutes(30));
        fails(Code.IMPOSSIBLE_PENDING_HOLD,request(soon,null,override(Rule.START_RULES)));
        succeeds(request(soon,ZOOM,override(Rule.START_RULES)));
    }

    @Test void unexpectedEffectFailuresPropagateAndRequestFingerprintsCoverEveryField() {
        f.effectFailure=new IllegalStateException("SYNTHETIC");
        assertSame(f.effectFailure,assertThrows(IllegalStateException.class,()->create(request())));
        f.effectFailure=null;
        Set<String> seen=new HashSet<>();
        List<AdminBooking.Request> variants=List.of(request(),request(start().plusSeconds(1800),null,SchedulingRules.Override.none()),request(start(),ZOOM,SchedulingRules.Override.none()),
                request(start(),null,override(Rule.NOTICE)),request(start(),null,override(Rule.NOTICE,Rule.HORIZON)),
                request(start(),null,new SchedulingRules.Override(Set.of(Rule.NOTICE),"Another synthetic reason",Set.of())),
                new AdminBooking.Request(f.service.id(),1,1,1,start(),"other@example.test",intake(),"UTC","local-v1",null,SchedulingRules.Override.none()),
                new AdminBooking.Request(f.service.id(),1,1,2,start(),"customer@example.test",intake(),"Europe/Brussels","local-v1",null,SchedulingRules.Override.none()),
                new AdminBooking.Request(f.service.id(),1,1,1,start(),"customer@example.test",intake(),"Europe/Brussels","local-v1",null,SchedulingRules.Override.none()));
        for(var variant:variants){f.command=null;f.created=null;create(variant);seen.add(f.command.fingerprint());}
        // The last two differ only in zone casing/duplicates handled above: eight distinct plus one equal to the baseline.
        assertEquals(variants.size()-1,seen.size());
        f.created=null;create(request());String first=f.command.fingerprint();create(request());assertEquals(first,f.command.fingerprint());
    }

    @Test void requestValidationRejectsMalformedValues() {
        for(Supplier<AdminBooking.Request> bad:List.<Supplier<AdminBooking.Request>>of(
                ()->new AdminBooking.Request(null,1,1,1,start(),"a@example.test",intake(),"UTC","v",null,SchedulingRules.Override.none()),
                ()->new AdminBooking.Request(f.service.id(),0,1,1,start(),"a@example.test",intake(),"UTC","v",null,SchedulingRules.Override.none()),
                ()->new AdminBooking.Request(f.service.id(),1,1,1,start().plusNanos(1),"a@example.test",intake(),"UTC","v",null,SchedulingRules.Override.none()),
                ()->new AdminBooking.Request(f.service.id(),1,1,1,start(),"not-an-email",intake(),"UTC","v",null,SchedulingRules.Override.none()),
                ()->new AdminBooking.Request(f.service.id(),1,1,1,start(),"a@example.test",intake(),"Not/AZone","v",null,SchedulingRules.Override.none()),
                ()->new AdminBooking.Request(f.service.id(),1,1,1,Instant.MAX.truncatedTo(java.time.temporal.ChronoUnit.MICROS),"a@example.test",intake(),"UTC","v",null,SchedulingRules.Override.none())))
            assertThrows(DomainFailure.class,bad::get);
        assertThrows(NullPointerException.class,()->admin.create(session,"key",null));
    }

    @Test void commandsRejectBlankKeysAndMalformedFingerprintsBeforeAnyTransaction() {
        String fingerprint="a".repeat(64);
        assertThrows(DomainFailure.class,()->new Command(session," ",fingerprint));
        assertThrows(DomainFailure.class,()->new Command(session,"key","short"));
        assertThrows(NullPointerException.class,()->new Command(null,"key",fingerprint));
    }

    static class Fixture extends PublicSchedulingTest.Fixture implements AdminBookingUnitOfWork,Creation {
        Booking created;Command command;RuntimeException effectFailure;SchedulingRules.Override override;String requestedEmail;
        final Customer identity=new Customer(UUID.randomUUID(),"customer@example.test");
        final Set<String> usedMeetings=new HashSet<>();final List<String> steps=new ArrayList<>();int active,requests,saves;boolean attempting;
        Fixture(){policy=new SchedulingPolicy(1,30,20,Duration.ZERO,Duration.ofDays(1),Duration.ZERO,Duration.ZERO);}
        public Receipt create(Command supplied,Function<Creation,Receipt> work){command=supplied;steps.clear();return work.apply(this);}
        public List<Booking> duePending(){steps.add("expiry");return List.of();}
        public void expiryEffects(UUID id,long version){throw new AssertionError("nothing is due");}
        public Receipt attempt(Supplier<Receipt> mutation){steps.add("attempt");attempting=true;try{return mutation.get();}finally{attempting=false;}}
        public CustomerQuota quota(UUID customer){steps.add("quota");assertEquals(identity.id(),customer);return new CustomerQuota(active,requests,CustomerQuota.dayAt(now));}
        public boolean chargeCreated(Booking value){throw new AssertionError("Administrator creation must never charge a submission");}
        public boolean meetingInUse(String id){steps.add("meeting");return usedMeetings.contains(id);}
        public Customer customer(String email){steps.add("customer");requestedEmail=email;return identity;}
        public void save(Booking value){steps.add("save");saves++;created=value;}
        public void createdEffects(Booking value,SchedulingRules.Override supplied){steps.add("effects");assertSame(created,value);override=supplied;if(effectFailure!=null)throw effectFailure;}
        @Override public Optional<Booking> booking(UUID id){return Optional.ofNullable(created);}
    }
}
