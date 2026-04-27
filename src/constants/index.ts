// 应用常量配置

export const APP_CONFIG = {
  name: 'PhoneGuard',
  version: '1.0.0',
  minSdkVersion: 23,
  targetSdkVersion: 34,
};

// 垃圾电话判定阈值
export const SPAM_THRESHOLD = {
  LOW: 0.3, // 低风险
  MEDIUM: 0.6, // 中风险
  HIGH: 0.8, // 高风险，自动拦截
};

// 默认设置
export const DEFAULT_SETTINGS: {
  autoBlock: boolean;
  blockThreshold: number;
  enableNotification: boolean;
  enableLog: boolean;
  whitelist: string[];
  blacklist: string[];
} = {
  autoBlock: true,
  blockThreshold: SPAM_THRESHOLD.HIGH,
  enableNotification: true,
  enableLog: true,
  whitelist: [],
  blacklist: [],
};

// 存储键名
export const STORAGE_KEYS = {
  SETTINGS: '@PhoneGuard:settings',
  CALL_LOG: '@PhoneGuard:callLog',
  WHITELIST: '@PhoneGuard:whitelist',
  BLACKLIST: '@PhoneGuard:blacklist',
};

// API 端点（后续接入）
export const API_ENDPOINTS = {
  // 电话号码查询
  CHECK_NUMBER: '/api/v1/phone/check',
  // 举报垃圾号码
  REPORT_SPAM: '/api/v1/phone/report',
  // 获取号码标记信息
  GET_MARK: '/api/v1/phone/mark',
};

// 通知渠道 ID (Android)
export const NOTIFICATION_CHANNELS = {
  CALL_BLOCKED: 'call_blocked_channel',
  SPAM_DETECTED: 'spam_detected_channel',
};
