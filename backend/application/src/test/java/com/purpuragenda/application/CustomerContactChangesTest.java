package com.purpuragenda.application;

import com.purpuragenda.application.port.out.ContactChangesUnitOfWork;
import com.purpuragenda.application.port.out.ContactChangesUnitOfWork.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.RateLimitStore.Decision;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.CustomerContactChanges;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerContactChangesTest {
    final GuestProofsTest limits=new GuestProofsTest();
    final Fixture f=new Fixture();
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"b".repeat(64));
    final CustomerContactChanges changes=new CustomerContactChanges(f,new RequestThrottle(limits.limits));
    static final String TOKEN="s".repeat(43);
    Receipt confirm(long version){return changes.confirm(session,f.current.id(),"synthetic-contact",new CustomerContactChanges.Confirmation(version,TOKEN));}

    @Test void proofRequestCanonicalizesOnlyTheNewDeliveryAddressAndChargesSharedEmailLimits() {
        UUID owner=f.current.customerId();Booking original=f.current;
        var result=changes.request(session,f.current.id(),"Synthetic@BÜCHER.example","203.0.113.9");
        assertTrue(result.accepted());assertEquals(0,result.retryAfterSeconds());
        assertEquals(List.of("throttle:[EMAIL_COOLDOWN, EMAIL_ADDRESS, EMAIL_IP]"),limits.events);
        assertEquals("synthetic@xn--bcher-kva.example",f.proof.email());assertSame(session,f.proof.session());assertEquals(original.id(),f.proof.bookingId());
        assertSame(original,f.current);assertEquals(owner,f.current.customerId());assertEquals(0,f.effects);assertTrue(f.steps.isEmpty());
        assertFalse(f.proof.toString().contains("synthetic"));assertFalse(f.proof.toString().contains(session.sessionId().toString()));
    }
    @Test void throttlingDoesNotIssueAProofAndPreservesTheRetryDeadline() {
        limits.decision=Decision.deny(61);
        var result=changes.request(session,f.current.id(),"synthetic@example.test","203.0.113.9");
        assertFalse(result.accepted());assertEquals(61,result.retryAfterSeconds());assertNull(f.proof);assertNull(f.contactCommand);
    }
    @Test void invalidProofRequestNeverChargesOrCallsPersistence() {
        assertThrows(NullPointerException.class,()->changes.request(null,f.current.id(),"synthetic@example.test","203.0.113.9"));
        assertThrows(DomainFailure.class,()->changes.request(session,null,"synthetic@example.test","203.0.113.9"));
        assertThrows(DomainFailure.class,()->changes.request(session,f.current.id(),"invalid","203.0.113.9"));
        assertTrue(limits.events.isEmpty());assertNull(f.proof);
    }
    @Test void unauthorizedIssuanceAndStorageFailuresPropagateToTheSafeBoundary() {
        for(var kind:List.of(Failure.Kind.NOT_FOUND,Failure.Kind.AUTHENTICATION_REQUIRED,Failure.Kind.STORAGE_FAILURE)) {
            f.authorityFailure=new Failure(kind);assertSame(f.authorityFailure,assertThrows(Failure.class,()->changes.request(session,f.current.id(),"synthetic@example.test","203.0.113.9")));assertNull(f.proof);
        }
    }
    @Test void confirmationUsesOnlyTheProvedAddressAndRetainsEveryOtherBookingFact() {
        Booking original=f.current;Receipt result=confirm(original.version());Booking changed=f.current;
        assertEquals(new Receipt(Code.OK,original.id(),original.version()+1),result);
        assertEquals("verified-new@example.test",changed.deliveryEmail());
        assertEquals(original.deliveryContact(f.proved),changed);assertSame(original.snapshot(),changed.snapshot());
        assertEquals(original.customerId(),changed.customerId());assertEquals(original.snapshot().creationEmail(),changed.snapshot().creationEmail());
        assertEquals(original.calendarRevision(),changed.calendarRevision());assertEquals(original.interval(),changed.interval());
        assertEquals(0,f.quotaReads);assertEquals(0,f.policyReads);assertEquals(0,f.calendarReads);
        assertEquals(List.of("expiry","attempt","authorized","proof","save","contact-effects"),f.steps);
        assertSame(original,f.effectOriginal);assertSame(changed,f.effectChanged);assertEquals(1,f.effects);
        assertSame(session,f.contactCommand.session());assertEquals(original.id(),f.contactCommand.bookingId());assertEquals(TOKEN,f.contactCommand.token());
    }
    @Test void staleVersionsDoNotReadOrConsumeTheProof() {
        Booking original=f.current;assertEquals(Receipt.failure(Code.STALE_VERSION),confirm(original.version()+1));
        assertSame(original,f.current);assertEquals(List.of("expiry","attempt","authorized"),f.steps);assertEquals(0,f.effects);
    }
    @Test void expiryReclamationPrecedesTheVersionCheckAndDoesNotConsumeContactProofs() {
        f.current=f.pending(f.current.start());Booking original=f.current;f.now=original.expiresAt();
        assertEquals(Receipt.failure(Code.STALE_VERSION),confirm(original.version()));assertEquals(Booking.State.EXPIRED,f.current.state());
        assertEquals(List.of("expiry","reclaim","expiry-effects","attempt","authorized"),f.steps);assertEquals(1,f.expiryEffects);assertEquals(0,f.effects);
    }
    @Test void deliveryUpdatesDoNotApplySchedulingCutoffsOrTransferBookingState() {
        Booking original=f.current;f.now=original.start().plusSeconds(1);
        assertTrue(confirm(original.version()).success());assertEquals(original.state(),f.current.state());
        f.reset(original.outcome(original.sessionEnd(),Booking.State.COMPLETED,""));original=f.current;
        assertTrue(confirm(original.version()).success());assertEquals(Booking.State.COMPLETED,f.current.state());
        assertEquals(original.calendarRevision(),f.current.calendarRevision());assertEquals(0,f.quotaReads);
    }
    @Test void sameAddressProofStillHasExactlyOneAuditedVersionedConsumption() {
        f.proved=f.current.deliveryEmail();Booking original=f.current;
        assertTrue(confirm(original.version()).success());assertEquals(original.deliveryEmail(),f.current.deliveryEmail());assertEquals(original.version()+1,f.current.version());assertEquals(1,f.effects);
    }
    @Test void proofFailuresAndUnexpectedEffectFailuresDoNotBecomeBusinessSuccess() {
        Booking original=f.current;f.proofFailure=new DomainFailure("INVALID_CONTACT_PROOF");
        assertSame(f.proofFailure,assertThrows(DomainFailure.class,()->confirm(original.version())));assertSame(original,f.current);assertEquals(0,f.effects);
        f.proofFailure=null;f.effectFailure=new Failure(Failure.Kind.STORAGE_FAILURE);
        assertSame(f.effectFailure,assertThrows(Failure.class,()->confirm(original.version())));assertEquals(1,f.effects);
    }
    @Test void invalidPersistedProofAddressPropagatesInsteadOfSavingAnUnverifiedValue() {
        f.proved="invalid";Booking original=f.current;assertThrows(DomainFailure.class,()->confirm(original.version()));assertSame(original,f.current);assertEquals(0,f.effects);
    }
    @Test void replayIsDelegatedToTheAuthorityCheckingUnitWithoutRepeatingEffects() {
        f.replay=new Receipt(Code.OK,f.current.id(),2);assertSame(f.replay,confirm(1));assertTrue(f.steps.isEmpty());assertEquals(0,f.effects);
        f.authorityFailure=new Failure(Failure.Kind.AUTHENTICATION_REQUIRED);assertSame(f.authorityFailure,assertThrows(Failure.class,()->confirm(1)));assertTrue(f.steps.isEmpty());
    }
    @Test void fingerprintBindsVersionAndTokenWithoutStoringPlaintextOrUsingClientActorIdentity() {
        f.replay=Receipt.failure(Code.STALE_VERSION);confirm(1);String first=f.contactCommand.fingerprint();confirm(2);assertNotEquals(first,f.contactCommand.fingerprint());
        changes.confirm(session,f.current.id(),"synthetic-contact",new CustomerContactChanges.Confirmation(1,"t".repeat(43)));assertNotEquals(first,f.contactCommand.fingerprint());
        assertTrue(first.matches("[a-f0-9]{64}"));assertFalse(first.contains(TOKEN));assertFalse(f.contactCommand.toString().contains("tttt"));
        assertFalse(new CustomerContactChanges.Confirmation(1,TOKEN).toString().contains(TOKEN));
    }
    @Test void invalidCommandFieldsNeverReachTheUnit() {
        for(long version:new long[]{0,-1,Long.MAX_VALUE})assertThrows(DomainFailure.class,()->new CustomerContactChanges.Confirmation(version,TOKEN));
        for(String token:Arrays.asList(null,"short","?".repeat(43)))assertThrows(DomainFailure.class,()->new CustomerContactChanges.Confirmation(1,token));
        var request=new CustomerContactChanges.Confirmation(1,TOKEN);
        assertThrows(NullPointerException.class,()->changes.confirm(null,f.current.id(),"key",request));assertThrows(DomainFailure.class,()->changes.confirm(session,null,"key",request));
        assertThrows(DomainFailure.class,()->changes.confirm(session,f.current.id()," ",request));assertThrows(NullPointerException.class,()->changes.confirm(session,f.current.id(),"key",null));
        for(String token:Arrays.asList(null,"short"))assertThrows(DomainFailure.class,()->new Command(session,f.current.id(),token,"key","b".repeat(64)));
        assertThrows(DomainFailure.class,()->new Command(session,f.current.id(),TOKEN,"key","invalid"));assertNull(f.contactCommand);
        assertThrows(NullPointerException.class,()->new CustomerContactChanges(null,new RequestThrottle(limits.limits)));assertThrows(NullPointerException.class,()->new CustomerContactChanges(f,null));
    }
    static final class Fixture extends CustomerBookingChangesTest.Fixture implements ContactChangesUnitOfWork,ContactChangesUnitOfWork.Change {
        ProofRequest proof;ContactChangesUnitOfWork.Command contactCommand;String proved="verified-new@example.test";RuntimeException proofFailure,authorityFailure;
        public void request(ProofRequest supplied){if(authorityFailure!=null)throw authorityFailure;proof=supplied;}
        public Receipt change(ContactChangesUnitOfWork.Command supplied,Function<ContactChangesUnitOfWork.Change,Receipt> work){if(authorityFailure!=null)throw authorityFailure;contactCommand=supplied;return replay==null?work.apply(this):replay;}
        public String verifiedEmail(){steps.add("proof");assertTrue(attempting);if(proofFailure!=null)throw proofFailure;return proved;}
        public void contactChangedEffects(Booking original,Booking changed){steps.add("contact-effects");effect(original,changed);}
    }
}
