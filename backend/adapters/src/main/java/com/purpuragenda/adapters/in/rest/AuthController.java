package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.usecase.IdentityService;
import com.purpuragenda.application.port.out.IdentityGateway.SessionIdentity;
import com.purpuragenda.domain.DomainFailure;
import static com.purpuragenda.domain.DomainFailure.require;

import jakarta.servlet.http.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class AuthController {
    private final IdentityService identities;
    public AuthController(IdentityService identities){this.identities=identities;}
    public record Challenge(String email,String purpose,String reference){}
    public record Token(String token){}
    public record Password(String identifier,String password){}
    public record Code(String code){}
    @GetMapping("/security/csrf") public Object csrf(CsrfToken token,HttpServletRequest request){nonce(request);return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
    @PostMapping("/auth/email/challenges") @ResponseStatus(HttpStatus.ACCEPTED) public void challenge(@RequestBody Challenge body,HttpServletRequest request){identities.challenge(body.email(),body.purpose(),body.reference(),nonce(request),request.getRemoteAddr());}
    @PostMapping("/auth/account-enrollment") @ResponseStatus(HttpStatus.ACCEPTED) public void enroll(@RequestBody Challenge body,HttpServletRequest request){identities.challenge(body.email(),"ACCOUNT",null,nonce(request),request.getRemoteAddr());}
    @PostMapping("/auth/management-recovery") @ResponseStatus(HttpStatus.ACCEPTED) public void recover(@RequestBody Challenge body,HttpServletRequest request){identities.challenge(body.email(),"MANAGE",body.reference(),nonce(request),request.getRemoteAddr());}
    @PostMapping("/auth/email/redemptions") public Object redeem(@RequestBody Token body,HttpServletRequest request){SessionIdentity identity=identities.redeem(body.token(),nonce(request));establish(request,identity);return me(identity);}
    @GetMapping("/me") public Object me(HttpServletRequest request){var identity=SessionAccess.identity(request);return identity==null?Map.of("authenticated",false):me(identity);}
    @PostMapping("/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT) public void logout(HttpServletRequest request){identities.revoke(SessionAccess.identity(request));var session=request.getSession(false);if(session!=null)session.invalidate();}
    @PostMapping("/admin/auth/password") public Object password(@RequestBody Password body,HttpServletRequest request){UUID id=identities.password(body.identifier(),body.password(),request.getRemoteAddr());request.changeSessionId();request.getSession().setAttribute("PRE_MFA",id.toString());request.getSession().setAttribute("PRE_MFA_EXPIRY",Instant.now().plusSeconds(300).toString());return Map.of("requiresMfa",true);}
    @PostMapping("/admin/auth/totp") public Object totp(@RequestBody Code body,HttpServletRequest request){SessionIdentity identity=identities.totp(pre(request,"PRE_MFA"),body.code());establish(request,identity);request.getSession().removeAttribute("PRE_MFA");return me(identity);}
    @PostMapping("/admin/auth/recovery-code") public Object recovery(@RequestBody Code body,HttpServletRequest request){SessionIdentity recovered=identities.recovery(pre(request,"PRE_MFA"),body.code());request.changeSessionId();request.getSession().removeAttribute(SessionAccess.IDENTITY);request.getSession().setAttribute("RECOVERY_MFA",recovered.id().toString());return Map.of("requiresReenrollment",true);}
    @GetMapping("/admin/auth/mfa-enrollment") public Object enrollment(HttpServletRequest request){return identities.enrollment(pre(request,"RECOVERY_MFA"));}
    @PostMapping("/admin/auth/mfa-enrollment") public Object enrollment(@RequestBody Code body,HttpServletRequest request){var codes=identities.completeEnrollment(pre(request,"RECOVERY_MFA"),body.code());request.getSession().invalidate();return Map.of("recoveryCodes",codes,"requiresSignIn",true);}
    private static UUID pre(HttpServletRequest request,String key){var session=request.getSession(false);if(session==null)throw new DomainFailure("AUTHENTICATION_FAILED");Object value=session.getAttribute(key),expiry=session.getAttribute("PRE_MFA_EXPIRY");require(value instanceof String&&expiry instanceof String&&Instant.now().isBefore(Instant.parse((String)expiry)),"AUTHENTICATION_FAILED");return UUID.fromString((String)value);}
    static String nonce(HttpServletRequest request){var session=request.getSession();String value=(String)session.getAttribute("BROWSER_NONCE");if(value==null){value=RequestSecrets.nonce();session.setAttribute("BROWSER_NONCE",value);}return value;}
    private static void establish(HttpServletRequest request,SessionIdentity identity){request.changeSessionId();var session=request.getSession();session.setAttribute(SessionAccess.IDENTITY,identity);session.removeAttribute("org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN");session.setMaxInactiveInterval(identity.kind()==com.purpuragenda.application.port.in.Actor.Kind.ADMINISTRATOR?900:1800);}
    private static Object me(SessionIdentity identity){Map<String,Object> result=new HashMap<>();result.put("authenticated",true);result.put("kind",identity.kind());result.put("bookingId",identity.bookingId());result.put("absoluteExpiry",identity.absoluteExpiry());return result;}
}
