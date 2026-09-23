package asia.jppwl.bridge.relay;

import java.util.Base64;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Google Gemini 3.8 Live API 官方双向流式协议 (Bidi WebSocket) 编解码工具.
 *
 * <p>协议规范：
 * <ul>
 *   <li>握手 Setup 帧：包含 {@code model}、{@code generationConfig} (音频模态 AUDIO+TEXT, Voice 声线)、{@code systemInstruction} 与可选的 {@code tools}；</li>
 *   <li>上行音频流：{@code realtimeInput.mediaChunks} (MIME: {@code audio/pcm;rate=16000}, Base64 负载)；</li>
 *   <li>上行文本/插话：{@code clientContent.turns}；</li>
 *   <li>上行打断信令：{@code clientContent { turnComplete: true, interrupt: true }}；</li>
 *   <li>下行模型输出：解析 {@code serverContent.modelTurn.parts} 中的音频切片与转写文本。</li>
 * </ul>
 */
public final class GeminiMessageCodec {

    private static final Base64.Encoder B64_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder B64_DECODER = Base64.getDecoder();

    private GeminiMessageCodec() {
    }

    /**
     * 构建握手首帧 (Bidi Setup Frame).
     *
     * @param modelVariant      模型名称 (如 {@code gemini-3.8-live})
     * @param voiceName         发音人名称 (如 {@code Puck})
     * @param systemInstruction 智能体人设提示词
     * @param functionDecls     可选的本地工具声明列表 (JSON Schema)
     * @return 完整的 Setup JSON 报文
     */
    public static JsonObject buildSetupFrame(
            String modelVariant,
            String voiceName,
            String systemInstruction,
            JsonArray functionDecls
    ) {
        JsonObject setup = new JsonObject();
        setup.put("model", "models/" + (modelVariant != null ? modelVariant : "gemini-3.8-live"));

        // 1. 生成参数配置 (强制请求 AUDIO + TEXT 模态，指定 Voice)
        JsonObject generationConfig = new JsonObject();
        generationConfig.put("responseModalities", new JsonArray().add("AUDIO").add("TEXT"));

        if (voiceName != null && !voiceName.isBlank()) {
            JsonObject speechConfig = new JsonObject();
            JsonObject voiceConfig = new JsonObject();
            voiceConfig.put("prebuiltVoiceConfig", new JsonObject().put("voiceName", voiceName));
            speechConfig.put("voiceConfig", voiceConfig);
            generationConfig.put("speechConfig", speechConfig);
        }
        setup.put("generationConfig", generationConfig);

        // 2. 注入 System Instruction 人设
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            JsonObject parts = new JsonObject().put("text", systemInstruction);
            JsonObject content = new JsonObject().put("parts", new JsonArray().add(parts));
            setup.put("systemInstruction", content);
        }

        // 3. 注入 Function Calling 工具列表
        if (functionDecls != null && !functionDecls.isEmpty()) {
            JsonObject toolObj = new JsonObject().put("functionDeclarations", functionDecls);
            setup.put("tools", new JsonArray().add(toolObj));
        }

        return new JsonObject().put("setup", setup);
    }

    /**
     * 将客户端 16kHz PCM 音频切片编码为上行推流帧 (realtimeInput).
     *
     * @param pcm16kChunk 16kHz, 16-bit, Mono PCM Buffer
     * @return 符合 Google 规范的 JSON 文本帧
     */
    public static JsonObject buildAudioInputFrame(Buffer pcm16kChunk) {
        String base64Audio = B64_ENCODER.encodeToString(pcm16kChunk.getBytes());
        JsonObject mediaChunk = new JsonObject()
                .put("mimeType", "audio/pcm;rate=16000")
                .put("data", base64Audio);

        return new JsonObject().put("realtimeInput", new JsonObject()
                .put("mediaChunks", new JsonArray().add(mediaChunk)));
    }

    /**
     * 将客户端文本插话编码为上行输入帧 (clientContent).
     *
     * @param text 用户输入的辅助文本或指令
     * @return 符合 Google 规范的 JSON 文本帧
     */
    public static JsonObject buildTextInputFrame(String text) {
        JsonObject part = new JsonObject().put("text", text != null ? text : "");
        JsonObject turn = new JsonObject()
                .put("role", "user")
                .put("parts", new JsonArray().add(part));

        return new JsonObject().put("clientContent", new JsonObject()
                .put("turns", new JsonArray().add(turn))
                .put("turnComplete", true));
    }

    /**
     * 构建用户主动打断与截断信令帧 (Barge-in Cut-off).
     */
    public static JsonObject buildInterruptSignalFrame() {
        return new JsonObject().put("clientContent", new JsonObject()
                .put("turnComplete", true)
                .put("interrupt", true));
    }

    /**
     * 构建 Function Calling 工具执行结果回填帧 (toolResponse).
     *
     * @param callId 调用的 Call ID
     * @param output 业务工具执行返回的 JSON 结果
     * @return 回填给模型的 toolResponse 帧
     */
    public static JsonObject buildToolResponseFrame(String callId, JsonObject output) {
        JsonObject responseObj = new JsonObject();
        responseObj.put("name", callId != null ? callId : "unnamed_tool");
        responseObj.put("response", new JsonObject().put("output", output != null ? output : new JsonObject()));

        return new JsonObject().put("toolResponse", new JsonObject()
                .put("functionResponses", new JsonArray().add(responseObj)));
    }

    /**
     * Base64 解码工具辅助方法.
     */
    public static Buffer decodeBase64Audio(String base64Data) {
        if (base64Data == null || base64Data.isBlank()) {
            return Buffer.buffer(0);
        }
        return Buffer.buffer(B64_DECODER.decode(base64Data));
    }
}
