package com.purpuragenda.application;

import com.purpuragenda.application.ReenrollmentGrantPolicy.*;
import com.purpuragenda.domain.DomainFailure;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReenrollmentGrantPolicyTest {
    final UUID id=UUID.randomUUID(),admin=UUID.randomUUID();
    final Instant issued=Instant.parse("2026-10-25T00:55:00Z"),expires=issued.plusSeconds(600);
    byte[] digest(int value) { byte[] bytes=new byte[32];Arrays.fill(bytes,(byte)value);return bytes; }
    Binding binding() { return new Binding(digest(1),digest(2)); }
    State state(Instant consumed,Instant revoked) { return new State(id,admin,Purpose.ADMIN_REENROLLMENT,binding(),digest(3),issued,expires,consumed,revoked); }
    boolean eligible(State state,Instant now) { return state.eligible(admin,binding(),Purpose.ADMIN_REENROLLMENT,now,true); }
    @Test void tenElapsedMinutesRemainStrictAcrossBrusselsDstAndNanosecondBoundaries() {
        var state=state(null,null);
        assertTrue(eligible(state,issued));assertTrue(eligible(state,expires.minusNanos(1)));
        assertFalse(eligible(state,issued.minusNanos(1)));assertFalse(eligible(state,expires));assertFalse(eligible(state,expires.plusNanos(1)));
        assertEquals(LocalTime.of(2,55),issued.atZone(ZoneId.of("Europe/Brussels")).toLocalTime());
        assertEquals(LocalTime.of(2,5),expires.atZone(ZoneId.of("Europe/Brussels")).toLocalTime());
    }
    @Test void purposeAdminBrowserRequestAndPendingStateAreAllRequired() {
        var state=state(null,null);
        assertFalse(state.eligible(admin,binding(),Purpose.ADMIN_AUTHENTICATION,issued,true));
        assertFalse(state.eligible(admin,binding(),null,issued,true));assertFalse(state.eligible(admin,binding(),Purpose.ADMIN_REENROLLMENT,issued,false));
        assertFalse(state.eligible(UUID.randomUUID(),binding(),Purpose.ADMIN_REENROLLMENT,issued,true));
        assertFalse(state.eligible(null,binding(),Purpose.ADMIN_REENROLLMENT,issued,true));
        assertFalse(state.eligible(admin,new Binding(digest(9),digest(2)),Purpose.ADMIN_REENROLLMENT,issued,true));
        assertFalse(state.eligible(admin,new Binding(digest(1),digest(9)),Purpose.ADMIN_REENROLLMENT,issued,true));
        assertFalse(state.eligible(admin,null,Purpose.ADMIN_REENROLLMENT,issued,true));assertFalse(eligible(state,null));
    }
    @Test void consumedOrRevokedProofNeverHasEligibilityAgain() {
        assertFalse(eligible(state(issued,null),issued));assertFalse(eligible(state(null,issued),issued));
        assertFalse(eligible(state(issued,issued),issued));
    }
    @Test void digestInputsAndAccessorsAreCopiesAndEveryRepresentationIsRedacted() {
        byte[] browser=digest(1),request=digest(2),token=digest(3);var binding=new Binding(browser,request);
        var state=new State(id,admin,Purpose.ADMIN_REENROLLMENT,binding,token,issued,expires,null,null);
        Arrays.fill(browser,(byte)0);Arrays.fill(request,(byte)0);Arrays.fill(token,(byte)0);
        assertTrue(binding.matches(binding()));assertTrue(Arrays.equals(digest(3),state.tokenHash()));
        Arrays.fill(binding.browserHash(),(byte)0);Arrays.fill(binding.requestHash(),(byte)0);Arrays.fill(state.tokenHash(),(byte)0);
        assertTrue(binding.matches(binding()));assertTrue(Arrays.equals(digest(3),state.tokenHash()));
        assertEquals("ReenrollmentBinding[REDACTED]",binding.toString());assertEquals("ReenrollmentGrantState[REDACTED]",state.toString());
    }
    @Test void malformedBindingsAndGrantMetadataFailWithFixedCodes() {
        for(byte[] value:new byte[][]{null,new byte[0],new byte[31],new byte[33]}) {
            assertThrows(DomainFailure.class,()->new Binding(value,digest(1)));assertThrows(DomainFailure.class,()->new Binding(digest(1),value));
        }
        assertThrows(DomainFailure.class,()->new State(null,admin,Purpose.ADMIN_REENROLLMENT,binding(),digest(3),issued,expires,null,null));
        assertThrows(DomainFailure.class,()->new State(id,null,Purpose.ADMIN_REENROLLMENT,binding(),digest(3),issued,expires,null,null));
        assertThrows(DomainFailure.class,()->new State(id,admin,Purpose.ADMIN_AUTHENTICATION,binding(),digest(3),issued,expires,null,null));
        assertThrows(DomainFailure.class,()->new State(id,admin,Purpose.ADMIN_REENROLLMENT,null,digest(3),issued,expires,null,null));
        for(byte[] token:new byte[][]{null,new byte[31],new byte[33]})assertThrows(DomainFailure.class,()->new State(id,admin,Purpose.ADMIN_REENROLLMENT,binding(),token,issued,expires,null,null));
    }
    @Test void missingWrongLengthOverflowOrReversedTimestampsNeverWidenLifetime() {
        for(Instant end:new Instant[]{null,issued,expires.minusNanos(1),expires.plusNanos(1)})
            assertThrows(DomainFailure.class,()->new State(id,admin,Purpose.ADMIN_REENROLLMENT,binding(),digest(3),issued,end,null,null));
        assertThrows(DomainFailure.class,()->new State(id,admin,Purpose.ADMIN_REENROLLMENT,binding(),digest(3),null,expires,null,null));
        assertThrows(DomainFailure.class,()->new State(id,admin,Purpose.ADMIN_REENROLLMENT,binding(),digest(3),Instant.MAX,Instant.MAX,null,null));
        assertThrows(DomainFailure.class,()->state(issued.minusNanos(1),null));assertThrows(DomainFailure.class,()->state(null,issued.minusNanos(1)));
    }
}
