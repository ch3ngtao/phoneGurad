// 电话记录类型
export interface CallRecord {
  id: string;
  phoneNumber: string;
  timestamp: number;
  duration: number;
  isBlocked: boolean;
  isSpam: boolean;
  spamScore?: number;
  label?: string;
}

// 电话号码标记结果
export interface PhoneMarkResult {
  phoneNumber: string;
  isSpam: boolean;
  spamType?: SpamType;
  confidence: number;
  source: string;
  reportCount?: number;
}

// 垃圾电话类型
export type SpamType = 
  | 'telemarketing'     // 营销电话
  | 'fraud'            // 诈骗
  | 'harassment'       // 骚扰
  | 'robot'            // 机器人呼叫
  | 'unknown';         // 未知

// 应用设置
export interface AppSettings {
  autoBlock: boolean;
  blockThreshold: number;
  enableNotification: boolean;
  enableLog: boolean;
  whitelist: string[];
  blacklist: string[];
  localProvince: string;
}

// 原生电话模块事件
export interface CallEvent {
  eventType: 'incoming' | 'outgoing' | 'answered' | 'ended' | 'blocked';
  phoneNumber: string;
  timestamp: number;
  callId?: string;
}
