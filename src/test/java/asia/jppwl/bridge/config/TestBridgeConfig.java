package asia.jppwl.bridge.config;

import java.util.Optional;

/**
 * 基于 Java 21 Record 的零容器纯内存 {@link BridgeConfig} 测试替身 (Test Stub).
 *
 * <p>完全还原了生产契约中的 {@code @WithDefault} 保底默认值，
 * 使得各类业务单元测试在离线、无真实容器、无外部环境变量或 .env 文件的环境下，
 * 均可获得确定性的配置对象并实现毫秒级验证。
 */
public record TestBridgeConfig(
        String environment,
        String publicBaseUrl,
        TestGeminiConfig gemini,
        TestSessionConfig session,
        TestFeishuConfig feishu,
        TestSlackConfig slack
) implements BridgeConfig {

    public record TestGeminiConfig(
            String apiKey,
            String modelName,
            String voiceName,
            String systemInstruction
    ) implements BridgeConfig.GeminiConfig {}

    public record TestSessionConfig(
            String secretKey,
            int tokenTtlSeconds,
            int maxCallDurationSeconds,
            int idleTimeoutSeconds
    ) implements BridgeConfig.SessionConfig {}

    public record TestFeishuConfig(
            Optional<String> appId,
            Optional<String> appSecret,
            Optional<String> verificationToken,
            Optional<String> encryptKey
    ) implements BridgeConfig.FeishuConfig {}

    public record TestSlackConfig(
            Optional<String> botToken,
            Optional<String> signingSecret
    ) implements BridgeConfig.SlackConfig {}

    /**
     * 构建具备标准默认值的测试替身实例 (对齐 @WithDefault 注解值).
     */
    public static TestBridgeConfig createDefault() {
        return new TestBridgeConfig(
                "production",
                "https://voice.jppwl.asia",
                new TestGeminiConfig(
                        "AIzaSy_mock_test_key_for_unit_tests",
                        "gemini-3.8-live",
                        "Puck",
                        "你叫Hebe，是主人的专属贴心女仆与专业个人秘书。语言风格自然、温暖、干练，回答言简意赅。"
                ),
                new TestSessionConfig(
                        "mock_32_bytes_hex_session_secret_key_12345",
                        300,
                        1800,
                        120
                ),
                new TestFeishuConfig(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                ),
                new TestSlackConfig(
                        Optional.empty(),
                        Optional.empty()
                )
        );
    }

    /** 流式衍生方法：自定义替换 Gemini API Key */
    public TestBridgeConfig withGeminiApiKey(String newApiKey) {
        return new TestBridgeConfig(
                environment,
                publicBaseUrl,
                new TestGeminiConfig(newApiKey, gemini.modelName(), gemini.voiceName(), gemini.systemInstruction()),
                session,
                feishu,
                slack
        );
    }

    /** 流式衍生方法：自定义替换服务公网访问地址 */
    public TestBridgeConfig withPublicBaseUrl(String newUrl) {
        return new TestBridgeConfig(
                environment,
                newUrl,
                gemini,
                session,
                feishu,
                slack
        );
    }

    /** 流式衍生方法：自定义替换 Session 通话时长硬上限 */
    public TestBridgeConfig withMaxCallDurationSeconds(int seconds) {
        return new TestBridgeConfig(
                environment,
                publicBaseUrl,
                gemini,
                new TestSessionConfig(session.secretKey(), session.tokenTtlSeconds(), seconds, session.idleTimeoutSeconds()),
                feishu,
                slack
        );
    }
}
