package com.purpuragenda.domain.customer;

import static com.purpuragenda.domain.DomainFailure.require;
import java.time.*;

public record Intake(String firstName, String lastName, String telephone, LocalDate birthDate,
                     LocalTime birthTime, String birthplace, String message) {
    public Intake {
        firstName = Input.text(firstName, 1, 100); lastName = Input.text(lastName, 1, 100);
        telephone = Input.phone(telephone); birthplace = Input.text(birthplace, 1, 200);
        message = Input.multiline(message, 1000);
        require(birthDate != null && birthTime != null, "VALIDATION_FAILED");
        require(birthTime.getNano() == 0, "INVALID_BIRTH_TIME");
    }
    public void validateAt(Instant now) { require(!birthDate.isAfter(now.atZone(ZoneId.of("Europe/Brussels")).toLocalDate()), "INVALID_BIRTH_DATE"); }
}
