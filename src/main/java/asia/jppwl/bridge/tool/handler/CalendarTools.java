package asia.jppwl.bridge.tool.handler;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import asia.jppwl.bridge.tool.ToolHandler;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 主人日程查询与管理工具 (Calendar Tools).
 */
@ApplicationScoped
public class CalendarTools implements ToolHandler {

    public static final String TOOL_NAME = "getTodaySchedule";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public JsonObject getDeclaration() {
        JsonObject properties = new JsonObject();
        properties.put("userId", new JsonObject()
                .put("type", "STRING")
                .put("description", "主人的用户唯一标识，例如 Jason 或当前通话用户名"));

        JsonObject parameters = new JsonObject()
                .put("type", "OBJECT")
                .put("properties", properties);

        return new JsonObject()
                .put("name", TOOL_NAME)
                .put("description", "查询主人当天的日程会议、待办事项与重要行程安排。当主人询问‘今天有什么安排’或‘下午几点开会’时调用。")
                .put("parameters", parameters);
    }

    @Override
    public Uni<JsonObject> execute(JsonObject arguments) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String user = arguments != null ? arguments.getString("userId", "Jason") : "Jason";

        JsonArray events = new JsonArray()
                .add("10:00 - 架构设计评审与 GitOps 流水线走查")
                .add("15:00 - Gemini Live Bridge 全双工流媒体阶段性汇报")
                .add("19:00 - 晚餐与健身安排");

        JsonObject output = new JsonObject()
                .put("status", "success")
                .put("date", today)
                .put("user", user)
                .put("totalEvents", events.size())
                .put("events", events)
                .put("summary", "主人，您今天共有 3 项核心行程，其中下午 15:00 是 Gemini Live Bridge 的技术汇报。");

        return Uni.createFrom().item(output);
    }
}
