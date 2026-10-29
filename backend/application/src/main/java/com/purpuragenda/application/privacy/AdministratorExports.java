package com.purpuragenda.application.privacy;

import com.purpuragenda.application.port.out.CustomerExportStore;
import com.purpuragenda.application.port.out.CustomerExportStore.*;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.domain.customer.Input;
import java.util.*;

public final class AdministratorExports {
    private final CustomerExportStore store;
    public AdministratorExports(CustomerExportStore store){this.store=Objects.requireNonNull(store);}
    public record Request(String reason) {
        public Request { reason=Input.reason(reason);com.purpuragenda.domain.DomainFailure.require(reason.codePoints().noneMatch(c->c>=0xd800&&c<=0xdfff),"INVALID_EXPORT_REASON"); }
        @Override public String toString(){return "ExportRequest[REDACTED]";}
    }
    public Receipt prepare(SessionBinding session,UUID customer,String key,String reason){
        Objects.requireNonNull(session);Objects.requireNonNull(customer);String normalized=new Request(reason).reason();
        new CommandKey(new Scope(session.sessionId(),"PREPARE_EXPORT",customer),key,CommandKey.fingerprint(List.of(normalized)));
        return store.prepare(session,customer,key,normalized);
    }
    public Receipt release(SessionBinding session,UUID id,String key,Review review){
        Objects.requireNonNull(session);Objects.requireNonNull(id);Objects.requireNonNull(review);
        new CommandKey(new Scope(session.sessionId(),"RELEASE_EXPORT",id),key,review.digest());
        return store.release(session,id,key,review);
    }
    public Document read(SessionBinding session,UUID id,boolean download){return store.read(Objects.requireNonNull(session),Objects.requireNonNull(id),download);}
}
