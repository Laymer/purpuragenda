package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.domain.customer.Input;
import java.time.Instant;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Administrator-only search of stable verified identities; delivery addresses never grant ownership. */
public interface AdministratorCustomerQuery {
    Page search(SessionBinding session,Search request);
    Bookings bookings(SessionBinding session,UUID customerId,AdministratorHistoryQuery.Request request);
    record Search(String query,UUID after,int limit) {
        public Search { query=Input.text(query,2,254);require(limit>=1 && limit<=100 && query.codePoints().noneMatch(c->c>=0xd800 && c<=0xdfff),"INVALID_CUSTOMER_SEARCH"); }
        @Override public String toString(){return "CustomerSearch[REDACTED]";}
    }
    record Customer(UUID id,String email,boolean enrolled) {
        public Customer { Objects.requireNonNull(id);Objects.requireNonNull(email); }
        @Override public String toString(){return "AdministratorCustomer[REDACTED]";}
    }
    record Page(List<Customer> entries,UUID next,Instant observedAt) {
        public Page { entries=List.copyOf(entries);Objects.requireNonNull(observedAt); }
        @Override public String toString(){return "CustomerSearchPage[REDACTED]";}
    }
    record Bookings(Customer customer,AccountDataStore.History history) {
        public Bookings { Objects.requireNonNull(customer);Objects.requireNonNull(history); }
        @Override public String toString(){return "AdministratorCustomerBookings[REDACTED]";}
    }
}
