package com.purpuragenda.adapters.out.postgres;

import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import com.purpuragenda.application.scheduling.CustomerQuota;
import com.purpuragenda.domain.DomainFailure;
import com.purpuragenda.domain.booking.Booking;
import com.purpuragenda.domain.scheduling.SchedulingPolicy;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Transaction-bound only; caller holds the shared scheduling resource lock. */
final class PostgresQuotaStore {
    private final JdbcTemplate jdbc;
    private final PostgresBookingStore bookings;
    private record Charge(UUID customer,LocalDate day,String origin) {}
    PostgresQuotaStore(JdbcTemplate jdbc,PostgresBookingStore bookings){this.jdbc=jdbc;this.bookings=bookings;}
    CustomerQuota quota(UUID customer,Instant now) {
        LocalDate day=CustomerQuota.dayAt(now);
        return jdbc.queryForObject("""
            SELECT
              (SELECT count(*) FROM customer_identity WHERE id=?) AS identities,
              (SELECT count(*) FROM booking WHERE customer_id=? AND session_end>?
                AND (state='CONFIRMED' OR (state='PENDING_CONFIRMATION' AND expires_at>?))) AS active,
              (SELECT count(*) FROM request_charge WHERE customer_id=? AND submission_day=?) AS charged,
              COALESCE((SELECT count FROM customer_request_day WHERE customer_id=? AND submission_day=?),0) AS counter
            """,(row,n)->{
                require(row.getInt("identities")==1 && row.getInt("charged")==row.getInt("counter"));
                return new CustomerQuota(row.getInt("active"),row.getInt("charged"),day);
            },customer,customer,Timestamp.from(now),Timestamp.from(now),customer,day,customer,day);
    }
    boolean chargeCreated(Booking value,Instant now) {
        require(bookings.booking(value.id()).filter(value::equals).isPresent());
        if(value.origin()==Booking.Origin.ADMINISTRATOR)return false;
        CustomerQuota usage=quota(value.customerId(),now);
        Charge expected=new Charge(value.customerId(),CustomerQuota.dayAt(value.createdAt()),"CUSTOMER");
        List<Charge> existing=jdbc.query("SELECT customer_id,submission_day,origin FROM request_charge WHERE booking_id=?",
                (row,n)->new Charge(row.getObject(1,UUID.class),row.getObject(2,LocalDate.class),row.getString(3)),value.id());
        if(!existing.isEmpty()){require(existing.getFirst().equals(expected));return false;}
        require(value.version()==1 && value.createdAt().equals(now));
        DomainFailure.require(usage.requests()<SchedulingPolicy.CUSTOMER_LIMIT,"REQUEST_LIMIT_REACHED");
        jdbc.update("""
            INSERT INTO customer_request_day(customer_id,submission_day,count) VALUES(?,?,1)
            ON CONFLICT(customer_id,submission_day) DO UPDATE SET count=customer_request_day.count+1
            """,value.customerId(),expected.day());
        jdbc.update("INSERT INTO request_charge(booking_id,customer_id,submission_day,origin) VALUES(?,?,?,'CUSTOMER')",
                value.id(),value.customerId(),expected.day());
        return true;
    }
    private static void require(boolean valid){if(!valid)throw new Failure(Failure.Kind.STORAGE_FAILURE);}
}
