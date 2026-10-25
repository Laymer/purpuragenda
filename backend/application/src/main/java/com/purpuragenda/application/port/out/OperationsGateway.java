package com.purpuragenda.application.port.out;
import com.purpuragenda.application.port.in.Actor;
import java.util.*;

public interface OperationsGateway {
    List<Map<String,Object>> read(String area,UUID target);
    UUID exportCustomer(UUID customer,Actor actor,String reason);
    String downloadExport(UUID export);
    UUID requestErasure(UUID customer,Actor actor,String reason);
    void retry(UUID notification,Actor actor,String reason);
    Map<String,Object> profile(UUID customer);
    void profile(UUID customer,long expectedVersion,Map<String,String> fields);
}
