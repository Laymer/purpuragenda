package com.purpuragenda.bootstrap;

import com.purpuragenda.application.scheduling.BookingNotice;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/** Local fixtures are explicit. Serving operational traffic requires a bounded, explicitly versioned notice file. */
final class RuntimeNotice {
    private RuntimeNotice() {}
    static BookingNotice read(Environment environment) {
        try {
            String mode=environment.getRequiredProperty("app.mode");
            if(!Set.of("local","test","restore","operational").contains(mode))throw new IllegalArgumentException();
            if(!mode.equals("operational"))return new BookingNotice(
                    environment.getProperty("app.notice-version","local-v1"),
                    "Synthetic local demonstration: use fictional details only. Requests hold a time until confirmation or expiry. "
                    +"External payments are recorded separately. Customer changes follow the displayed cutoff. "
                    +"This fixture is not an approved privacy or business notice.",true);
            String version=environment.getRequiredProperty("app.notice-version");
            if(version.toLowerCase(Locale.ROOT).startsWith("local-"))throw new IllegalArgumentException();
            Path path=Path.of(environment.getRequiredProperty("app.notice-file"));
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException();
            byte[] bytes;
            try(var channel=Files.newByteChannel(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
                var input=Channels.newInputStream(channel)) { bytes=input.readNBytes(65537); }
            if(bytes.length>65536)throw new IllegalArgumentException();
            String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().replace("\r\n","\n");
            return new BookingNotice(version,text,false);
        } catch(Exception invalid) { throw new IllegalArgumentException("INVALID_BOOKING_NOTICE_CONFIGURATION"); }
    }
}
