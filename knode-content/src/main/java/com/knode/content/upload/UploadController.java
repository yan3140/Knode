package com.knode.content.upload;

import com.knode.auth.AuthFailure;
import com.knode.content.admin.AdminGuard;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class UploadController {
    private static final int MAX_BYTES=1_048_576;
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private final AdminGuard guard;
    private final UploadStorage storage;
    private final UploadSettings settings;
    public UploadController(AdminGuard guard,UploadStorage storage,UploadSettings settings) {
        this.guard=guard; this.storage=storage; this.settings=settings;
    }

    @PostMapping({"/api/admin/upload","/upload"})
    public Map<String,Object> upload(HttpServletRequest request) throws IOException,ServletException {
        guard.require(request,"content:upload:add","content:article:add","content:article:edit");
        if (!settings.enabled()) throw new UploadFailure(503,500);
        try {
            MediaType type=MediaType.parseMediaType(request.getContentType());
            String boundary=type.getParameter("boundary");
            if (!"multipart".equalsIgnoreCase(type.getType()) || !"form-data".equalsIgnoreCase(type.getSubtype())
                    || boundary==null || boundary.isBlank() || boundary.length()>70) throw new UploadFailure(400,400);
        } catch (IllegalArgumentException invalid) {
            throw new UploadFailure(400,400);
        }
        Collection<Part> parts=request.getParts();
        if (parts.size()!=1) throw new UploadFailure(400,400);
        Part part=parts.iterator().next();
        if (!"img".equals(part.getName()) || part.getSubmittedFileName()==null || part.getSubmittedFileName().isEmpty())
            throw new UploadFailure(400,400);
        if (part.getSize()>MAX_BYTES) throw new UploadFailure(413,413);
        byte[] bytes;
        try (var in=part.getInputStream()) { bytes=in.readNBytes(MAX_BYTES+1); }
        if (bytes.length>MAX_BYTES) throw new UploadFailure(413,413);
        String contentType=ImageVerifier.verify(bytes);
        String extension="image/png".equals(contentType)?".png":".jpg";
        String objectName=DATE.format(Instant.now())+"/"+UUID.randomUUID().toString().replace("-","")+extension;
        String url;
        try { url=storage.put(objectName,contentType,bytes); }
        catch (RuntimeException unavailable) { throw new UploadFailure(503,500); }
        if (url==null || url.isBlank()) throw new UploadFailure(503,500);
        return Map.of("code",200,"msg","\u4e0a\u4f20\u6210\u529f","data",url);
    }
    @ExceptionHandler(AuthFailure.class)
    public ResponseEntity<Map<String,Object>> auth(AuthFailure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code",failure.status()==503?500:failure.status(),"msg","Authentication failed"));
    }
    @ExceptionHandler(UploadFailure.class)
    public ResponseEntity<Map<String,Object>> error(UploadFailure failure) {
        return response(failure.status(),failure.code());
    }
    @ExceptionHandler({IOException.class,ServletException.class})
    public ResponseEntity<Map<String,Object>> invalidMultipart() { return response(400,400); }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String,Object>> systemFailure() { return response(500,500); }
    static ResponseEntity<Map<String,Object>> response(int status,int code) {
        String message=code==507?"Invalid image":code==413?"Upload too large":code==400?"Invalid request":"Upload unavailable";
        return ResponseEntity.status(status).body(Map.of("code",code,"msg",message));
    }
}
