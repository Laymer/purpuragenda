package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Persisted administrator authority and private history share one read-only snapshot; reads never touch sessions. */
public interface AdministratorHistoryQuery {
    NotePage notes(SessionBinding session,UUID bookingId,Request request);
    /** Null bookingId selects the global audit stream, including non-booking events. */
    AuditPage audit(SessionBinding session,UUID bookingId,Request request);
    record Cursor(Instant time,UUID id) {
        public Cursor {
            Objects.requireNonNull(time);Objects.requireNonNull(id);
            require(!time.isBefore(Instant.parse("0001-01-01T00:00:00Z")) && time.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))
                    && time.equals(time.truncatedTo(ChronoUnit.MICROS)),"INVALID_HISTORY_CURSOR");
        }
        @Override public String toString(){return "HistoryCursor[REDACTED]";}
    }
    record Request(Cursor before,int limit) {
        public Request { require(limit>=1 && limit<=100,"INVALID_HISTORY_LIMIT"); }
        @Override public String toString(){return "HistoryRequest[REDACTED]";}
    }
    record Note(UUID id,String text,Instant createdAt) {
        public Note { Objects.requireNonNull(id);Objects.requireNonNull(text);Objects.requireNonNull(createdAt); }
        @Override public String toString(){return "PrivateNote[REDACTED]";}
    }
    /** No arbitrary event metadata, credentials or note text is exposed by this projection. */
    record Audit(UUID id,UUID bookingId,String actorKind,String type,String reason,Instant occurredAt,Long version) {
        public Audit { Objects.requireNonNull(id);Objects.requireNonNull(actorKind);Objects.requireNonNull(type);Objects.requireNonNull(reason);Objects.requireNonNull(occurredAt); }
        @Override public String toString(){return "AuditEntry[REDACTED]";}
    }
    record NotePage(List<Note> entries,Cursor next,Instant observedAt) {
        public NotePage { entries=List.copyOf(entries);Objects.requireNonNull(observedAt); }
        @Override public String toString(){return "PrivateNotePage[REDACTED]";}
    }
    record AuditPage(List<Audit> entries,Cursor next,Instant observedAt) {
        public AuditPage { entries=List.copyOf(entries);Objects.requireNonNull(observedAt); }
        @Override public String toString(){return "AuditPage[REDACTED]";}
    }
}
