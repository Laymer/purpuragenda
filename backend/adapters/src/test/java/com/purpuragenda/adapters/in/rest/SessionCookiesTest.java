package com.purpuragenda.adapters.in.rest;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionCookiesTest {
    @Test void readAcceptsExactlyOneWellFormedNamedCookie() {
        var cookies=SessionCookies.operational();var request=new MockHttpServletRequest();
        assertNull(cookies.read(request));
        request.setCookies(new Cookie("other","x"));assertNull(cookies.read(request));
        request.setCookies(new Cookie("__Host-purpuragenda","A".repeat(43)),new Cookie("other","x"));assertArrayEquals("A".repeat(43).toCharArray(),cookies.read(request));
        request.setCookies(new Cookie("__Host-purpuragenda","A".repeat(42)));assertNull(cookies.read(request));
        request.setCookies(new Cookie("__Host-purpuragenda","A".repeat(44)));assertNull(cookies.read(request));
        request.setCookies(new Cookie("__Host-purpuragenda","A".repeat(42)+"="));assertNull(cookies.read(request));
        request.setCookies(new Cookie("__Host-purpuragenda","A".repeat(43)),new Cookie("__Host-purpuragenda","B".repeat(43)));assertNull(cookies.read(request));
    }
    @Test void operationalAndLocalCookiesDifferOnlyInNameAndSecure() {
        var response=new MockHttpServletResponse();SessionCookies.operational().write(response,"A".repeat(43).toCharArray());
        assertEquals("__Host-purpuragenda="+"A".repeat(43)+"; Path=/; Secure; HttpOnly; SameSite=Lax",response.getHeader("Set-Cookie"));
        response=new MockHttpServletResponse();SessionCookies.local().write(response,"A".repeat(43).toCharArray());
        assertEquals("purpuragenda-local="+"A".repeat(43)+"; Path=/; HttpOnly; SameSite=Lax",response.getHeader("Set-Cookie"));
        response=new MockHttpServletResponse();SessionCookies.local().clear(response);
        String cleared=response.getHeader("Set-Cookie");
        assertTrue(cleared.startsWith("purpuragenda-local=;") && cleared.contains("Max-Age=0") && cleared.contains("Expires=Thu, 1 Jan 1970")
                && cleared.contains("HttpOnly") && cleared.contains("SameSite=Lax") && cleared.contains("Path=/") && !cleared.contains("Secure"));
    }
}
