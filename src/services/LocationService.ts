import { Platform } from 'react-native';
import CallDetection from '../native/modules/CallDetection';

/**
 * 归属地查询结果
 */
export interface LocationInfo {
  province: string;
  city: string;
  carrier: string;
  isKnown: boolean;
}

/**
 * 归属地服务
 *
 * 使用 Android 原生 phone2region.db 进行归属地查询。
 * localProvince 只是启动后从已保存设置填进来的缓存，真正记住的家乡在原生存储里。
 */
class LocationService {
  private static instance: LocationService;
  private localProvince: string | null = null;

  private constructor() {}

  static getInstance(): LocationService {
    if (!LocationService.instance) {
      LocationService.instance = new LocationService();
    }
    return LocationService.instance;
  }

  /**
   * 查询号码归属地
   * 支持手机号（1开头）和座机号（0开头）
   * @param phoneNumber 电话号码
   */
  async queryLocation(phoneNumber: string): Promise<LocationInfo> {
    const cleaned = phoneNumber.replace(/\D/g, '');

    if (cleaned.length < 7) {
      return {
        province: '未知',
        city: '未知',
        carrier: '未知',
        isKnown: false,
      };
    }

    // iOS/other: return unknown (no native module)
    if (Platform.OS !== 'android') {
      return {
        province: '未知',
        city: '未知',
        carrier: '未知',
        isKnown: false,
      };
    }

    try {
      const result = await CallDetection.queryPhoneLocation(cleaned);
      return {
        province: result.province,
        city: result.city,
        carrier: result.carrier,
        isKnown: result.isKnown,
      };
    } catch {
      return {
        province: '未知',
        city: '未知',
        carrier: '未知',
        isKnown: false,
      };
    }
  }

  /**
   * 设置本机归属地
   */
  setLocalLocation(province: string): void {
    this.localProvince = province;
  }

  /**
   * 获取本机归属地
   */
  getLocalLocation(): string | null {
    return this.localProvince;
  }

  /**
   * 判断是否为外地号码
   * @param phoneNumber 电话号码
   * @returns true 表示非本机归属地号码
   */
  async isNonLocalNumber(phoneNumber: string): Promise<boolean> {
    if (!this.localProvince) return false;

    const location = await this.queryLocation(phoneNumber);
    if (!location.isKnown) return false; // 未知归属地保守处理

    return location.province !== this.localProvince;
  }

  /**
   * 获取号码完整归属地信息
   */
  async getFullLocation(phoneNumber: string): Promise<string> {
    const location = await this.queryLocation(phoneNumber);
    if (location.isKnown) {
      const cityPart = location.city ? ` ${location.city}` : '';
      return `${location.province}${cityPart} ${location.carrier}`;
    }
    return '未知归属地';
  }
}

export default LocationService.getInstance();
