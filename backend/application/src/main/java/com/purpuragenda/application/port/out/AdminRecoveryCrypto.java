package com.purpuragenda.application.port.out;

import java.util.Optional;

public interface AdminRecoveryCrypto extends AdminFactorCrypto {
    Optional<String> digest(char[] value);
}
