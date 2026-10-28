package com.purpuragenda.application;

import com.purpuragenda.application.port.out.AdministratorCustomerQuery.*;
import com.purpuragenda.application.port.out.AccountDataStore;
import com.purpuragenda.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorCustomerQueryTest {
    @Test void searchIsBoundedNormalizedAndNeverLogsPrivateTerms() {
        UUID id=UUID.randomUUID();Search search=new Search("  Synthetic cafe\u0301 ",id,100);assertEquals("Synthetic café",search.query());assertEquals(id,search.after());assertEquals(100,search.limit());
        assertEquals(2,new Search("ab",null,1).query().length());assertEquals(254,new Search("x".repeat(254),null,1).query().length());assertEquals("😀😀",new Search("😀😀",null,1).query());
        for(String query:Arrays.asList(null,"","x","x".repeat(255),"ab\ncd","bad\ud800"))assertThrows(DomainFailure.class,()->new Search(query,null,1));
        for(int limit:new int[]{0,101})assertThrows(DomainFailure.class,()->new Search("synthetic",null,limit));assertFalse(search.toString().contains("Synthetic"));
    }
    @Test void searchPagesCannotMutateAndPrivateRecordsAreRedacted() {
        UUID id=UUID.randomUUID();Customer customer=new Customer(id,"synthetic@example.test",false);var entries=new ArrayList<>(List.of(customer));var page=new Page(entries,id,Instant.EPOCH);entries.clear();
        assertEquals(List.of(customer),page.entries());assertThrows(UnsupportedOperationException.class,()->page.entries().clear());var bookings=new Bookings(customer,new AccountDataStore.History(List.of(),null,Instant.EPOCH));
        for(Object value:List.of(customer,page,bookings)){assertTrue(value.toString().contains("REDACTED"));assertFalse(value.toString().contains("synthetic"));}
        assertEquals(Set.of("id","email","enrolled"),Arrays.stream(Customer.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()));
    }
}
