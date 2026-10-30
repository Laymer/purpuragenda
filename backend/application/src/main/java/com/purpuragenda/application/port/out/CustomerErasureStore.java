package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Receipt;
import com.purpuragenda.domain.customer.Input;
import com.purpuragenda.domain.privacy.PrivacyReviewPolicy;
import java.time.*;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Explicitly reviewed, scoped redaction. A retention deadline never executes deletion. */
public interface CustomerErasureStore {
    enum Kind { INTAKE, NOTE, PROFILE, IDENTITY, AUDIT, REQUEST, FINANCIAL }
    record Scope(Kind kind,UUID id) {public Scope{Objects.requireNonNull(kind);Objects.requireNonNull(id);}}
    record Item(Scope scope,PrivacyReviewPolicy.Status status,LocalDate reviewOn,Instant activityAt,String effect,List<UUID> holds) {
        public Item{holds=List.copyOf(holds);}
    }
    record Overview(UUID customerId,List<Item> items,Instant observedAt){public Overview{items=List.copyOf(items);}}
    record Request(List<Scope> scopes,String reason){
        public Request{scopes=List.copyOf(scopes);require(!scopes.isEmpty()&&scopes.size()<=500&&new HashSet<>(scopes).size()==scopes.size()&&scopes.stream().noneMatch(s->s.kind()==Kind.FINANCIAL),"INVALID_ERASURE_SCOPE");reason=Input.reason(reason);require(reason.codePoints().noneMatch(c->c>=0xd800&&c<=0xdfff),"INVALID_ERASURE_REASON");}
        @Override public String toString(){return "ErasureRequest[REDACTED]";}
    }
    record Preview(UUID id,UUID customerId,List<Item> selected,List<Item> retained,String reason,String digest,Instant expiresAt,long version){public Preview{selected=List.copyOf(selected);retained=List.copyOf(retained);}@Override public String toString(){return "ErasurePreview[REDACTED]";}}
    record Review(String digest,boolean confirmed){public Review{require(digest!=null&&digest.matches("[0-9a-f]{64}")&&confirmed,"INVALID_ERASURE_REVIEW");}}
    record Hold(Scope scope,String reason,LocalDate through,boolean confirmed){public Hold{Objects.requireNonNull(scope);reason=Input.reason(reason);require(confirmed,"INVALID_PRIVACY_HOLD");}@Override public String toString(){return "PrivacyHold[REDACTED]";}}
    Overview overview(SessionBinding session,UUID customer);
    Receipt prepare(SessionBinding session,UUID customer,String key,Request request);
    Preview preview(SessionBinding session,UUID id);
    Receipt confirm(SessionBinding session,UUID id,String key,long version,Review review);
    Receipt hold(SessionBinding session,UUID customer,String key,Hold hold);
    Receipt releaseHold(SessionBinding session,UUID holdId,String key,String reason,boolean confirmed);
    int purge(int limit);
}
