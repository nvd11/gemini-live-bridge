package asia.jppwl.bridge.api.slack;

import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import asia.jppwl.bridge.config.BridgeConfig;
import asia.jppwl.bridge.domain.CallSession;
import asia.jppwl.bridge.domain.CreateSessionRequest;
import asia.jppwl.bridge.domain.SessionManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Slack Slash Command ({@code /call}) 控制器.
 *
 * <p>响应 Slack 指令，在 3000ms 限制内快速签发短期 Token 并返回 Block Kit 交互卡片。
 */
@Path("/api/v1/slack")
@ApplicationScoped
public class SlackWebhookResource {

    private static final Logger LOG = Logger.getLogger(SlackWebhookResource.class);

    private final SessionManager sessionManager;
    private final BridgeConfig config;

    @Inject
    public SlackWebhookResource(SessionManager sessionManager, BridgeConfig config) {
        this.sessionManager = sessionManager;
        this.config = config;
    }

    /**
     * 响应 Slack Slash Command (/call).
     */
    @POST
    @Path("/command")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleCommand(
            @FormParam("command") String command,
            @FormParam("text") String text,
            @FormParam("user_id") String userId,
            @FormParam("user_name") String userName,
            @FormParam("channel_id") String channelId
    ) {
        String effectiveUser = userId != null && !userId.isBlank() ? userId : "slack_user";
        LOG.infof("Received Slack command: %s %s from user=%s (name=%s)", command, text, effectiveUser, userName);

        // 1. 签发单次会话 Token
        CreateSessionRequest req = CreateSessionRequest.of(effectiveUser, "slack", channelId);
        CallSession session = sessionManager.createSession(req);

        // 2. 组装 H5 电话进入链接
        String callUrl = config.publicBaseUrl() + "/call?token=" + session.ephemeralToken();

        // 3. 构建 Slack Block Kit 富文本交互卡片
        Map<String, Object> headerBlock = Map.of(
                "type", "header",
                "text", Map.of("type", "plain_text", "text", "🎙️ Hebe 实时语音连线", "emoji", true)
        );

        Map<String, Object> sectionBlock = Map.of(
                "type", "section",
                "text", Map.of("type", "mrkdwn", "text", "主人，全双工语音通道已为您就绪！点击下方按钮即可进入低延迟对讲界面：")
        );

        Map<String, Object> actionBlock = Map.of(
                "type", "actions",
                "elements", List.of(
                        Map.of(
                                "type", "button",
                                "text", Map.of("type", "plain_text", "text", "📞 立即接通通话", "emoji", true),
                                "style", "primary",
                                "url", callUrl,
                                "action_id", "btn_join_voice"
                        )
                )
        );

        Map<String, Object> contextBlock = Map.of(
                "type", "context",
                "elements", List.of(
                        Map.of("type", "mrkdwn", "text", "⚠️ 临时通话链接 5 分钟内有效，支持随时打断与全双工对讲。")
                )
        );

        Map<String, Object> responsePayload = Map.of(
                "response_type", "ephemeral",
                "blocks", List.of(headerBlock, sectionBlock, actionBlock, contextBlock)
        );

        return Response.ok(responsePayload).build();
    }
}
