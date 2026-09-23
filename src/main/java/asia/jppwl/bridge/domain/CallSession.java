package asia.jppwl.bridge.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通话会话核心领域实体 (Java 21 Record).
 *
 * <p>承载单次通话全生命周期的不可变元数据与高性能并发流量计数器。
 * 纯内存无数据库设计，生命周期由 {@code SessionManager} 统一调度。
 *
 * @param sessionId       全局唯一会话 ID (UUID 或包含日期的序列号，例如 {@code call_sess_20260921_0001})
 * @param ephemeralToken  用于单次 WSS 握手核销的短期加密防重放令牌 (5分钟有效)
 * @param userId          发起呼叫的用户平台唯一标识 (如 Slack User ID 或飞书 Open ID)
 * @param platform        触发呼叫的企业平台 ({@code feishu} / {@code slack} / {@code web})
 * @param channelId       来源群聊/会话频道 ID
 * @param modelVariant    使用的 Gemini 模型变体 (如 {@code gemini-3.8-live})
 * @param voiceName       指定的发音人音色 (如 {@code Puck})
 * @param status          当前会话状态机
 * @param createdAt       会话与 Token 创建时间戳
 * @param expiresAt       Token 有效期截止时间戳 (通常为 {@code createdAt + 5min})
 * @param connectedAt     客户端成功建立 WebSocket 握手的时间戳 (未连线时为 null)
 * @param maxDuration     该会话允许持续的最大通话时长硬上限 (默认 1800 秒)
 * @param bytesUploaded   客户端上行音频字节数累计器 (16kHz PCM 零拷贝上推)
 * @param bytesDownloaded 服务端下行音频字节数累计器 (24kHz PCM 播放分片)
 */
public record CallSession(
        String sessionId,
        String ephemeralToken,
        String userId,
        String platform,
        String channelId,
        String modelVariant,
        String voiceName,
        SessionStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant connectedAt,
        Duration maxDuration,
        AtomicLong bytesUploaded,
        AtomicLong bytesDownloaded
) {

    public CallSession {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(ephemeralToken, "ephemeralToken must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (maxDuration == null) {
            maxDuration = Duration.ofMinutes(30);
        }
        if (bytesUploaded == null) {
            bytesUploaded = new AtomicLong(0);
        }
        if (bytesDownloaded == null) {
            bytesDownloaded = new AtomicLong(0);
        }
    }

    /**
     * 初始工厂方法：构建处于 {@link SessionStatus#PENDING} 状态的全新会话实体.
     */
    public static CallSession createPending(
            String sessionId,
            String ephemeralToken,
            String userId,
            String platform,
            String channelId,
            String modelVariant,
            String voiceName,
            Duration tokenTtl,
            Duration maxDuration
    ) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenTtl != null ? tokenTtl : Duration.ofMinutes(5));
        return new CallSession(
                sessionId,
                ephemeralToken,
                userId,
                platform,
                channelId,
                modelVariant,
                voiceName,
                SessionStatus.PENDING,
                now,
                expiresAt,
                null,
                maxDuration != null ? maxDuration : Duration.ofMinutes(30),
                new AtomicLong(0),
                new AtomicLong(0)
        );
    }

    /**
     * 判断会话的单次临时 Token 是否已经超时过期.
     */
    public boolean isTokenExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /**
     * 判断当前进行中的通话是否已经触及单次通话时间绝对硬上限 (例如 30 分钟).
     */
    public boolean isMaxDurationExceeded() {
        if (connectedAt == null) {
            return false;
        }
        return Duration.between(connectedAt, Instant.now()).compareTo(maxDuration) >= 0;
    }

    /**
     * 计算当前实际通话持续时长 (秒). 未接通则返回 0.
     */
    public long getActiveDurationSeconds() {
        if (connectedAt == null) {
            return 0;
        }
        return Duration.between(connectedAt, Instant.now()).toSeconds();
    }

    /**
     * 累加上行音频字节数 (16kHz PCM).
     */
    public void recordUploadBytes(long bytes) {
        if (bytes > 0) {
            this.bytesUploaded.addAndGet(bytes);
        }
    }

    /**
     * 累加下行音频字节数 (24kHz PCM).
     */
    public void recordDownloadBytes(long bytes) {
        if (bytes > 0) {
            this.bytesDownloaded.addAndGet(bytes);
        }
    }

    /**
     * 状态跃迁：将处于 PENDING 状态的会话激活为 ACTIVE.
     */
    public CallSession toActive() {
        return new CallSession(
                sessionId,
                ephemeralToken,
                userId,
                platform,
                channelId,
                modelVariant,
                voiceName,
                SessionStatus.ACTIVE,
                createdAt,
                expiresAt,
                Instant.now(),
                maxDuration,
                bytesUploaded,
                bytesDownloaded
        );
    }

    /**
     * 状态跃迁：将会话置为指定终态 (TERMINATED 或 EXPIRED).
     */
    public CallSession toTerminal(SessionStatus terminalStatus) {
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("Target status must be terminal: " + terminalStatus);
        }
        return new CallSession(
                sessionId,
                ephemeralToken,
                userId,
                platform,
                channelId,
                modelVariant,
                voiceName,
                terminalStatus,
                createdAt,
                expiresAt,
                connectedAt,
                maxDuration,
                bytesUploaded,
                bytesDownloaded
        );
    }
}
