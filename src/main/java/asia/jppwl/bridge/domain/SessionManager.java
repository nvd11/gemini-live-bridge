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
 * <p>特性：
 * <ul>
 *   <li><b>普通临时 Token：</b>5 分钟有效，握手建立时 CAS 原子出队核销（防重放）；</li>
 *   <li><b>主人专属永久 Token：</b>以 {@code permanent-} 或 {@code master-} 为前缀的特权 Token，
 *       不核销、不淘汰、永久有效，供主人随时收藏、多次连线与日常直接使用！</li>
 *   <li><b>定时扫盘看门狗：</b>基于 {@code @Scheduled(every = "30s")} 自动清理超期普通 Token。</li>
 * </ul>
 */
@ApplicationScoped
public class SessionManager {

    private static final Logger LOG = Logger.getLogger(SessionManager.class);

    /** 主人专属默认永久特权 Token */
    public static final String MASTER_PERMANENT_TOKEN = "permanent-gateman-voice-master";

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
     * 客户端建立 WebSocket 握手时原子校验并核销 Token.
     *
     * <p>特权机制：若是主人永久 Token (以 {@code permanent-} 或 {@code master-} 开头)，
     * 则不执行物理移除与过期判断，永久放行并动态生成活跃会话，支持主人无限次连接与收藏！
     */
    public Optional<CallSession> validateAndConsumeToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        // 🌟 1. 主人专属永久 Token 特权放行通道
        if (isPermanentToken(token)) {
            String sessionId = "call_perm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            CallSession permanentActive = new CallSession(
                    sessionId,
                    token,
                    "Jason",
                    "master-direct",
                    "cindy-vip",
                    config.gemini().modelName(),
                    config.gemini().voiceName(),
                    SessionStatus.ACTIVE,
                    Instant.now(),
                    Instant.now().plus(Duration.ofDays(3650)), // 10年不过期
                    Instant.now(),
                    Duration.ofHours(2), // 单次允许畅聊 2 小时
                    null,
                    null
            );
            activeSessions.put(sessionId, permanentActive);
            LOG.infof("Master permanent token validated! Created active VIP session: id=%s", sessionId);
            return Optional.of(permanentActive);
        }

        // 2. 普通单次 Token 原子出队核销 (CAS 防重放验证)
        CallSession pendingSession = tokenIndex.remove(token);
        if (pendingSession == null) {
            LOG.warnf("Rejecting connection: token not found or already consumed (Replay Attack blocked): %s", token);
            return Optional.empty();
        }

        // 3. 检查普通 Token 是否超时
        if (pendingSession.isTokenExpired()) {
            LOG.warnf("Rejecting connection: token has expired: id=%s, token=%s", pendingSession.sessionId(), token);
            return Optional.empty();
        }

        // 4. 状态跃迁至 ACTIVE 并纳入活跃会话索引
        CallSession activeSession = pendingSession.toActive();
        activeSessions.put(activeSession.sessionId(), activeSession);
        LOG.infof("Session activated: id=%s, user=%s, durationMax=%s",
                activeSession.sessionId(), activeSession.userId(), activeSession.maxDuration());

        return Optional.of(activeSession);
    }

    /**
     * 正常或异常挂断会话，清理内存索引并生成最终结算单.
     */
    public Optional<SessionSummary> terminateSession(String sessionId, String reason) {
        if (sessionId == null) {
            return Optional.empty();
        }

        CallSession active = activeSessions.remove(sessionId);
        if (active == null) {
            return Optional.empty();
        }

        CallSession terminated = active.toTerminal(SessionStatus.TERMINATED);
        SessionSummary summary = SessionSummary.fromSession(terminated, reason != null ? reason : "user_hangup");
        LOG.infof("Session terminated: id=%s, reason=%s, duration=%ds, uploaded=%d bytes, downloaded=%d bytes",
                sessionId, summary.reason(), summary.durationSeconds(), summary.bytesUploaded(), summary.bytesDownloaded());

        return Optional.of(summary);
    }

    /**
     * 判断是否为永久特权 Token.
     */
    public boolean isPermanentToken(String token) {
        if (token == null) return false;
        return token.startsWith("permanent-") || token.startsWith("master-") || token.equals(MASTER_PERMANENT_TOKEN);
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
     * 30 秒定时看门狗：清理超期未连线的僵尸 Token 以及超过通话时长硬上限的会话 (跳过永久 Token).
     */
    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void sweepExpiredSessions() {
        int sweptCount = 0;

        // 1. 扫描清理未接入且超期的 Token
        for (var entry : tokenIndex.entrySet()) {
            if (isPermanentToken(entry.getKey())) {
                continue;
            }
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
    }

    /**
     * 生成高强度 16 字节 (32-char Hex) 随机单次令牌 (运行时安全初始化).
     */
    private String generateSecureToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return "ses_live_" + HexFormat.of().formatHex(bytes);
    }
}
