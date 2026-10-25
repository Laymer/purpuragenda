package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.in.Actor;
import java.time.Instant;
import java.util.*;

public interface IdentityGateway {
    record SessionIdentity(UUID id,Actor.Kind kind,UUID customerId,UUID bookingId,Instant authenticatedAt,Instant absoluteExpiry) implements java.io.Serializable {
        public Actor actor(){return new Actor(id,kind,customerId,bookingId);}
    }
    void challenge(String email,String purpose,String reference,String browserNonce,String ip);
    SessionIdentity redeem(String token,String browserNonce);
    UUID password(String email,String password,String ip);
    SessionIdentity totp(UUID preMfa,String code);
    SessionIdentity recovery(UUID preMfa,String code);
    Optional<SessionIdentity> validate(SessionIdentity identity);
    void revoke(SessionIdentity identity);
    UUID bootstrap(String email,char[] password);
    Map<String,String> enrollment(UUID admin);
    void completeEnrollment(UUID admin,String code);
    List<String> recoveryCodes(UUID admin);
    UUID ensureCustomer(Actor administrator,String email);
    void contactChallenge(UUID booking,String email,String browserNonce,String ip);
}
