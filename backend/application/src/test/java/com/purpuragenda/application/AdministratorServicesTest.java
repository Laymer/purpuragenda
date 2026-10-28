package com.purpuragenda.application;

import com.purpuragenda.application.port.out.ConfigurationUnitOfWork.Operation;
import com.purpuragenda.application.port.out.CustomerBookingUnitOfWork.SessionBinding;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.AdministratorConfiguration;
import com.purpuragenda.application.scheduling.AdministratorConfiguration.ServiceValues;
import com.purpuragenda.domain.DomainFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdministratorServicesTest {
    final AdministratorConfigurationTest.Fixture f=new AdministratorConfigurationTest.Fixture();
    final AdministratorConfiguration config=new AdministratorConfiguration(f);
    final SessionBinding session=new SessionBinding(UUID.randomUUID(),"e".repeat(64));
    static final String REASON="Synthetic service change";
    ServiceValues values(){return new ServiceValues("  Synthetic service  ",45,2500);}
    Receipt create(){return config.createService(session,"create",values(),REASON);}
    @Test void creationUsesAServerIdVersionOneAndNormalizedFingerprintWithExpiryFirst() {
        var receipt=create();assertTrue(receipt.success());assertEquals(1,receipt.version());assertNotEquals(f.service.id(),receipt.entityId());
        var stored=f.services().stream().filter(s->s.id().equals(receipt.entityId())).findFirst().orElseThrow();
        assertEquals("Synthetic service",stored.name());assertEquals(45,stored.durationMinutes());assertEquals(2500,stored.price().minor());assertTrue(stored.active());
        assertEquals(List.of("expiry","attempt","save-service","service-effects"),f.steps);assertEquals(Operation.CREATE_SERVICE,f.operation);assertEquals(REASON,f.effectReason);
        String fingerprint=f.command.fingerprint();assertNull(f.command.targetId());
        config.createService(session,"create",new ServiceValues("Synthetic service",45,2500),REASON);assertEquals(fingerprint,f.command.fingerprint());
        assertEquals("ServiceValues[REDACTED]",values().toString());
    }
    @Test void editingAndArchivingPreserveIdentityAndRestoreOnlyAdvancesTheServiceVersion() {
        var created=create();UUID id=created.entityId();
        assertEquals(new Receipt(Code.OK,id,2),config.updateService(session,id,"edit",1,new ServiceValues("Changed service",60,0),REASON));
        assertEquals(id,f.command.targetId());assertEquals(Operation.UPDATE_SERVICE,f.operation);
        assertEquals(new Receipt(Code.OK,id,3),config.archiveService(session,id,"archive",2,REASON));assertEquals(Operation.ARCHIVE_SERVICE,f.operation);
        var archived=f.services().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();assertFalse(archived.active());assertEquals("Changed service",archived.name());
        assertEquals(new Receipt(Code.OK,id,4),config.updateService(session,id,"edit-archived",3,new ServiceValues("Archived service",120,100000000),REASON));
        assertFalse(f.services().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow().active());
        assertEquals(new Receipt(Code.OK,id,5),config.restoreService(session,id,"restore",4,REASON));assertEquals(Operation.RESTORE_SERVICE,f.operation);
        assertTrue(f.services().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow().active());
    }
    @Test void staleUnknownAndNoOpChangesDoNotSave() {
        var created=create();UUID id=created.entityId();f.reset();
        assertEquals(Code.STALE_VERSION,config.archiveService(session,id,"stale",2,REASON).code());
        assertEquals(Code.INVALID_CONFIGURATION,config.updateService(session,id,"same",1,values(),REASON).code());
        assertEquals(Code.INVALID_CONFIGURATION,config.restoreService(session,id,"already-active",1,REASON).code());
        assertEquals(Failure.Kind.NOT_FOUND,assertThrows(Failure.class,()->config.archiveService(session,UUID.randomUUID(),"missing",1,REASON)).kind());assertEquals(0,f.saves);
        config.archiveService(session,id,"archive",1,REASON);f.reset();
        assertEquals(Code.INVALID_CONFIGURATION,config.archiveService(session,id,"already-archived",2,REASON).code());assertEquals(0,f.saves);
    }
    @Test void validationAndFingerprintsCoverTheWholeIntent() {
        for(Runnable invalid:List.<Runnable>of(()->new ServiceValues("",30,1),()->new ServiceValues("x",0,1),()->new ServiceValues("x",121,1),
                ()->new ServiceValues("x",30,-1),()->new ServiceValues("x",30,100000001),()->config.createService(session,"k",values(),"short"),
                ()->config.archiveService(session,null,"k",1,REASON),()->config.archiveService(session,UUID.randomUUID(),"k",0,REASON)))assertThrows(DomainFailure.class,invalid::run);
        assertNull(f.command);assertThrows(NullPointerException.class,()->config.updateService(session,UUID.randomUUID(),"k",1,null,REASON));
        Set<String> fingerprints=new HashSet<>();
        for(var v:List.of(values(),new ServiceValues("Other service",45,2500),new ServiceValues("Synthetic service",46,2500),new ServiceValues("Synthetic service",45,2501))) {
            config.createService(session,"k",v,REASON);fingerprints.add(f.command.fingerprint());
        }
        config.createService(session,"k",values(),"Another synthetic reason");fingerprints.add(f.command.fingerprint());assertEquals(5,fingerprints.size());
        UUID id=f.service.id();config.archiveService(session,id,"k",99,REASON);String original=f.command.fingerprint();
        config.archiveService(session,id,"k",98,REASON);assertNotEquals(original,f.command.fingerprint());
        assertThrows(Failure.class,()->config.archiveService(session,UUID.randomUUID(),"k",99,REASON));assertNotEquals(original,f.command.fingerprint());
    }
}
