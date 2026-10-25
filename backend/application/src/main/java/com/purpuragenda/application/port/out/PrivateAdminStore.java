package com.purpuragenda.application.port.out;

/** Private bootstrap only: never updates or replaces any existing administrator. */
public interface PrivateAdminStore {
    enum Result { CREATED, ALREADY_EXISTS }
    Result createUnenrolled(String emailKey, String passwordHash);
}
