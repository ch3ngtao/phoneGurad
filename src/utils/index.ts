/**
 * 格式化电话号码
 */
export const formatPhoneNumber = (phone: string): string => {
  // 移除所有非数字字符
  const cleaned = phone.replace(/\D/g, '');
  
  // 中国大陆手机号格式：138-0000-0000
  if (cleaned.length === 11 && cleaned.startsWith('1')) {
    return cleaned.replace(/(\d{3})(\d{4})(\d{4})/, '$1-$2-$3');
  }
  
  // 固定电话号码格式
  if (cleaned.length >= 7 && cleaned.length <= 11) {
    return cleaned.replace(/(\d{3,4})(\d{3,4})(\d{4})/, '$1-$2-$3');
  }
  
  return phone;
};

/**
 * 验证电话号码格式（中国大陆）
 */
export const isValidPhone = (phone: string): boolean => {
  const cleaned = phone.replace(/\D/g, '');
  
  // 手机号：1 开头，11 位
  const mobileRegex = /^1[3-9]\d{9}$/;
  
  // 固定电话：区号 + 号码
  const landlineRegex = /^0\d{2,3}\d{7,8}$/;
  
  return mobileRegex.test(cleaned) || landlineRegex.test(cleaned);
};

/**
 * 从电话号码提取归属地前缀
 */
export const getPhonePrefix = (phone: string): string => {
  const cleaned = phone.replace(/\D/g, '');
  if (cleaned.length >= 7) {
    return cleaned.substring(0, 7); // 手机号前 7 位
  }
  return cleaned;
};

/**
 * 延迟执行
 */
export const delay = (ms: number): Promise<void> => {
  return new Promise(resolve => setTimeout(resolve, ms));
};

/**
 * 生成唯一 ID
 */
export const generateId = (): string => {
  return `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
};

/**
 * 脱敏电话号码（用于显示）
 */
export const maskPhoneNumber = (phone: string, visibleChars = 4): string => {
  const cleaned = phone.replace(/\D/g, '');
  if (cleaned.length <= visibleChars) {
    return cleaned;
  }
  const masked = cleaned.substring(0, cleaned.length - visibleChars).replace(/\d/g, '*');
  return masked + cleaned.substring(cleaned.length - visibleChars);
};
