// audio-processor.js - AudioWorklet 纯原生 16kHz PCM 音频采集处理器 (严密 3200 字节帧对齐)
class AudioInputProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    // 目标 1600 个采样点 (100ms * 16kHz) = 3200 字节
    this.TARGET_CHUNK_SAMPLES = 1600;
    this.pcmAccumulator = new Int16Array(this.TARGET_CHUNK_SAMPLES);
    this.accumulatedSamples = 0;
  }

  process(inputs, outputs, parameters) {
    const input = inputs[0];
    if (!input || !input[0]) return true;

    const channelData = input[0];
    const inputSampleRate = sampleRate; // 浏览器环境物理采样率 (如 48000)
    const targetSampleRate = 16000;
    const ratio = inputSampleRate / targetSampleRate;

    // 线性均值降采样
    const downsampledLength = Math.floor(channelData.length / ratio);
    for (let i = 0; i < downsampledLength; i++) {
      const start = Math.floor(i * ratio);
      const end = Math.floor((i + 1) * ratio);
      let sum = 0;
      let count = 0;
      for (let j = start; j < end && j < channelData.length; j++) {
        sum += channelData[j];
        count++;
      }
      const avg = count > 0 ? sum / count : 0;
      const s = Math.max(-1, Math.min(1, avg));
      const int16Val = s < 0 ? s * 0x8000 : s * 0x7FFF;

      this.pcmAccumulator[this.accumulatedSamples++] = int16Val;

      // 只要凑齐严密的 1600 个采样点 (3200 字节 / 100ms)，立刻打包推往主线程！
      if (this.accumulatedSamples >= this.TARGET_CHUNK_SAMPLES) {
        // 创建精确的一维拷贝，确保 Transferable 传递无死角
        const outBuffer = new Int16Array(this.pcmAccumulator).buffer;
        this.port.postMessage(outBuffer, [outBuffer]);
        this.accumulatedSamples = 0;
      }
    }

    return true;
  }
}

registerProcessor('audio-input-processor', AudioInputProcessor);
