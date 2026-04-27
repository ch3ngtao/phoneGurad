import { NativeModules, NativeEventEmitter, Platform } from 'react-native';
import { CallEvent } from '../../types';

const { CallDetectionModule } = NativeModules;

const EVENT_EMITTER = new NativeEventEmitter(CallDetectionModule);

/**
 * 电话检测原生模块接口
 *
 * Android 需要以下权限:
 * - READ_PHONE_STATE
 * - READ_CALL_LOG
 * - ANSWER_PHONE_CALLS
 * - READ_PHONE_NUMBERS
 */
export interface CallDetectionInterface {
  // 开始监听来电
  startListening(): Promise<void>;

  // 停止监听
  stopListening(): Promise<void>;

  // 挂断电话
  endCall(): Promise<void>;

  // 拒接电话
  rejectCall(): Promise<void>;

  // 检查权限
  checkPermissions(): Promise<PermissionStatus>;

  // 请求权限
  requestPermissions(): Promise<boolean>;

  // 查询号码归属地
  queryPhoneLocation(phoneNumber: string): Promise<PhoneLocationResult>;

  // 监听来电事件
  onCallEvent(callback: (event: CallEvent) => void): () => void;
}

export type PermissionStatus = {
  hasPhoneStatePermission: boolean;
  hasCallLogPermission: boolean;
  hasAnswerPhonePermission: boolean;
  allGranted: boolean;
};

export type PhoneLocationResult = {
  province: string;
  city: string;
  carrier: string;
  isKnown: boolean;
};

class CallDetection implements CallDetectionInterface {
  private isListening = false;

  /**
   * 开始监听来电
   */
  async startListening(): Promise<void> {
    if (Platform.OS !== 'android') {
      console.warn('[CallDetection] Only supported on Android');
      return;
    }

    const hasPermission = await this.requestPermissions();
    if (!hasPermission) {
      throw new Error('Permission denied');
    }

    await CallDetectionModule.startListening();
    this.isListening = true;
    console.log('[CallDetection] Started listening');
  }

  /**
   * 停止监听
   */
  async stopListening(): Promise<void> {
    if (Platform.OS !== 'android') {
      return;
    }

    await CallDetectionModule.stopListening();
    this.isListening = false;
    console.log('[CallDetection] Stopped listening');
  }

  /**
   * 挂断当前通话
   */
  async endCall(): Promise<void> {
    if (Platform.OS !== 'android') {
      console.warn('[CallDetection] Only supported on Android');
      return;
    }

    await CallDetectionModule.endCall();
    console.log('[CallDetection] Call ended');
  }

  /**
   * 拒接来电
   */
  async rejectCall(): Promise<void> {
    if (Platform.OS !== 'android') {
      console.warn('[CallDetection] Only supported on Android');
      return;
    }

    await CallDetectionModule.rejectCall();
    console.log('[CallDetection] Call rejected');
  }

  /**
   * 检查权限状态
   */
  async checkPermissions(): Promise<PermissionStatus> {
    if (Platform.OS !== 'android') {
      return {
        hasPhoneStatePermission: false,
        hasCallLogPermission: false,
        hasAnswerPhonePermission: false,
        allGranted: false,
      };
    }

    return await CallDetectionModule.checkPermissions();
  }

  /**
   * 请求必要权限
   */
  async requestPermissions(): Promise<boolean> {
    if (Platform.OS !== 'android') {
      return false;
    }

    return await CallDetectionModule.requestPermissions();
  }

  /**
   * 查询号码归属地
   */
  async queryPhoneLocation(phoneNumber: string): Promise<PhoneLocationResult> {
    if (Platform.OS !== 'android') {
      return {
        province: '未知',
        city: '未知',
        carrier: '未知',
        isKnown: false,
      };
    }
    return await CallDetectionModule.queryPhoneLocation(phoneNumber);
  }

  /**
   * 同步设置到原生层（供前台服务读取）
   */
  async syncSettings(params: {
    localProvince: string;
    autoBlock: boolean;
    whitelist: string[];
    blacklist: string[];
  }): Promise<void> {
    if (Platform.OS !== 'android') return;
    CallDetectionModule.syncSettingsToNative(
      params.localProvince,
      params.autoBlock,
      params.whitelist,
      params.blacklist,
    );
  }

  /**
   * 监听来电事件
   */
  onCallEvent(callback: (event: CallEvent) => void): () => void {
    const subscription = EVENT_EMITTER.addListener('onCallEvent', callback);

    return () => {
      subscription.remove();
    };
  }

  /**
   * 检查是否正在监听
   */
  getIsListening(): boolean {
    return this.isListening;
  }
}

export default new CallDetection();
