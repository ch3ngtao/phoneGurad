import { EMPTY_PROTECTION_STATUS, ProtectionStatus } from '../src/native/modules/CallDetection';
import { restoreUserState } from '../src/services/userState';
import { DEFAULT_SETTINGS } from '../src/constants';
import { AppSettings } from '../src/types';

function native(partial: Partial<ProtectionStatus> = {}): ProtectionStatus {
  return { ...EMPTY_PROTECTION_STATUS, ...partial };
}

function stored(partial: Partial<AppSettings> = {}): AppSettings {
  return {
    ...DEFAULT_SETTINGS,
    whitelist: [],
    blacklist: [],
    ...partial,
  };
}

test('原生已有家乡时，空的本地备份不能盖掉', () => {
  const restored = restoreUserState(
    native({
      localProvince: '四川',
      hasPersistedProvince: true,
      hasPersistedLists: true,
      hasPersistedProtection: true,
      protectionEnabled: true,
      whitelist: ['13800138000'],
    }),
    stored({ localProvince: '', whitelist: [], protectionEnabled: false }),
  );
  expect(restored.settings.localProvince).toBe('四川');
  expect(restored.settings.protectionEnabled).toBe(true);
  expect(restored.settings.whitelist).toEqual(['13800138000']);
  expect(restored.needsNativeRepair).toBe(false);
});

test('备份里有家乡而原生没有时，要补写回原生', () => {
  const restored = restoreUserState(native(), stored({ localProvince: '四川', protectionEnabled: true }));
  expect(restored.settings.localProvince).toBe('四川');
  expect(restored.settings.protectionEnabled).toBe(true);
  expect(restored.needsNativeRepair).toBe(true);
});

test('两边都是空的时候不要用默认值去写存储', () => {
  const restored = restoreUserState(native(), null);
  expect(restored.settings.localProvince).toBe('');
  expect(restored.settings.protectionEnabled).toBe(false);
  expect(restored.needsNativeRepair).toBe(false);
  expect(restored.needsAsyncRepair).toBe(false);
});

test('没有保存过的名单时，用备份里的号码补回原生', () => {
  const restored = restoreUserState(native(), stored({ whitelist: ['13900000000'] }));
  expect(restored.settings.whitelist).toEqual(['13900000000']);
  expect(restored.needsNativeRepair).toBe(true);
});

test('用户关掉拦截外地电话后，默认的开启值不能再写回去', () => {
  const restored = restoreUserState(
    native({ hasPersistedAutoBlock: true, autoBlock: false }),
    stored({ autoBlock: true }),
  );
  expect(restored.settings.autoBlock).toBe(false);
  expect(restored.needsNativeRepair).toBe(false);
});

test('引导进度以已经保存过的原生记录为准', () => {
  const restored = restoreUserState(
    native({
      hasPersistedGuidance: true,
      batteryPromptDismissed: true,
      autostartOpened: true,
    }),
    stored({ batteryPromptDismissed: false, autostartOpened: false }),
  );
  expect(restored.settings.batteryPromptDismissed).toBe(true);
  expect(restored.settings.autostartOpened).toBe(true);
});
