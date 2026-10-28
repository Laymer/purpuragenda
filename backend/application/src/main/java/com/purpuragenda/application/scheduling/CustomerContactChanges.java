package com.purpuragenda.application.scheduling;

import com.purpuragenda.application.RequestThrottle;
import com.purpuragenda.application.port.out.ContactChangesUnitOfWork;
import com.purpuragenda.application.port.out.ContactChangesUnitOfWork.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.booking.Booking;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Delivery contact only: stable ownership, account login, immutable snapshots and quotas are never transferred. */
public final class CustomerContactChanges {
    private final ContactChangesUnitOfWork unit;private final RequestThrottle throttle;
    public CustomerContactChanges(ContactChangesUnitOfWork unit,RequestThrottle throttle) { this.unit=Objects.requireNonNull(unit);this.throttle=Objects.requireNonNull(throttle); }
    public record Requested(boolean accepted,long retryAfterSeconds) { }
    public record Confirmation(long expectedVersion,String token) {
        public Confirmation { require(expectedVersion>0 && expectedVersion<Long.MAX_VALUE && token!=null && token.matches("[A-Za-z0-9_-]{43}"),"INVALID_CONTACT_CHANGE"); }
        @Override public String toString() { return "ContactConfirmation[REDACTED]"; }
    }
    public Requested request(SessionBinding session,UUID bookingId,String email,String clientAddress) {
        var request=new ProofRequest(session,bookingId,email);
        var admission=throttle.emailIssuance(request.email(),clientAddress);
        if(!admission.allowed())return new Requested(false,admission.retryAfterSeconds());
        unit.request(request);return new Requested(true,0);
    }
    public Receipt confirm(SessionBinding session,UUID bookingId,String key,Confirmation request) {
        Objects.requireNonNull(request);
        String fingerprint=CommandKey.fingerprint(List.of(Long.toString(request.expectedVersion()),request.token()));
        return unit.change(new Command(session,bookingId,request.token(),key,fingerprint),tx -> SchedulingCommands.within(tx,() -> {
            Booking original=tx.authorizedBooking();require(original.version()==request.expectedVersion(),"STALE_VERSION");
            Booking changed=original.deliveryContact(tx.verifiedEmail());
            tx.save(changed);tx.contactChangedEffects(original,changed);
            return new Receipt(Code.OK,changed.id(),changed.version());
        }));
    }
}
