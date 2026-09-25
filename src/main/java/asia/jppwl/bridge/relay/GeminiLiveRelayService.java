package asia.jppwl.bridge.relay;

import java.net.URI;

import org.jboss.logging.Logger;

import asia.jppwl.bridge.config.BridgeConfig;
import asia.jppwl.bridge.domain.CallSession;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Google Gemini 3.8 Live API 上游 WSS 直连中继服务工厂.
 *
 * <p>当前阶段采用纯净对话模式 (无工具干扰)，确保大模型把全部算力聚焦于正常拟真人机语音交流。
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
    private final HttpClient httpClient;

    @Inject
    public GeminiLiveRelayService(Vertx vertx, BridgeConfig config) {
        this.vertx = vertx;
        this.config = config;
        this.httpClient = vertx.createHttpClient();
    }

    /**
     * 为指定的会话开启直连 Google Gemini Live 的上游双向管道 (纯净自然语音对讲模式).
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

        LOG.infof("Initiating upstream WSS connection to Google Gemini Live for session %s (pure voice mode)",
                session.sessionId());

        return httpClient.webSocket(options)
                .onItem().transform(ws -> {
                    io.vertx.core.http.WebSocket underlyingWs = ws.getDelegate();

                    // 构建纯净 Setup 帧：不挂载任何 Tool，专心回答主人提问！
                    JsonObject setupFrame = GeminiMessageCodec.buildSetupFrame(
                            session.modelVariant(),
                            session.voiceName(),
                            config.gemini().systemInstruction(),
                            null // 移除工具声明，恢复 100% 正常人类智能交谈！
                    );
                    underlyingWs.writeTextMessage(setupFrame.encode());
                    LOG.infof("Sent pure Bidi Setup frame (No tools attached) for session %s", session.sessionId());

                    return new GeminiLiveSession(underlyingWs, session, downstreamSink, null);
                })
                .onFailure().invoke(err ->
                        LOG.errorf(err, "Failed to connect to Google Gemini Live upstream for session %s", session.sessionId())
                );
    }
}
