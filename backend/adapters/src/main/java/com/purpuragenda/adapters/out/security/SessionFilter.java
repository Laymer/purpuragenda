package com.purpuragenda.adapters.out.security;

import com.purpuragenda.application.usecase.IdentityService;
import com.purpuragenda.application.port.out.IdentityGateway.SessionIdentity;
import com.purpuragenda.adapters.in.rest.SessionAccess;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class SessionFilter extends OncePerRequestFilter {
    private final IdentityService identities;
    public SessionFilter(IdentityService identities){this.identities=identities;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        var session=request.getSession(false);Object value=session==null?null:session.getAttribute(SessionAccess.IDENTITY);
        if(value instanceof SessionIdentity stored)identities.validate(stored).ifPresent(identity->{
            request.setAttribute(SessionAccess.IDENTITY,identity);
            var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(identity.id(),null,List.of(new SimpleGrantedAuthority("ROLE_"+identity.kind().name()))));SecurityContextHolder.setContext(context);
        });
        chain.doFilter(request,response);
    }
}
