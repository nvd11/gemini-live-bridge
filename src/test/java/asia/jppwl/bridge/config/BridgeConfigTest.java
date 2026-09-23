package asia.jppwl.bridge.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BridgeConfig} 配置映射与默认值契约单元测试.
 */
class BridgeConfigTest {

    @Test
    @DisplayName("测试缺省测试替身具备正确的代码保底值 (@WithDefault 对齐)")
    void shouldHaveExpectedDefaults() {
        TestBridgeConfig config = TestBridgeConfig.createDefault();

        assertThat(config.environment()).isEqualTo("production");
        assertThat(config.publicBaseUrl()).isEqualTo("https://voice.jppwl.asia");

        // Gemini 组
        assertThat(config.gemini().modelName()).isEqualTo("gemini-3.8-live");
        assertThat(config.gemini().voiceName()).isEqualTo("Puck");
        assertThat(config.gemini().systemInstruction()).contains("Hebe");

        // Session 组
        assertThat(config.session().tokenTtlSeconds()).isEqualTo(300);
        assertThat(config.session().maxCallDurationSeconds()).isEqualTo(1800);
        assertThat(config.session().idleTimeoutSeconds()).isEqualTo(120);

        // IM 组 (缺省为空)
        assertThat(config.feishu().appId()).isEmpty();
        assertThat(config.feishu().appSecret()).isEmpty();
        assertThat(config.slack().botToken()).isEmpty();
    }

    @Test
    @DisplayName("测试流式衍生方法能够精准修改特定配置字段")
    void shouldSupportFluentMutation() {
        TestBridgeConfig original = TestBridgeConfig.createDefault();
        TestBridgeConfig mutated = original
                .withGeminiApiKey("AIzaSy_custom_key_for_test")
                .withMaxCallDurationSeconds(3600);

        assertThat(mutated.gemini().apiKey()).isEqualTo("AIzaSy_custom_key_for_test");
        assertThat(mutated.session().maxCallDurationSeconds()).isEqualTo(3600);

        // 原对象保持不可变
        assertThat(original.gemini().apiKey()).isEqualTo("AIzaSy_mock_test_key_for_unit_tests");
        assertThat(original.session().maxCallDurationSeconds()).isEqualTo(1800);
    }
}
