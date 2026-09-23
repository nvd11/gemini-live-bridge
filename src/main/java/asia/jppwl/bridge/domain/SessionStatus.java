package asia.jppwl.bridge.domain;

/**
 * 通话会话生命周期状态机枚举.
 */
public enum SessionStatus {

    /**
     * 等待接入：临时 Token 已生成，用户在飞书/Slack 收到了呼叫卡片，尚未建立 WebSocket 握手。
     */
    PENDING,

    /**
     * 通话活跃中：客户端已通过 Token 完成 WSS 鉴权，且与 Google Gemini Live API 保持全双工双向推流。
     */
    ACTIVE,

    /**
     * 正常结束：用户主动点击挂断、或达到单次通话时长硬上限 (默认30分钟) 优雅终止。
     */
    TERMINATED,

    /**
     * 超时作废：Token 超过 5 分钟有效期未接入、或建立长连接后连续 2 分钟无任何上下行音频/心跳互动被熔断。
     */
    EXPIRED;

    /**
     * 判断当前会话是否处于不可再建立连接的终态 (Terminal State).
     */
    public boolean isTerminal() {
        return this == TERMINATED || this == EXPIRED;
    }

    /**
     * 判断当前会话是否处于正在通话的活跃状态.
     */
    public boolean isActive() {
        return this == ACTIVE;
    }
}
