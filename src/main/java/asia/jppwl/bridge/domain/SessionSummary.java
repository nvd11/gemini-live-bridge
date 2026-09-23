package asia.jppwl.bridge.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 通话会话终止结果结算单 (Response / Result DTO).
 *
 * <p>当一次通话结束时 (用户主动挂断、空闲熔断或超时)，系统生成该结算单并返回，
 * 可用于记录日志、向飞书/Slack 异步回推通话总结卡片或触发下游纪要沉淀。
 *
 * @param sessionId        会话唯一 ID
 * @param userId           发起通话的用户标识
 * @param platform         来源平台 (feishu / slack / web)
 * @param reason           挂断或释放原因 (例如 {@code user_hangup}, {@code timeout}, {@code max_duration_exceeded})
 * @param startedAt        会话创建时间
 * @param connectedAt      WebSocket 正式接入时间 (若未连线则为 null)
 * @param endedAt          会话挂断释放时间
 * @param durationSeconds  实际语音对讲持续秒数
 * @param bytesUploaded    上行音频总流量字节数 (16kHz PCM)
 * @param bytesDownloaded  下行音频总流量字节数 (24kHz PCM)
 */
public record SessionSummary(
        String sessionId,
        String userId,
        String platform,
        String reason,
        Instant startedAt,
        Instant connectedAt,
        Instant endedAt,
        long durationSeconds,
        long bytesUploaded,
        long bytesDownloaded
) {

    public SessionSummary {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(endedAt, "endedAt must not be null");
    }

    /**
     * 从活跃的 {@link CallSession} 结算生成最终摘要.
     *
     * @param session 目标会话实体
     * @param reason  挂断原因
     * @return 完整的结算单
     */
    public static SessionSummary fromSession(CallSession session, String reason) {
        Instant now = Instant.now();
        return new SessionSummary(
                session.sessionId(),
                session.userId(),
                session.platform(),
                reason,
                session.createdAt(),
                session.connectedAt(),
                now,
                session.getActiveDurationSeconds(),
                session.bytesUploaded().get(),
                session.bytesDownloaded().get()
        );
    }
}
