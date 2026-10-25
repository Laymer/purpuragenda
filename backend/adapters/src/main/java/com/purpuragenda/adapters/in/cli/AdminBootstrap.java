package com.purpuragenda.adapters.in.cli;
import com.purpuragenda.application.port.out.IdentityGateway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import java.util.Arrays;

@Component
public class AdminBootstrap implements CommandLineRunner {
    private final IdentityGateway identities;
    public AdminBootstrap(IdentityGateway identities){this.identities=identities;}
    public void run(String... args){if(args.length<2||!args[0].equals("admin")||!args[1].equals("bootstrap"))return;
        var console=System.console();if(console==null)throw new IllegalStateException("PRIVATE_INTERACTIVE_TERMINAL_REQUIRED");
        String email=console.readLine("Administrator email: ");char[] password=console.readPassword("Password (15–128 characters): ");char[] repeated=console.readPassword("Repeat password: ");
        if(!Arrays.equals(password,repeated)){Arrays.fill(password,'\0');Arrays.fill(repeated,'\0');throw new IllegalArgumentException("PASSWORD_MISMATCH");}Arrays.fill(repeated,'\0');
        var id=identities.bootstrap(email,password);var enrollment=identities.enrollment(id);
        console.printf("Privately add this TOTP secret to your authenticator: %s%n",enrollment.get("secret"));
        identities.completeEnrollment(id,console.readLine("Current six-digit authenticator code: "));
        console.printf("Store these ten one-use recovery codes privately. They will not be displayed again.%n");
        for(String code:identities.recoveryCodes(id))console.printf("%s%n",code);
        console.printf("Administrator provisioned. Sign in using password and a fresh TOTP code.%n");
    }
}
