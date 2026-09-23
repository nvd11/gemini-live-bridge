package asia.jppwl.bridge.domain;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jboss.logging.Logger;

import asia.jppwl.bridge.config.BridgeConfig;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 会话生命周期与安全防重放核心领域服务 (Domain Service / CDI Bean).
 *
 * <p>在无持久化数据库的纯内存架构下，负责会话安全鉴权、单次 Token 核销以及多维度看门狗管理：
 * <ul>
 *   <li><b>单次 Token 防重放：</b>客户端握手时原子核销 Token，确保一个 URL 绝对无法被二次复用；</li>
 *   <li><b>双向高并发索引：</b>{@code tokenIndex}（Token &rarr; Session）与 {@code activeSessions}（SessionId &rarr; Session）；</li>
 *   <li><b>定时扫盘看门狗：</b>基于 {@code @Scheduled(every = "30s")} 自动清理超期未接入或超过通话硬上限的僵尸会话。</li>
 * </ul>
 */
@ApplicationScoped
public class SessionManager {

    private static final Logger LOG = Logger.getLogger(SessionManager.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BridgeConfig config;

    /** Token 到会话实体的映射 (包含 PENDING 与刚激活的会话) */
    private final ConcurrentMap<String, CallSession> tokenIndex = new ConcurrentHashMap<>();

    /** 活跃通话会话映射 (仅包含 ACTIVE 状态的会话，以 sessionId 为主键) */
    private final ConcurrentMap<String, CallSession> activeSessions = new ConcurrentHashMap<>();

    @Inject
    public SessionManager(BridgeConfig config) {
        this.config = config;
    }

    /**
     * 签发全新的通话会话并生成有效期 5 分钟的一次性 Token.
     *
     * @param req 呼叫请求 DTO
     * @return 处于 {@link SessionStatus#PENDING} 状态的会话实体
     */
    public CallSession createSession(CreateSessionRequest req) {
        String sessionId = "call_" + UUID.randomUUID().toString().replace("-", "");
        String token = generateSecureToken();

        Duration tokenTtl = req.tokenTtl() != null
                ? req.tokenTtl()
                : Duration.ofSeconds(config.session().tokenTtlSeconds());

        Duration maxDuration = req.maxDuration() != null
                ? req.maxDuration()
                : Duration.ofSeconds(config.session().maxCallDurationSeconds());

        String modelVariant = (req.modelVariant() != null && !req.modelVariant().isBlank())
                ? req.modelVariant()
                : config.gemini().modelName();

        String voiceName = (req.voiceName() != null && !req.voiceName().isBlank())
                ? req.voiceName()
                : config.gemini().voiceName();

        CallSession session = CallSession.createPending(
                sessionId,
                token,
                req.userId(),
                req.platform(),
                req.channelId(),
                modelVariant,
                voiceName,
                tokenTtl,
                maxDuration
        );

        tokenIndex.put(token, session);
        LOG.infof("Created pending session: id=%s, user=%s, platform=%s, expiresAt=%s",
                sessionId, req.userId(), req.platform(), session.expiresAt());
        return session;
    }

    /**
     * 客户端建立 WebSocket 握手时原子校验并核销 Token (CAS 防重放操作).
     *
     * <p>若 Token 不存在、已过期或已被其他连接核销，立即返回 {@link Optional#empty()}，
     * 驱动网关层下发 4001 Close Frame 并无情阻断非法连接。
     *
     * @param token 客户端 URL path 携带的单次凭证
     * @return 成功激活跃迁为 {@link SessionStatus#ACTIVE} 的会话实体，或空
     */
    public Optional<CallSession> validateAndConsumeToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        // 1. 原子性从 tokenIndex 中移除该 Token，保证全局只能消费一次 (Single-Use Guarantee)
        CallSession pendingSession = tokenIndex.remove(token);
        if (pendingSession == null) {
            LOG.warnf("Rejecting connection: token not found or already consumed (Replay Attack blocked): %s", token);
            return Optional.empty();
        }

        // 2. 检查 Token 是否已超时
        if (pendingSession.isTokenExpired()) {
            LOG.warnf("Rejecting connection: token has expired: id=%s, token=%s", pendingSession.sessionId(), token);
            return Optional.empty();
        }

        // 3. 状态跃迁至 ACTIVE 并纳入活跃会话索引
        CallSession activeSession = pendingSession.toActive();
        activeSessions.put(activeSession.sessionId(), activeSession);
        LOG.infof("Session activated: id=%s, user=%s, durationMax=%s",
                activeSession.sessionId(), activeSession.userId(), activeSession.maxDuration());

        return Optional.of(activeSession);
    }

    /**
     * 正常或异常挂断会话，清理内存索引并生成最终结算单.
     *
     * @param sessionId 目标会话 ID
     * @param reason    挂断原因 (如 {@code user_hangup}, {@code timeout}, {@code max_duration_exceeded})
     * @return 结算响应 DTO (若会话不存在则返回空 Optional)
     */
    public Optional<SessionSummary> terminateSession(String sessionId, String reason) {
        if (sessionId == null) {
            return Optional.empty();
        }

        CallSession active = activeSessions.remove(sessionId);
        if (active == null) {
            // 尝试在 tokenIndex 查找未连线但被取消的会话
            return Optional.empty();
        }

        CallSession terminated = active.toTerminal(SessionStatus.TERMINATED);
        SessionSummary summary = SessionSummary.fromSession(terminated, reason != null ? reason : "user_hangup");
        LOG.infof("Session terminated: id=%s, reason=%s, duration=%ds, uploaded=%d bytes, downloaded=%d bytes",
                sessionId, summary.reason(), summary.durationSeconds(), summary.bytesUploaded(), summary.bytesDownloaded());

        return Optional.of(summary);
    }

    /**
     * 根据会话 ID 获取当前活跃会话.
     */
    public Optional<CallSession> getActiveSession(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeSessions.get(sessionId));
    }

    /**
     * 获取当前活跃通话数.
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * 获取当前待连线 Token 队列长度.
     */
    public int getPendingTokenCount() {
        return tokenIndex.size();
    }

    /**
     * 30 秒定时看门狗：清理超期未连线的僵尸 Token 以及超过通话时长硬上限的会话.
     *
     * @return 本轮扫盘清理的过期实体总数
     */
    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public int sweepExpiredSessions() {
        int sweptCount = 0;

        // 1. 扫描清理未接入且超期的 Token
        for (var entry : tokenIndex.entrySet()) {
            if (entry.getValue().isTokenExpired()) {
                if (tokenIndex.remove(entry.getKey(), entry.getValue())) {
                    sweptCount++;
                    LOG.debugf("Watchdog swept expired pending token: id=%s", entry.getValue().sessionId());
                }
            }
        }

        // 2. 扫描清理超出单次最大通话硬时限的活跃会话
        for (var entry : activeSessions.entrySet()) {
            if (entry.getValue().isMaxDurationExceeded()) {
                terminateSession(entry.getKey(), "max_duration_exceeded");
                sweptCount++;
                LOG.warnf("Watchdog terminated session exceeding max duration: id=%s", entry.getKey());
            }
        }

        if (sweptCount > 0) {
            LOG.infof("Watchdog cycle completed: cleaned up %d expired sessions/tokens.", sweptCount);
        }
        return sweptCount;
    }

    /**
     * 生成高强度 16 字节 (32-char Hex) 随机单次令牌.
     */
    private String generateSecureToken() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return "ses_live_" + HexFormat.of().formatHex(bytes);
    }
}
