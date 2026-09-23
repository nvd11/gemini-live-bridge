package asia.jppwl.bridge.relay;

import java.net.URI;

import org.jboss.logging.Logger;

import asia.jppwl.bridge.config.BridgeConfig;
import asia.jppwl.bridge.domain.CallSession;
import asia.jppwl.bridge.tool.ToolExecutionRouter;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.HttpClient;
import io.vertx.mutiny.core.http.WebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Google Gemini 3.8 Live API 上游 WSS 直连中继服务工厂 (Relay Service).
 *
 * <p>基于 Eclipse Vert.x 响应式客户端，与 Google 官方 Gemini Live 端点建立全双工长连接通道，
 * 在握手成功的第一毫秒自动推送 Setup 帧（注入 Hebe 人设、AUDIO+TEXT 模态、发音人与 Native Function Calling 工具声明）。
 */
@ApplicationScoped
public class GeminiLiveRelayService {

    private static final Logger LOG = Logger.getLogger(GeminiLiveRelayService.class);

    private static final String GEMINI_HOST = "generativelanguage.googleapis.com";
    private static final int GEMINI_PORT = 443;
    private static final String GEMINI_BIDI_PATH =
            "/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent";

    private final Vertx vertx;
    private final BridgeConfig config;
    private final ToolExecutionRouter toolRouter;
    private final HttpClient httpClient;

    @Inject
    public GeminiLiveRelayService(Vertx vertx, BridgeConfig config, ToolExecutionRouter toolRouter) {
        this.vertx = vertx;
        this.config = config;
        this.toolRouter = toolRouter;
        this.httpClient = vertx.createHttpClient();
    }

    /**
     * 为指定的会话开启直连 Google Gemini Live 的上游双向管道 (异步 Uni).
     *
     * @param session        当前活跃会话实体
     * @param downstreamSink 下游客户端事件与音频分发下沉接口
     * @return 异步包装的已握手初始化的 {@link GeminiLiveSession}
     */
    public Uni<GeminiLiveSession> openUpstreamChannel(CallSession session, DownstreamSink downstreamSink) {
        String apiKey = config.gemini().apiKey();
        String uri = GEMINI_BIDI_PATH + "?key=" + apiKey;

        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost(GEMINI_HOST)
                .setPort(GEMINI_PORT)
                .setSsl(true)
                .setURI(uri)
                .setTimeout(10000);

        LOG.infof("Initiating upstream WSS connection to Google Gemini Live for session %s (model=%s, voice=%s)",
                session.sessionId(), session.modelVariant(), session.voiceName());

        return httpClient.webSocket(options)
                .onItem().transform(ws -> {
                    // 转换为底层原生 Vert.x WebSocket 实例
                    io.vertx.core.http.WebSocket underlyingWs = ws.getDelegate();

                    // 1. 提取所有本地注册工具的 JSON Schema 声明列表
                    JsonArray toolDeclarations = toolRouter.buildFunctionDeclarations();

                    // 2. 立即推送握手 Setup 帧 (注入 Hebe 人设、发音人与工具声明)
                    JsonObject setupFrame = GeminiMessageCodec.buildSetupFrame(
                            session.modelVariant(),
                            session.voiceName(),
                            config.gemini().systemInstruction(),
                            toolDeclarations
                    );
                    underlyingWs.writeTextMessage(setupFrame.encode());
                    LOG.infof("Sent Bidi Setup frame to Google Live API with %d registered native tools for session %s",
                            toolDeclarations.size(), session.sessionId());

                    // 3. 构造状态化会话实例并绑定 Tool 路由器
                    return new GeminiLiveSession(underlyingWs, session, downstreamSink, toolRouter);
                })
                .onFailure().invoke(err ->
                        LOG.errorf(err, "Failed to connect to Google Gemini Live upstream for session %s", session.sessionId())
                );
    }
}
