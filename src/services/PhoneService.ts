import { PhoneMarkResult } from '../types';
import locationService from './LocationService';
import StorageService from './StorageService';

/**
 * 电话检测服务
 * 负责判断来电是否为营销/骚扰电话
 *
 * 判定规则：
 * 1. 黑名单号码 → 直接拦截
 * 2. 白名单号码 → 不拦截
 * 3. 外地号码（非本省） → 标记为疑似营销号
 * 4. 未知归属地号码 → 可选择拦截或放行
 */
class PhoneService {
  private static instance: PhoneService;

  private constructor() {}

  static getInstance(): PhoneService {
    if (!PhoneService.instance) {
      PhoneService.instance = new PhoneService();
    }
    return PhoneService.instance;
  }

  /**
   * 检查号码是否为垃圾电话
   * 基于归属地判断：非本省号码标记为疑似营销号
   * @param phoneNumber 电话号码
   * @returns 检测结果
   */
  async checkPhoneNumber(phoneNumber: string): Promise<PhoneMarkResult> {
    const location = await locationService.queryLocation(phoneNumber);
    const isNonLocal = await locationService.isNonLocalNumber(phoneNumber);

    // 外地号码标记为疑似营销
    const isSpam = isNonLocal;

    return {
      phoneNumber,
      isSpam,
      spamType: isSpam ? 'telemarketing' : 'unknown',
      confidence: isSpam ? 0.7 : 0,
      source: 'location',
      reportCount: 0,
    };
  }

  /**
   * 根据规则判断是否为营销号
   * @param phoneNumber 电话号码
   * @param markResult 标记结果
   * @returns 是否应该拦截
   */
  async shouldBlockNumber(
    phoneNumber: string,
    markResult: PhoneMarkResult,
  ): Promise<boolean> {
    const settings = await StorageService.getSettings();

    // 黑名单直接拦截
    if ((settings.blacklist ?? []).includes(phoneNumber)) {
      return true;
    }

    // 白名单不拦截
    if ((settings.whitelist ?? []).includes(phoneNumber)) {
      return false;
    }

    // 根据标记结果判断
    if (markResult.isSpam && settings.autoBlock) {
      return true;
    }

    return false;
  }

  /**
   * 获取号码归属地信息
   */
  async getPhoneLocation(phoneNumber: string): Promise<string> {
    return locationService.getFullLocation(phoneNumber);
  }

  /**
   * 设置本机归属地
   */
  setLocalLocation(province: string): void {
    locationService.setLocalLocation(province);
  }

  /**
   * 获取本机归属地
   */
  getLocalLocation(): string | null {
    return locationService.getLocalLocation();
  }
}

export default PhoneService.getInstance();
