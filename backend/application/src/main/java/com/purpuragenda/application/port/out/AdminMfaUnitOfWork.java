package com.purpuragenda.application.port.out;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Owns one administrator row lock, one DB time and one transaction; contexts cannot escape. */
public interface AdminMfaUnitOfWork {
    <T> T locked(String emailKey,Function<Admin,T> work);
    interface Admin {
        Instant now();
        Optional<Credential> credential();
        boolean initialEnrollment();
        void enroll(long acceptedStep,List<String> recoveryHashes);
        void acceptStep(long acceptedStep);
    }
    record Credential(UUID id,String passwordHash,boolean enrolled,long acceptedStep,Secret secret) {
        @Override public String toString() { return "AdminCredential[REDACTED]"; }
    }
    record Secret(byte[] bytes) implements AutoCloseable {
        public Secret { bytes=bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public void close() { Arrays.fill(bytes,(byte)0); }
        @Override public String toString() { return "AdminSecret[REDACTED]"; }
    }
    record RecoveryCode(char[] value,String hash) implements AutoCloseable {
        public RecoveryCode { value=value.clone(); }
        @Override public char[] value() { return value.clone(); }
        @Override public void close() { Arrays.fill(value,'\0'); }
        @Override public String toString() { return "AdminRecoveryCode[REDACTED]"; }
    }
    record Enrollment(List<RecoveryCode> codes) implements AutoCloseable {
        public Enrollment { codes=List.copyOf(codes); }
        @Override public void close() { codes.forEach(RecoveryCode::close); }
        @Override public String toString() { return "AdminEnrollment[REDACTED]"; }
    }
}
