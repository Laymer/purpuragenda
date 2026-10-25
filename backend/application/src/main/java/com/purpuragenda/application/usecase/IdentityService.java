package com.purpuragenda.application.usecase;

import com.purpuragenda.application.port.in.Actor;
import com.purpuragenda.application.port.out.IdentityGateway;
import com.purpuragenda.domain.customer.Input;
import static com.purpuragenda.domain.DomainFailure.require;
import java.util.*;

/** Identity operations are purpose-specific; verifying a guest never enrolls an account. */
public final class IdentityService {
    private final IdentityGateway gateway;
    public IdentityService(IdentityGateway gateway){this.gateway=gateway;}
    public void challenge(String email,String purpose,String reference,String browser,String ip){require(Set.of("CREATE","ACCOUNT","MANAGE").contains(purpose),"INVALID_PURPOSE");gateway.challenge(Input.email(email),purpose,reference,browser,ip);}
    public IdentityGateway.SessionIdentity redeem(String token,String browser){require(token!=null&&token.matches("[A-Za-z0-9_-]{43}"),"INVALID_TOKEN");return gateway.redeem(token,browser);}
    public UUID password(String email,String password,String ip){return gateway.password(Input.email(email),password,ip);}
    public IdentityGateway.SessionIdentity totp(UUID pre,String code){return gateway.totp(pre,code);}
    public IdentityGateway.SessionIdentity recovery(UUID pre,String code){return gateway.recovery(pre,code);}
    public Optional<IdentityGateway.SessionIdentity> validate(IdentityGateway.SessionIdentity identity){return gateway.validate(identity);}
    public void revoke(IdentityGateway.SessionIdentity identity){gateway.revoke(identity);}
    public UUID ensureCustomer(Actor actor,String email){actor.requireAdmin();return gateway.ensureCustomer(actor,Input.email(email));}
    public Map<String,String> enrollment(UUID admin){return gateway.enrollment(admin);}
    public List<String> completeEnrollment(UUID admin,String code){gateway.completeEnrollment(admin,code);return gateway.recoveryCodes(admin);}
    public void contactChallenge(UUID booking,String email,String nonce,String ip){gateway.contactChallenge(booking,Input.email(email),nonce,ip);}
}
