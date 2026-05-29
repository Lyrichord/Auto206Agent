package org.example.frontend;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前端功能 UI 测试（Selenium + Chrome）。
 * <p>
 * 前置：应用已启动（{@code mvn spring-boot:run}），Milvus/后端可用与否不影响本类多数用例。
 * 不调用大模型对话，避免依赖 DASHSCOPE_API_KEY。
 */
@Tag("selenium")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FrontendUiTest extends FrontendSeleniumSupport {

    @BeforeAll
    void startBrowser() {
        setUpDriver();
    }

    @AfterAll
    void stopBrowser() {
        tearDownDriver();
    }

    @BeforeEach
    void openFreshHome() {
        openHome();
    }

    @Test
    void homePageShowsTitleAndWelcome() {
        assertEquals("Auto206Agent", driver.getTitle());
        WebElement welcome = waitVisible(By.id("welcomeGreeting"));
        assertTrue(welcome.getText().contains("Auto206Agent"));
        assertTrue(welcome.getText().contains("206"));
    }

    @Test
    void mainNavigationButtonsAreVisible() {
        assertTrue(waitVisible(By.id("newChatBtn")).isDisplayed());
        assertTrue(driver.findElement(By.id("serverMonitorSidebarBtn")).isDisplayed());
        assertTrue(driver.findElement(By.id("researchFeedSidebarBtn")).isDisplayed());
        assertTrue(driver.findElement(By.id("serverMonitorBtn")).isDisplayed());
        assertTrue(driver.findElement(By.id("aiOpsSidebarBtn")).isDisplayed());
    }

    @Test
    void chatInputAndSendControlsPresent() {
        WebElement input = waitVisible(By.id("messageInput"));
        assertNotNull(input.getAttribute("placeholder"));
        assertTrue(driver.findElement(By.id("sendButton")).isEnabled());
        assertTrue(driver.findElement(By.id("uploadFileBtn")).isDisplayed());
        assertTrue(driver.findElement(By.id("chatImageBtn")).isDisplayed());
    }

    @Test
    void newChatButtonClearsMessagesAndCentersLayout() {
        driver.findElement(By.id("newChatBtn")).click();
        wait.until(d -> d.findElements(By.cssSelector("#chatMessages .message")).isEmpty());
        WebElement container = driver.findElement(By.cssSelector(".chat-container"));
        assertTrue(container.getAttribute("class").contains("centered"),
                "无消息时应保持居中布局并显示欢迎区");
        assertTrue(waitVisible(By.id("welcomeGreeting")).isDisplayed());
    }

    @Test
    void modeSelectorCanSwitchToStream() {
        driver.findElement(By.id("modeSelectorBtn")).click();
        waitVisible(By.cssSelector("#modeDropdown .dropdown-item[data-mode='stream']")).click();
        WebElement modeText = wait.until(d -> d.findElement(By.id("currentModeText")));
        assertEquals("流式", modeText.getText().trim());
    }

    @Test
    void serverMonitorModalOpensFromSidebarAndCloses() {
        driver.findElement(By.id("serverMonitorSidebarBtn")).click();
        waitUntilModalOpen("serverMonitorModal");
        assertTrue(driver.findElement(By.id("serverMonitorModalTitle")).getText().contains("206"));
        assertTrue(driver.findElement(By.cssSelector("#serverMonitorBody .server-monitor-loading")).isDisplayed());
        driver.findElement(By.id("serverMonitorCloseBtn")).click();
        waitUntilModalClosed("serverMonitorModal");
    }

    @Test
    void serverMonitorModalOpensFromTopBar() {
        driver.findElement(By.id("serverMonitorBtn")).click();
        waitUntilModalOpen("serverMonitorModal");
        driver.findElement(By.id("serverMonitorCloseBtn")).click();
        waitUntilModalClosed("serverMonitorModal");
    }

    @Test
    void researchFeedModalOpensAndShowsActions() {
        driver.findElement(By.id("researchFeedSidebarBtn")).click();
        waitUntilModalOpen("researchFeedModal");
        assertTrue(driver.findElement(By.id("researchFeedFetchBtn")).isDisplayed());
        assertTrue(driver.findElement(By.id("researchFeedMoreBtn")).isDisplayed());
        driver.findElement(By.id("researchFeedCloseBtn")).click();
        waitUntilModalClosed("researchFeedModal");
    }

    @Test
    void researchFeedFetchLoadsListOrShowsError() {
        driver.findElement(By.id("researchFeedSidebarBtn")).click();
        waitUntilModalOpen("researchFeedModal");
        driver.findElement(By.id("researchFeedFetchBtn")).click();
        wait.until(d -> {
            String list = d.findElement(By.id("researchFeedList")).getText();
            String hint = d.findElement(By.id("researchFeedHint")).getText();
            boolean listReady = !list.contains("正在请求") && list.length() > 5;
            boolean hintReady = !hint.isBlank();
            return listReady || hintReady;
        });
        String listText = driver.findElement(By.id("researchFeedList")).getText();
        assertFalse(listText.isBlank());
        driver.findElement(By.id("researchFeedCloseBtn")).click();
    }

    @Test
    void emptyMessageDoesNotAddUserBubble() {
        int before = driver.findElements(By.cssSelector("#chatMessages .message.user")).size();
        driver.findElement(By.id("messageInput")).clear();
        driver.findElement(By.id("sendButton")).click();
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int after = driver.findElements(By.cssSelector("#chatMessages .message.user")).size();
        assertEquals(before, after, "空消息不应产生用户气泡");
    }
}
