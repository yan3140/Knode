package com.knode.content.upload;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class MultipartErrorAdvice {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String,Object>> tooLarge() { return UploadController.response(413,413); }
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String,Object>> malformed() { return UploadController.response(400,400); }
}
