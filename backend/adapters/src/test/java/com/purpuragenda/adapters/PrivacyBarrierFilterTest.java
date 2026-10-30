package com.purpuragenda.adapters;

import com.purpuragenda.adapters.in.rest.*;
import com.purpuragenda.application.port.out.SessionStore.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class PrivacyBarrierFilterTest {
    record Route(String method,String path){}
    void check(boolean stopped,Route route,Kind kind,boolean allowed,boolean unavailable) throws Exception {
        var request=new MockHttpServletRequest(route.method(),route.path());var response=new MockHttpServletResponse();var chain=new MockFilterChain();
        if(kind!=null)request.setAttribute(ApiGuardFilter.SESSION_ATTRIBUTE,new Snapshot(new SessionReference(UUID.randomUUID(),"a".repeat(64)),kind,null,null,null,UUID.randomUUID(),0L,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH.plusSeconds(60),Instant.EPOCH));
        new PrivacyBarrierFilter(()->{if(unavailable)throw new IllegalStateException("SYNTHETIC_UNAVAILABLE");return stopped;}).doFilter(request,response,chain);
        assertEquals(allowed,chain.getRequest()!=null,route.toString());assertEquals(allowed?200:503,response.getStatus());if(!allowed){assertTrue(response.getContentAsString().contains("MAINTENANCE"));assertEquals("no-store",response.getHeader("Cache-Control"));}
    }
    @Test void barrierAllowsOnlyHealthSessionLifecycleAndGuardedAdministratorRetry() throws Exception {
        var allowed=List.of(new Route("GET","/actuator/health"),new Route("GET","/actuator/health/readiness"),new Route("GET","/actuator/health/liveness"),new Route("POST","/api/v1/session/bootstrap"),new Route("POST","/api/v1/session/logout"),new Route("POST","/api/v1/admin/login"),new Route("GET","/api/v1/session"));
        for(var route:allowed){check(true,route,null,true,false);check(false,route,null,true,false);}
        String retry="/api/v1/admin/erasures/30000000-0000-4000-8000-000000000001/confirm";
        check(true,new Route("POST",retry),Kind.ADMIN,true,false);check(true,new Route("POST",retry),Kind.GUEST,false,false);check(true,new Route("POST",retry),null,false,false);
        for(var route:List.of(new Route("GET","/api/v1/admin/bookings"),new Route("HEAD","/api/v1/account/profile"),new Route("PUT","/api/v1/account/profile"),new Route("POST",retry+"/extra"),new Route("GET","/%61pi/v1/account/profile"))){check(true,route,Kind.ADMIN,false,false);check(false,route,null,true,false);}
        check(false,new Route("GET","/api/v1/account/profile"),null,false,true);
    }
}
