package asia.jppwl.bridge.tool;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;

/**
 * 原生轻量 Function Calling 工具处理器接口 (Native-Friendly Tool Handler).
 *
 * <p>零第三方 LLM 框架依赖，100% 免疫 GraalVM Native AOT 编译反射地雷。
 */
public interface ToolHandler {

    /**
     * 获取工具全局唯一标识名称 (例如 {@code getTodaySchedule}).
     */
    String getName();

    /**
     * 获取声明给 Google Gemini Live Setup 帧的 JSON Schema 规格声明.
     */
    JsonObject getDeclaration();

    /**
     * 异步执行具体的本地业务逻辑并返回结构化输出.
     *
     * @param arguments 模型解析下发的入参 JSON 字典
     * @return 包含业务输出的 JSON 对象
     */
    Uni<JsonObject> execute(JsonObject arguments);
}
