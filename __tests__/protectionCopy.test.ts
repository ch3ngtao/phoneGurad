import { ProtectionStatus } from '../src/native/modules/CallDetection';
import { describeProtection } from '../src/services/protectionCopy';

function status(partial: Partial<ProtectionStatus> = {}): ProtectionStatus {
  return {
    protectionEnabled: true,
    serviceRunning: true,
    screeningRoleHeld: true,
    screeningRoleAvailable: true,
    batteryUnrestricted: true,
    notificationsEnabled: true,
    phonePermissionGranted: true,
    localProvince: '广西',
    autoBlock: true,
    whitelist: [],
    blacklist: [],
    enableNotification: true,
    enableLog: true,
    batteryPromptDismissed: false,
    autostartOpened: false,
    hasPersistedProvince: true,
    hasPersistedLists: true,
    hasPersistedAutoBlock: true,
    hasPersistedProtection: true,
    hasPersistedGuidance: true,
    blockedCount: 0,
    lastBlockedNumber: '',
    lastBlockedAt: 0,
    lastServiceError: '',
    sdkInt: 34,
    ...partial,
  };
}

test('防护关闭时用大字说明可以打开', () => {
  const view = describeProtection(status({ protectionEnabled: false }));
  expect(view.tone).toBe('off');
  expect(view.title).toBe('防护关着');
  expect(view.blockers).toEqual([]);
});

test('条件齐了就告诉老人可以回到桌面', () => {
  const view = describeProtection(status());
  expect(view.tone).toBe('on');
  expect(view.title).toBe('防护开着');
  expect(view.detail).toContain('已存进手机通讯录的人会打进来');
  expect(view.detail).toContain('没存的外地号请加白名单');
  expect(view.detail).toContain('同省');
  expect(view.detail).toContain('座机也按区号算');
  expect(view.detail).not.toContain('读取通讯录');
  expect(view.blockers).toEqual([]);
});

test('没填家乡或没来电过滤时不能算准备好，但不再要求读取通讯录', () => {
  expect(describeProtection(status({ localProvince: '  ' })).tone).toBe('warn');
  const missingRole = describeProtection(status({ screeningRoleHeld: false }));
  expect(missingRole.blockers[0]).toContain('来电过滤');
  expect(missingRole.blockers.join('')).not.toContain('读取通讯录');
  expect(missingRole.detail).toContain('已存进手机通讯录的人会打进来');
});

test('来电过滤还在但常驻通知没了，仍然算开着并给出引导', () => {
  const view = describeProtection(status({ serviceRunning: false }));
  expect(view.tone).toBe('on');
  expect(view.tips.join('')).toContain('来电过滤还在');
});

test('旧系统必须看到防护服务还在跑', () => {
  const view = describeProtection(status({ sdkInt: 28, serviceRunning: false, screeningRoleHeld: false }));
  expect(view.tone).toBe('warn');
  expect(view.blockers.join('')).toContain('系统较旧');
  expect(view.blockers.join('')).toContain('不会自动挂断');
  expect(view.blockers.join('')).not.toContain('读取通讯录');
});
