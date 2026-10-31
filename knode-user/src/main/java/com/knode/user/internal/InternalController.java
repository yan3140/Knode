package com.knode.user.internal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.knode.auth.AuthFailure;
import com.knode.auth.ServiceTokenVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
public class InternalController {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ServiceTokens tokens;
    private final JdbcTemplate db;
    public InternalController(ServiceTokens tokens,JdbcTemplate db) {this.tokens=tokens;this.db=db;}
    private JsonNode body(byte[] input,Set<String> allowed) {
        try {
            if (input==null || input.length==0 || input.length>16384) throw new IllegalArgumentException();
            JsonNode root=JSON.readTree(input);ServicePolicy.fields(root,allowed);return root;
        } catch (Exception e) {throw new InternalFailure(400,"INVALID_ARGUMENT");}
    }
    private String header(HttpServletRequest request,String scheme) {
        var headers=Collections.list(request.getHeaders("Authorization"));
        if (headers.size()!=1 || headers.get(0).contains(",") || !headers.get(0).startsWith(scheme+" "))
            throw new InternalFailure(401,"INVALID_SERVICE_TOKEN");
        String value=headers.get(0).substring(scheme.length()+1);
        if (value.isBlank() || value.length()>8192 || value.indexOf(' ')>=0) throw new InternalFailure(401,"INVALID_SERVICE_TOKEN");
        return value;
    }
    private String[] basic(HttpServletRequest request) {
        try {
            String encoded=header(request,"Basic");
            byte[] bytes=Base64.getDecoder().decode(encoded);
            String decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            int sep=decoded.indexOf(':');
            if (sep<1 || sep==decoded.length()-1 || decoded.substring(sep+1).getBytes(StandardCharsets.UTF_8).length>72) throw new IllegalArgumentException();
            String id=decoded.substring(0,sep),secret=decoded.substring(sep+1);
            if (!ServiceTokenVerifier.validService(id)) throw new IllegalArgumentException();
            return new String[]{id,secret};
        } catch (InternalFailure e) {throw e;}
        catch (Exception e) {throw new InternalFailure(401,"INVALID_CLIENT_CREDENTIALS");}
    }
    private void service(HttpServletRequest request,String permission) {
        tokens.verify(header(request,"Bearer"),permission);
    }
    private List<String> permissions(JsonNode root) {
        JsonNode n=root.path("permissions");if (!n.isArray() || n.isEmpty() || n.size()>100) throw new InternalFailure(400,"INVALID_ARGUMENT");
        List<String> result=new ArrayList<>();Set<String> distinct=new HashSet<>();
        for (JsonNode item:n) {
            if (!item.isTextual() || !ServiceTokenVerifier.validPermission(item.textValue()) || !distinct.add(item.textValue())) throw new InternalFailure(400,"INVALID_ARGUMENT");
            result.add(item.textValue());
        }
        return result;
    }
    private List<Long> ids(JsonNode root) {
        JsonNode n=root.path("userIds");if (!n.isArray() || n.isEmpty() || n.size()>100) throw new InternalFailure(400,"INVALID_ARGUMENT");
        Set<Long> result=new LinkedHashSet<>();
        for (JsonNode item:n) {
            if (!item.isTextual() || !item.textValue().matches("[1-9][0-9]{0,18}")) throw new InternalFailure(400,"INVALID_ARGUMENT");
            try {result.add(Long.parseLong(item.textValue()));}
            catch (NumberFormatException e) {throw new InternalFailure(400,"INVALID_ARGUMENT");}
        }
        return List.copyOf(result);
    }
    private Map<Long,Map<String,Object>> query(List<Long> ids) {
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        return db.query("SELECT id,nick_name,avatar,status,del_flag,profile_revision,profile_updated_at FROM sys_user WHERE id IN ("+placeholders+")",rs -> {
            Map<Long,Map<String,Object>> result=new HashMap<>();
            while (rs.next()) {
                long id=rs.getLong("id"),revision=rs.getLong("profile_revision");var updated=rs.getTimestamp("profile_updated_at");
                if (rs.wasNull() || revision<1 || updated==null) throw new InternalFailure(503,"DEPENDENCY_UNAVAILABLE");
                var row=new HashMap<String,Object>();row.put("userId",Long.toString(id));row.put("nickName",rs.getString("nick_name"));
                row.put("avatar",rs.getString("avatar"));row.put("active","0".equals(rs.getString("status")));row.put("deleted",rs.getInt("del_flag")!=0);
                row.put("profileRevision",Long.toString(revision));row.put("updatedAt",updated.toInstant().toString());result.put(id,row);
            }
            return result;
        },ids.toArray());
    }
    @PostMapping("/auth/service-tokens")
    public ServiceTokens.Issued issue(@RequestBody(required=false) byte[] input,HttpServletRequest request) {
        String[] identity=basic(request);
        JsonNode root=body(input,Set.of("audience","permissions"));
        String audience;
        try {audience=ServicePolicy.text(root,"audience");}
        catch (Exception e) {throw new InternalFailure(400,"INVALID_ARGUMENT");}
        if (!ServiceTokenVerifier.validService(audience)) throw new InternalFailure(400,"INVALID_ARGUMENT");
        return tokens.issue(identity[0],identity[1],audience,permissions(root));
    }
    @GetMapping("/auth/keys")
    public Map<String,Object> keys(HttpServletRequest request) {service(request,"auth:keys:read");return tokens.jwks();}
    @PostMapping("/users/public-profiles:batch")
    public Map<String,Object> profiles(@RequestBody(required=false) byte[] input,HttpServletRequest request) {
        service(request,"users:public:read");JsonNode root=body(input,Set.of("userIds"));
        List<Long> ids=ids(root);var found=query(ids);List<Map<String,Object>> items=new ArrayList<>();
        for (Long id:ids) {
            var row=found.get(id);
            if (row==null || Boolean.TRUE.equals(row.get("deleted"))) {items.add(Map.of("userId",id.toString(),"status","MISSING"));continue;}
            var out=new LinkedHashMap<String,Object>();out.put("userId",id.toString());out.put("status","FOUND");
            for (String field:List.of("nickName","avatar","profileRevision","updatedAt")) out.put(field,row.get(field));
            items.add(out);
        }
        return Map.of("items",items);
    }
    @PostMapping("/users/validity:batch")
    public Map<String,Object> validity(@RequestBody(required=false) byte[] input,HttpServletRequest request) {
        service(request,"users:state:read");JsonNode root=body(input,Set.of("userIds","purpose"));
        if (!root.path("purpose").isTextual() || !Set.of("FOLLOW_TARGET","DELEGATION_CHECK").contains(root.path("purpose").textValue()))
            throw new InternalFailure(400,"INVALID_ARGUMENT");
        List<Long> ids=ids(root);var found=query(ids);List<Map<String,Object>> items=new ArrayList<>();
        for (Long id:ids) {
            var row=found.get(id);boolean exists=row!=null && !Boolean.TRUE.equals(row.get("deleted"));
            items.add(Map.of("userId",id.toString(),"exists",exists,"active",exists && Boolean.TRUE.equals(row.get("active"))));
        }
        return Map.of("items",items);
    }
}
