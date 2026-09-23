package asia.jppwl.bridge.relay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * {@link GeminiMessageCodec} 单元测试：验证 Google Gemini Live 官方 Bidi 协议报文封包与解包.
 */
class GeminiMessageCodecTest {

    @Test
    @DisplayName("测试握手 Setup 帧正确封装模型名、AUDIO+TEXT 模态、发音人与 System Instruction")
    void shouldBuildSetupFrameCorrectly() {
        String model = "gemini-3.8-live";
        String voice = "Puck";
        String instruction = "你叫Hebe，是主人的专属女仆秘书。";

        JsonObject setupFrame = GeminiMessageCodec.buildSetupFrame(model, voice, instruction, null);

        assertThat(setupFrame).isNotNull();
        JsonObject setup = setupFrame.getJsonObject("setup");
        assertThat(setup.getString("model")).isEqualTo("models/gemini-3.8-live");

        JsonObject genConfig = setup.getJsonObject("generationConfig");
        assertThat(genConfig.getJsonArray("responseModalities")).containsExactly("AUDIO", "TEXT");
        assertThat(genConfig.getJsonObject("speechConfig")
                .getJsonObject("voiceConfig")
                .getJsonObject("prebuiltVoiceConfig")
                .getString("voiceName")).isEqualTo("Puck");

        JsonObject sysInstruction = setup.getJsonObject("systemInstruction");
        assertThat(sysInstruction.getJsonArray("parts").getJsonObject(0).getString("text"))
                .isEqualTo(instruction);
    }

    @Test
    @DisplayName("测试 16kHz PCM 音频切片编码为 realtimeInput 帧 (Base64)")
    void shouldBuildAudioInputFrameCorrectly() {
        byte[] pcmBytes = new byte[] { 0x01, 0x02, (byte) 0xFF, (byte) 0xFE };
        Buffer buffer = Buffer.buffer(pcmBytes);

        JsonObject audioFrame = GeminiMessageCodec.buildAudioInputFrame(buffer);

        assertThat(audioFrame).isNotNull();
        JsonObject realtimeInput = audioFrame.getJsonObject("realtimeInput");
        JsonArray mediaChunks = realtimeInput.getJsonArray("mediaChunks");
        assertThat(mediaChunks).hasSize(1);

        JsonObject chunk = mediaChunks.getJsonObject(0);
        assertThat(chunk.getString("mimeType")).isEqualTo("audio/pcm;rate=16000");
        assertThat(chunk.getString("data")).isEqualTo("AQL//g=="); // Base64
    }

    @Test
    @DisplayName("测试文本插话与打断控制信令封包")
    void shouldBuildTextAndInterruptFrames() {
        // 文本插话帧
        JsonObject textFrame = GeminiMessageCodec.buildTextInputFrame("主人下午好");
        JsonObject clientContent = textFrame.getJsonObject("clientContent");
        assertThat(clientContent.getBoolean("turnComplete")).isTrue();
        assertThat(clientContent.getJsonArray("turns").getJsonObject(0).getJsonArray("parts").getJsonObject(0).getString("text"))
                .isEqualTo("主人下午好");

        // 打断控制信令帧
        JsonObject interruptFrame = GeminiMessageCodec.buildInterruptSignalFrame();
        JsonObject interruptContent = interruptFrame.getJsonObject("clientContent");
        assertThat(interruptContent.getBoolean("turnComplete")).isTrue();
        assertThat(interruptContent.getBoolean("interrupt")).isTrue();
    }

    @Test
    @DisplayName("测试 Base64 音频安全解码")
    void shouldDecodeBase64AudioCorrectly() {
        String base64 = "AQIDBA=="; // [1, 2, 3, 4]
        Buffer decoded = GeminiMessageCodec.decodeBase64Audio(base64);

        assertThat(decoded.length()).isEqualTo(4);
        assertThat(decoded.getBytes()).containsExactly(1, 2, 3, 4);

        // 空保护
        assertThat(GeminiMessageCodec.decodeBase64Audio(null).length()).isZero();
        assertThat(GeminiMessageCodec.decodeBase64Audio("").length()).isZero();
    }
}
