package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AccountDataStore;
import com.purpuragenda.application.port.out.AccountDataStore.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Saving an optional profile is explicit; it never copies intake, changes login identity or edits booking snapshots. */
public final class AccountData {
    private final AccountDataStore store;
    public AccountData(AccountDataStore store) { this.store=Objects.requireNonNull(store); }
    public Snapshot profile(SessionBinding session) { return store.profile(Objects.requireNonNull(session)); }
    public History history(SessionBinding session,Cursor before,int limit) {
        require(limit>=1 && limit<=100,"INVALID_HISTORY_LIMIT");return store.history(Objects.requireNonNull(session),before,limit);
    }
    public Receipt update(SessionBinding session,String key,long expectedVersion,Fields fields) {
        require(expectedVersion>0 && expectedVersion<Long.MAX_VALUE && fields!=null,"INVALID_PROFILE");
        String fingerprint=CommandKey.fingerprint(List.of(Long.toString(expectedVersion),fields.firstName(),fields.lastName(),fields.telephone()));
        return store.change(new Command(session,key,fingerprint),current->{
            require(current.version()==expectedVersion,"STALE_VERSION");return new Profile(expectedVersion+1,fields);
        });
    }
}
