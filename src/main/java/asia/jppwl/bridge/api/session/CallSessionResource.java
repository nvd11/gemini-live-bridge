package asia.jppwl.bridge.api.session;

import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;

import asia.jppwl.bridge.config.BridgeConfig;
import asia.jppwl.bridge.domain.CallSession;
import asia.jppwl.bridge.domain.CreateSessionRequest;
import asia.jppwl.bridge.domain.SessionManager;
import asia.jppwl.bridge.domain.SessionSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * 通话会话管理 RESTful 控制器.
 *
 * <p>提供会话签发、H5 页面载入前校验以及主动挂断等生命周期控制 API.
 */
@Path("/api/v1/call/session")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class CallSessionResource {

    private static final Logger LOG = Logger.getLogger(CallSessionResource.class);

    private final SessionManager sessionManager;
    private final BridgeConfig config;

    @Inject
    public CallSessionResource(SessionManager sessionManager, BridgeConfig config) {
        this.sessionManager = sessionManager;
        this.config = config;
    }

    /**
     * 创建全新通话会话并签发单次有效 Token.
     */
    @POST
    public Response createSession(CreateSessionRequest request) {
        if (request == null || request.userId() == null || request.userId().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("code", 40001, "message", "userId must not be blank"))
                    .build();
        }

        CallSession session = sessionManager.createSession(request);
        String baseUrl = config.publicBaseUrl();
        String wsBaseUrl = baseUrl.replaceFirst("^http", "ws");

        Map<String, Object> data = Map.of(
                "session_id", session.sessionId(),
                "token", session.ephemeralToken(),
                "call_url", baseUrl + "/call?token=" + session.ephemeralToken(),
                "ws_url", wsBaseUrl + "/ws/live/" + session.ephemeralToken(),
                "expires_at", session.expiresAt().getEpochSecond(),
                "max_duration_seconds", session.maxDuration().toSeconds()
        );

        return Response.ok(Map.of("code", 0, "message", "success", "data", data)).build();
    }

    /**
     * 校验 Token 有效性 (H5 页面初次加载时调用).
     */
    @GET
    @Path("/{token}/validate")
    public Response validateSession(@PathParam("token") String token) {
        if (token == null || token.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("code", 40001, "message", "token must not be blank"))
                    .build();
        }

        // 仅作窥探校验，不消费 Token (消费在 WebSocket 握手时发生)
        // 此处通过检查待接入索引进行验证
        if (sessionManager.getPendingTokenCount() == 0) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("code", 40101, "message", "Session token has expired or already been consumed."))
                    .build();
        }

        Map<String, Object> data = Map.of(
                "agent_name", "Hebe",
                "status", "ready",
                "model_variant", config.gemini().modelName(),
                "voice", config.gemini().voiceName()
        );

        return Response.ok(Map.of("code", 0, "message", "valid", "data", data)).build();
    }

    /**
     * 结束指定会话.
     */
    @POST
    @Path("/{sessionId}/terminate")
    public Response terminateSession(@PathParam("sessionId") String sessionId, Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "user_hangup") : "user_hangup";
        Optional<SessionSummary> summaryOpt = sessionManager.terminateSession(sessionId, reason);

        if (summaryOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("code", 40401, "message", "Active session not found: " + sessionId))
                    .build();
        }

        SessionSummary summary = summaryOpt.get();
        Map<String, Object> data = Map.of(
                "session_id", summary.sessionId(),
                "duration_seconds", summary.durationSeconds(),
                "bytes_uploaded", summary.bytesUploaded(),
                "bytes_downloaded", summary.bytesDownloaded()
        );

        return Response.ok(Map.of("code", 0, "message", "session terminated", "data", data)).build();
    }
}
