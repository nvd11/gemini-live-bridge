package asia.jppwl.bridge.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 生产级无依赖轻量本地 .env 动态加载工具类.
 *
 * <p>行为模拟 Python 的 {@code python-dotenv} ({@code dotenv_values})，
 * 为本地单元测试、离线调试与脚本提供一致的动态配置字典读取能力。
 *
 * <p><b>覆盖规则：</b><br>
 * 真实 Linux/OS 环境变量（优先级 300）自动无缝覆盖 {@code .env} 中的同名变量（优先级 290），
 * 严格遵从云原生十二要素与 Quarkus 配置优先级。
 */
public final class DotEnv {

    private DotEnv() {
        // 工具类私有构造器
    }

    /**
     * 自动扫描候选路径下的 .env 文件并叠加上真实操作系统环境变量。
     *
     * @return 包含最终合并后配置项的不可变 Map 字典
     */
    public static Map<String, String> load() {
        Map<String, String> envMap = new HashMap<>();

        // 1. 扫描候选 .env 文件路径（适配根目录与子目录测试运行上下文）
        Path[] candidatePaths = new Path[] {
                Path.of(".env"),
                Path.of("../.env")
        };

        for (Path candidate : candidatePaths) {
            if (Files.exists(candidate)) {
                envMap.putAll(loadFile(candidate));
                break;
            }
        }

        // 2. 真实环境变量覆盖注入（高优先级）
        envMap.putAll(System.getenv());

        return Collections.unmodifiableMap(envMap);
    }

    /**
     * 将指定的 .env 文件内容解析为键值对 Map.
     *
     * @param path 目标 .env 路径
     * @return 不可变的键值对集合
     */
    public static Map<String, String> loadFile(Path path) {
        if (!Files.exists(path)) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new HashMap<>();
        try {
            for (String line : Files.readAllLines(path)) {
                line = line.trim();
                // 忽略空行与 # 注释行
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    String key = line.substring(0, eqIdx).trim();
                    String value = line.substring(eqIdx + 1).trim();
                    // 剥除首尾单双引号
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                            (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    result.put(key, value);
                }
            }
        } catch (IOException ignored) {
            // 静默忽略 I/O 异常，返回已成功解析的部分
        }
        return Collections.unmodifiableMap(result);
    }
}
