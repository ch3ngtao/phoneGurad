import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  AppState,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import { DEFAULT_SETTINGS } from '../constants';
import CallDetection, {
  EMPTY_PROTECTION_STATUS,
  ProtectionStatus,
} from '../native/modules/CallDetection';
import PhoneService from '../services/PhoneService';
import StorageService from '../services/StorageService';
import { describeProtection } from '../services/protectionCopy';
import { restoreUserState } from '../services/userState';
import { AppSettings } from '../types';

const PROVINCES = [
  '北京',
  '天津',
  '上海',
  '重庆',
  '河北',
  '山西',
  '辽宁',
  '吉林',
  '黑龙江',
  '江苏',
  '浙江',
  '安徽',
  '福建',
  '江西',
  '山东',
  '河南',
  '湖北',
  '湖南',
  '广东',
  '广西',
  '海南',
  '四川',
  '贵州',
  '云南',
  '陕西',
  '甘肃',
  '青海',
  '内蒙古',
  '宁夏',
  '新疆',
  '西藏',
  '香港',
  '澳门',
  '台湾',
];

const HomeScreen: React.FC = () => {
  const [settings, setSettings] = useState<AppSettings | null>(null);
  const [status, setStatus] = useState<ProtectionStatus>(EMPTY_PROTECTION_STATUS);
  const [provinceInput, setProvinceInput] = useState('');
  const [busy, setBusy] = useState(false);
  const inputReady = useRef(false);
  const persistGeneration = useRef(0);

  const persist = useCallback(async (nextSettings: AppSettings) => {
    const generation = persistGeneration.current + 1;
    persistGeneration.current = generation;
    setSettings(nextSettings);
    if (nextSettings.localProvince.trim()) {
      PhoneService.setLocalLocation(nextSettings.localProvince.trim());
    }
    await CallDetection.saveUserState({
      localProvince: nextSettings.localProvince,
      autoBlock: nextSettings.autoBlock,
      protectionEnabled: nextSettings.protectionEnabled,
      enableNotification: nextSettings.enableNotification,
      enableLog: nextSettings.enableLog,
      batteryPromptDismissed: nextSettings.batteryPromptDismissed,
      autostartOpened: nextSettings.autostartOpened,
      whitelist: nextSettings.whitelist ?? [],
      blacklist: nextSettings.blacklist ?? [],
    });
    if (generation !== persistGeneration.current) return;
    await StorageService.saveSettings(nextSettings);
  }, []);

  const refresh = useCallback(async () => {
    const stamp = persistGeneration.current;
    let nativeStatus = EMPTY_PROTECTION_STATUS;
    let nativeOk = false;
    try {
      nativeStatus = await CallDetection.getProtectionStatus();
      nativeOk = true;
    } catch (error) {
      console.warn('[HomeScreen] read native state failed', error);
    }

    const stored = await StorageService.readSettings();
    if (!nativeOk) {
      if (stored) {
        setSettings(stored);
        if (stored.localProvince.trim()) {
          PhoneService.setLocalLocation(stored.localProvince.trim());
        }
        if (!inputReady.current) {
          setProvinceInput(stored.localProvince);
          inputReady.current = true;
        }
      } else {
        setSettings(current => current ?? { ...DEFAULT_SETTINGS, whitelist: [], blacklist: [] });
      }
      return;
    }

    const restored = restoreUserState(nativeStatus, stored);
    if (stamp !== persistGeneration.current) return;
    if (restored.needsNativeRepair) {
      await CallDetection.saveUserState({
        localProvince: restored.settings.localProvince,
        autoBlock: restored.settings.autoBlock,
        protectionEnabled: restored.settings.protectionEnabled,
        enableNotification: restored.settings.enableNotification,
        enableLog: restored.settings.enableLog,
        batteryPromptDismissed: restored.settings.batteryPromptDismissed,
        autostartOpened: restored.settings.autostartOpened,
        whitelist: restored.settings.whitelist,
        blacklist: restored.settings.blacklist,
      });
    }
    if (stamp !== persistGeneration.current) return;
    if (restored.needsAsyncRepair) {
      await StorageService.saveSettings(restored.settings);
    }
    if (stamp !== persistGeneration.current) return;
    if (restored.settings.localProvince.trim()) {
      PhoneService.setLocalLocation(restored.settings.localProvince.trim());
    }
    if (!inputReady.current) {
      setProvinceInput(restored.settings.localProvince);
      inputReady.current = true;
    }

    let nextStatus = nativeStatus;
    if (restored.settings.protectionEnabled && !nativeStatus.serviceRunning) {
      try {
        await CallDetection.startListening();
      } catch (error) {
        console.warn('[HomeScreen] restart guard failed', error);
      }
      nextStatus = await CallDetection.getProtectionStatus();
    }

    setSettings(restored.settings);
    setStatus({
      ...nextStatus,
      protectionEnabled: restored.settings.protectionEnabled,
      localProvince: restored.settings.localProvince,
      autoBlock: restored.settings.autoBlock,
      whitelist: restored.settings.whitelist,
      blacklist: restored.settings.blacklist,
      enableNotification: restored.settings.enableNotification,
      enableLog: restored.settings.enableLog,
      batteryPromptDismissed: restored.settings.batteryPromptDismissed,
      autostartOpened: restored.settings.autostartOpened,
    });
  }, []);

  useEffect(() => {
    refresh().catch(() => {
      setSettings(current => current ?? { ...DEFAULT_SETTINGS, whitelist: [], blacklist: [] });
    });
    const appState = AppState.addEventListener('change', state => {
      if (state === 'active') {
        refresh().catch(() => undefined);
      }
    });
    const unsubscribe = CallDetection.onCallEvent(() => {
      refresh().catch(() => undefined);
    });
    return () => {
      appState.remove();
      unsubscribe();
    };
  }, [refresh]);

  const saveProvince = async (province: string, announce: boolean) => {
    const trimmed = province.trim();
    if (!trimmed) {
      Alert.alert('请填写家乡', '例如：广西。不填就分不清本地和外地。');
      return;
    }
    const nextSettings: AppSettings = {
      ...(settings ?? DEFAULT_SETTINGS),
      localProvince: trimmed,
    };
    setProvinceInput(trimmed);
    inputReady.current = true;
    await persist(nextSettings);
    if (announce) {
      Alert.alert('已记住', `家乡是${trimmed}。本地电话会打进来。`);
    }
    await refresh();
  };

  const enableProtection = async () => {
    const province = provinceInput.trim();
    if (!province) {
      Alert.alert('请先填写家乡', '点下面的省份，或自己输入，例如：广西。');
      return;
    }
    setBusy(true);
    try {
      await saveProvince(province, false);
      await CallDetection.requestAndroidPermissions();
      const roleGranted = await CallDetection.ensureScreeningRole();
      await CallDetection.startListening();
      const nextStatus = await CallDetection.getProtectionStatus();
      setStatus(nextStatus);
      if (nextStatus.sdkInt >= 29 && nextStatus.screeningRoleAvailable && !roleGranted) {
        Alert.alert(
          '还差一步',
          '请允许「来电过滤」或「骚扰电话拦截」。不允的话，退出应用后就拦不住。',
        );
        return;
      }
      const remembered = settings ?? { ...DEFAULT_SETTINGS, whitelist: [], blacklist: [] };
      if (!nextStatus.batteryUnrestricted && !remembered.batteryPromptDismissed) {
        Alert.alert('请允许后台运行', '接下来请点允许。这是为了让手机不要把防护关掉。', [
          {
            text: '稍后',
            style: 'cancel',
            onPress: () => {
              persist({
                ...remembered,
                localProvince: province,
                protectionEnabled: true,
                batteryPromptDismissed: true,
              }).catch(() => undefined);
            },
          },
          {
            text: '去允许',
            onPress: () => {
              CallDetection.requestIgnoreBatteryOptimizations().catch(() => undefined);
            },
          },
        ]);
      }
    } catch {
      Alert.alert('没有打开', '请检查权限后再试一次。');
    } finally {
      setBusy(false);
      refresh().catch(() => undefined);
    }
  };

  const disableProtection = () => {
    Alert.alert('关掉防护？', '关掉之后，外地骚扰电话会打进来。', [
      { text: '继续防护', style: 'cancel' },
      {
        text: '关掉',
        style: 'destructive',
        onPress: () => {
          setBusy(true);
          CallDetection.stopListening()
            .catch(() => undefined)
            .finally(() => {
              setBusy(false);
              refresh().catch(() => undefined);
            });
        },
      },
    ]);
  };

  const updateAutoBlock = async (value: boolean) => {
    if (!settings) return;
    const nextSettings = { ...settings, autoBlock: value };
    await persist(nextSettings);
    await refresh();
  };

  const openBatterySettings = () => {
    Alert.alert('允许后台运行', '接下来请点允许。这样手机省电时不容易把防护关掉。', [
      { text: '取消', style: 'cancel' },
      {
        text: '去允许',
        onPress: () => {
          CallDetection.requestIgnoreBatteryOptimizations().catch(() => undefined);
        },
      },
    ]);
  };

  const openAutoStart = () => {
    if (!settings) return;
    Alert.alert(
      '允许自启动',
      '如果看到自启动、后台活动或耗电管理，请允许「异地禁呼」。允许后，手机更不容易把防护关掉。',
      [
        { text: '取消', style: 'cancel' },
        {
          text: '去设置',
          onPress: () => {
            persist({ ...settings, autostartOpened: true }).catch(() => undefined);
            CallDetection.openAutoStartSettings().catch(() => undefined);
          },
        },
      ],
    );
  };

  if (!settings) {
    return (
      <View style={styles.loading}>
        <Text style={styles.loadingText}>正在打开…</Text>
      </View>
    );
  }

  const view = describeProtection(status);
  const toneStyle =
    view.tone === 'on' ? styles.toneOn : view.tone === 'warn' ? styles.toneWarn : styles.toneOff;

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.content}
      keyboardShouldPersistTaps="handled">
      <View style={[styles.statusCard, toneStyle]}>
        <Text style={styles.statusTitle} accessibilityRole="header">
          {view.title}
        </Text>
        <Text style={styles.statusDetail}>{view.detail}</Text>
        {view.blockers.map(item => (
          <Text key={item} style={styles.blocker}>
            {item}
          </Text>
        ))}
        {view.tips.map(item => (
          <Text key={item} style={styles.tip}>
            {item}
          </Text>
        ))}
        <Text style={styles.count}>已经拦住 {status.blockedCount} 个外地来电</Text>
        {status.lastBlockedNumber ? (
          <Text style={styles.lastNumber}>最近一次：{status.lastBlockedNumber}</Text>
        ) : null}
      </View>

      {view.tone === 'on' ? (
        <Text style={styles.homeHint}>现在可以按手机上的圆圈或横条，回到桌面。</Text>
      ) : (
        <TouchableOpacity
          style={[styles.primaryButton, busy && styles.buttonDisabled]}
          onPress={enableProtection}
          disabled={busy}
          accessibilityRole="button"
          accessibilityLabel="打开防护">
          <Text style={styles.primaryButtonText}>{busy ? '请稍等…' : '打开防护'}</Text>
        </TouchableOpacity>
      )}

      <TouchableOpacity style={styles.secondaryButton} onPress={openBatterySettings} accessibilityRole="button">
        <Text style={styles.secondaryButtonText}>允许后台运行</Text>
      </TouchableOpacity>
      <TouchableOpacity style={styles.secondaryButton} onPress={openAutoStart} accessibilityRole="button">
        <Text style={styles.secondaryButtonText}>允许自启动</Text>
      </TouchableOpacity>

      <View style={styles.section}>
        <Text style={styles.sectionTitle}>上次记住的</Text>
        <Text style={styles.sectionBody}>
          家乡：{settings.localProvince.trim() || '还没填写'}。防护：
          {settings.protectionEnabled ? '开着' : '关着'}。
        </Text>
        <Text style={styles.sectionBody}>
          {settings.whitelist.length > 0
            ? `额外放行 ${settings.whitelist.length} 个号码：${settings.whitelist.slice(0, 20).join('、')}`
            : '通讯录里的人一直会打进来。'}
        </Text>
        {settings.batteryPromptDismissed ? (
          <Text style={styles.sectionBody}>你选择了稍后允许后台运行。</Text>
        ) : null}
        {settings.autostartOpened ? (
          <Text style={styles.sectionBody}>已经去过自启动设置。</Text>
        ) : null}
      </View>

      <View style={styles.section}>
        <Text style={styles.sectionTitle}>您的家乡</Text>
        <Text style={styles.sectionBody}>本地电话会打进来。外地、又不在通讯录里的电话会被拦住。</Text>
        <TextInput
          style={styles.input}
          placeholder="例如：广西"
          placeholderTextColor="#8A8175"
          value={provinceInput}
          onChangeText={setProvinceInput}
        />
        <TouchableOpacity
          style={styles.saveButton}
          onPress={() => saveProvince(provinceInput, true)}
          accessibilityRole="button">
          <Text style={styles.saveButtonText}>保存家乡</Text>
        </TouchableOpacity>
        <View style={styles.chips}>
          {PROVINCES.map(province => {
            const selected = provinceInput.trim() === province;
            return (
              <TouchableOpacity
                key={province}
                style={[styles.chip, selected && styles.chipSelected]}
                onPress={() => saveProvince(province, false)}
                accessibilityRole="button"
                accessibilityLabel={province}>
                <Text style={[styles.chipText, selected && styles.chipTextSelected]}>{province}</Text>
              </TouchableOpacity>
            );
          })}
        </View>
      </View>

      <View style={styles.section}>
        <View style={styles.settingRow}>
          <Text style={styles.settingLabel}>拦截外地陌生电话</Text>
          <Switch
            value={settings.autoBlock}
            onValueChange={updateAutoBlock}
            accessibilityLabel="拦截外地陌生电话"
          />
        </View>
        <Text style={styles.sectionBody}>关掉后，只拦截黑名单。通讯录和本地电话一直会打进来。</Text>
      </View>

      <View style={styles.section}>
        <Text style={styles.sectionTitle}>如果防护被关掉</Text>
        <Text style={styles.sectionBody}>
          从最近使用的应用里划掉后，只要允许了来电过滤，外地陌生电话仍会拦住。
        </Text>
        <Text style={styles.sectionBody}>
          如果在系统设置里点了「强制停止」，防护会停。请重新打开本应用，再点一次「打开防护」。
        </Text>
        <Text style={styles.sectionBody}>小米、华为、OPPO、vivo 还需要允许自启动，否则过一会儿可能被关掉。</Text>
      </View>

      {status.protectionEnabled ? (
        <TouchableOpacity onPress={disableProtection} accessibilityRole="button" style={styles.stopButton}>
          <Text style={styles.stopButtonText}>关闭防护</Text>
        </TouchableOpacity>
      ) : null}
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F6F3EC',
  },
  content: {
    padding: 20,
    paddingBottom: 48,
  },
  loading: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#F6F3EC',
  },
  loadingText: {
    fontSize: 28,
    color: '#1C1917',
  },
  statusCard: {
    borderRadius: 20,
    padding: 24,
    marginBottom: 16,
  },
  toneOn: {
    backgroundColor: '#E5F6EA',
  },
  toneWarn: {
    backgroundColor: '#FFF4E5',
  },
  toneOff: {
    backgroundColor: '#FDECEC',
  },
  statusTitle: {
    fontSize: 40,
    fontWeight: '700',
    color: '#1C1917',
    marginBottom: 12,
  },
  statusDetail: {
    fontSize: 22,
    lineHeight: 32,
    color: '#1C1917',
  },
  blocker: {
    marginTop: 12,
    fontSize: 20,
    lineHeight: 30,
    color: '#9A3412',
    fontWeight: '700',
  },
  tip: {
    marginTop: 12,
    fontSize: 18,
    lineHeight: 28,
    color: '#44403C',
  },
  count: {
    marginTop: 18,
    fontSize: 22,
    fontWeight: '700',
    color: '#1C1917',
  },
  lastNumber: {
    marginTop: 8,
    fontSize: 20,
    color: '#1C1917',
  },
  homeHint: {
    fontSize: 22,
    lineHeight: 32,
    color: '#14532D',
    fontWeight: '700',
    marginBottom: 16,
  },
  primaryButton: {
    backgroundColor: '#163A5F',
    minHeight: 72,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 12,
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  primaryButtonText: {
    color: '#FFFFFF',
    fontSize: 28,
    fontWeight: '700',
  },
  secondaryButton: {
    backgroundColor: '#FFFFFF',
    minHeight: 64,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 12,
    borderWidth: 2,
    borderColor: '#163A5F',
  },
  secondaryButtonText: {
    color: '#163A5F',
    fontSize: 22,
    fontWeight: '700',
  },
  section: {
    backgroundColor: '#FFFFFF',
    borderRadius: 16,
    padding: 20,
    marginTop: 8,
    marginBottom: 16,
  },
  sectionTitle: {
    fontSize: 26,
    fontWeight: '700',
    color: '#1C1917',
    marginBottom: 8,
  },
  sectionBody: {
    fontSize: 18,
    lineHeight: 28,
    color: '#44403C',
    marginBottom: 8,
  },
  input: {
    borderWidth: 2,
    borderColor: '#D6D3D1',
    borderRadius: 12,
    minHeight: 56,
    paddingHorizontal: 16,
    fontSize: 22,
    color: '#1C1917',
    marginTop: 8,
    marginBottom: 12,
  },
  saveButton: {
    backgroundColor: '#163A5F',
    minHeight: 56,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 12,
  },
  saveButtonText: {
    color: '#FFFFFF',
    fontSize: 22,
    fontWeight: '700',
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  chip: {
    minHeight: 48,
    paddingHorizontal: 14,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#D6D3D1',
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 8,
    marginBottom: 8,
    backgroundColor: '#FAFAF9',
  },
  chipSelected: {
    backgroundColor: '#163A5F',
    borderColor: '#163A5F',
  },
  chipText: {
    fontSize: 18,
    color: '#1C1917',
  },
  chipTextSelected: {
    color: '#FFFFFF',
    fontWeight: '700',
  },
  settingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 8,
  },
  settingLabel: {
    fontSize: 22,
    fontWeight: '700',
    color: '#1C1917',
    flex: 1,
    paddingRight: 12,
  },
  stopButton: {
    minHeight: 56,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stopButtonText: {
    fontSize: 18,
    color: '#78716C',
  },
});

export default HomeScreen;
