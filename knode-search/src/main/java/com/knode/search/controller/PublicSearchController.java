package com.knode.search.controller;

import com.knode.search.model.ApiResponse;
import com.knode.search.model.PageResult;
import com.knode.search.service.PublicArticleSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

@RestController
@RequestMapping({"/search", "/api/blog/search"})
public class PublicSearchController {
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final PublicArticleSearchService publicArticleSearchService;

    public PublicSearchController(PublicArticleSearchService publicArticleSearchService) {
        this.publicArticleSearchService = publicArticleSearchService;
    }

    @GetMapping
    public ApiResponse<PageResult> search(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        String sanitizedRequestId = sanitizeRequestId(requestId);
        PageResult result = publicArticleSearchService.search(keyword, pageNum, pageSize, sort, sanitizedRequestId);
        return ApiResponse.ok(result);
    }

    private static String sanitizeRequestId(String requestId) {
        if (requestId == null || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
            return null;
        }
        return requestId;
    }
}
