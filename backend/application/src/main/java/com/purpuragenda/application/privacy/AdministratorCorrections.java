package com.purpuragenda.application.privacy;

import com.purpuragenda.application.port.out.CustomerCorrectionStore;
import com.purpuragenda.application.port.out.CustomerCorrectionStore.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import java.util.*;

public final class AdministratorCorrections {
    private final CustomerCorrectionStore store;
    public AdministratorCorrections(CustomerCorrectionStore store){this.store=Objects.requireNonNull(store);}
    public Target target(SessionBinding session,UUID customer,Kind kind,UUID target){return store.target(Objects.requireNonNull(session),Objects.requireNonNull(customer),Objects.requireNonNull(kind),Objects.requireNonNull(target));}
    public Receipt prepare(SessionBinding session,UUID customer,UUID target,String key,Change change){
        Objects.requireNonNull(change);new CommandKey(new Scope(session.sessionId(),"PREPARE_CORRECTION",target),key,CommandKey.fingerprint(List.of(change.kind().name(),change.reason(),change.values().toString())));return store.prepare(session,Objects.requireNonNull(customer),Objects.requireNonNull(target),key,change);
    }
    public Preview preview(SessionBinding session,UUID id){return store.preview(Objects.requireNonNull(session),Objects.requireNonNull(id));}
    public Receipt confirm(SessionBinding session,UUID id,String key,long version,Review review){
        Objects.requireNonNull(review);new CommandKey(new Scope(session.sessionId(),"CONFIRM_CORRECTION",id),key,review.digest());return store.confirm(session,Objects.requireNonNull(id),key,version,review);
    }
}
