package org.example.frontend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openqa.selenium.By;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需要后端对话 API 与 DASHSCOPE_API_KEY 的 Selenium 用例（默认跳过）。
 * <p>
 * 运行：{@code set RUN_SELENIUM_CHAT=true} 后 {@code mvn test -Dtest=FrontendChatSeleniumTest}
 */
@Tag("selenium")
@EnabledIfEnvironmentVariable(named = "RUN_SELENIUM_CHAT", matches = "true")
class FrontendChatSeleniumTest extends FrontendSeleniumSupport {

    @BeforeEach
    void setUp() {
        setUpDriver();
        openHome();
        driver.findElement(By.id("newChatBtn")).click();
    }

    @AfterEach
    void tearDown() {
        tearDownDriver();
    }

    @Test
    void quickModeChatShowsAssistantReply() {
        var input = waitVisible(By.id("messageInput"));
        input.sendKeys("你好，请用一句话介绍你自己");
        driver.findElement(By.id("sendButton")).click();
        wait.withTimeout(java.time.Duration.ofSeconds(120)).until(d -> {
            var messages = d.findElements(By.cssSelector("#chatMessages .message.assistant"));
            if (messages.isEmpty()) {
                return false;
            }
            String text = messages.get(messages.size() - 1).getText();
            return text != null && text.length() > 8;
        });
        String reply = driver.findElements(By.cssSelector("#chatMessages .message.assistant"))
                .get(driver.findElements(By.cssSelector("#chatMessages .message.assistant")).size() - 1)
                .getText();
        assertTrue(reply.length() > 8);
    }
}
