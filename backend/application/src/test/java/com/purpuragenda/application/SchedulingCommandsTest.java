package com.purpuragenda.application;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.SchedulingCommands;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingCommandsTest {
    final CommandKey key=new CommandKey(new Scope(UUID.randomUUID(),"SYNTHETIC",null),"request",CommandKey.fingerprint(List.of()));
    final Fixture unit=new Fixture();
    final SchedulingCommands commands=new SchedulingCommands(unit);

    @Test void reclamationPrecedesSavepointAndMutationSharesTheSameTransaction() {
        Receipt expected=new Receipt(Code.OK,UUID.randomUUID(),1);
        Receipt actual=commands.execute(key,tx->{
            assertSame(unit,tx); assertSame(key,unit.key);
            unit.steps.add("mutation"); return expected;
        });
        assertSame(expected,actual);
        assertEquals(List.of("expiry","savepoint","mutation"),unit.steps);
        assertEquals(1,unit.timeReads);
    }

    @Test void everyExplicitBusinessFailureBecomesAnEmptyFailureReceiptInsideTheAttempt() {
        Set<Code> expected=EnumSet.of(Code.SLOT_UNAVAILABLE,Code.SERVICE_UNAVAILABLE,Code.SERVICE_CHANGED,Code.POLICY_CHANGED,Code.CALENDAR_CHANGED,Code.NOTICE_CHANGED,Code.INVALID_BIRTH_DATE,Code.STALE_VERSION,
                Code.START_NOT_FUTURE,Code.START_NOT_AVAILABLE,Code.MINIMUM_NOTICE,Code.OUTSIDE_HORIZON,
                Code.DAILY_CAPACITY_REACHED,Code.ACTIVE_LIMIT_REACHED,Code.REQUEST_LIMIT_REACHED,Code.HARD_BLACKOUT,
                Code.IMPOSSIBLE_PENDING_HOLD,Code.BLACKOUT_OVERRIDE_REQUIRED,Code.OVERRIDE_UNUSED,Code.INVALID_TRANSITION,Code.HOLD_EXPIRED,
                Code.CUTOFF_PASSED,Code.SESSION_NOT_STARTED,Code.MEETING_REQUIRED,
                Code.INVALID_PAYMENT,Code.DUPLICATE_PAYMENT,Code.INVALID_REFUND,Code.REFUND_EXCEEDS_RECEIPT,
                Code.INVALID_MEETING,Code.MEETING_IN_USE,Code.PAYMENT_INSUFFICIENT);
        for(Code code:expected) {
            assertTrue(code.businessFailure());
            assertEquals(Receipt.failure(code),commands.execute(key,tx->{throw new DomainFailure(code.name());}));
            assertEquals(Receipt.failure(code),unit.attemptReceipt);
        }
        assertEquals(EnumSet.complementOf(EnumSet.of(Code.OK,Code.IDEMPOTENCY_KEY_REUSED)),expected);
    }

    @Test void unknownValidationCorruptionAndControlCodesAbortInsteadOfBecomingBusinessReceipts() {
        for(String code:Arrays.asList("INVALID_SNAPSHOT","INVALID_BOOKING","SYNTHETIC_UNEXPECTED","OK","IDEMPOTENCY_KEY_REUSED",null)) {
            DomainFailure failure=new DomainFailure(code);
            assertSame(failure,assertThrows(DomainFailure.class,()->commands.execute(key,tx->{throw failure;})));
        }
        Failure storage=new Failure(Failure.Kind.STORAGE_FAILURE);
        assertSame(storage,assertThrows(Failure.class,()->commands.execute(key,tx->{throw storage;})));
        assertThrows(NullPointerException.class,()->commands.execute(key,tx->null));
    }

    @Test void reclamationFailureEvenWithABusinessCodeAbortsBeforeTheAttempt() {
        DomainFailure failure=new DomainFailure("INVALID_TRANSITION");unit.expiryFailure=failure;
        assertSame(failure,assertThrows(DomainFailure.class,()->commands.execute(key,tx->{fail("No mutation");return null;})));
        assertEquals(List.of("expiry"),unit.steps);
    }

    @Test void replayDoesNotReclaimOrRepeatMutationAndNullArgumentsCannotReachTheUnit() {
        Receipt stored=Receipt.failure(Code.REQUEST_LIMIT_REACHED);unit.replay=stored;
        assertSame(stored,commands.execute(key,tx->{fail("No mutation");return null;}));
        assertTrue(unit.steps.isEmpty());assertEquals(0,unit.timeReads);
        assertThrows(NullPointerException.class,()->new SchedulingCommands(null));
        assertThrows(NullPointerException.class,()->commands.execute(null,tx->stored));
        assertThrows(NullPointerException.class,()->commands.execute(key,null));
        assertThrows(NullPointerException.class,()->SchedulingCommands.within(null,()->stored));
        assertThrows(NullPointerException.class,()->SchedulingCommands.within(unit,null));
    }

    static class Fixture extends ExpiryReclamationTest.Fake implements SchedulingUnitOfWork {
        CommandKey key;Receipt replay,attemptReceipt;DomainFailure expiryFailure;
        public Receipt execute(CommandKey supplied,Function<Transaction,Receipt> work){key=supplied;return replay==null?work.apply(this):replay;}
        public <T>T read(Function<View,T> work){throw new AssertionError("Commands must use the mutating unit");}
        @Override public List<Booking> duePending(){steps.add("expiry");if(expiryFailure!=null)throw expiryFailure;return List.of();}
        @Override public Receipt attempt(Supplier<Receipt> mutation){steps.add("savepoint");attemptReceipt=mutation.get();return attemptReceipt;}
    }
}
