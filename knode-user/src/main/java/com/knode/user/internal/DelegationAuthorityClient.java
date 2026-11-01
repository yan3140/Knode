package com.knode.user.internal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.knode.auth.DelegationTokenVerifier;
import com.knode.auth.ServiceTokenVerifier;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Only configured authority origins are used; the response is read with a hard byte limit. */
public final class DelegationAuthorityClient implements AutoCloseable {
    public static final int MAX_RESPONSE_BYTES=16384;
    private static final String PATH="/internal/v1/authz/delegation-proofs:resolve";
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> RESPONSE_FIELDS=Set.of("schemaVersion","proofRef","authority","proofKind","status","subject",
            "actorService","resource","permissionsByAudience","sourceRevision","validUntil","authorizationExpiresAt","parentGrantId","rootGrantId");
    private final Map<String,URI> origins;
    private final TokenSource tokenSource;
    private final Clock clock;
    private final Duration timeout;
    private final com.knode.governance.GovernedCall governed;
    private final io.opentelemetry.api.OpenTelemetry telemetry;
    private final HttpClient http;
    private final ThreadPoolExecutor workers;
    @FunctionalInterface public interface TokenSource {String token(String audience);}
    public record Subject(String userId,String sid,String userScope) {}
    public record Request(String proofRef,String proofKind,String purpose,Subject subject,String actorService,
                          String parentGrantId,String rootGrantId,Map<String,String> expectedResource) {
        public Request {expectedResource=Map.copyOf(expectedResource);}
    }
    public record Proof(String proofRef,String authority,String proofKind,Subject subject,String actorService,
                        Map<String,String> resource,Map<String,Set<String>> permissionsByAudience,
                        String sourceRevision,long validUntil,long authorizationExpiresAt,String parentGrantId,String rootGrantId) {
        public Proof {resource=Map.copyOf(resource);var copy=new LinkedHashMap<String,Set<String>>();
            permissionsByAudience.forEach((k,v)->copy.put(k,Set.copyOf(v)));permissionsByAudience=Map.copyOf(copy);}
    }
    public DelegationAuthorityClient(Map<String,URI> configured,TokenSource tokens,Clock clock,Duration timeout) {
        this(configured,tokens,clock,timeout,null,null);
    }
    public DelegationAuthorityClient(Map<String,URI> configured,TokenSource tokens,Clock clock,Duration timeout,com.knode.governance.GovernedCall governed) {
        this(configured,tokens,clock,timeout,governed,null);
    }
    public DelegationAuthorityClient(Map<String,URI> configured,TokenSource tokens,Clock clock,Duration timeout,
                                     com.knode.governance.GovernedCall governed,io.opentelemetry.api.OpenTelemetry telemetry) {
        this.governed=governed;this.telemetry=telemetry;
        var safe=new HashMap<String,URI>();
        configured.forEach((name,uri)->{
            if (!Set.of("knode-knowledge","knode-agent").contains(name) || !validOrigin(uri))
                throw new IllegalArgumentException("Invalid delegation authority origin");
            safe.put(name,uri.resolve(PATH));
        });
        origins=Map.copyOf(safe);tokenSource=Objects.requireNonNull(tokens);this.clock=Objects.requireNonNull(clock);
        if (timeout==null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(5))>0)
            throw new IllegalArgumentException("Invalid authority timeout");
        this.timeout=timeout;
        http=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        workers=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r -> {
            var t=new Thread(r,"delegation-authority");t.setDaemon(true);return t;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    private static boolean validOrigin(URI uri) {
        if (uri==null || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getPath()==null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))) return false;
        if ("https".equalsIgnoreCase(uri.getScheme())) return true;
        return "http".equalsIgnoreCase(uri.getScheme()) && ("127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()) || "::1".equals(uri.getHost()));
    }
    public boolean configured() {return origins.keySet().containsAll(Set.of("knode-knowledge","knode-agent"));}
    /** Local credential/policy readiness only; does not claim the remote authority is healthy. */
    public boolean credentialsReady() {
        try {
            for (String audience:List.of("knode-knowledge","knode-agent")) {
                String token=tokenSource.token(audience);
                if (token==null || token.isBlank() || token.length()>8192) return false;
            }
            return true;
        } catch (RuntimeException e) {return false;}
    }
    private record Resolution(Proof proof,InternalFailure denied) {}
    public Proof resolve(String authority,Request request) {
        if (telemetry==null) return resolveGoverned(authority,request);
        var span=telemetry.getTracer("com.knode.user.authority").spanBuilder("user.delegation.proof.resolve")
                .setSpanKind(io.opentelemetry.api.trace.SpanKind.CLIENT).startSpan();
        try (var scope=span.makeCurrent()) {return resolveGoverned(authority,request);}
        catch (InternalFailure e) {
            if (e.status()>=500) span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            throw e;
        } finally {span.end();}
    }
    private Proof resolveGoverned(String authority,Request request) {
        if (governed==null) return resolveDirect(authority,request);
        try {
            Resolution outcome=governed.execute("feign:delegationAuthority#resolve",() -> {
                try {return new Resolution(resolveDirect(authority,request),null);}
                catch (InternalFailure failure) {
                    if (failure.status()==403) return new Resolution(null,failure);
                    throw failure;
                }
            });
            if (outcome.denied()!=null) throw outcome.denied();
            return outcome.proof();
        } catch (com.knode.governance.GovernanceUnavailableException failure) {throw unavailable();}
    }
    private Proof resolveDirect(String authority,Request request) {
        URI uri=origins.get(authority);
        if (uri==null) throw unavailable();
        if (request==null || !DelegationTokenVerifier.validOpaque(request.proofRef()) || !ServiceTokenVerifier.validService(request.actorService()))
            throw unavailable();
        final String body;
        try {
            var out=new LinkedHashMap<String,Object>();out.put("schemaVersion",1);out.put("proofRef",request.proofRef());
            out.put("proofKind",request.proofKind());out.put("purpose",request.purpose());out.put("subject",request.subject());
            out.put("actorService",request.actorService());out.put("parentGrantId",request.parentGrantId());out.put("rootGrantId",request.rootGrantId());
            out.put("expectedResource",request.expectedResource());body=JSON.writeValueAsString(out);
            if (body.getBytes(StandardCharsets.UTF_8).length>8192) throw new IllegalArgumentException();
        } catch (Exception e) {throw unavailable();}
        final String machineToken;
        try {machineToken=tokenSource.token(authority);}
        catch (RuntimeException e) {throw unavailable();}
        if (machineToken==null || machineToken.isBlank() || machineToken.length()>8192) throw unavailable();
        var builder=HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type","application/json")
                .header("Authorization","Bearer "+machineToken).POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
        if (telemetry!=null) {
            var fields=new HashMap<String,String>();
            io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance().inject(
                    io.opentelemetry.context.Context.current(),fields,Map::put);
            String traceparent=fields.get("traceparent");
            if (traceparent!=null) builder.header("traceparent",traceparent);
        }
        var httpRequest=builder.build();
        Future<HttpResult> task;
        try {task=workers.submit(() -> {
            var response=http.send(httpRequest,HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input=response.body()) {
                byte[] bytes=input.readNBytes(MAX_RESPONSE_BYTES+1);
                if (bytes.length>MAX_RESPONSE_BYTES) throw new IOException("response too large");
                return new HttpResult(response.statusCode(),bytes);
            }
        });} catch (RejectedExecutionException e) {throw unavailable();}
        try {
            HttpResult response=task.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
            if (response.status()==403) throw denied();
            if (response.status()!=200) throw unavailable();
            return parse(response.body(),authority,request);
        } catch (TimeoutException e) {
            // Closing a stalled HTTP response stream here can itself block the caller.
            // Cancellation interrupts the bounded worker, whose try-with-resources owns the stream.
            task.cancel(true);throw unavailable();
        } catch (InterruptedException e) {
            task.cancel(true);Thread.currentThread().interrupt();throw unavailable();
        } catch (ExecutionException e) {throw unavailable();}
    }
    private record HttpResult(int status,byte[] body) {}
    private Proof parse(byte[] bytes,String authority,Request expected) {
        try {
            JsonNode root=JSON.readTree(bytes);fields(root,RESPONSE_FIELDS);
            if (!root.path("schemaVersion").isInt() || root.path("schemaVersion").intValue()!=1) throw new IllegalArgumentException();
            String ref=text(root,"proofRef"),who=text(root,"authority"),kind=text(root,"proofKind"),status=text(root,"status"),actor=text(root,"actorService");
            JsonNode subjectNode=root.path("subject");fields(subjectNode,Set.of("userId","sid","userScope"));
            var subject=new Subject(text(subjectNode,"userId"),text(subjectNode,"sid"),text(subjectNode,"userScope"));
            if (!ref.equals(expected.proofRef()) || !who.equals(authority) || !kind.equals(expected.proofKind())
                    || !actor.equals(expected.actorService()) || !subject.equals(expected.subject())) throw new IllegalArgumentException();
            if ("DENIED".equals(status)) throw denied();
            if (!"ACTIVE".equals(status)) throw new IllegalArgumentException();
            String parent=optional(root,"parentGrantId"),rootId=optional(root,"rootGrantId");
            if (!Objects.equals(parent,expected.parentGrantId()) || !Objects.equals(rootId,expected.rootGrantId())) throw new IllegalArgumentException();
            Map<String,String> resource=DelegationTokenVerifier.parseResource(root.path("resource"),expected.purpose());
            if (!resource.entrySet().containsAll(expected.expectedResource().entrySet())) throw new IllegalArgumentException();
            JsonNode p=root.path("permissionsByAudience");
            if (!p.isObject() || p.isEmpty() || p.size()>8) throw new IllegalArgumentException();
            var permissions=new LinkedHashMap<String,Set<String>>();
            p.fields().forEachRemaining(e -> {
                if (!ServiceTokenVerifier.validService(e.getKey())) throw new IllegalArgumentException();
                permissions.put(e.getKey(),DelegationTokenVerifier.parsePermissions(e.getValue()));
            });
            String revision=text(root,"sourceRevision");if (!DelegationTokenVerifier.validId(revision)) throw new IllegalArgumentException();
            long until=Instant.parse(text(root,"validUntil")).getEpochSecond(),authUntil=Instant.parse(text(root,"authorizationExpiresAt")).getEpochSecond();
            if (until<=clock.instant().getEpochSecond() || authUntil<=clock.instant().getEpochSecond()) throw new IllegalArgumentException();
            return new Proof(ref,authority,kind,subject,actor,resource,permissions,revision,until,authUntil,parent,rootId);
        } catch (InternalFailure e) {throw e;}
        catch (Exception e) {throw unavailable();}
    }
    private static void fields(JsonNode n,Set<String> allowed) {
        if (n==null || !n.isObject()) throw new IllegalArgumentException();
        n.fieldNames().forEachRemaining(f -> {if (!allowed.contains(f)) throw new IllegalArgumentException();});
    }
    private static String text(JsonNode n,String key) {
        if (!n.path(key).isTextual() || n.path(key).textValue().isBlank()) throw new IllegalArgumentException();return n.path(key).textValue();
    }
    private static String optional(JsonNode n,String key) {
        if (!n.has(key) || n.get(key).isNull()) return null;
        String v=text(n,key);if (!com.knode.auth.UserTokenVerifier.validUuid(v)) throw new IllegalArgumentException();return v;
    }
    private static InternalFailure unavailable() {return new InternalFailure(503,"DELEGATION_AUTHORITY_UNAVAILABLE");}
    private static InternalFailure denied() {return new InternalFailure(403,"DELEGATION_DENIED");}
    @Override public void close() {workers.shutdownNow();}
}
