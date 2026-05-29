package org.example;

import org.example.config.ChatIntentProperties;
import org.example.config.ChatMemoryProperties;
import org.example.config.HybridRetrievalProperties;
import org.example.config.KnowledgeProperties;
import org.example.config.ResearchFeedProperties;
import org.example.config.RagRerankProperties;
import org.example.config.SkillProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({ChatMemoryProperties.class, ChatIntentProperties.class, KnowledgeProperties.class,
        RagRerankProperties.class, HybridRetrievalProperties.class, ResearchFeedProperties.class, SkillProperties.class})
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}