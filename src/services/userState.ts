import { DEFAULT_SETTINGS } from '../constants';
import { ProtectionStatus } from '../native/modules/CallDetection';
import { AppSettings } from '../types';

export type RestoredUserState = {
  settings: AppSettings;
  needsNativeRepair: boolean;
  needsAsyncRepair: boolean;
};

function sameList(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function sameSettings(left: AppSettings, right: AppSettings): boolean {
  return (
    left.localProvince.trim() === right.localProvince.trim() &&
    left.autoBlock === right.autoBlock &&
    left.protectionEnabled === right.protectionEnabled &&
    left.enableNotification === right.enableNotification &&
    left.enableLog === right.enableLog &&
    left.batteryPromptDismissed === right.batteryPromptDismissed &&
    left.autostartOpened === right.autostartOpened &&
    left.blockThreshold === right.blockThreshold &&
    sameList(left.whitelist, right.whitelist) &&
    sameList(left.blacklist, right.blacklist)
  );
}

/**
 * 原生 SharedPreferences 是拦截实际读取的那份。
 * AsyncStorage 只做备份。启动时用空默认值去写原生，会把已记住的家乡和名单盖掉。
 */
export function restoreUserState(
  native: ProtectionStatus,
  stored: AppSettings | null,
): RestoredUserState {
  const storedProvince = stored?.localProvince.trim() ?? '';
  const nativeProvince = native.localProvince.trim();
  const localProvince = nativeProvince || storedProvince;
  const whitelist = native.hasPersistedLists ? native.whitelist ?? [] : stored?.whitelist ?? [];
  const blacklist = native.hasPersistedLists ? native.blacklist ?? [] : stored?.blacklist ?? [];
  const autoBlock = native.hasPersistedAutoBlock ? native.autoBlock : stored?.autoBlock ?? true;
  const protectionEnabled = native.hasPersistedProtection
    ? native.protectionEnabled
    : stored?.protectionEnabled === true;
  const enableNotification = native.hasPersistedGuidance
    ? native.enableNotification
    : stored?.enableNotification ?? true;
  const enableLog = native.hasPersistedGuidance ? native.enableLog : stored?.enableLog ?? true;
  const batteryPromptDismissed = native.hasPersistedGuidance
    ? native.batteryPromptDismissed
    : stored?.batteryPromptDismissed === true;
  const autostartOpened = native.hasPersistedGuidance
    ? native.autostartOpened
    : stored?.autostartOpened === true;

  const settings: AppSettings = {
    ...DEFAULT_SETTINGS,
    blockThreshold: stored?.blockThreshold ?? DEFAULT_SETTINGS.blockThreshold,
    localProvince,
    autoBlock,
    protectionEnabled,
    enableNotification,
    enableLog,
    batteryPromptDismissed,
    autostartOpened,
    whitelist: [...whitelist],
    blacklist: [...blacklist],
  };

  const needsNativeRepair =
    (!native.hasPersistedProvince && storedProvince.length > 0) ||
    (!native.hasPersistedLists &&
      ((stored?.whitelist.length ?? 0) > 0 || (stored?.blacklist.length ?? 0) > 0)) ||
    (!native.hasPersistedAutoBlock && stored != null && stored.autoBlock === false) ||
    (!native.hasPersistedProtection && stored?.protectionEnabled === true) ||
    (!native.hasPersistedGuidance &&
      (stored?.batteryPromptDismissed === true || stored?.autostartOpened === true));

  const hasUserData =
    localProvince.length > 0 ||
    protectionEnabled ||
    whitelist.length > 0 ||
    blacklist.length > 0 ||
    batteryPromptDismissed ||
    autostartOpened ||
    autoBlock === false;
  const needsAsyncRepair = stored == null ? hasUserData : !sameSettings(stored, settings);

  return { settings, needsNativeRepair, needsAsyncRepair };
}
