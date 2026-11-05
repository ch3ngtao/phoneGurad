import { PhoneMarkResult } from '../types';
import CallDetection from '../native/modules/CallDetection';
import locationService from './LocationService';

/**
 * 电话检测服务。
 * 后台是否拦截以原生 CallGuardPolicy 为准，不依赖界面是否还在前台。
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
    _markResult?: PhoneMarkResult,
  ): Promise<boolean> {
    try {
      return await CallDetection.shouldBlockNumber(phoneNumber);
    } catch {
      return false;
    }
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
