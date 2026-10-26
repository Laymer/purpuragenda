package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AuthDeliveryStore;
import com.purpuragenda.application.port.out.GuestRecoveryLookup;
import java.util.Objects;

/**
 * Throttled guest proof issuance. The distributed email limits are charged before any lookup or delivery so the
 * outcome never depends on whether a contact or reference exists. Acceptance carries no authority.
 */
public final class GuestProofs {
    /** Either accepted (identically for known and unknown inputs) or a positive whole-second wait. */
    public record Outcome(boolean accepted,long retryAfterSeconds) {
        static final Outcome ACCEPTED=new Outcome(true,0);
    }
    private final RequestThrottle throttle;
    private final AuthDeliveryStore delivery;
    private final GuestRecovery recovery;
    public GuestProofs(RequestThrottle throttle,AuthDeliveryStore delivery,GuestRecovery recovery) {
        this.throttle=Objects.requireNonNull(throttle);this.delivery=Objects.requireNonNull(delivery);this.recovery=Objects.requireNonNull(recovery);
    }
    /** Booking-creation proof, bound to the requesting browser's server-owned hash. */
    public Outcome requestCreate(String email,String clientAddress,String browserHash) {
        var issue=new AuthDeliveryStore.Issue(email,AuthDeliveryStore.Purpose.CREATE,null,browserHash);
        var admission=throttle.emailIssuance(email,clientAddress);
        if(!admission.allowed()) return new Outcome(false,admission.retryAfterSeconds());
        delivery.issue(issue);
        return Outcome.ACCEPTED;
    }
    /** Exact booking reference plus current delivery contact; unknown pairs are indistinguishable from known ones. */
    public Outcome requestManage(String reference,String email,String clientAddress) {
        var request=new GuestRecoveryLookup.Request(reference,email);
        var admission=throttle.emailIssuance(email,clientAddress);
        if(!admission.allowed()) return new Outcome(false,admission.retryAfterSeconds());
        recovery.request(request);
        return Outcome.ACCEPTED;
    }
}
