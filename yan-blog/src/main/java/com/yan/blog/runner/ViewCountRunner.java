package com.yan.blog.runner;

import com.yan.service.ArticleCounterService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ViewCountRunner implements CommandLineRunner {

    private final ArticleCounterService articleCounterService;

    public ViewCountRunner(ArticleCounterService articleCounterService) {
        this.articleCounterService = articleCounterService;
    }

    @Override
    public void run(String... args) {
        articleCounterService.initializeCounters();
    }
}
