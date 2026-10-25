package com.purpuragenda.adapters.in.rest;
import com.purpuragenda.application.usecase.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.purpuragenda.domain.DomainFailure.require;

@RestController @RequestMapping("/api/v1/bookings/{id}")
public class ContactController {
    private final BookingService bookings;private final IdentityService identities;
    public ContactController(BookingService bookings,IdentityService identities){this.bookings=bookings;this.identities=identities;}
    public record Contact(String email){}
    public record Proof(String token){}
    @PostMapping("/contact-changes") @ResponseStatus(HttpStatus.ACCEPTED) public void challenge(@PathVariable UUID id,@RequestBody Contact body,HttpServletRequest request){bookings.get(SessionAccess.actor(request),id);require(SessionAccess.identity(request).authenticatedAt().plusSeconds(600).isAfter(Instant.now()),"REAUTHENTICATION_REQUIRED");identities.contactChallenge(id,body.email(),AuthController.nonce(request),request.getRemoteAddr());}
    @PostMapping("/contact-change-confirmations") public Object confirm(@PathVariable UUID id,@RequestBody Proof body,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="If-Match",required=false)String version,HttpServletRequest request){require(body.token()!=null&&body.token().matches("[A-Za-z0-9_-]{43}"),"INVALID_TOKEN");return BookingController.receipt(bookings.confirmContact(SessionAccess.actor(request),id,BookingController.version(version),key,BookingController.fingerprint(body),RequestSecrets.hash(body.token()),RequestSecrets.hash(AuthController.nonce(request))),false);}
}
