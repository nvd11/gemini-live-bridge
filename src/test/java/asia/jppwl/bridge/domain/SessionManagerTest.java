package asia.jppwl.bridge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import asia.jppwl.bridge.config.TestBridgeConfig;

/**
 * {@link SessionManager} 核心领域服务单元测试 (纯内存无容器毫秒级验证).
 */
class SessionManagerTest {

    private SessionManager sessionManager;
    private TestBridgeConfig testConfig;

    @BeforeEach
    void setUp() {
        testConfig = TestBridgeConfig.createDefault();
        sessionManager = new SessionManager(testConfig);
    }

    @Test
    @DisplayName("测试正常创建会话：Token 具备 ses_live_ 前缀且处于 PENDING 队列")
    void shouldCreatePendingSessionCorrectly() {
        CreateSessionRequest req = CreateSessionRequest.of("U0AM8G9AARF", "slack", "C0B2JA5PSKW");
        CallSession session = sessionManager.createSession(req);

        assertThat(session).isNotNull();
        assertThat(session.sessionId()).startsWith("call_");
        assertThat(session.ephemeralToken()).startsWith("ses_live_");
        assertThat(session.userId()).isEqualTo("U0AM8G9AARF");
        assertThat(session.platform()).isEqualTo("slack");
        assertThat(session.channelId()).isEqualTo("C0B2JA5PSKW");
        assertThat(session.modelVariant()).isEqualTo("gemini-3.8-live");
        assertThat(session.voiceName()).isEqualTo("Puck");
        assertThat(session.status()).isEqualTo(SessionStatus.PENDING);

        assertThat(sessionManager.getPendingTokenCount()).isEqualTo(1);
        assertThat(sessionManager.getActiveSessionCount()).isZero();
    }

    @Test
    @DisplayName("测试 Token 单次原子核销与防重放攻击 (Anti-Replay Attack)")
    void shouldConsumeTokenAtomicallyAndPreventReplay() {
        CreateSessionRequest req = CreateSessionRequest.of("ou_12345", "feishu");
        CallSession session = sessionManager.createSession(req);
        String token = session.ephemeralToken();

        // 第一次连接尝试：核销成功，会话跃迁为 ACTIVE
        Optional<CallSession> firstAttempt = sessionManager.validateAndConsumeToken(token);
        assertThat(firstAttempt).isPresent();
        assertThat(firstAttempt.get().status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(sessionManager.getActiveSessionCount()).isEqualTo(1);
        assertThat(sessionManager.getPendingTokenCount()).isZero();

        // 第二次连接尝试 (重放攻击)：由于 Token 已被物理移除，直接返回 empty 拦截！
        Optional<CallSession> secondAttempt = sessionManager.validateAndConsumeToken(token);
        assertThat(secondAttempt).isEmpty();

        // 第三次尝试非法 Token：拦截
        assertThat(sessionManager.validateAndConsumeToken("invalid_fake_token")).isEmpty();
        assertThat(sessionManager.validateAndConsumeToken(null)).isEmpty();
    }

    @Test
    @DisplayName("测试主人专属永久 Token：无限次连线、不核销、永不被看门狗清理")
    void shouldSupportPermanentMasterToken() {
        String masterToken = SessionManager.MASTER_PERMANENT_TOKEN;

        // 第一次连接：直接放行
        Optional<CallSession> firstAttempt = sessionManager.validateAndConsumeToken(masterToken);
        assertThat(firstAttempt).isPresent();
        assertThat(firstAttempt.get().status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(firstAttempt.get().userId()).isEqualTo("Jason");

        // 第二次连接 (永久 Token 永不核销，随时可重连)：依然放行！
        Optional<CallSession> secondAttempt = sessionManager.validateAndConsumeToken(masterToken);
        assertThat(secondAttempt).isPresent();

        // 前缀式永久 Token 也同样放行
        Optional<CallSession> customPerm = sessionManager.validateAndConsumeToken("permanent-jason-vip");
        assertThat(customPerm).isPresent();
    }

    @Test
    @DisplayName("测试正常挂断会话：生成结算单并从活跃索引清理")
    void shouldTerminateSessionAndGenerateSummary() {
        CreateSessionRequest req = CreateSessionRequest.of("user_jason", "slack");
        CallSession session = sessionManager.createSession(req);
        CallSession active = sessionManager.validateAndConsumeToken(session.ephemeralToken()).orElseThrow();

        // 模拟上行推流 6400 字节，下行 9600 字节
        active.recordUploadBytes(6400);
        active.recordDownloadBytes(9600);

        // 用户主动挂断
        Optional<SessionSummary> summaryOpt = sessionManager.terminateSession(active.sessionId(), "user_hangup");
        assertThat(summaryOpt).isPresent();

        SessionSummary summary = summaryOpt.get();
        assertThat(summary.sessionId()).isEqualTo(active.sessionId());
        assertThat(summary.userId()).isEqualTo("user_jason");
        assertThat(summary.reason()).isEqualTo("user_hangup");
        assertThat(summary.bytesUploaded()).isEqualTo(6400L);
        assertThat(summary.bytesDownloaded()).isEqualTo(9600L);

        // 活跃索引归零
        assertThat(sessionManager.getActiveSessionCount()).isZero();
    }

    @Test
    @DisplayName("测试看门狗自动扫盘：清理超期未连线的僵尸 Token 与超时通话")
    void shouldSweepExpiredPendingTokensAndOverDurationSessions() {
        // 1. 注入一个只有 1 毫秒有效期的超短 Token
        CreateSessionRequest shortTtlReq = new CreateSessionRequest(
                "user_zombie", "web", null, null, null,
                Duration.ofMillis(1), Duration.ofMinutes(30)
        );
        CallSession zombieSession = sessionManager.createSession(shortTtlReq);
        assertThat(sessionManager.getPendingTokenCount()).isEqualTo(1);

        // 等待 10 毫秒让其自然过期
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {}

        // 执行看门狗扫盘
        sessionManager.sweepExpiredSessions();
        assertThat(sessionManager.getPendingTokenCount()).isZero();

        // 此时再去核销该 Token 必定失败
        assertThat(sessionManager.validateAndConsumeToken(zombieSession.ephemeralToken())).isEmpty();
    }
}
