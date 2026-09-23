package asia.jppwl.bridge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import asia.jppwl.bridge.api.feishu.FeishuWebhookResource;
import asia.jppwl.bridge.api.session.CallSessionResource;
import asia.jppwl.bridge.api.slack.SlackWebhookResource;
import asia.jppwl.bridge.config.TestBridgeConfig;
import asia.jppwl.bridge.domain.CreateSessionRequest;
import asia.jppwl.bridge.domain.SessionManager;
import io.vertx.core.json.JsonObject;
import jakarta.ws.rs.core.Response;

/**
 * 控制面 Webhook 与 REST 控制器单元测试.
 */
class WebhookAndApiResourceTest {

    private SessionManager sessionManager;
    private TestBridgeConfig testConfig;

    private CallSessionResource sessionResource;
    private SlackWebhookResource slackResource;
    private FeishuWebhookResource feishuResource;
    private WebCallPageResource webCallPageResource;

    @BeforeEach
    void setUp() {
        testConfig = TestBridgeConfig.createDefault();
        sessionManager = new SessionManager(testConfig);

        sessionResource = new CallSessionResource(sessionManager, testConfig);
        slackResource = new SlackWebhookResource(sessionManager, testConfig);
        feishuResource = new FeishuWebhookResource(sessionManager, testConfig);
        webCallPageResource = new WebCallPageResource();
    }

    @Test
    @DisplayName("测试 Session 创建 REST API 成功签发呼叫 URL 与 WS URL")
    void shouldCreateSessionViaRestApi() {
        CreateSessionRequest req = CreateSessionRequest.of("user_test", "web");
        Response response = sessionResource.createSession(req);

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body.get("code")).isEqualTo(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data.get("call_url").toString()).contains("https://voice.jppwl.asia/call?token=ses_live_");
        assertThat(data.get("ws_url").toString()).contains("wss://voice.jppwl.asia/ws/live/ses_live_");
    }

    @Test
    @DisplayName("测试 Slack /call 指令秒级回推 Block Kit 电话卡片")
    void shouldHandleSlackSlashCommand() {
        Response response = slackResource.handleCommand(
                "/call", "", "U0AM8G9AARF", "jason", "C0B2JA5PSKW"
        );

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body.get("response_type")).isEqualTo("ephemeral");
        assertThat(body.get("blocks")).isNotNull();
    }

    @Test
    @DisplayName("测试飞书 3 秒 URL Challenge 验证极速回传")
    void shouldRespondFeishuUrlVerificationChallengeImmediately() {
        JsonObject challengePayload = new JsonObject()
                .put("type", "url_verification")
                .put("challenge", "challenge_code_99999");

        Response response = feishuResource.handleEvent(challengePayload);

        assertThat(response.getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body.get("challenge")).isEqualTo("challenge_code_99999");
    }

    @Test
    @DisplayName("测试 H5 电话呼叫静态页面能够正常被资源加载器读出")
    void shouldLoadH5CallPageSuccessfully() {
        Response response = webCallPageResource.getCallPage();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isNotNull();
    }
}
