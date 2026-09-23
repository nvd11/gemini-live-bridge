package asia.jppwl.bridge.api.feishu;

import java.util.Map;

import org.jboss.logging.Logger;

import asia.jppwl.bridge.config.BridgeConfig;
import asia.jppwl.bridge.domain.CallSession;
import asia.jppwl.bridge.domain.CreateSessionRequest;
import asia.jppwl.bridge.domain.SessionManager;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * 飞书 (Feishu / Lark) 开放平台事件与卡片回调控制器.
 *
 * <p>核心红线：
 * <ul>
 *   <li>首次接入配置时的 URL 验证（{@code type: "url_verification"}）必须在 3.0 秒内同步回传 {@code challenge}；</li>
 *   <li>用户触发指令或点击卡片时，签发 Token 并返回轻量响应。</li>
 * </ul>
 */
@Path("/api/v1/feishu")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class FeishuWebhookResource {

    private static final Logger LOG = Logger.getLogger(FeishuWebhookResource.class);

    private final SessionManager sessionManager;
    private final BridgeConfig config;

    @Inject
    public FeishuWebhookResource(SessionManager sessionManager, BridgeConfig config) {
        this.sessionManager = sessionManager;
        this.config = config;
    }

    /**
     * 飞书事件与指令回调入口 (Event Webhook).
     */
    @POST
    @Path("/event")
    public Response handleEvent(JsonObject payload) {
        if (payload == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        // 1. 【飞书 3 秒极速挑战红线】顶层直接拦截并同步回传 challenge
        if ("url_verification".equals(payload.getString("type"))) {
            String challenge = payload.getString("challenge");
            LOG.infof("Feishu URL verification challenge received: %s", challenge);
            return Response.ok(Map.of("challenge", challenge != null ? challenge : "")).build();
        }

        // 2. 处理普通事件或消息接收 (如用户发了 /call)
        JsonObject header = payload.getJsonObject("header");
        String eventType = header != null ? header.getString("event_type") : "unknown";
        LOG.infof("Received Feishu event: %s", eventType);

        // 默认快速返回成功回执 (避免飞书服务端重试)
        return Response.ok(Map.of("code", 0, "msg", "success")).build();
    }

    /**
     * 飞书交互卡片按钮点击回调 (Card Action Callback).
     */
    @POST
    @Path("/card-action")
    public Response handleCardAction(JsonObject payload) {
        if (payload == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        String openId = payload.getString("open_id", "feishu_user");
        LOG.infof("Feishu card action clicked by openId=%s", openId);

        // 签发通话会话
        CreateSessionRequest req = CreateSessionRequest.of(openId, "feishu");
        CallSession session = sessionManager.createSession(req);
        String callUrl = config.publicBaseUrl() + "/call?token=" + session.ephemeralToken();

        Map<String, Object> response = Map.of(
                "toast", Map.of(
                        "type", "info",
                        "content", "通话已就绪，正在开启..."
                ),
                "data", Map.of(
                        "call_url", callUrl
                )
        );

        return Response.ok(response).build();
    }
}
