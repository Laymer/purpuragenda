package com.purpuragenda.adapters.in.rest;

import com.purpuragenda.application.RequestThrottle;
import com.purpuragenda.application.port.out.*;
import com.purpuragenda.application.port.out.ContactChangesUnitOfWork.*;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.*;
import com.purpuragenda.application.scheduling.CustomerContactChanges;
import com.purpuragenda.domain.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class CustomerContactHttpTest {
    final SessionHttpTest.Memory sessions=new SessionHttpTest.Memory();final UUID id=UUID.randomUUID();
    final String body="{\"token\":\""+"s".repeat(43)+"\"}";RuntimeException failure;Code code=Code.OK;boolean limited;int calls;ProofRequest proof;Command command;
    final ContactChangesUnitOfWork unit=new ContactChangesUnitOfWork(){
        void called(){calls++;if(failure!=null)throw failure;}
        public void request(ProofRequest value){called();proof=value;}
        public Receipt change(Command value,Function<Change,Receipt> work){called();command=value;return code==Code.OK?new Receipt(Code.OK,id,2):Receipt.failure(code);}
    };
    final RateLimitStore limits=new RateLimitStore(){
        public Decision admit(List<Charge> charges){return limited?Decision.deny(42):Decision.allow(List.of());}
        public boolean release(Reservation value){throw new AssertionError();}
    };
    final CustomerContactController controller=new CustomerContactController(new CustomerContactChanges(unit,new RequestThrottle(limits)));
    MockHttpServletRequest request(String text){var r=new MockHttpServletRequest();r.setContentType("application/json");r.setContent(text.getBytes(StandardCharsets.UTF_8));r.addHeader("Idempotency-Key","synthetic-key");r.addHeader("If-Match","\"1\"");try(var session=sessions.anonymous()){r.setAttribute(ApiGuardFilter.SESSION_ATTRIBUTE,session.session());}return r;}
    MockHttpServletResponse send(boolean issue,MockHttpServletRequest request) throws Exception {var r=new MockHttpServletResponse();if(issue)controller.requestProof(id.toString(),request,r);else controller.confirm(id.toString(),request,r);return r;}
    void problem(MockHttpServletResponse response,int status,String code) throws Exception {assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));assertTrue(response.getContentAsString().contains("\"code\":\""+code+"\""));assertFalse(response.getContentAsString().contains("synthetic"));}
    @Test void exactProofAndReceiptResponsesCarryNoSecretAndIgnoreConditionalCacheHeaders() throws Exception {
        var request=request("{\"email\":\"New@Example.TEST\"}");request.setAttribute(ApiGuardFilter.CLIENT_ATTRIBUTE,"203.0.113.9");var issued=send(true,request);assertEquals(202,issued.getStatus());assertEquals("{\"accepted\":true}",issued.getContentAsString());assertEquals("new@example.test",proof.email());assertEquals(id,proof.bookingId());
        request=request(body);request.addHeader("If-None-Match","\"2\"");var response=send(false,request);assertEquals(200,response.getStatus());assertEquals("\"2\"",response.getHeader("ETag"));assertEquals("no-store",response.getHeader("Cache-Control"));assertTrue(response.getContentAsString().contains(id.toString()));assertFalse(response.getContentAsString().contains("sss"));assertTrue(command.fingerprint().matches("[a-f0-9]{64}"));
    }
    @Test void proofBudgetReturnsRetryAfterWithoutIssuing() throws Exception {limited=true;var response=send(true,request("{\"email\":\"new@example.test\"}"));problem(response,429,"RATE_LIMITED");assertEquals("42",response.getHeader("Retry-After"));assertEquals(0,calls);}
    @Test void missingSessionAndVersionAreExplicit() throws Exception {
        for(boolean issue:List.of(false,true)){var request=request(body);request.removeAttribute(ApiGuardFilter.SESSION_ATTRIBUTE);problem(send(issue,request),401,"SESSION_REQUIRED");}
        var missing=request(body);missing.removeHeader("If-Match");problem(send(false,missing),428,"VERSION_REQUIRED");assertEquals(0,calls);
    }
    @Test void invalidBodiesQueriesAndHeadersNeverReachPersistence() throws Exception {
        for(String text:List.of("{}","{}{}","{\"token\":1}","{\"token\":\"short\"}",body.replace("}",",\"extra\":true}"),"{\"token\":\"x\",\"token\":\"x\"}"," ".repeat(17000)))problem(send(false,request(text)),400,"INVALID_REQUEST");
        for(String tag:List.of("W/\"1\"","*","\"0\"","\"9223372036854775807\"")){var r=request(body);r.removeHeader("If-Match");r.addHeader("If-Match",tag);problem(send(false,r),400,"INVALID_REQUEST");}
        var missing=request(body);missing.removeHeader("Idempotency-Key");problem(send(false,missing),400,"INVALID_REQUEST");
        var query=request(body);query.addParameter("extra","1");problem(send(false,query),400,"INVALID_REQUEST");
        problem(send(true,request("{\"email\":\"invalid\"}")),400,"INVALID_REQUEST");assertEquals(0,calls);
        var response=new MockHttpServletResponse();controller.confirm("bad",request(body),response);problem(response,400,"INVALID_REQUEST");
    }
    @Test void businessAndProofFailuresAreSafeAndDistinct() throws Exception {
        code=Code.STALE_VERSION;problem(send(false,request(body)),412,"STALE_VERSION");code=Code.IDEMPOTENCY_KEY_REUSED;problem(send(false,request(body)),409,"IDEMPOTENCY_KEY_REUSED");
        failure=new DomainFailure("INVALID_CONTACT_PROOF");problem(send(false,request(body)),400,"PROOF_INVALID");failure=new DomainFailure("INVALID_CONTACT_CHANGE");problem(send(false,request(body)),400,"INVALID_REQUEST");
    }
    @Test void everyStorageAuthorityAndUnexpectedFailureIsContained() throws Exception {
        for(boolean issue:List.of(false,true))for(var kind:Failure.Kind.values()) {
            failure=new Failure(kind);int expected=switch(kind){case AUTHENTICATION_REQUIRED->401;case FORBIDDEN,NOT_FOUND->404;case FRESH_AUTHENTICATION_REQUIRED->403;default->503;};
            assertEquals(expected,send(issue,request(issue?"{\"email\":\"new@example.test\"}":body)).getStatus());
        }
        failure=new IllegalArgumentException("synthetic-private");problem(send(false,request(body)),400,"INVALID_REQUEST");failure=new IllegalStateException("synthetic-private");problem(send(false,request(body)),503,"TEMPORARILY_UNAVAILABLE");
        assertThrows(NullPointerException.class,()->new CustomerContactController(null));
    }
}
