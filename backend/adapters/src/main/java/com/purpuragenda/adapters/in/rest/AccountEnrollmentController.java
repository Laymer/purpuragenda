package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.*;
import com.purpuragenda.application.port.out.SessionStore.Snapshot;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Browser-bound ACCOUNT proofs are consumed only by explicit password enrollment, never by a GET or guest redemption. */
@RestController
@RequestMapping("/api/v1/account")
public final class AccountEnrollmentController {
    private final AccountEnrollmentProofs proofs;private final AccountEnrollmentRequest enrollment;private final long minimumNanos;
    public AccountEnrollmentController(AccountEnrollmentProofs proofs,AccountEnrollmentRequest enrollment,Duration minimumLatency) {
        this.proofs=Objects.requireNonNull(proofs);this.enrollment=Objects.requireNonNull(enrollment);
        if(minimumLatency.isNegative() || minimumLatency.compareTo(Duration.ofSeconds(5))>0)throw new IllegalArgumentException("INVALID_LATENCY");minimumNanos=minimumLatency.toNanos();
    }
    @PostMapping("/enrollment-proofs")
    public ResponseEntity<String> requestProof(HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { Problems.reject(response,Problems.Code.SESSION_REQUIRED);return null; }
        long start=System.nanoTime();
        try {
            if(!request.getParameterMap().isEmpty())throw new BookingHttp.Invalid();var body=BookingHttp.body(request,"email");
            var result=proofs.request(BookingHttp.text(body,"email"),client(request),session.reference().browserHash());
            if(!result.accepted()) { Problems.reject(response,Problems.Code.RATE_LIMITED,result.retryAfterSeconds());return null; }
            return ResponseEntity.accepted().header("Cache-Control","no-store").contentType(MediaType.APPLICATION_JSON).body("{\"accepted\":true}");
        } catch(BookingHttp.Invalid | DomainFailure | IllegalArgumentException invalid) { Problems.reject(response,Problems.Code.INVALID_REQUEST);return null; }
        catch(RuntimeException unavailable) { Problems.reject(response,Problems.Code.TEMPORARILY_UNAVAILABLE);return null; }
        finally { pad(start); }
    }
    @PostMapping("/enrollments")
    public ResponseEntity<Void> enroll(HttpServletRequest request,HttpServletResponse response) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) { Problems.reject(response,Problems.Code.SESSION_REQUIRED);return null; }
        long start=System.nanoTime();char[] password=null;
        try {
            if(!request.getParameterMap().isEmpty())throw new BookingHttp.Invalid();var body=BookingHttp.body(request,"token","password");
            String token=BookingHttp.text(body,"token");if(!token.matches("[A-Za-z0-9_-]{43}")) { Problems.reject(response,Problems.Code.PROOF_INVALID);return null; }
            password=BookingHttp.text(body,"password").toCharArray();
            var result=enrollment.enroll(token,session.reference().browserHash(),password,client(request));
            if(result.retryAfterSeconds()>0) { Problems.reject(response,Problems.Code.RATE_LIMITED,result.retryAfterSeconds());return null; }
            if(!result.enrolled()) { Problems.reject(response,Problems.Code.PROOF_INVALID);return null; }
            return ResponseEntity.noContent().header("Cache-Control","no-store").build();
        } catch(BookingHttp.Invalid | DomainFailure | IllegalArgumentException invalid) { Problems.reject(response,Problems.Code.INVALID_REQUEST);return null; }
        catch(RuntimeException unavailable) { Problems.reject(response,Problems.Code.TEMPORARILY_UNAVAILABLE);return null; }
        finally { if(password!=null)Arrays.fill(password,'\0');pad(start); }
    }
    private static String client(HttpServletRequest request) { return request.getAttribute(ApiGuardFilter.CLIENT_ATTRIBUTE) instanceof String value?value:"0.0.0.0"; }
    private void pad(long started) {
        long remaining=minimumNanos-(System.nanoTime()-started);
        if(remaining>0)try { Thread.sleep(remaining/1_000_000,(int)(remaining%1_000_000)); }catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
