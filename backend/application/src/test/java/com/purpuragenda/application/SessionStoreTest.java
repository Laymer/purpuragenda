package com.purpuragenda.application;

import com.purpuragenda.application.port.out.SessionStore.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    @Test void referencesAndProofBindingsAreBoundedAndRedacted() {
        UUID id=UUID.randomUUID();String hash="a".repeat(64);
        for(String invalid:new String[]{null,"",hash.substring(1),hash+"a","A".repeat(64)}) {
            assertThrows(IllegalArgumentException.class,()->new SessionReference(id,invalid));
            assertThrows(IllegalArgumentException.class,()->new ProofBinding(invalid,hash));
            assertThrows(IllegalArgumentException.class,()->new ProofBinding(hash,invalid));
        }
        assertThrows(NullPointerException.class,()->new SessionReference(null,hash));
        assertEquals("SessionReference[REDACTED]",new SessionReference(id,hash).toString());
        assertEquals("ProofBinding[REDACTED]",new ProofBinding(hash,hash).toString());
    }
    @Test void issuedSessionOwnsCopiesOfTokensAndErasesThemWithoutRenderingIdentity() {
        var reference=new SessionReference(UUID.randomUUID(),"a".repeat(64));
        var snapshot=new Snapshot(reference,Kind.ANONYMOUS,null,null,null,null,null,Instant.EPOCH,Instant.EPOCH,Instant.EPOCH.plusSeconds(7200),null);
        char[] token="a".repeat(43).toCharArray(),csrf="b".repeat(43).toCharArray();
        try(var issued=new Issued(snapshot,token,csrf)) {
            token[0]='c';csrf[0]='d';assertTrue(issued.token()[0]=='a');assertTrue(issued.csrf()[0]=='b');
            char[] copy=issued.token();copy[0]='e';assertTrue(issued.token()[0]=='a');
            char[] nonce=issued.csrf();nonce[0]='f';assertTrue(issued.csrf()[0]=='b');
            assertSame(snapshot,issued.session());assertEquals("IssuedSession[REDACTED]",issued.toString());assertEquals("SessionSnapshot[REDACTED]",snapshot.toString());
            issued.close();assertTrue(Arrays.equals(new char[43],issued.token()));assertTrue(Arrays.equals(new char[43],issued.csrf()));
        }
    }
}
