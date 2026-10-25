package com.purpuragenda.application.port.out;

import java.time.Instant;
import com.purpuragenda.application.port.out.AdminMfaUnitOfWork.RecoveryCode;

public interface AdminFactorCrypto {
    long acceptedStep(byte[] secret,String code,Instant now,long previousStep);
    RecoveryCode recoveryCode();
}
