package asia.jppwl.bridge.tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * 原生轻量 Function Calling 工具执行路由器 (Tool Execution Router).
 *
 * <p>核心职责：
 * <ol>
 *   <li>在 Setup 握手期，自动收集所有 CDI 注入的 {@link ToolHandler}，静态拼装为符合 Google 规范的 JSON Schema 声明；</li>
 *   <li>在通话过程中，接收 Google Gemini 吐出的 {@code toolCall} 决策，按方法名无反射静态分发执行，并回填结构化结果。</li>
 * </ol>
 */
@ApplicationScoped
public class ToolExecutionRouter {

    private static final Logger LOG = Logger.getLogger(ToolExecutionRouter.class);

    private final Map<String, ToolHandler> handlerMap = new ConcurrentHashMap<>();

    @Inject
    public ToolExecutionRouter(Instance<ToolHandler> handlers) {
        for (ToolHandler handler : handlers) {
            handlerMap.put(handler.getName(), handler);
            LOG.infof("Registered native tool handler: %s", handler.getName());
        }
    }

    /**
     * 构建所有已注册工具的 Function Declarations 数组 (注入 Google Setup 帧).
     */
    public JsonArray buildFunctionDeclarations() {
        JsonArray array = new JsonArray();
        for (ToolHandler handler : handlerMap.values()) {
            array.add(handler.getDeclaration());
        }
        return array;
    }

    /**
     * 静态分发执行目标工具方法.
     *
     * @param functionName 调用的工具函数名
     * @param arguments    模型下发的参数 JSON 对象
     * @return 异步执行结果 Uni&lt;JsonObject&gt;
     */
    public Uni<JsonObject> executeToolCall(String functionName, JsonObject arguments) {
        if (functionName == null || !handlerMap.containsKey(functionName)) {
            LOG.warnf("Tool function not found: %s", functionName);
            JsonObject notFound = new JsonObject()
                    .put("error", "Function not found")
                    .put("function", functionName);
            return Uni.createFrom().item(notFound);
        }

        ToolHandler handler = handlerMap.get(functionName);
        LOG.infof("Dispatching tool call to %s with args: %s", functionName, arguments);

        return handler.execute(arguments != null ? arguments : new JsonObject())
                .onFailure().recoverWithItem(err -> {
                    LOG.errorf(err, "Tool execution failed for %s", functionName);
                    return new JsonObject()
                            .put("error", "Execution failed")
                            .put("message", err.getMessage());
                });
    }

    /**
     * 检查是否注册了指定名称的工具.
     */
    public boolean hasTool(String functionName) {
        return functionName != null && handlerMap.containsKey(functionName);
    }

    /**
     * 获取当前已注册的工具总数.
     */
    public int getToolCount() {
        return handlerMap.size();
    }
}
