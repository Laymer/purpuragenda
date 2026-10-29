package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import java.time.Instant;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Fresh persisted MFA precedes every preview, release and download, including idempotent replay. */
public interface CustomerExportStore {
    Receipt prepare(SessionBinding session, UUID customer, String key, String reason);
    Receipt release(SessionBinding session, UUID export, String key, Review review);
    Document read(SessionBinding session, UUID export, boolean download);
    /** Removes expired ciphertext without reading or decrypting it; at most limit rows. */
    int purge(int limit);
    record Review(String digest, boolean reviewedThirdPartyInformation) {
        public Review { require(digest!=null && digest.matches("[0-9a-f]{64}") && reviewedThirdPartyInformation,"EXPORT_REVIEW_REQUIRED"); }
        @Override public String toString(){return "ExportReview[REDACTED]";}
    }
    record Document(UUID id, UUID customerId, Instant expiresAt, String digest, boolean released, String json) {
        public Document { Objects.requireNonNull(id);Objects.requireNonNull(customerId);Objects.requireNonNull(expiresAt);Objects.requireNonNull(digest);Objects.requireNonNull(json); }
        @Override public String toString(){return "CustomerExport[REDACTED]";}
    }
}
