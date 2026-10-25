package com.purpuragenda.application;

import com.purpuragenda.application.port.out.PasswordHash;
import com.purpuragenda.application.port.out.PrivateAdminStore;
import com.purpuragenda.domain.customer.Input;
import java.util.Arrays;
import java.util.Objects;
import static com.purpuragenda.domain.DomainFailure.require;

/** Private use case only; no session or authenticated capability is issued. */
public final class PrivateAdminBootstrap {
    private final PasswordHash passwords;
    private final PrivateAdminStore admins;
    public PrivateAdminBootstrap(PasswordHash passwords, PrivateAdminStore admins) {
        this.passwords=Objects.requireNonNull(passwords); this.admins=Objects.requireNonNull(admins);
    }
    /** Consumes and clears the caller's password buffer on success and failure. */
    public PrivateAdminStore.Result create(String email, char[] password) {
        try {
            validatePassword(password);
            return admins.createUnenrolled(Input.email(email), passwords.hash(password));
        } finally { if(password!=null) Arrays.fill(password, '\0'); }
    }
    /** Count Unicode codepoints exactly; never trim, normalize or truncate a password. */
    public static void validatePassword(char[] password) {
        require(password!=null && password.length<=256, "INVALID_ADMIN_PASSWORD");
        int count=0;
        for(int i=0;i<password.length;i++) {
            char current=password[i];
            if(Character.isHighSurrogate(current))
                require(i+1<password.length && Character.isLowSurrogate(password[++i]), "INVALID_ADMIN_PASSWORD");
            else require(!Character.isLowSurrogate(current), "INVALID_ADMIN_PASSWORD");
            require(!Character.isISOControl(current), "INVALID_ADMIN_PASSWORD");
            count++;
        }
        require(count>=15 && count<=128, "INVALID_ADMIN_PASSWORD");
    }
}
