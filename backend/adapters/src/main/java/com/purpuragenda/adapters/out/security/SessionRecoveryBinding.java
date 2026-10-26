package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.ReenrollmentGrantPolicy.Binding;
import com.purpuragenda.application.port.out.AdminRecoveryBinding;
import com.purpuragenda.application.port.out.SessionStore.SessionReference;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** No caller-supplied request identifier: session identity and browser bind this recovery purpose. */
public final class SessionRecoveryBinding implements AdminRecoveryBinding {
    @Override public Binding derive(SessionReference session) {
        Objects.requireNonNull(session);
        byte[] browser=HexFormat.of().parseHex(session.browserHash()),request=null;
        try {
            request=MessageDigest.getInstance("SHA-256").digest(("ADMIN_REENROLLMENT:v1:"+session.id()+":"+session.browserHash()).getBytes(StandardCharsets.US_ASCII));
            return new Binding(browser,request);
        } catch(GeneralSecurityException unavailable) { throw new IllegalStateException("ADMIN_RECOVERY_BINDING_UNAVAILABLE"); }
        finally { Arrays.fill(browser,(byte)0);if(request!=null)Arrays.fill(request,(byte)0); }
    }
}
