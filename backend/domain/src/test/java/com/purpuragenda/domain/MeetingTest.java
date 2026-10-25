package com.purpuragenda.domain;

import com.purpuragenda.domain.booking.Meeting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.Modifier;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class MeetingTest {
    @Test void parsingIsTheOnlyConstructionPathAndDiagnosticsAreRedacted() {
        for(var constructor:Meeting.class.getDeclaredConstructors())assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        var meeting=Meeting.parse("https://us02web.zoom.us/j/12345678901?pwd=synthetic",Set.of("zoom.us"));
        assertEquals("12345678901",meeting.meetingId());
        assertEquals("https://us02web.zoom.us/j/12345678901?pwd=synthetic",meeting.participantUrl());
        assertEquals("Meeting[redacted]",meeting.toString());
        assertEquals(meeting,Meeting.parse(meeting.participantUrl(),Set.of("ZOOM.US")));
        assertEquals(meeting.hashCode(),Meeting.parse(meeting.participantUrl(),Set.of("zoom.us")).hashCode());
        assertNotEquals(meeting,Meeting.parse("https://zoom.us/j/987654321",Set.of("zoom.us")));
        assertNotEquals(meeting,null);assertNotEquals(meeting,"anything");
        assertDoesNotThrow(()->Meeting.parse("https://meet.example.test/j/123456789",Set.of("meet.example.test")));
        assertThrows(DomainFailure.class,()->Meeting.parse("https://meet.example.test/j/123456789",Set.of("zoom.us")));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={
        "http://zoom.us/j/123456789", "https://user@zoom.us/j/123456789", "https://zoom.us:443/j/123456789",
        "https://zoom.us.evil.test/j/123456789", "https://evilzoom.us/j/123456789", "https://zoom.us./j/123456789",
        "https://zoom.us/j/123456789#fragment", "https://zoom.us/s/123456789", "https://zoom.us/j/12345678",
        "https://zoom.us/j/1234567890123", "https://zoom.us/j/123456789?zak=synthetic", "https://zoom.us/j/123456789?host=1",
        "https://zoom.us/j/123456789?pwd=a&other=b", "https://zoom.us/j/123456789?pwd=", "https://zoom.us/j/123456789?pwd=%20",
        "https://zoom.us/j/123456789?", "https://zoom.us/j/123456789 ", "https://zoom.us/j/123456789\n",
        "https://zoom.us/j/%31%32%33%34%35%36%37%38%39", "https://[broken/j/123456789", "/j/123456789", "mailto:synthetic@example.test"
    })
    void unsafeUrlsFailWithAFixedCode(String url) {
        var failure=assertThrows(DomainFailure.class,()->Meeting.parse(url,Set.of("zoom.us")));
        assertEquals("INVALID_MEETING",failure.getMessage());
    }

    @Test void configuredHostsCannotAuthorizeIpAddressesOrMissingConfiguration() {
        assertThrows(DomainFailure.class,()->Meeting.parse("https://127.0.0.1/j/123456789",Set.of("127.0.0.1")));
        assertThrows(DomainFailure.class,()->Meeting.parse("https://[::1]/j/123456789",Set.of("[::1]")));
        assertThrows(DomainFailure.class,()->Meeting.parse("https://zoom.us/j/123456789",null));
        assertThrows(DomainFailure.class,()->Meeting.parse("https://zoom.us/j/123456789",Set.of()));
        assertThrows(DomainFailure.class,()->Meeting.parse("https://zoom.us/j/123456789?pwd="+"x".repeat(2050),Set.of("zoom.us")));
        assertThrows(DomainFailure.class,()->Meeting.parse("https://zoom.us/j/123456789?pwd=a\u0000b",Set.of("zoom.us")));
    }
}
