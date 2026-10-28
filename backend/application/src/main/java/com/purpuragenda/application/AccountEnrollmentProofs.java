package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AuthDeliveryStore;
import java.util.Objects;

/** Explicit enrollment proof issuance never looks up or discloses existing account membership. */
public final class AccountEnrollmentProofs {
    public record Result(boolean accepted,long retryAfterSeconds) { }
    private final RequestThrottle throttle;private final AuthDeliveryStore delivery;
    public AccountEnrollmentProofs(RequestThrottle throttle,AuthDeliveryStore delivery) { this.throttle=Objects.requireNonNull(throttle);this.delivery=Objects.requireNonNull(delivery); }
    public Result request(String email,String clientAddress,String browserHash) {
        var issue=new AuthDeliveryStore.Issue(email,AuthDeliveryStore.Purpose.ACCOUNT,null,browserHash);
        var admission=throttle.emailIssuance(issue.email(),clientAddress);
        if(!admission.allowed())return new Result(false,admission.retryAfterSeconds());
        delivery.issue(issue);return new Result(true,0);
    }
}
