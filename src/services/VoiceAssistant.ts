import Voice from '@react-native-voice/voice';
import { Platform } from 'react-native';

/**
 * 语音助手 SDK 封装
 * 提供语音识别、语音命令等功能
 */
export type VoiceEvent = {
  type: 'start' | 'end' | 'error' | 'results' | 'partialResults';
  data?: any;
};

export type VoiceListener = (event: VoiceEvent) => void;

class VoiceAssistant {
  private static instance: VoiceAssistant;
  private listeners: Set<VoiceListener> = new Set();
  private isListening = false;
  private isSupported = false;

  private constructor() {
    this.init();
  }

  static getInstance(): VoiceAssistant {
    if (!VoiceAssistant.instance) {
      VoiceAssistant.instance = new VoiceAssistant();
    }
    return VoiceAssistant.instance;
  }

  /**
   * 初始化语音助手
   */
  private async init() {
    try {
      // 检查是否支持语音识别
      const isAvailable = await Voice.isAvailable();
      this.isSupported = isAvailable && Platform.OS === 'android';

      console.log('[VoiceAssistant] Initialized, supported:', this.isSupported);

      // 绑定事件监听器
      Voice.onSpeechStart = this.handleStart.bind(this);
      Voice.onSpeechEnd = this.handleEnd.bind(this);
      Voice.onSpeechResults = this.handleResults.bind(this);
      Voice.onSpeechPartialResults = this.handlePartialResults.bind(this);
      Voice.onSpeechError = this.handleError.bind(this);
    } catch {
      // 原生模块不可用，静默降级
      this.isSupported = false;
      console.log(
        '[VoiceAssistant] Native module not available, voice disabled',
      );
    }
  }

  /**
   * 检查语音识别是否可用
   */
  isAvailable(): boolean {
    return this.isSupported;
  }

  /**
   * 开始语音识别
   */
  async startListening(language = 'zh-CN'): Promise<boolean> {
    if (!this.isSupported) {
      console.warn(
        '[VoiceAssistant] Voice recognition not supported on this platform',
      );
      return false;
    }

    if (this.isListening) {
      console.log('[VoiceAssistant] Already listening');
      return true;
    }

    try {
      await Voice.start(language);
      this.isListening = true;
      console.log('[VoiceAssistant] Started listening, language:', language);
      return true;
    } catch (error) {
      console.error('[VoiceAssistant] Failed to start:', error);
      return false;
    }
  }

  /**
   * 停止语音识别
   */
  async stopListening(): Promise<void> {
    if (!this.isListening) {
      return;
    }

    try {
      await Voice.stop();
      this.isListening = false;
      console.log('[VoiceAssistant] Stopped listening');
    } catch (error) {
      console.error('[VoiceAssistant] Failed to stop:', error);
    }
  }

  /**
   * 取消语音识别
   */
  async cancelListening(): Promise<void> {
    try {
      await Voice.cancel();
      this.isListening = false;
      console.log('[VoiceAssistant] Cancelled listening');
    } catch (error) {
      console.error('[VoiceAssistant] Failed to cancel:', error);
    }
  }

  /**
   * 销毁语音助手（移除所有监听器）
   */
  async destroy(): Promise<void> {
    await this.cancelListening();

    Voice.onSpeechStart = undefined;
    Voice.onSpeechEnd = undefined;
    Voice.onSpeechResults = undefined;
    Voice.onSpeechPartialResults = undefined;
    Voice.onSpeechError = undefined;

    this.listeners.clear();
    console.log('[VoiceAssistant] Destroyed');
  }

  /**
   * 添加事件监听器
   */
  addListener(listener: VoiceListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /**
   * 移除所有监听器
   */
  removeAllListeners(): void {
    this.listeners.clear();
  }

  // ========== 事件处理 ==========

  private handleStart(event: any) {
    console.log('[VoiceAssistant] Speech started:', event);
    this.notifyListeners({ type: 'start', data: event });
  }

  private handleEnd(event: any) {
    console.log('[VoiceAssistant] Speech ended:', event);
    this.isListening = false;
    this.notifyListeners({ type: 'end', data: event });
  }

  private handleResults(event: any) {
    console.log('[VoiceAssistant] Speech results:', event.value);
    this.notifyListeners({ type: 'results', data: event.value });
  }

  private handlePartialResults(event: any) {
    console.log('[VoiceAssistant] Partial results:', event.value);
    this.notifyListeners({ type: 'partialResults', data: event.value });
  }

  private handleError(event: any) {
    console.error('[VoiceAssistant] Speech error:', event.error);
    this.isListening = false;
    this.notifyListeners({ type: 'error', data: event.error });
  }

  private notifyListeners(event: VoiceEvent): void {
    this.listeners.forEach(listener => {
      try {
        listener(event);
      } catch (error) {
        console.error('[VoiceAssistant] Listener error:', error);
      }
    });
  }

  /**
   * 获取支持的语音识别语言列表
   */
  async getSupportedLanguages(): Promise<string[]> {
    if (!this.isSupported) {
      return [];
    }

    try {
      const languages = await Voice.getSupportedRecognitionLanguages();
      return languages.map((lang: any) => lang || '').filter(Boolean);
    } catch (error) {
      console.error(
        '[VoiceAssistant] Failed to get supported languages:',
        error,
      );
      return ['zh-CN', 'en-US'];
    }
  }
}

export default VoiceAssistant.getInstance();
