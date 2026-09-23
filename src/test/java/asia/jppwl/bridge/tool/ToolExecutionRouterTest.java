package asia.jppwl.bridge.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import asia.jppwl.bridge.tool.handler.CalendarTools;
import asia.jppwl.bridge.tool.handler.SystemControlTools;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

/**
 * {@link ToolExecutionRouter} 单元测试：验证本地工具 Schema 自动抽取与无反射静态路由分发.
 */
class ToolExecutionRouterTest {

    private ToolExecutionRouter router;
    private CalendarTools calendarTools;
    private SystemControlTools systemTools;

    @BeforeEach
    void setUp() {
        calendarTools = new CalendarTools();
        systemTools = new SystemControlTools();

        // 构造简单的 Instance 测试替代实现
        List<ToolHandler> handlers = Arrays.asList(calendarTools, systemTools);
        Instance<ToolHandler> instanceStub = createInstanceStub(handlers);

        router = new ToolExecutionRouter(instanceStub);
    }

    @Test
    @DisplayName("测试工具声明集中提取：正确生成 Google Setup 帧所需的 JSON Schema 数组")
    void shouldBuildFunctionDeclarationsProperly() {
        assertThat(router.getToolCount()).isEqualTo(2);
        assertThat(router.hasTool("getTodaySchedule")).isTrue();
        assertThat(router.hasTool("queryClusterHealth")).isTrue();
        assertThat(router.hasTool("non_existent_tool")).isFalse();

        JsonArray declarations = router.buildFunctionDeclarations();
        assertThat(declarations).hasSize(2);

        JsonObject first = declarations.getJsonObject(0);
        assertThat(first.getString("name")).isIn("getTodaySchedule", "queryClusterHealth");
        assertThat(first.getString("description")).isNotBlank();
        assertThat(first.getJsonObject("parameters").getString("type")).isEqualTo("OBJECT");
    }

    @Test
    @DisplayName("测试成功分发执行 CalendarTools：返回日程列表与贴心总结")
    void shouldExecuteCalendarToolsSuccessfully() {
        JsonObject args = new JsonObject().put("userId", "Jason");

        JsonObject result = router.executeToolCall("getTodaySchedule", args).await().indefinitely();

        assertThat(result).isNotNull();
        assertThat(result.getString("status")).isEqualTo("success");
        assertThat(result.getString("user")).isEqualTo("Jason");
        assertThat(result.getInteger("totalEvents")).isEqualTo(3);
        assertThat(result.getString("summary")).contains("Gemini Live Bridge");
    }

    @Test
    @DisplayName("测试成功分发执行 SystemControlTools：返回 Kong 网关与跨云节点健康指标")
    void shouldExecuteSystemControlToolsSuccessfully() {
        JsonObject args = new JsonObject().put("clusterName", "tencent-dp1-cluster");

        JsonObject result = router.executeToolCall("queryClusterHealth", args).await().indefinitely();

        assertThat(result).isNotNull();
        assertThat(result.getString("status")).isEqualTo("HEALTHY");
        assertThat(result.getString("primaryGateway")).contains("Kong Gateway");
        assertThat(result.getString("activeNode")).contains("free-arm-vm");
        assertThat(result.getString("redisState")).contains("CONNECTED");
    }

    @Test
    @DisplayName("测试不存在的工具调用能够安全优雅降级")
    void shouldHandleUnknownToolGracefully() {
        JsonObject result = router.executeToolCall("unknown_magic_tool", new JsonObject()).await().indefinitely();

        assertThat(result).isNotNull();
        assertThat(result.getString("error")).isEqualTo("Function not found");
        assertThat(result.getString("function")).isEqualTo("unknown_magic_tool");
    }

    @SuppressWarnings("unchecked")
    private Instance<ToolHandler> createInstanceStub(List<ToolHandler> handlers) {
        return new Instance<>() {
            @Override
            public Iterator<ToolHandler> iterator() {
                return handlers.iterator();
            }

            @Override
            public ToolHandler get() {
                return handlers.get(0);
            }

            @Override
            public Instance<ToolHandler> select(java.lang.annotation.Annotation... qualifiers) {
                return this;
            }

            @Override
            public <U extends ToolHandler> Instance<U> select(Class<U> subtype, java.lang.annotation.Annotation... qualifiers) {
                return (Instance<U>) this;
            }

            @Override
            public <U extends ToolHandler> Instance<U> select(TypeLiteral<U> subtype, java.lang.annotation.Annotation... qualifiers) {
                return (Instance<U>) this;
            }

            @Override
            public boolean isUnsatisfied() {
                return handlers.isEmpty();
            }

            @Override
            public boolean isAmbiguous() {
                return handlers.size() > 1;
            }

            @Override
            public void destroy(ToolHandler instance) {
            }

            @Override
            public Handle<ToolHandler> getHandle() {
                return null;
            }

            @Override
            public Iterable<? extends Handle<ToolHandler>> handles() {
                return null;
            }
        };
    }
}
