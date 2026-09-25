// audio-processor.js - AudioWorklet 纯原生 16kHz PCM 音频采集处理器
class AudioInputProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.buffer = new Float32Array(0);
  }

  process(inputs, outputs, parameters) {
    const input = inputs[0];
    if (!input || !input[0]) return true;

    const channelData = input[0];
    
    // 降采样为 16kHz
    const sampleRateRatio = sampleRate / 16000;
    const newLength = Math.round(channelData.length / sampleRateRatio);
    const pcm16 = new Int16Array(newLength);

    let offsetResult = 0;
    let offsetBuffer = 0;
    while (offsetResult < newLength) {
      const nextOffsetBuffer = Math.round((offsetResult + 1) * sampleRateRatio);
      let accum = 0, count = 0;
      for (let i = offsetBuffer; i < nextOffsetBuffer && i < channelData.length; i++) {
        accum += channelData[i];
        count++;
      }
      const sample = count > 0 ? accum / count : 0;
      const s = Math.max(-1, Math.min(1, sample));
      pcm16[offsetResult] = s < 0 ? s * 0x8000 : s * 0x7FFF;
      offsetResult++;
      offsetBuffer = nextOffsetBuffer;
    }

    // 通过端口向主线程推送处理好的 16kHz PCM Buffer
    this.port.postMessage(pcm16.buffer, [pcm16.buffer]);
    return true;
  }
}

registerProcessor('audio-input-processor', AudioInputProcessor);
