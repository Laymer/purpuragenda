package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.GuestProofs;
import com.purpuragenda.application.SessionAccess;
import com.purpuragenda.application.port.out.GuestAccessStore;
import com.purpuragenda.application.port.out.SessionStore.Snapshot;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * Guest email proofs. Every route is unsafe, so {@link ApiGuardFilter} has already required the exact Origin, a live
 * session and its CSRF proof. Issuance answers 202 identically for known and unknown contacts, after the distributed
 * email limits and a fixed minimum latency. The proof itself is only ever redeemed from a POST body: the emailed link
 * carries it in a URL fragment that a landing page must strip and never send to the server in a GET.
 */
@RestController
@RequestMapping("/api/v1/guest")
public final class GuestAuthController {
    private final GuestProofs proofs;private final SessionAccess access;private final SessionCookies cookies;private final Duration minimum;
    public GuestAuthController(GuestProofs proofs,SessionAccess access,SessionCookies cookies,Duration minimumIssuanceLatency) {
        this.proofs=Objects.requireNonNull(proofs);this.access=Objects.requireNonNull(access);this.cookies=Objects.requireNonNull(cookies);
        if(minimumIssuanceLatency.isNegative() || minimumIssuanceLatency.compareTo(Duration.ofSeconds(5))>0) throw new IllegalArgumentException("INVALID_LATENCY");
        this.minimum=minimumIssuanceLatency;
    }

    @PostMapping("/create-proofs")
    public ResponseEntity<String> requestCreate(HttpServletRequest request,HttpServletResponse response) throws IOException {
        return issue(request,response,session -> {
            String[] body=body(request,"email");
            return proofs.requestCreate(body[0],client(request),session.reference().browserHash());
        });
    }
    @PostMapping("/management-proofs")
    public ResponseEntity<String> requestManage(HttpServletRequest request,HttpServletResponse response) throws IOException {
        return issue(request,response,session -> {
            String[] body=body(request,"reference","email");
            return proofs.requestManage(body[0],body[1],client(request));
        });
    }
    @PostMapping("/redemptions")
    public ResponseEntity<String> redeem(HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { Problems.reject(response,Problems.Code.SESSION_REQUIRED);return null; }
        GuestAccessStore.Redemption proof;
        try {
            String[] body=JsonBodies.read(request,"token","purpose");
            proof=new GuestAccessStore.Redemption(body[0],GuestAccessStore.Kind.valueOf(body[1]),session.reference().browserHash());
        } catch(JsonBodies.Invalid | RuntimeException malformed) { Problems.reject(response,Problems.Code.PROOF_INVALID);return null; }
        try {
            var started=access.redeemGuest(session,proof);
            if(started.isEmpty()) { Problems.reject(response,Problems.Code.PROOF_INVALID);return null; }
            try(var value=started.get()) {
                cookies.write(response,value.cookie());
                return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_JSON)
                        .body(SessionController.state(value.view(),new String(value.csrf())));
            }
        } catch(IllegalStateException unavailable) { Problems.reject(response,Problems.Code.TEMPORARILY_UNAVAILABLE);return null; }
    }

    private interface Work { GuestProofs.Outcome run(Snapshot session) throws JsonBodies.Invalid; }

    private ResponseEntity<String> issue(HttpServletRequest request,HttpServletResponse response,Work work) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { Problems.reject(response,Problems.Code.SESSION_REQUIRED);return null; }
        long start=System.nanoTime();
        try {
            var outcome=work.run(session);
            pad(start);
            if(!outcome.accepted()) { Problems.reject(response,Problems.Code.RATE_LIMITED,outcome.retryAfterSeconds());return null; }
            return ResponseEntity.accepted().header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_JSON).body("{\"accepted\":true}");
        } catch(JsonBodies.Invalid | IllegalArgumentException | com.purpuragenda.domain.DomainFailure invalid) {
            Problems.reject(response,Problems.Code.INVALID_REQUEST);return null;
        } catch(RuntimeException unavailable) {
            pad(start);Problems.reject(response,Problems.Code.TEMPORARILY_UNAVAILABLE);return null;
        }
    }
    private static String[] body(HttpServletRequest request,String... fields) throws JsonBodies.Invalid { return JsonBodies.read(request,fields); }
    private static String client(HttpServletRequest request) {
        return request.getAttribute(ApiGuardFilter.CLIENT_ATTRIBUTE) instanceof String text?text:"0.0.0.0";
    }
    /** Hides the cost difference between a known contact (database work and delivery) and an unknown one. */
    private void pad(long startNanos) {
        long remaining=minimum.toNanos()-(System.nanoTime()-startNanos);
        if(remaining>0) try { Thread.sleep(remaining/1_000_000,(int)(remaining%1_000_000)); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
