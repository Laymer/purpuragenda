package com.purpuragenda.application;

import com.purpuragenda.application.port.out.CustomerErasureStore;
import com.purpuragenda.application.port.out.CustomerErasureStore.*;
import com.purpuragenda.application.privacy.AdministratorErasure;
import com.purpuragenda.domain.DomainFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorErasureTest {
    final Scope scope=new Scope(Kind.NOTE,UUID.randomUUID());
    @Test void exactRecordsAndExplicitReviewAreRequiredBeforeStorage(){
        assertThrows(DomainFailure.class,()->new Request(List.of(),"Synthetic reason"));
        assertThrows(DomainFailure.class,()->new Request(List.of(scope,scope),"Synthetic reason"));
        assertThrows(DomainFailure.class,()->new Request(List.of(new Scope(Kind.FINANCIAL,scope.id())),"Synthetic reason"));
        assertThrows(DomainFailure.class,()->new Request(List.of(scope),"short"));
        assertThrows(DomainFailure.class,()->new Request(List.of(scope),"Synthetic reason\ud800"));
        assertThrows(DomainFailure.class,()->new Review("a".repeat(64),false));
        assertThrows(DomainFailure.class,()->new Review(null,true));
        assertThrows(DomainFailure.class,()->new Review("invalid",true));
        assertThrows(DomainFailure.class,()->new Hold(scope,"Synthetic reason",null,false));
        assertThrows(NullPointerException.class,()->new Scope(null,scope.id()));
        assertThrows(NullPointerException.class,()->new Scope(Kind.NOTE,null));
        assertThrows(NullPointerException.class,()->new Hold(null,"Synthetic reason",null,true));
        var request=new Request(List.of(scope)," Synthetic cafe\u0301 reason ");assertEquals("Synthetic café reason",request.reason());assertEquals("ErasureRequest[REDACTED]",request.toString());
        assertTrue(new Review("a".repeat(64),true).confirmed());assertEquals("PrivacyHold[REDACTED]",new Hold(scope,"Synthetic reason",null,true).toString());
    }
    @Test void applicationPortIsRequiredAndDoesNotStartAutomaticErasure(){
        var store=(CustomerErasureStore)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CustomerErasureStore.class},(p,m,a)->{throw new AssertionError("NO_AUTOMATIC_MUTATION");});
        assertSame(store,new AdministratorErasure(store).store());assertThrows(NullPointerException.class,()->new AdministratorErasure(null));
    }
}
