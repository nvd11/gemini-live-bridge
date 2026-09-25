package asia.jppwl.bridge.relay;

import java.util.Base64;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Google Gemini 3.8 Live API 官方双向流式协议 (Bidi WebSocket) 编解码工具.
 */
public final class GeminiMessageCodec {

    private static final Base64.Encoder B64_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder B64_DECODER = Base64.getDecoder();

    private GeminiMessageCodec() {
    }

    /**
     * 构建握手首帧 (Bidi Setup Frame).
     *
     * <p>特别说明：Google Live 官方协议支持通过 outputAudioConfig 与 responseModalities
     * 同时下发 24kHz 音频与文本转写，若模型生成语音，其对应的字幕转写也将一并送达。
     */
    public static JsonObject buildSetupFrame(
            String modelVariant,
            String voiceName,
            String systemInstruction,
            JsonArray functionDecls
    ) {
        JsonObject setup = new JsonObject();
        setup.put("model", "models/" + (modelVariant != null ? modelVariant : "gemini-3.8-live"));

        // 1. 生成参数配置 (请求 AUDIO + TEXT 模态)
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
