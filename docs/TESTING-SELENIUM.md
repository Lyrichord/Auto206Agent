# Selenium 前端功能测试

使用 **Selenium 4 + Chrome + JUnit 5** 对 `src/main/resources/static` 页面做浏览器级功能测试（非 HTTP 单元测试）。

## 前置条件

1. 安装 **Google Chrome** 浏览器。
2. 启动应用（另开终端）：

```powershell
cd D:\code\Auto206Agent
docker compose -f vector-database.yml up -d
mvn spring-boot:run
```

3. 浏览器可访问：http://localhost:9900

## 运行测试

```powershell
cd D:\code\Auto206Agent

# 默认无头模式（SELENIUM_HEADLESS=true）
mvn test "-Dtest=org.example.frontend.FrontendUiTest"

# 有界面模式（调试用）
$env:SELENIUM_HEADLESS="false"
mvn test "-Dtest=org.example.frontend.FrontendUiTest"

# 指定地址
mvn test -Dtest=FrontendUiTest -Dauto206.base.url=http://127.0.0.1:9900
```

## 用例说明

| 类 | 说明 | 依赖 |
|----|------|------|
| `FrontendUiTest` | 首页、导航、206 监控弹层、文献弹层、模式切换、空消息 | 仅需 Web 服务 |
| `FrontendChatSeleniumTest` | 真实发一条对话 | 需 `DASHSCOPE_API_KEY`，默认**跳过** |

启用对话测试：

```powershell
$env:RUN_SELENIUM_CHAT="true"
mvn test -Dtest=FrontendChatSeleniumTest
```

## 与 Postman / 接口测试的区别

| 方式 | 测什么 |
|------|--------|
| Selenium | 真实浏览器：按钮、弹层、DOM、前端逻辑 |
| curl / Postman | 仅 HTTP API，不覆盖 UI |

## 常见问题

| 现象 | 处理 |
|------|------|
| `Connection refused` | 先 `mvn spring-boot:run` |
| ChromeDriver 版本 | WebDriverManager 会自动下载 |
| `NoSuchMethodError` / Guava | 项目已在 `pom.xml` 统一 `selenium.version` 与 `guava`（jre），勿单独降级 |
| PowerShell 下 `-Dtest` 报错 | 给参数加引号：`"-Dtest=FrontendUiTest"` |
| 206 监控用例失败 | 检查 Prometheus 隧道；失败时弹层仍应显示错误文案 |
| 文献拉取超时 | 检查外网 arXiv/GitHub；或调大 `FrontendSeleniumSupport.DEFAULT_WAIT` |
