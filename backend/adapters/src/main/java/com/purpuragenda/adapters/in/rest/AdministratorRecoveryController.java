package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.purpuragenda.application.AdministratorRecovery;
import com.purpuragenda.application.port.out.SessionStore.Snapshot;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import org.bouncycastle.util.encoders.Base32;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Recovery returns restricted setup material, never a cookie, session or ordinary administrator authorization. */
@RestController
@RequestMapping("/api/v1/admin/recovery")
public final class AdministratorRecoveryController {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final AdministratorRecovery recovery;private final long minimumNanos;
    public AdministratorRecoveryController(AdministratorRecovery recovery,Duration minimumLatency) {
        this.recovery=Objects.requireNonNull(recovery);
        if(minimumLatency.isNegative() || minimumLatency.compareTo(Duration.ofSeconds(5))>0)throw new IllegalArgumentException("INVALID_LATENCY");
        minimumNanos=minimumLatency.toNanos();
    }
    @PostMapping
    public ResponseEntity<String> begin(HttpServletRequest request,HttpServletResponse response) throws IOException { return handle(request,response,false); }
    @PostMapping("/reenrollment")
    public ResponseEntity<String> reenroll(HttpServletRequest request,HttpServletResponse response) throws IOException { return handle(request,response,true); }
    private ResponseEntity<String> handle(HttpServletRequest request,HttpServletResponse response,boolean finish) throws IOException {
        if(!(request.getAttribute(ApiGuardFilter.SESSION_ATTRIBUTE) instanceof Snapshot session)) {
            Problems.reject(response,Problems.Code.SESSION_REQUIRED);return null;
        }
        long start=System.nanoTime();char[] password=null,bearer=null;
        try {
            String[] body=finish?JsonBodies.read(request,"email","password","token","totp"):JsonBodies.read(request,"email","password","recoveryCode");
            password=body[1].toCharArray();bearer=body[2].toCharArray();
            String client=request.getAttribute(ApiGuardFilter.CLIENT_ATTRIBUTE) instanceof String value?value:"0.0.0.0";
            var wire=JSON.createObjectNode();
            if(finish) {
                try(var attempt=recovery.reenroll(session,body[0],password,bearer,body[3],client)) {
                    if(!accepted(attempt,response))return null;
                    var codes=wire.putArray("recoveryCodes");
                    for(var code:attempt.value().orElseThrow().codes()) {
                        char[] raw=code.value();try { codes.add(new String(raw)); } finally { Arrays.fill(raw,'\0'); }
                    }
                    wire.put("requiresFreshMfa",true);
                }
            } else {
                try(var attempt=recovery.begin(session,body[0],password,bearer,client)) {
                    if(!accepted(attempt,response))return null;
                    var value=attempt.value().orElseThrow();char[] raw=value.token().value();byte[] bytes=value.secret().bytes();
                    try {
                        String secret=Base32.toBase32String(bytes).replace("=","");
                        wire.put("token",new String(raw)).put("expiresAt",value.expiresAt().toString()).put("totpSecret",secret)
                                .put("provisioningUri","otpauth://totp/Purpuragenda:Administrator?secret="+secret+"&issuer=Purpuragenda&algorithm=SHA1&digits=6&period=30");
                    } finally { Arrays.fill(raw,'\0');Arrays.fill(bytes,(byte)0); }
                }
            }
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").contentType(MediaType.APPLICATION_JSON).body(wire.toString());
        } catch(JsonBodies.Invalid | DomainFailure | IllegalArgumentException invalid) { Problems.reject(response,Problems.Code.INVALID_REQUEST);return null; }
        catch(RuntimeException unavailable) { Problems.reject(response,Problems.Code.TEMPORARILY_UNAVAILABLE);return null; }
        finally {
            if(password!=null)Arrays.fill(password,'\0');if(bearer!=null)Arrays.fill(bearer,'\0');
            long remaining=minimumNanos-(System.nanoTime()-start);
            if(remaining>0)try { Thread.sleep(remaining/1_000_000,(int)(remaining%1_000_000)); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
    private static boolean accepted(AdministratorRecovery.Attempt<?> attempt,HttpServletResponse response) throws IOException {
        if(attempt.retryAfterSeconds()>0) { Problems.reject(response,Problems.Code.RATE_LIMITED,attempt.retryAfterSeconds());return false; }
        if(attempt.value().isEmpty()) { Problems.reject(response,Problems.Code.AUTHENTICATION_FAILED);return false; }
        return true;
    }
}
