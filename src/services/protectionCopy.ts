import { ProtectionStatus } from '../native/modules/CallDetection';

export type ProtectionTone = 'on' | 'warn' | 'off';

export type ProtectionView = {
  tone: ProtectionTone;
  title: string;
  detail: string;
  blockers: string[];
  tips: string[];
};

export function describeProtection(status: ProtectionStatus): ProtectionView {
  if (!status.protectionEnabled) {
    return {
      tone: 'off',
      title: '防护关着',
      detail: '点下面的大按钮打开。打开后可以回到桌面，防护还会继续。',
      blockers: [],
      tips: [],
    };
  }

  const blockers: string[] = [];
  const tips: string[] = [];

  if (!status.localProvince.trim()) {
    blockers.push('请先填写家乡，例如：广西。不填就分不清本地和外地。');
  }

  if (status.sdkInt >= 29) {
    if (!status.screeningRoleAvailable) {
      blockers.push('这台手机没有来电过滤功能，退出应用后可能拦不住。');
    } else if (!status.screeningRoleHeld) {
      blockers.push('请允许「来电过滤」。这是退出应用后还能拦住电话的关键。');
    }
  } else if (status.sdkInt > 0) {
    blockers.push(
      '这台手机系统较旧，没有来电过滤。已存进通讯录的人不能被误挂，所以这里不会自动挂断。',
    );
  }

  if (!status.batteryUnrestricted) {
    tips.push('建议允许后台运行，避免手机省电时把防护关掉。');
  }
  if (!status.notificationsEnabled) {
    tips.push('通知没打开，状态栏里可能看不到「正在防护」。');
  }
  if (!status.serviceRunning && status.lastServiceError.trim()) {
    tips.push(status.lastServiceError.trim());
  } else if (
    status.sdkInt >= 29 &&
    status.screeningRoleHeld &&
    !status.serviceRunning
  ) {
    tips.push(
      '常驻通知不在了。来电过滤还在，外地陌生电话仍会拦住。建议再点一次「打开防护」，并允许自启动。',
    );
  }
  if (!status.autoBlock) {
    tips.push('「拦截外地陌生电话」关着，现在只会拦住黑名单里的号码。');
  }

  if (blockers.length > 0) {
    return {
      tone: 'warn',
      title: '防护还没准备好',
      detail: '下面几项没做完之前，退到后台可能拦不住外地陌生电话。已存进手机通讯录的人会打进来。',
      blockers,
      tips,
    };
  }

  return {
    tone: 'on',
    title: '防护开着',
    detail: status.autoBlock
      ? '已存进手机通讯录的人会打进来。没存的外地号请加白名单。和家乡同省、同城的电话会放行，座机也按区号算。可以回到桌面。'
      : '外地拦截关着。只有黑名单里的号码会被拦住。已存进手机通讯录的人会打进来。',
    blockers,
    tips,
  };
}
