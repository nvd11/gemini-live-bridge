package asia.jppwl.bridge.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import asia.jppwl.bridge.util.DotEnv;

/**
 * 验证 {@link DotEnv} 动态配置解析与优先级覆盖机制.
 */
class EnvVariableTest {

    @Test
    @DisplayName("测试能够正确解析标准的 .env 文件语法 (支持注释、首尾引号去除)")
    void shouldParseDotEnvFileCorrectly(@TempDir Path tempDir) throws IOException {
        Path envFile = tempDir.resolve(".env");
        String content = """
                # 这是一个注释行
                BRIDGE_PUBLIC_BASE_URL="https://test.jppwl.asia"
                BRIDGE_GEMINI_MODEL_NAME='gemini-3.8-live-extended-thinking'
                BRIDGE_SESSION_TOKEN_TTL_SECONDS=600
                EMPTY_LINE_FOLLOWS=true
                
                # 尾部注释
                """;
        Files.writeString(envFile, content);

        Map<String, String> parsed = DotEnv.loadFile(envFile);

        assertThat(parsed).containsEntry("BRIDGE_PUBLIC_BASE_URL", "https://test.jppwl.asia");
        assertThat(parsed).containsEntry("BRIDGE_GEMINI_MODEL_NAME", "gemini-3.8-live-extended-thinking");
        assertThat(parsed).containsEntry("BRIDGE_SESSION_TOKEN_TTL_SECONDS", "600");
        assertThat(parsed).containsEntry("EMPTY_LINE_FOLLOWS", "true");
        assertThat(parsed).doesNotContainKey("# 这是一个注释行");
    }

    @Test
    @DisplayName("测试不存在的 .env 文件能够安全返回空 Map 而不抛出异常")
    void shouldHandleNonExistentFileGracefully() {
        Map<String, String> result = DotEnv.loadFile(Path.of("non_existent_env_file_123.env"));
        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("测试 DotEnv.load() 能够正常装载并叠加上当前操作系统的环境变量")
    void shouldOverlaySystemEnvironmentVariables() {
        Map<String, String> env = DotEnv.load();

        // 验证当前运行用户环境存在
        assertThat(env).isNotEmpty();
        if (System.getenv("USER") != null) {
            assertThat(env).containsEntry("USER", System.getenv("USER"));
        }
    }
}
