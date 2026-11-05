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
    contactsPermissionGranted: true,
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
  expect(view.detail).toContain('通讯录');
  expect(view.detail).toContain('本地');
  expect(view.blockers).toEqual([]);
});

test('没填家乡、没通讯录或没来电过滤时不能算准备好', () => {
  expect(describeProtection(status({ localProvince: '  ' })).tone).toBe('warn');
  expect(describeProtection(status({ contactsPermissionGranted: false })).tone).toBe('warn');
  expect(describeProtection(status({ screeningRoleHeld: false })).blockers[0]).toContain('来电过滤');
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
});
