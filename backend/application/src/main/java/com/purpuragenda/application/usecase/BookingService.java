package com.purpuragenda.application.usecase;

import static com.purpuragenda.domain.DomainFailure.require;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.customer.*;
import com.purpuragenda.domain.payment.PaymentLedger;
import com.purpuragenda.domain.scheduling.*;
import com.purpuragenda.application.port.in.Actor;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

public final class BookingService {
    private final SchedulingUnitOfWork work;
    public BookingService(SchedulingUnitOfWork work) { this.work=Objects.requireNonNull(work); }
    public record Request(UUID serviceId,long serviceVersion,long policyVersion,Instant startInstant,String displayZone,
                          Intake intake,String privacyNoticeVersion,UUID customerId,boolean immediatelyConfirmed,Meeting meeting,SchedulingRules.Override override) {}
    public record Slot(Instant startInstant,Instant sessionEndInstant,Instant reservedEndInstant,LocalDate businessDate,String displayLocalTime,String offset) {}
    public record Availability(Instant serverNow,String businessZone,String displayZone,Instant horizonInstant,long policyVersion,List<Slot> slots,List<String> restrictions) {}
    public record BookingView(Booking booking, PaymentLedger.Summary payment, List<String> permittedActions) {}
    public List<ServiceDefinition> services() { return work.read(v->v.services().stream().filter(ServiceDefinition::active).toList()); }
    public List<ServiceDefinition> adminServices(Actor actor) { actor.requireAdmin();return work.read(View::services); }
    public Availability availability(UUID serviceId,LocalDate from,LocalDate to,String zone,Actor actor) {
        return availability(serviceId,from,to,zone,actor,null);
    }
    public Availability rescheduleAvailability(Actor actor,UUID id,LocalDate from,LocalDate to,String zone){
        Booking booking=get(actor,id).booking();return availability(booking.snapshot().service().id(),from,to,zone,actor,booking);
    }
    private Availability availability(UUID serviceId,LocalDate from,LocalDate to,String zone,Actor actor,Booking moving) {
        ZoneId display=Input.zone(zone);
        require(from!=null && to!=null && !to.isBefore(from) && ChronoUnit.DAYS.between(from,to)<31,"INVALID_DATE_RANGE");
        return work.read(v->{
            var service=moving==null?service(v,serviceId):moving.snapshot().service();if(moving==null)require(service.active(),"SERVICE_UNAVAILABLE");
            Instant now=v.now(); var policy=v.policy();var calendar=v.calendar();var bookings=v.bookings();
            Instant lower=from.atStartOfDay(display).toInstant(), upper=to.plusDays(1).atStartOfDay(display).toInstant();
            LocalDate businessFrom=lower.atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate();
            LocalDate businessTo=upper.atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate();
            UUID except=moving==null?null:moving.id();var occupied=bookings.stream().filter(b->!b.id().equals(except)&&b.occupies(now)).map(Booking::interval).toList();
            List<Slot> slots=new ArrayList<>();List<String> restrictions=new ArrayList<>();
            int active=actor!=null && actor.customerId()!=null ? active(bookings,actor.customerId(),now,except):0;
            int requests=moving==null&&actor!=null && actor.customerId()!=null ? v.requestCount(actor.customerId(),now.atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate()):0;
            if(active>=3)restrictions.add("ACTIVE_LIMIT_REACHED");if(requests>=3)restrictions.add("REQUEST_LIMIT_REACHED");
            for(LocalDate day=businessFrom;!day.isAfter(businessTo);day=day.plusDays(1)) {
                int daily=daily(bookings,day,now,except);
                for(Instant start:calendar.candidates(day,policy.gridMinutes())) {
                    if(start.isBefore(lower)||!start.isBefore(upper)||!start.isAfter(now))continue;
                    if(!SchedulingRules.violations(start,now,policy,calendar,daily,0,0).isEmpty())continue;
                    if(occupied.stream().anyMatch(i->i.intersects(ReservationInterval.reserve(start))))continue;
                    var local=start.atZone(display);
                    slots.add(new Slot(start,start.plusSeconds(service.durationMinutes()*60L),start.plusSeconds(7200),day,local.toLocalDateTime().toString(),local.getOffset().toString()));
                }
            }
            slots.sort(Comparator.comparing(Slot::startInstant));
            return new Availability(now,"Europe/Brussels",zone,policy.horizon(now),policy.version(),List.copyOf(slots),List.copyOf(restrictions));
        });
    }
    public Receipt create(Actor actor,String key,String fingerprint,Request request) {
        return command(actor,"create",key,fingerprint,tx->{
            actor.requireCreate();
            require(request.startInstant()!=null && request.intake()!=null,"VALIDATION_FAILED");
            require(request.startInstant().equals(request.startInstant().truncatedTo(ChronoUnit.MICROS)),"INVALID_PRECISION");
            var service=service(tx,request.serviceId());require(service.active(),"SERVICE_UNAVAILABLE");
            require(service.version()==request.serviceVersion(),"SERVICE_CHANGED");
            require(tx.policy().version()==request.policyVersion(),"POLICY_CHANGED");
            UUID customer=actor.admin()?request.customerId():actor.customerId();require(customer!=null,"VALIDATION_FAILED");
            SchedulingRules.Override override=actor.admin()?Objects.requireNonNullElseGet(request.override(),SchedulingRules.Override::none):SchedulingRules.Override.none();
            validateTarget(tx,customer,request.startInstant(),null,actor.admin(),override);
            var snapshot=new Booking.Snapshot(service,tx.policy(),request.intake(),tx.email(customer),request.displayZone(),request.privacyNoticeVersion());
            UUID id=UUID.randomUUID();String reference="PP-"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT);
            Booking.Origin origin=actor.admin()?Booking.Origin.ADMINISTRATOR:Booking.Origin.CUSTOMER;
            require(actor.admin()||!request.immediatelyConfirmed(),"FORBIDDEN");
            Booking booking=request.immediatelyConfirmed()?Booking.confirmed(id,reference,customer,snapshot,origin,tx.now(),request.startInstant(),request.meeting()):Booking.pending(id,reference,customer,snapshot,origin,tx.now(),request.startInstant());
            tx.save(booking);
            if(!actor.admin())tx.charge(booking,tx.now().atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate());
            tx.bindCreationGrant(actor,id);
            tx.event(booking,request.immediatelyConfirmed()?"CONFIRMED":"REQUESTED",actor,override.reason());
            if(!override.rules().isEmpty())tx.recordOverride(booking,override,actor);
            return Receipt.ok(booking);
        });
    }
    public BookingView get(Actor actor,UUID id) {
        return work.read(v->{var b=owned(v,actor,id);return view(v,b,actor);});
    }
    public List<BookingView> list(Actor actor) {
        require(actor.admin()||actor.kind()==Actor.Kind.ACCOUNT,"FORBIDDEN");
        return work.read(v->v.bookings().stream().filter(b->actor.admin()||b.customerId().equals(actor.customerId())).sorted(Comparator.comparing(Booking::start).reversed()).map(b->view(v,b,actor)).toList());
    }
    public Receipt approve(Actor actor,UUID id,long version,String key,String fingerprint,Meeting meeting) {
        actor.requireAdmin();return command(actor,"approve/"+id,key,fingerprint,tx->{
            require(owned(tx,actor,id).state()!=Booking.State.EXPIRED,"HOLD_EXPIRED");
            var b=versioned(tx,actor,id,version);var approved=b.approve(tx.now(),meeting,Booking.ConfirmationBasis.MANUAL_APPROVAL);
            tx.save(approved);tx.event(approved,"CONFIRMED",actor,"");return Receipt.ok(approved);
        });
    }
    public Receipt reject(Actor actor,UUID id,long version,String key,String fingerprint,String reason) {
        actor.requireAdmin();Input.reason(reason);return command(actor,"reject/"+id,key,fingerprint,tx->{
            var b=versioned(tx,actor,id,version);var rejected=b.reject();tx.save(rejected);
            tx.saveLedger(id,tx.ledger(id).requireRefunds("REJECTED"));tx.event(rejected,"REJECTED",actor,reason);return Receipt.ok(rejected);
        });
    }
    public Receipt cancel(Actor actor,UUID id,long version,String key,String fingerprint,String classification,String reason) {
        if(actor.admin()) {Input.reason(reason);require(Set.of("PROVIDER","CUSTOMER_LATE").contains(classification),"INVALID_CLASSIFICATION");}
        return command(actor,"cancel/"+id,key,fingerprint,tx->{
            var b=versioned(tx,actor,id,version);
            if(b.state()==Booking.State.CANCELLED)return Receipt.ok(b);
            var cancelled=b.cancel(tx.now(),!actor.admin());tx.save(cancelled);
            if(!actor.admin()||"PROVIDER".equals(classification))tx.saveLedger(id,tx.ledger(id).requireRefunds("CANCELLED"));
            tx.event(cancelled,"CANCELLED",actor,actor.admin()?classification+": "+reason:"");return Receipt.ok(cancelled);
        });
    }
    public Receipt reschedule(Actor actor,UUID id,long version,String key,String fingerprint,Instant target,long policyVersion,SchedulingRules.Override override,String reason) {
        if(actor.admin())Input.reason(reason);
        return command(actor,"reschedule/"+id,key,fingerprint,tx->{
            var b=versioned(tx,actor,id,version);require(tx.policy().version()==policyVersion,"POLICY_CHANGED");
            require(target!=null && target.equals(target.truncatedTo(ChronoUnit.MICROS)),"INVALID_PRECISION");
            var moved=b.reschedule(tx.now(),target,!actor.admin());
            validateTarget(tx,b.customerId(),target,id,true,actor.admin()?Objects.requireNonNullElseGet(override,SchedulingRules.Override::none):SchedulingRules.Override.none());
            tx.save(moved);tx.event(moved,"RESCHEDULED",actor,actor.admin()?reason:"");if(actor.admin()&&override!=null&&!override.rules().isEmpty())tx.recordOverride(moved,override,actor);return Receipt.ok(moved);
        });
    }
    public Receipt meeting(Actor actor,UUID id,long version,String key,String fingerprint,Meeting meeting) {
        actor.requireAdmin();return command(actor,"meeting/"+id,key,fingerprint,tx->{var b=versioned(tx,actor,id,version).updateMeeting(meeting);tx.save(b);tx.event(b,"MEETING_CHANGED",actor,"");return Receipt.ok(b);});
    }
    public Receipt outcome(Actor actor,UUID id,long version,String key,String fingerprint,Booking.State outcome,String reason) {
        actor.requireAdmin();return command(actor,"outcome/"+id,key,fingerprint,tx->{var b=versioned(tx,actor,id,version).outcome(tx.now(),outcome,reason);tx.save(b);tx.event(b,"OUTCOME",actor,Objects.requireNonNullElse(reason,""));return Receipt.ok(b);});
    }
    public Receipt payment(Actor actor,UUID id,long version,String key,String fingerprint,Money money,String method,String reference,Instant verifiedAt,boolean confirm,Meeting meeting) {
        actor.requireAdmin();return command(actor,"payment/"+id,key,fingerprint,tx->{
            var b=versioned(tx,actor,id,version);require(verifiedAt!=null && !verifiedAt.isAfter(tx.now()),"INVALID_PAYMENT_TIME");
            var entry=new PaymentLedger.Entry(UUID.randomUUID(),id,PaymentLedger.Kind.RECEIPT,money,method,reference,verifiedAt,null);
            var ledger=tx.ledger(id).append(entry);
            if(Set.of(Booking.State.CANCELLED,Booking.State.REJECTED,Booking.State.EXPIRED).contains(b.state()))ledger=ledger.requireRefund(entry.id(),"LATE_RECEIPT");
            tx.saveLedger(id,ledger);String warning=null;
            if(confirm) {
                if(!b.livePending(tx.now()))warning="PAYMENT_RECORDED_NOT_CONFIRMED";
                else if(!ledger.sufficient(b.snapshot().service().price()))warning="PAYMENT_INSUFFICIENT";
                else if(meeting==null)warning="MEETING_REQUIRED";
                else {
                    Booking confirmed=b.approve(tx.now(),meeting,Booking.ConfirmationBasis.EXTERNAL_PAYMENT);
                    // Meeting conflicts are a separate attempted change: valid money remains recorded.
                    Receipt confirmation=tx.attempt(()->{tx.save(confirmed);tx.event(confirmed,"CONFIRMED",actor,"");return Receipt.ok(confirmed);});
                    if(confirmation.success())b=confirmed;else warning=confirmation.code();
                }
            }
            tx.event(b,"PAYMENT_RECORDED",actor,"");
            return new Receipt("OK",b.id(),b.version(),warning);
        });
    }
    public Receipt refund(Actor actor,UUID id,String key,String fingerprint,UUID receiptId,Money money,String method,String reference,Instant verifiedAt) {
        actor.requireAdmin();return command(actor,"refund/"+id,key,fingerprint,tx->{
            var b=owned(tx,actor,id);require(verifiedAt!=null&&!verifiedAt.isAfter(tx.now()),"INVALID_PAYMENT_TIME");
            var ledger=tx.ledger(id).append(new PaymentLedger.Entry(UUID.randomUUID(),id,PaymentLedger.Kind.REFUND,money,method,reference,verifiedAt,receiptId));
            tx.saveLedger(id,ledger);tx.event(b,"REFUND_RECORDED",actor,"");return Receipt.ok(b);
        });
    }
    public void reclaim() { work.execute(tx->{reclaim(tx);return null;}); }
    public Receipt updateService(Actor actor,String key,String fingerprint,ServiceDefinition service,Long expectedVersion) {
        actor.requireAdmin();return command(actor,"service/"+service.id(),key,fingerprint,tx->{
            Optional<ServiceDefinition> previous=tx.services().stream().filter(s->s.id().equals(service.id())).findFirst();
            if(previous.isPresent())require(expectedVersion!=null&&previous.get().version()==expectedVersion&&service.version()==expectedVersion+1,"STALE_VERSION");
            else require(service.version()==1,"STALE_VERSION");
            tx.saveService(service);return new Receipt("OK",null,service.version(),null);
        });
    }
    public record Configuration(SchedulingPolicy policy,AvailabilityCalendar calendar,List<UUID> conflicts) {}
    public Configuration configuration(Actor actor) { actor.requireAdmin();return work.read(v->new Configuration(v.policy(),v.calendar(),conflicts(v,v.policy(),v.calendar()))); }
    public Receipt configuration(Actor actor,String key,String fingerprint,long expected,SchedulingPolicy policy,AvailabilityCalendar calendar,String reason) {
        actor.requireAdmin();Input.reason(reason);return command(actor,"configuration",key,fingerprint,tx->{
            require(tx.policy().version()==expected&&policy.version()==expected+1,"STALE_VERSION");
            tx.saveConfiguration(policy,calendar,actor,reason);return new Receipt("OK",null,policy.version(),null);
        });
    }
    public Receipt note(Actor actor,UUID id,String key,String fingerprint,String note) {
        actor.requireAdmin();String text=Input.multiline(note,4000);require(!text.isBlank(),"VALIDATION_FAILED");
        return command(actor,"note/"+id,key,fingerprint,tx->{var b=owned(tx,actor,id);tx.addNote(id,text,actor);return Receipt.ok(b);});
    }
    public Receipt confirmContact(Actor actor,UUID id,long version,String key,String fingerprint,String tokenHash,String browserHash){
        return command(actor,"contact/"+id,key,fingerprint,tx->{var old=versioned(tx,actor,id,version);String email=tx.verifyContact(id,tokenHash,browserHash);Booking updated=old.deliveryContact(email);tx.save(updated);tx.contactChanged(old,updated,actor);return Receipt.ok(updated);});
    }
    private Receipt command(Actor actor,String operation,String key,String fingerprint,Function<Transaction,Receipt> mutation) {
        Input.text(key,8,120);require(fingerprint!=null&&fingerprint.matches("[a-f0-9]{64}"),"INVALID_FINGERPRINT");
        String scope=actor.id()+"/"+operation;
        return work.execute(tx->{
            Optional<Receipt> replay=tx.replay(scope,key,fingerprint);if(replay.isPresent())return replay.get();
            reclaim(tx);Receipt receipt=tx.attempt(()->mutation.apply(tx));tx.saveReceipt(scope,key,fingerprint,receipt);return receipt;
        });
    }
    private void reclaim(Transaction tx) {
        tx.bookings().stream().filter(b->b.state()==Booking.State.PENDING_CONFIRMATION&&!tx.now().isBefore(b.expiresAt())).sorted(Comparator.comparing(Booking::id)).forEach(b->{
            Booking expired=b.expire(tx.now());tx.save(expired);tx.saveLedger(b.id(),tx.ledger(b.id()).requireRefunds("EXPIRED"));tx.event(expired,"EXPIRED",null,"");
        });
    }
    private void validateTarget(Transaction tx,UUID customer,Instant start,UUID except,boolean skipRequestQuota,SchedulingRules.Override override) {
        List<Booking> all=tx.bookings();
        var occupied=all.stream().filter(b->!b.id().equals(except)&&b.occupies(tx.now())).map(Booking::interval).toList();
        SchedulingRules.validate(start,tx.now(),tx.policy(),tx.calendar(),daily(all,start.atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate(),tx.now(),except),
                active(all,customer,tx.now(),except),skipRequestQuota?0:tx.requestCount(customer,tx.now().atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate()),occupied,override);
    }
    private static int daily(List<Booking> all,LocalDate day,Instant now,UUID except) { return (int)all.stream().filter(b->!b.id().equals(except)&&b.businessDate().equals(day)&&b.occupies(now)).count(); }
    private static int active(List<Booking> all,UUID customer,Instant now,UUID except) { return (int)all.stream().filter(b->!b.id().equals(except)&&b.customerId().equals(customer)&&b.active(now)).count(); }
    private static ServiceDefinition service(View view,UUID id) { return view.services().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->new DomainFailure("SERVICE_UNAVAILABLE")); }
    private static Booking owned(View view,Actor actor,UUID id) { require(actor!=null,"UNAUTHENTICATED");return view.bookings().stream().filter(b->b.id().equals(id)&&actor.canRead(b.customerId(),b.id())).findFirst().orElseThrow(()->new DomainFailure("NOT_FOUND")); }
    private static Booking versioned(View view,Actor actor,UUID id,long version) { var b=owned(view,actor,id);require(b.version()==version,"STALE_VERSION");return b; }
    private static BookingView view(View v,Booking b,Actor actor) {
        List<String> actions=new ArrayList<>();
        if(b.customerMayChange(v.now())||actor.admin()&&(b.livePending(v.now())||b.state()==Booking.State.CONFIRMED)){actions.add("CANCEL");actions.add("RESCHEDULE");}
        if(b.previouslyConfirmed())actions.add("CALENDAR");if(actor.admin()&&b.livePending(v.now())){actions.add("APPROVE");actions.add("REJECT");}
        return new BookingView(b,v.ledger(b.id()).summary(b.snapshot().service().price()),List.copyOf(actions));
    }
    private static List<UUID> conflicts(View v,SchedulingPolicy policy,AvailabilityCalendar calendar) {
        List<Booking> all=v.bookings();return all.stream().filter(b->b.start().isAfter(v.now())&&b.occupies(v.now()))
            .filter(b->!calendar.permitsStart(b.start(),policy.gridMinutes())||calendar.blackouts().stream().anyMatch(x->x.interval().intersects(b.interval()))||daily(all,b.businessDate(),v.now(),null)>policy.dailyCapacity())
            .map(Booking::id).toList();
    }
}
