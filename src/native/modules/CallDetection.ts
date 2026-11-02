import { NativeEventEmitter, NativeModules, PermissionsAndroid, Platform } from 'react-native';
import { CallEvent } from '../../types';

const { CallDetectionModule } = NativeModules;

function createEmitter(): NativeEventEmitter | null {
  if (Platform.OS !== 'android' || CallDetectionModule == null) {
    return null;
  }
  try {
    return new NativeEventEmitter(CallDetectionModule);
  } catch (error) {
    console.warn('[CallDetection] event emitter unavailable', error);
    return null;
  }
}

const EVENT_EMITTER = createEmitter();

export type PermissionStatus = {
  hasPhoneStatePermission: boolean;
  hasCallLogPermission: boolean;
  hasAnswerPhonePermission: boolean;
  hasContactsPermission: boolean;
  allGranted: boolean;
};

export type PhoneLocationResult = {
  province: string;
  city: string;
  carrier: string;
  isKnown: boolean;
};

export type ProtectionStatus = {
  protectionEnabled: boolean;
  serviceRunning: boolean;
  screeningRoleHeld: boolean;
  screeningRoleAvailable: boolean;
  batteryUnrestricted: boolean;
  notificationsEnabled: boolean;
  contactsPermissionGranted: boolean;
  phonePermissionGranted: boolean;
  localProvince: string;
  autoBlock: boolean;
  blockedCount: number;
  lastBlockedNumber: string;
  lastBlockedAt: number;
  lastServiceError: string;
  sdkInt: number;
};

export const EMPTY_PROTECTION_STATUS: ProtectionStatus = {
  protectionEnabled: false,
  serviceRunning: false,
  screeningRoleHeld: false,
  screeningRoleAvailable: false,
  batteryUnrestricted: false,
  notificationsEnabled: false,
  contactsPermissionGranted: false,
  phonePermissionGranted: false,
  localProvince: '',
  autoBlock: true,
  blockedCount: 0,
  lastBlockedNumber: '',
  lastBlockedAt: 0,
  lastServiceError: '',
  sdkInt: 0,
};

const ANDROID_PERMISSIONS = [
  PermissionsAndroid.PERMISSIONS.READ_PHONE_STATE,
  PermissionsAndroid.PERMISSIONS.READ_CALL_LOG,
  PermissionsAndroid.PERMISSIONS.READ_CONTACTS,
  PermissionsAndroid.PERMISSIONS.ANSWER_PHONE_CALLS,
  PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
];

class CallDetection {
  async startListening(): Promise<void> {
    if (!this.isAndroidModule()) return;
    await CallDetectionModule.startListening();
  }

  async stopListening(): Promise<void> {
    if (!this.isAndroidModule()) return;
    await CallDetectionModule.stopListening();
  }

  async shouldBlockNumber(phoneNumber: string): Promise<boolean> {
    if (!this.isAndroidModule()) return false;
    return CallDetectionModule.shouldBlockNumber(phoneNumber);
  }

  async checkPermissions(): Promise<PermissionStatus> {
    if (!this.isAndroidModule()) {
      return {
        hasPhoneStatePermission: false,
        hasCallLogPermission: false,
        hasAnswerPhonePermission: false,
        hasContactsPermission: false,
        allGranted: false,
      };
    }
    return CallDetectionModule.checkPermissions();
  }

  async requestPermissions(): Promise<boolean> {
    await this.requestAndroidPermissions();
    const status = await this.getProtectionStatus();
    return status.phonePermissionGranted && status.contactsPermissionGranted;
  }

  async requestAndroidPermissions(): Promise<void> {
    if (Platform.OS !== 'android') return;
    await PermissionsAndroid.requestMultiple(ANDROID_PERMISSIONS);
  }

  async queryPhoneLocation(phoneNumber: string): Promise<PhoneLocationResult> {
    if (!this.isAndroidModule()) {
      return {
        province: '未知',
        city: '未知',
        carrier: '未知',
        isKnown: false,
      };
    }
    return CallDetectionModule.queryPhoneLocation(phoneNumber);
  }

  async syncSettings(params: {
    localProvince: string;
    autoBlock: boolean;
    whitelist: string[];
    blacklist: string[];
  }): Promise<void> {
    if (!this.isAndroidModule()) return;
    await CallDetectionModule.syncSettingsToNative(
      params.localProvince,
      params.autoBlock,
      params.whitelist,
      params.blacklist,
    );
  }

  async getProtectionStatus(): Promise<ProtectionStatus> {
    if (!this.isAndroidModule()) return EMPTY_PROTECTION_STATUS;
    return CallDetectionModule.getProtectionStatus();
  }

  async ensureScreeningRole(): Promise<boolean> {
    const status = await this.getProtectionStatus();
    if (status.sdkInt < 29 || !status.screeningRoleAvailable) return false;
    if (status.screeningRoleHeld) return true;
    if (!this.isAndroidModule()) return false;
    return CallDetectionModule.requestCallScreeningRole();
  }

  async requestIgnoreBatteryOptimizations(): Promise<string> {
    if (!this.isAndroidModule()) return 'failed';
    return CallDetectionModule.requestIgnoreBatteryOptimizations();
  }

  async openAutoStartSettings(): Promise<string> {
    if (!this.isAndroidModule()) return 'failed';
    return CallDetectionModule.openAutoStartSettings();
  }

  onCallEvent(callback: (event: CallEvent) => void): () => void {
    if (!EVENT_EMITTER) return () => undefined;
    const subscription = EVENT_EMITTER.addListener('onCallEvent', callback);
    return () => subscription.remove();
  }

  private isAndroidModule(): boolean {
    return Platform.OS === 'android' && CallDetectionModule != null;
  }
}

export default new CallDetection();
