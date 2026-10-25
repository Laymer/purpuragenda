package com.purpuragenda.application.usecase;
import com.purpuragenda.application.port.in.Actor;
import com.purpuragenda.application.port.out.OperationsGateway;
import com.purpuragenda.domain.customer.Input;
import static com.purpuragenda.domain.DomainFailure.require;
import java.util.*;

public final class OperationsService {
    private final OperationsGateway operations;
    public OperationsService(OperationsGateway operations){this.operations=operations;}
    public List<Map<String,Object>> read(Actor actor,String area,UUID target){actor.requireAdmin();return operations.read(area,target);}
    public UUID exportCustomer(Actor actor,UUID customer,String reason){actor.requireAdmin();return operations.exportCustomer(customer,actor,Input.reason(reason));}
    public String downloadExport(Actor actor,UUID export){actor.requireAdmin();return operations.downloadExport(export);}
    public UUID erasure(Actor actor,UUID customer,String reason){actor.requireAdmin();return operations.requestErasure(customer,actor,Input.reason(reason));}
    public void retry(Actor actor,UUID id,String reason){actor.requireAdmin();operations.retry(id,actor,Input.reason(reason));}
    public Map<String,Object> profile(Actor actor){require(actor.kind()==Actor.Kind.ACCOUNT,"FORBIDDEN");return operations.profile(actor.customerId());}
    public void profile(Actor actor,long version,Map<String,String> fields){require(actor.kind()==Actor.Kind.ACCOUNT,"FORBIDDEN");require(fields!=null&&fields.keySet().stream().allMatch(Set.of("firstName","lastName","telephone","displayZone")::contains),"VALIDATION_FAILED");Map<String,String> validated=new HashMap<>();fields.forEach((k,v)->validated.put(k,switch(k){case "telephone"->Input.phone(v);case "displayZone"->Input.zone(v).getId();default->Input.text(v,1,100);}));operations.profile(actor.customerId(),version,validated);}
}
