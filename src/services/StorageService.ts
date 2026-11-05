import AsyncStorage from '@react-native-async-storage/async-storage';
import { AppSettings, CallRecord } from '../types';
import { STORAGE_KEYS, DEFAULT_SETTINGS } from '../constants';

/**
 * 本地存储服务
 */
class StorageService {
  private static instance: StorageService;

  private constructor() {}

  static getInstance(): StorageService {
    if (!StorageService.instance) {
      StorageService.instance = new StorageService();
    }
    return StorageService.instance;
  }

  /**
   * 获取应用设置
   */
  /**
   * 读到设置返回对象；存储失败返回 null，避免用空名单覆盖原生配置。
   */
  async readSettings(): Promise<AppSettings | null> {
    try {
      const data = await AsyncStorage.getItem(STORAGE_KEYS.SETTINGS);
      if (!data) {
        return null;
      }
      const parsed = JSON.parse(data);
      return this.cloneSettings({
        ...DEFAULT_SETTINGS,
        ...parsed,
        localProvince:
          typeof parsed.localProvince === 'string' ? parsed.localProvince : '',
        whitelist: Array.isArray(parsed.whitelist) ? parsed.whitelist : [],
        blacklist: Array.isArray(parsed.blacklist) ? parsed.blacklist : [],
        protectionEnabled: parsed.protectionEnabled === true,
        batteryPromptDismissed: parsed.batteryPromptDismissed === true,
        autostartOpened: parsed.autostartOpened === true,
      });
    } catch (error) {
      console.error('[StorageService] Error getting settings:', error);
      return null;
    }
  }

  async getSettings(): Promise<AppSettings> {
    const stored = await this.readSettings();
    return stored ?? this.cloneSettings(DEFAULT_SETTINGS);
  }

  private cloneSettings(settings: AppSettings): AppSettings {
    return {
      ...settings,
      whitelist: [...(settings.whitelist ?? [])],
      blacklist: [...(settings.blacklist ?? [])],
    };
  }

  /**
   * 保存应用设置
   */
  async saveSettings(settings: AppSettings): Promise<void> {
    try {
      await AsyncStorage.setItem(
        STORAGE_KEYS.SETTINGS,
        JSON.stringify(settings)
      );
    } catch (error) {
      console.error('[StorageService] Error saving settings:', error);
    }
  }

  /**
   * 获取通话记录
   */
  async getCallLog(): Promise<CallRecord[]> {
    try {
      const data = await AsyncStorage.getItem(STORAGE_KEYS.CALL_LOG);
      if (data) {
        return JSON.parse(data);
      }
    } catch (error) {
      console.error('[StorageService] Error getting call log:', error);
    }
    return [];
  }

  /**
   * 添加通话记录
   */
  async addCallRecord(record: CallRecord): Promise<void> {
    try {
      const logs = await this.getCallLog();
      logs.unshift(record); // 新记录放在前面
      
      // 只保留最近 1000 条
      const trimmed = logs.slice(0, 1000);
      
      await AsyncStorage.setItem(
        STORAGE_KEYS.CALL_LOG,
        JSON.stringify(trimmed)
      );
    } catch (error) {
      console.error('[StorageService] Error adding call record:', error);
    }
  }

  /**
   * 添加到黑名单
   */
  async addToBlacklist(phoneNumber: string): Promise<void> {
    try {
      const data = await AsyncStorage.getItem(STORAGE_KEYS.BLACKLIST);
      const blacklist: string[] = data ? JSON.parse(data) : [];
      
      if (!blacklist.includes(phoneNumber)) {
        blacklist.push(phoneNumber);
        await AsyncStorage.setItem(
          STORAGE_KEYS.BLACKLIST,
          JSON.stringify(blacklist)
        );
      }
    } catch (error) {
      console.error('[StorageService] Error adding to blacklist:', error);
    }
  }

  /**
   * 从黑名单移除
   */
  async removeFromBlacklist(phoneNumber: string): Promise<void> {
    try {
      const data = await AsyncStorage.getItem(STORAGE_KEYS.BLACKLIST);
      if (data) {
        const blacklist: string[] = JSON.parse(data);
        const filtered = blacklist.filter(num => num !== phoneNumber);
        await AsyncStorage.setItem(
          STORAGE_KEYS.BLACKLIST,
          JSON.stringify(filtered)
        );
      }
    } catch (error) {
      console.error('[StorageService] Error removing from blacklist:', error);
    }
  }

  /**
   * 添加到白名单
   */
  async addToWhitelist(phoneNumber: string): Promise<void> {
    try {
      const data = await AsyncStorage.getItem(STORAGE_KEYS.WHITELIST);
      const whitelist: string[] = data ? JSON.parse(data) : [];
      
      if (!whitelist.includes(phoneNumber)) {
        whitelist.push(phoneNumber);
        await AsyncStorage.setItem(
          STORAGE_KEYS.WHITELIST,
          JSON.stringify(whitelist)
        );
      }
    } catch (error) {
      console.error('[StorageService] Error adding to whitelist:', error);
    }
  }

  /**
   * 清空通话记录
   */
  async clearCallLog(): Promise<void> {
    try {
      await AsyncStorage.removeItem(STORAGE_KEYS.CALL_LOG);
    } catch (error) {
      console.error('[StorageService] Error clearing call log:', error);
    }
  }
}

export default StorageService.getInstance();
