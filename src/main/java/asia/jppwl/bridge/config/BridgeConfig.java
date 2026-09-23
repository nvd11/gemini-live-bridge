package asia.jppwl.bridge.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * 强类型配置映射接口 (Quarkus @ConfigMapping).
 *
 * <p><b>自动映射推导算法 (MicroProfile / SmallRye Config 规范):</b><br>
 * Java 驼峰方法名与 Linux/Kubernetes POSIX 环境变量之间的双向绑定完全由编译器与框架自动完成，
 * 无需手动维护任何转换表：
 * <ol>
 *   <li><b>CamelCase 转 kebab-case:</b> {@code publicBaseUrl} &rarr; {@code public-base-url},
 *       {@code maxCallDurationSeconds} &rarr; {@code max-call-duration-seconds}。</li>
 *   <li><b>前缀与分组拼装:</b> prefix {@code "bridge"} + 嵌套子接口名 {@code "gemini"} 形成标准点号键
 *       {@code bridge.gemini.api-key}。</li>
 *   <li><b>POSIX 环境变量规范转换:</b> 将点号与连字符全部替换为下划线并全大写：
 *       {@code bridge.gemini.api-key} &rarr; {@code BRIDGE_GEMINI_API_KEY}。</li>
 * </ol>
 *
 * <p><b>配置优先级层级 (优先级由低到高覆盖):</b>
 * <pre>
 *   1. {@link WithDefault} 固化在方法签名的代码保底值       (Priority 100)
 *   2. 打包在镜像内部的 application.properties 基础配置    (Priority 250)
 *   3. 本地脱敏配置文件 .env (被 .gitignore 排除)          (Priority 290)
 *   4. K3s ConfigMap / Secret (POSIX UPPER_UNDERSCORE)    (Priority 300 - 生产权威)
 * </pre>
 *
 * <p><b>启动期防呆熔断 (Fail-Fast Validation):</b><br>
 * 未标注 {@link WithDefault} 的核心配置项（如 {@link #publicBaseUrl()}、{@link GeminiConfig#apiKey()}）
 * 为绝对必填项。若任何环境均未提供，Quarkus 会在启动第一毫秒抛出 {@code ConfigValidationException} 熔断，
 * 绝不让半生不熟的脏配置带病运行。
 */
@ConfigMapping(prefix = "bridge")
public interface BridgeConfig {

    /**
     * 部署环境标识 (dev / production).
     * 映射自 {@code bridge.environment} 或环境变量 {@code BRIDGE_ENVIRONMENT}.
     */
    @WithDefault("production")
    String environment();

    /**
     * 服务公网 HTTPS 访问基础 URL (用于生成 H5 呼叫卡片外链与 WebSocket 端点),
     * 例如 {@code https://voice.jppwl.asia}.
     * 映射自 {@code bridge.public-base-url} 或环境变量 {@code BRIDGE_PUBLIC_BASE_URL}.
     * <p><b>必填项 (Fail-Fast)</b>：缺失将阻止服务启动。</p>
     */
    String publicBaseUrl();

    /** Google Gemini 3.8 Live API 配置组. 对应 {@code bridge.gemini.*} / {@code BRIDGE_GEMINI_*}. */
    GeminiConfig gemini();

    /** 会话鉴权与通话生命周期保护组. 对应 {@code bridge.session.*} / {@code BRIDGE_SESSION_*}. */
    SessionConfig session();

    /** 飞书开放平台配置组. 对应 {@code bridge.feishu.*} / {@code BRIDGE_FEISHU_*}. */
    FeishuConfig feishu();

    /** Slack 应用配置组. 对应 {@code bridge.slack.*} / {@code BRIDGE_SLACK_*}. */
    SlackConfig slack();

    // ---------------------------------------------------------------------
    // 嵌套子配置契约 (Nested Config Groups)
    // ---------------------------------------------------------------------

    interface GeminiConfig {

        /**
         * Google AI Studio / Vertex API Key.
         * 映射自 {@code BRIDGE_GEMINI_API_KEY}. <b>必填项 (Fail-Fast)</b>.
         * 生产环境严禁入库，由 OCI Vault 备份并由 kubectl patch 注入至 K3s Secret.
         */
        String apiKey();

        /**
         * 实时音频大模型标识. 映射自 {@code BRIDGE_GEMINI_MODEL_NAME}.
         * 默认 {@code gemini-3.8-live}，备选思考模型 {@code gemini-3.8-live-extended-thinking}.
         */
        @WithDefault("gemini-3.8-live")
        String modelName();

        /**
         * 预设发音人声线. 映射自 {@code BRIDGE_GEMINI_VOICE_NAME}.
         * 可选发音人: Aoede, Charon, Fenrir, Kore, Puck. 默认 {@code Puck}.
         */
        @WithDefault("Puck")
        String voiceName();

        /**
         * 握手 Setup 帧注入的智能体人设提示词 (System Instruction).
         * 映射自 {@code BRIDGE_GEMINI_SYSTEM_INSTRUCTION}.
         */
        @WithDefault("你叫Hebe，是主人的专属贴心女仆与专业个人秘书。语言风格自然、温暖、干练，回答言简意赅。")
        String systemInstruction();
    }

    interface SessionConfig {

        /**
         * 用于对单次临时通话令牌 (JWT) 进行签名的 32 字节 Hex 密钥.
         * 映射自 {@code BRIDGE_SESSION_SECRET_KEY}. <b>必填项 (Fail-Fast)</b>.
         */
        String secretKey();

        /** 临时 Token 有效期 (秒). 映射自 {@code BRIDGE_SESSION_TOKEN_TTL_SECONDS}. 默认 300 (5分钟). */
        @WithDefault("300")
        int tokenTtlSeconds();

        /** 单次通话绝对硬上限时长 (秒). 映射自 {@code BRIDGE_SESSION_MAX_CALL_DURATION_SECONDS}. 默认 1800 (30分钟). */
        @WithDefault("1800")
        int maxCallDurationSeconds();

        /** 静音空闲熔断阈值 (秒). 映射自 {@code BRIDGE_SESSION_IDLE_TIMEOUT_SECONDS}. 默认 120 (2分钟无互动断连停计费). */
        @WithDefault("120")
        int idleTimeoutSeconds();
    }

    interface FeishuConfig {

        /** 飞书应用 App ID (cli_xxx). 映射自 {@code BRIDGE_FEISHU_APP_ID}. 选填. */
        Optional<String> appId();

        /** 飞书应用 App Secret. 映射自 {@code BRIDGE_FEISHU_APP_SECRET}. 选填. */
        Optional<String> appSecret();

        /** 飞书事件订阅校验 Token. 映射自 {@code BRIDGE_FEISHU_VERIFICATION_TOKEN}. 选填. */
        Optional<String> verificationToken();

        /** 飞书事件解密 Encrypt Key. 映射自 {@code BRIDGE_FEISHU_ENCRYPT_KEY}. 选填. */
        Optional<String> encryptKey();
    }

    interface SlackConfig {

        /** Slack Bot OAuth Token (xoxb-...). 映射自 {@code BRIDGE_SLACK_BOT_TOKEN}. 选填. */
        Optional<String> botToken();

        /** Slack Slash Command 签名校验密钥. 映射自 {@code BRIDGE_SLACK_SIGNING_SECRET}. 选填. */
        Optional<String> signingSecret();
    }
}
