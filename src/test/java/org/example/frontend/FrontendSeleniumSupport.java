package org.example.frontend;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

/**
 * Selenium 前端测试公共配置：连接已运行的 Auto206Agent（默认 http://localhost:9900）。
 */
abstract class FrontendSeleniumSupport {

    static final Duration DEFAULT_WAIT = Duration.ofSeconds(15);

    WebDriver driver;
    WebDriverWait wait;
    String baseUrl;

    void setUpDriver() {
        String configured = System.getProperty("auto206.base.url");
        if (configured == null || configured.isBlank() || configured.contains("${")) {
            baseUrl = "http://localhost:9900";
        } else {
            baseUrl = configured.trim().replaceAll("/$", "");
        }
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        if (!"false".equalsIgnoreCase(System.getenv().getOrDefault("SELENIUM_HEADLESS", "true"))) {
            options.addArguments("--headless=new", "--window-size=1440,900");
        }
        options.addArguments("--disable-gpu", "--no-sandbox", "--lang=zh-CN");
        driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(2));
        wait = new WebDriverWait(driver, DEFAULT_WAIT);
    }

    void tearDownDriver() {
        if (driver != null) {
            driver.quit();
        }
    }

    void openHome() {
        driver.get(baseUrl + "/");
    }

    WebElement waitVisible(By locator) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    boolean isModalOpen(String modalId) {
        WebElement modal = driver.findElement(By.id(modalId));
        String hidden = modal.getAttribute("aria-hidden");
        String display = modal.getCssValue("display");
        return "false".equals(hidden) && display != null && !display.equals("none");
    }

    void waitUntilModalOpen(String modalId) {
        wait.until(d -> isModalOpen(modalId));
    }

    void waitUntilModalClosed(String modalId) {
        wait.until(d -> !isModalOpen(modalId));
    }
}
