package com.purpuragenda.application.port.out;

/** Credential buffers are borrowed only for the duration of each operation. */
public interface PasswordHash {
    String hash(char[] password);
    boolean verify(char[] password, String encoded);
}
