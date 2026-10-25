package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.port.in.Actor;
import com.purpuragenda.application.port.out.IdentityGateway.SessionIdentity;
import com.purpuragenda.domain.DomainFailure;
import jakarta.servlet.http.HttpServletRequest;

public final class SessionAccess {
    public static final String IDENTITY="PURPURAGENDA_IDENTITY";
    private SessionAccess(){}
    public static SessionIdentity identity(HttpServletRequest request){Object value=request.getAttribute(IDENTITY);return value instanceof SessionIdentity i?i:null;}
    public static Actor actor(HttpServletRequest request){var identity=identity(request);if(identity==null)throw new DomainFailure("UNAUTHENTICATED");return identity.actor();}
}
