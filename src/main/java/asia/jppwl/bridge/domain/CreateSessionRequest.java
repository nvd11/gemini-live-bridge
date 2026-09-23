package asia.jppwl.bridge.domain;

import java.time.Duration;

/**
 * 创建通话会话请求传输对象 (Request DTO).
 *
 * <p>封装从飞书 (Feishu)、Slack 或 Web 控制台发起 {@code /call} 指令时的上下文参数。
 *
 * @param userId            发起呼叫的用户唯一标识 (如 Slack User ID: {@code U0AM8G9AARF} 或飞书 Open ID)
 * @param platform          呼叫来源平台 ({@code feishu} / {@code slack} / {@code web})
 * @param channelId         发起会话的频道/群聊 ID (可为空)
 * @param modelVariant      指定的 Gemini 3.8 Live 模型变体 (若为空则自动回退至全局配置)
 * @param voiceName         指定的发音人音色 (如 {@code Puck}, 若为空则自动回退至全局配置)
 * @param tokenTtl          单次临时凭据有效时长 (若为空默认 5 分钟)
 * @param maxDuration       通话绝对硬上限时长 (若为空默认 30 分钟)
 */
public record CreateSessionRequest(
        String userId,
        String platform,
        String channelId,
        String modelVariant,
        String voiceName,
        Duration tokenTtl,
        Duration maxDuration
) {

    /**
     * 极简便捷工厂方法：仅需指定用户与平台.
     */
    public static CreateSessionRequest of(String userId, String platform) {
        return new CreateSessionRequest(userId, platform, null, null, null, null, null);
    }

    /**
     * 常用便捷工厂方法：包含群组/频道上下文.
     */
    public static CreateSessionRequest of(String userId, String platform, String channelId) {
        return new CreateSessionRequest(userId, platform, channelId, null, null, null, null);
    }
}
