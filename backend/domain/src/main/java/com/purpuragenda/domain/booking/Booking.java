package com.purpuragenda.domain.booking;

import static com.purpuragenda.domain.DomainFailure.require;
import com.purpuragenda.domain.customer.*;
import com.purpuragenda.domain.scheduling.*;
import java.time.*;
import java.util.UUID;

public record Booking(UUID id, String reference, UUID customerId, Snapshot snapshot, String deliveryEmail,
                      State state, Origin origin, Instant createdAt, Instant start, Instant expiresAt,
                      Meeting meeting, ConfirmationBasis confirmationBasis, long version, int calendarRevision,
                      boolean previouslyConfirmed) {
    public enum State { PENDING_CONFIRMATION, CONFIRMED, CANCELLED, REJECTED, EXPIRED, COMPLETED, NO_SHOW }
    public enum Origin { CUSTOMER, ADMINISTRATOR }
    public enum ConfirmationBasis { MANUAL_APPROVAL, EXTERNAL_PAYMENT }
    public record Snapshot(ServiceDefinition service, SchedulingPolicy policy, Intake intake, String creationEmail,
                           String displayZone, String privacyNoticeVersion) {
        public Snapshot {
            require(service != null && policy != null && intake != null, "INVALID_SNAPSHOT");
            creationEmail = Input.email(creationEmail); Input.zone(displayZone); privacyNoticeVersion = Input.text(privacyNoticeVersion, 1, 120);
        }
    }
    public Booking {
        require(id != null && customerId != null && snapshot != null && state != null && origin != null
                && createdAt != null && start != null && version > 0 && calendarRevision >= 0, "INVALID_BOOKING");
        reference = Input.text(reference, 8, 80); deliveryEmail = Input.email(deliveryEmail);
        require(state != State.PENDING_CONFIRMATION || (expiresAt != null && expiresAt.isAfter(createdAt)), "IMPOSSIBLE_PENDING_HOLD");
        require(state != State.CONFIRMED || (meeting != null && confirmationBasis != null), "MEETING_REQUIRED");
    }
    public static Booking pending(UUID id, String reference, UUID customer, Snapshot snapshot, Origin origin, Instant now, Instant start) {
        snapshot.intake.validateAt(now);
        return new Booking(id,reference,customer,snapshot,snapshot.creationEmail,State.PENDING_CONFIRMATION,origin,now,start,
                snapshot.policy.expiresAt(now,start),null,null,1,0,false);
    }
    public static Booking confirmed(UUID id, String reference, UUID customer, Snapshot snapshot, Origin origin, Instant now, Instant start, Meeting meeting) {
        snapshot.intake.validateAt(now); require(start.isAfter(now), "START_NOT_FUTURE"); require(meeting != null, "MEETING_REQUIRED");
        return new Booking(id,reference,customer,snapshot,snapshot.creationEmail,State.CONFIRMED,origin,now,start,null,meeting,ConfirmationBasis.MANUAL_APPROVAL,1,0,true);
    }
    public Instant sessionEnd() { return start.plus(Duration.ofMinutes(snapshot.service.durationMinutes())); }
    public Instant reservedEnd() { return start.plus(Duration.ofMinutes(120)); }
    public ReservationInterval interval() { return ReservationInterval.reserve(start); }
    public LocalDate businessDate() { return start.atZone(SchedulingPolicy.BUSINESS_ZONE).toLocalDate(); }
    public boolean livePending(Instant now) { return state == State.PENDING_CONFIRMATION && now.isBefore(expiresAt); }
    public boolean occupies(Instant now) { return livePending(now) || state == State.CONFIRMED || state == State.COMPLETED || state == State.NO_SHOW; }
    public boolean active(Instant now) { return (livePending(now) || state == State.CONFIRMED) && sessionEnd().isAfter(now); }
    public boolean customerMayChange(Instant now) { return (livePending(now) || state == State.CONFIRMED) && snapshot.policy.customerMayChange(start,now); }
    public Booking approve(Instant now, Meeting details, ConfirmationBasis basis) {
        require(state == State.PENDING_CONFIRMATION, "INVALID_TRANSITION"); require(now.isBefore(expiresAt), "HOLD_EXPIRED");
        require(start.isAfter(now), "START_NOT_FUTURE"); require(details != null, "MEETING_REQUIRED"); require(basis != null, "INVALID_TRANSITION");
        return copy(State.CONFIRMED,start,expiresAt,details,basis,calendarRevision,true);
    }
    public Booking expire(Instant now) {
        require(state == State.PENDING_CONFIRMATION && !now.isBefore(expiresAt), "INVALID_TRANSITION");
        return copy(State.EXPIRED,start,expiresAt,meeting,confirmationBasis,calendarRevision,previouslyConfirmed);
    }
    public Booking reject() {
        require(state == State.PENDING_CONFIRMATION, "INVALID_TRANSITION");
        return copy(State.REJECTED,start,expiresAt,meeting,confirmationBasis,calendarRevision,previouslyConfirmed);
    }
    public Booking cancel(Instant now, boolean customer) {
        require(livePending(now) || state == State.CONFIRMED, "INVALID_TRANSITION");
        if (customer) require(customerMayChange(now), "CUTOFF_PASSED");
        return copy(State.CANCELLED,start,expiresAt,meeting,confirmationBasis,calendarRevision+(previouslyConfirmed?1:0),previouslyConfirmed);
    }
    public Booking reschedule(Instant now, Instant target, boolean customer) {
        require(livePending(now) || state == State.CONFIRMED, "INVALID_TRANSITION");
        if (customer) require(customerMayChange(now), "CUTOFF_PASSED");
        require(target.isAfter(now), "START_NOT_FUTURE");
        Instant deadline = expiresAt;
        if (state == State.PENDING_CONFIRMATION) {
            Instant ceiling = target.minus(snapshot.policy.expiryBuffer());
            if (ceiling.isBefore(deadline)) deadline = ceiling;
            require(deadline.isAfter(now), "IMPOSSIBLE_PENDING_HOLD");
        }
        return copy(state,target,deadline,meeting,confirmationBasis,calendarRevision+(previouslyConfirmed?1:0),previouslyConfirmed);
    }
    public Booking updateMeeting(Meeting details) {
        require(state == State.CONFIRMED || state == State.PENDING_CONFIRMATION, "INVALID_TRANSITION"); require(details != null, "INVALID_MEETING");
        return copy(state,start,expiresAt,details,confirmationBasis,calendarRevision+(previouslyConfirmed?1:0),previouslyConfirmed);
    }
    public Booking deliveryContact(String verifiedEmail){return new Booking(id,reference,customerId,snapshot,Input.email(verifiedEmail),state,origin,createdAt,start,expiresAt,meeting,confirmationBasis,version+1,calendarRevision,previouslyConfirmed);}
    public Booking outcome(Instant now, State outcome, String earlyReason) {
        require(state == State.CONFIRMED && (outcome == State.COMPLETED || outcome == State.NO_SHOW), "INVALID_TRANSITION");
        require(!now.isBefore(start), "SESSION_NOT_STARTED");
        if (now.isBefore(sessionEnd())) Input.reason(earlyReason);
        return copy(outcome,start,expiresAt,meeting,confirmationBasis,calendarRevision,previouslyConfirmed);
    }
    private Booking copy(State next, Instant when, Instant expiry, Meeting details, ConfirmationBasis basis, int revision, boolean confirmed) {
        return new Booking(id,reference,customerId,snapshot,deliveryEmail,next,origin,createdAt,when,expiry,details,basis,version+1,revision,confirmed);
    }
}
