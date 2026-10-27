package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.AccountAuthenticationUnitOfWork.Credential;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import java.util.Optional;
import java.util.function.Function;

/** Account then session locks, one captured time, credential update and complete revocation in one transaction. */
public interface AccountPasswordUnitOfWork {
    <T>T locked(String loginEmail,SessionBinding session,Function<Account,T> work);
    interface Account {
        Optional<Credential> credential();
        void replacePassword(String encoded);
    }
}
