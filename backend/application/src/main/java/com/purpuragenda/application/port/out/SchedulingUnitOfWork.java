package com.purpuragenda.application.port.out;

import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.application.scheduling.CustomerQuota;
import com.purpuragenda.domain.booking.*;
import com.purpuragenda.domain.scheduling.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.function.*;

/** One resource-serialized transaction. Database/framework types stay in the adapter. */
public interface SchedulingUnitOfWork {
    Receipt execute(CommandKey key, Function<Transaction,Receipt> work);
    <T> T read(Function<View,T> work);
    interface View {
        Instant now();
        List<ServiceDefinition> services();
        SchedulingPolicy policy();
        AvailabilityCalendar calendar();
        Optional<Booking> booking(UUID id);
        List<Booking> bookings(ReservationInterval interval);
    }
    interface Transaction extends View {
        void save(Booking booking);
        /** Due at captured now, locked and ordered by PostgreSQL UUID order. */
        List<Booking> duePending();
        /** Fixed expiry refund obligations and system audit/outbox; repeat calls are harmless. */
        void expiryEffects(UUID bookingId, long expiredVersion);
        CustomerQuota quota(UUID stableCustomerId);
        /** New customer creation only; false for an existing immutable charge or an administrator origin. */
        boolean chargeCreated(Booking booking);
        /** Earlier reclamation survives expected failure; unexpected failure aborts the whole command. */
        Receipt attempt(Supplier<Receipt> mutation);
    }
    /** Additive names preserve existing persisted receipts. Only explicit business codes may commit reclamation. */
    enum Code {
        OK(false), IDEMPOTENCY_KEY_REUSED(false), SLOT_UNAVAILABLE(true),
        SERVICE_UNAVAILABLE(true), SERVICE_CHANGED(true), POLICY_CHANGED(true), NOTICE_CHANGED(true), INVALID_BIRTH_DATE(true),
        START_NOT_FUTURE(true), START_NOT_AVAILABLE(true), MINIMUM_NOTICE(true), OUTSIDE_HORIZON(true),
        DAILY_CAPACITY_REACHED(true), ACTIVE_LIMIT_REACHED(true), REQUEST_LIMIT_REACHED(true), HARD_BLACKOUT(true),
        IMPOSSIBLE_PENDING_HOLD(true), BLACKOUT_OVERRIDE_REQUIRED(true),
        INVALID_TRANSITION(true), HOLD_EXPIRED(true), CUTOFF_PASSED(true), SESSION_NOT_STARTED(true), MEETING_REQUIRED(true);
        private final boolean businessFailure;
        Code(boolean businessFailure) { this.businessFailure=businessFailure; }
        public boolean businessFailure() { return businessFailure; }
    }
    record Receipt(Code code, UUID entityId, long version) {
        public Receipt {
            DomainFailure.require(code!=null && version>=0, "INVALID_RECEIPT");
            DomainFailure.require(code==Code.OK ? (entityId==null ? version==0 : version>0)
                    : entityId==null && version==0, "INVALID_RECEIPT");
        }
        public boolean success() { return code==Code.OK; }
        public static Receipt failure(Code code) {
            DomainFailure.require(code!=Code.OK,"INVALID_RECEIPT");
            return new Receipt(code,null,0);
        }
    }
    record Scope(UUID actorId, String operation, UUID targetId) {
        public Scope {
            DomainFailure.require(actorId!=null && operation!=null && operation.matches("[A-Z][A-Z0-9_]{0,63}"),"INVALID_IDEMPOTENCY_SCOPE");
        }
        public String storageKey() { return actorId+"/"+operation+"/"+(targetId==null?"-":targetId); }
    }
    record CommandKey(Scope scope, String key, String fingerprint) {
        public CommandKey {
            DomainFailure.require(scope!=null && key!=null && !key.isBlank() && key.length()<=120
                    && key.codePoints().noneMatch(Character::isISOControl),"INVALID_IDEMPOTENCY_KEY");
            DomainFailure.require(fingerprint!=null && fingerprint.matches("[0-9a-f]{64}"),"INVALID_FINGERPRINT");
        }
        /** Canonical length-prefixed UTF-8 of ordered, already business-normalized fields. */
        public static String fingerprint(List<String> normalizedFields) {
            DomainFailure.require(normalizedFields!=null,"INVALID_FINGERPRINT");
            try {
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                for(String field:normalizedFields) {
                    DomainFailure.require(field!=null,"INVALID_FINGERPRINT");
                    byte[] bytes=field.getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
        }
    }
    final class Failure extends RuntimeException {
        public enum Kind { RETRY_LATER, STORAGE_FAILURE }
        private final Kind kind;
        public Failure(Kind kind) { super(Objects.requireNonNull(kind).name()); this.kind=kind; }
        public Kind kind() { return kind; }
    }
}
