package com.purpuragenda.adapters.in.rest;

import jakarta.servlet.http.*;
import java.util.regex.Pattern;

/** Bearer cookie codec. Operational uses the __Host- prefix; local HTTP uses a distinct unprefixed name. */
public final class SessionCookies {
    private static final Pattern VALUE=Pattern.compile("[A-Za-z0-9_-]{43}");
    private final String name;private final boolean secure;
    private SessionCookies(String name,boolean secure) { this.name=name;this.secure=secure; }
    public static SessionCookies operational() { return new SessionCookies("__Host-purpuragenda",true); }
    public static SessionCookies local() { return new SessionCookies("purpuragenda-local",false); }
    public String name() { return name; }
    /** Absent, repeated or malformed cookies are treated as no session. */
    public char[] read(HttpServletRequest request) {
        Cookie[] all=request.getCookies();String found=null;
        if(all!=null) for(Cookie cookie:all) if(cookie.getName().equals(name)) { if(found!=null) return null;found=cookie.getValue(); }
        return found!=null && VALUE.matcher(found).matches()?found.toCharArray():null;
    }
    public void write(HttpServletResponse response,char[] token) { response.addHeader("Set-Cookie",header(new String(token),"")); }
    public void clear(HttpServletResponse response) { response.addHeader("Set-Cookie",header("","; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")); }
    private String header(String value,String expiry) {
        return name+"="+value+"; Path=/"+(secure?"; Secure":"")+"; HttpOnly; SameSite=Lax"+expiry;
    }
}
