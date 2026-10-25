package com.purpuragenda.acceptance.compat;

import com.purpuragenda.domain.scheduling.ReservationInterval;
import io.cucumber.java.en.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

public class CompatibilitySteps {
    ReservationInterval reservation;
    @Given("a reservation beginning at {string}") public void reservation(String start) { reservation=ReservationInterval.reserve(Instant.parse(start)); }
    @Then("it reserves exactly {int} elapsed minutes") public void duration(int minutes) { assertEquals(minutes,Duration.between(reservation.start(),reservation.end()).toMinutes()); }
    @Then("a following reservation is adjacent") public void adjacent() { assertFalse(reservation.intersects(ReservationInterval.reserve(reservation.end()))); }
}
