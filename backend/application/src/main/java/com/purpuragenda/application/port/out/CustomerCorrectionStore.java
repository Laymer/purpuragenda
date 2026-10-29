package com.purpuragenda.application.port.out;

import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.AdministratorNotes.Addition;
import com.purpuragenda.domain.customer.Intake;
import java.time.*;
import java.util.*;
import static com.purpuragenda.domain.DomainFailure.require;

/** Reviewed corrections never change identity, ownership, service/policy snapshots or payment facts. */
public interface CustomerCorrectionStore {
    enum Kind { PROFILE, NOTE, INTAKE }
    Target target(SessionBinding session,UUID customer,Kind kind,UUID target);
    Receipt prepare(SessionBinding session,UUID customer,UUID target,String key,Change change);
    Preview preview(SessionBinding session,UUID id);
    Receipt confirm(SessionBinding session,UUID id,String key,long version,Review review);
    int purge(int limit);
    record Change(Kind kind,Map<String,String> values,String reason) {
        public Change {
            Objects.requireNonNull(kind);values=new TreeMap<>(Objects.requireNonNull(values));
            reason=new com.purpuragenda.application.privacy.AdministratorExports.Request(reason).reason();
            require(values.values().stream().allMatch(v->v!=null && v.codePoints().noneMatch(c->c>=0xd800&&c<=0xdfff)),"INVALID_CORRECTION");
            Set<String> fields=switch(kind){case PROFILE->Set.of("firstName","lastName","telephone");case NOTE->Set.of("text");case INTAKE->Set.of("firstName","lastName","telephone","birthDate","birthTime","birthplace","message");};
            require(values.keySet().equals(fields),"INVALID_CORRECTION");
            if(kind==Kind.PROFILE){var p=new AccountDataStore.Fields(values.get("firstName"),values.get("lastName"),values.get("telephone"));values.put("firstName",p.firstName());values.put("lastName",p.lastName());values.put("telephone",p.telephone());}
            if(kind==Kind.NOTE)values.put("text",new Addition(values.get("text")).text());
            if(kind==Kind.INTAKE){var p=intake(values);values.put("firstName",p.firstName());values.put("lastName",p.lastName());values.put("telephone",p.telephone());values.put("birthplace",p.birthplace());values.put("message",p.message());values.put("birthDate",p.birthDate().toString());values.put("birthTime",p.birthTime().toString());}
            values=Collections.unmodifiableMap(values);
        }
        public static Intake intake(Map<String,String> v){return new Intake(v.get("firstName"),v.get("lastName"),v.get("telephone"),LocalDate.parse(v.get("birthDate")),LocalTime.parse(v.get("birthTime")),v.get("birthplace"),v.get("message"));}
        @Override public String toString(){return "CorrectionChange[REDACTED]";}
    }
    record Target(Kind kind,UUID targetId,long version,Map<String,String> values) {
        public Target {Objects.requireNonNull(kind);Objects.requireNonNull(targetId);require(version>0,"INVALID_VERSION");values=Map.copyOf(values);}
        @Override public String toString(){return "CorrectionTarget[REDACTED]";}
    }
    record Preview(UUID id,UUID customerId,Target before,Map<String,String> after,String reason,String digest,Instant expiresAt,long version) {
        public Preview {after=Map.copyOf(after);}
        @Override public String toString(){return "CorrectionPreview[REDACTED]";}
    }
    record Review(String digest,boolean confirmed) {
        public Review {require(digest!=null&&digest.matches("[0-9a-f]{64}")&&confirmed,"CORRECTION_REVIEW_REQUIRED");}
        @Override public String toString(){return "CorrectionReview[REDACTED]";}
    }
}
